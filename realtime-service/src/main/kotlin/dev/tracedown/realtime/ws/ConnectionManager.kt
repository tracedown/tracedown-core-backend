package dev.tracedown.realtime.ws

import dev.tracedown.common.agents.FleetAudience
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.realtime.auth.ChannelAuthorizer
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages all active WebSocket client connections and their channel subscriptions.
 *
 * Thread-safe — uses concurrent collections for connection/subscription tracking.
 * Called from both the WebSocket handler (subscribe/unsubscribe) and the
 * EventRouter (broadcast events from Redis pub/sub).
 *
 * Everything is keyed by the per-socket [ClientSession.connectionId] — never
 * by the auth session id, which is shared across reconnects of the same
 * browser session and would let a dying socket's cleanup unregister its
 * freshly connected successor.
 */
class ConnectionManager {

    private val log = LoggerFactory.getLogger(javaClass)

    /** All active client sessions, keyed by per-socket connection ID. */
    private val clients = ConcurrentHashMap<UUID, ClientSession>()

    /** Reverse index: channel → set of connection IDs subscribed to it. */
    private val channelSubscribers = ConcurrentHashMap<String, MutableSet<UUID>>()

    /** Registers a new client connection and auto-subscribes to the session channel. */
    fun add(client: ClientSession) {
        clients[client.connectionId] = client
        // Auto-subscribe to session revocation channel (named by auth session)
        val sessionChannel = "session:${client.sessionId}"
        client.subscriptions.add(sessionChannel)
        channelSubscribers.getOrPut(sessionChannel) { ConcurrentHashMap.newKeySet() }.add(client.connectionId)
        log.info("client connected: user={} session={} conn={}", client.userId, client.sessionId, client.connectionId)
    }

    /** Removes a client connection and all its subscriptions. */
    fun remove(connectionId: UUID) {
        val client = clients.remove(connectionId) ?: return
        for (channel in client.subscriptions) {
            channelSubscribers[channel]?.remove(connectionId)
        }
        log.info("client disconnected: session={} conn={}", client.sessionId, connectionId)
    }

    /** Subscribes a client connection to a channel. Validates session-scoped channels. */
    suspend fun subscribe(connectionId: UUID, channel: String) {
        val client = clients[connectionId] ?: return

        // Validate session-scoped channel against the connection's auth session
        if (channel.startsWith("session:")) {
            val targetSessionId = channel.removePrefix("session:")
            if (targetSessionId != client.sessionId.toString()) {
                sendError(client, "cannot subscribe to another session's channel")
                return
            }
        }

        // Resource-scoped channels (service/project/workspace/svc-edit) require
        // the same resource grant the REST API demands — org membership alone
        // (which the session proves) must not expose another team's resources.
        if (!ChannelAuthorizer.canSubscribe(client.userId, client.orgId, channel)) {
            sendError(client, "not authorized for this channel")
            return
        }

        client.subscriptions.add(channel)
        channelSubscribers.getOrPut(channel) { ConcurrentHashMap.newKeySet() }.add(connectionId)

        sendJson(client, buildJsonObject {
            put("type", "subscribed")
            put("channel", channel)
        }.toString())

        log.info("subscribed session={} conn={} to channel={}", client.sessionId, connectionId, channel)
    }

    /** Unsubscribes a client connection from a channel. */
    suspend fun unsubscribe(connectionId: UUID, channel: String) {
        val client = clients[connectionId] ?: return
        client.subscriptions.remove(channel)
        channelSubscribers[channel]?.remove(connectionId)

        sendJson(client, buildJsonObject {
            put("type", "unsubscribed")
            put("channel", channel)
        }.toString())
    }

    /**
     * Relays a client-originated collaborative-editing message to the rest of a
     * `svc-edit:` channel by re-publishing it through the normal `rt:*` pipeline
     * (so every replica's subscribers receive it, the sender included — clients
     * ignore their own echo).
     *
     * Guarded two ways: only `svc-edit:` channels may be relayed (a client can't
     * forge server-authoritative resource events), and the sender must already be
     * subscribed to the channel. Published under the sender's own [orgId], so it
     * can never cross org boundaries.
     */
    fun relay(connectionId: UUID, channel: String, event: String, data: JsonObject) {
        if (!channel.startsWith("svc-edit:")) return
        val client = clients[connectionId] ?: return
        if (!client.subscriptions.contains(channel)) return
        // Injecting a live edit is a write: require write access to the service,
        // not merely a subscription. Without this any org member on the channel
        // could push script edits into a service they cannot modify via REST.
        if (!ChannelAuthorizer.canRelay(client.userId, client.orgId, channel)) return
        RealtimePublisher.publish(channel, client.orgId, event, data)
    }

    /** Sends a pong response to a client connection. */
    suspend fun pong(connectionId: UUID) {
        val client = clients[connectionId] ?: return
        sendJson(client, """{"type":"pong"}""")
    }

    /**
     * Queues an event for all clients subscribed to the given channel; the
     * flusher coalesces queued events into one batch frame per interval so
     * dispatch bursts don't spam thousands of tiny frames per client.
     *
     * Filters by orgId, with one exemption: a fleet event published under
     * [FleetAudience.GLOBAL]. That sentinel means the event belongs to no
     * organization — Core's agents are shared platform infrastructure, so an org
     * filter would deliver it to nobody — and who may hold a subscription to the
     * feed at all is decided at subscribe time by
     * [ChannelAuthorizer.canSubscribe].
     *
     * The exemption is on the sentinel, not on the channel. A deployment that
     * gives agents owners installs a [FleetAudience.Ownership], its publishes
     * name real organizations, and those events are filtered here exactly like
     * every other channel's — no second gate and no per-client lookup. The
     * read-side twin for REST fleet lists is `AgentVisibility`.
     */
    fun broadcast(channel: String, orgId: UUID, eventJson: String) {
        val global = ChannelAuthorizer.isFleetChannel(channel) && orgId == FleetAudience.GLOBAL
        val subscribers = channelSubscribers[channel] ?: return
        for (cid in subscribers) {
            val client = clients[cid] ?: continue
            if (!global && client.orgId != orgId) continue
            client.pendingEvents.add(eventJson)
            // Bounded buffer: shed oldest under pathological backlog.
            while (client.pendingEvents.size > MAX_PENDING_EVENTS) client.pendingEvents.poll()
        }
    }

    /**
     * Starts the batch flusher: every [FLUSH_INTERVAL_MS] each connection's
     * queued events go out as `{"type":"eventBatch","events":[…]}` frames.
     */
    fun startFlusher(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                for (client in clients.values) {
                    if (client.pendingEvents.isEmpty()) continue
                    try {
                        while (client.pendingEvents.isNotEmpty()) {
                            val chunk = ArrayList<String>(BATCH_CHUNK_SIZE)
                            while (chunk.size < BATCH_CHUNK_SIZE) {
                                chunk.add(client.pendingEvents.poll() ?: break)
                            }
                            if (chunk.isEmpty()) break
                            sendJson(client, """{"type":"eventBatch","events":[${chunk.joinToString(",")}]}""")
                        }
                    } catch (e: Exception) {
                        log.debug("flush failed for conn={}: {}", client.connectionId, e.message)
                    }
                }
            }
        }
        log.info("event batch flusher started (interval={}ms)", FLUSH_INTERVAL_MS)
    }

    /** Returns current connection count (for health/debug). */
    fun connectionCount(): Int = clients.size

    private companion object {
        const val FLUSH_INTERVAL_MS = 250L
        const val MAX_PENDING_EVENTS = 5_000
        const val BATCH_CHUNK_SIZE = 500
    }

    private suspend fun sendJson(client: ClientSession, json: String) {
        client.wsSession.outgoing.send(Frame.Text(json))
    }

    private suspend fun sendError(client: ClientSession, message: String) {
        sendJson(client, buildJsonObject {
            put("type", "error")
            put("message", message)
        }.toString())
    }
}
