package dev.tracedown.realtime.routes

import dev.tracedown.common.realtime.ConnectionAuthorizer
import dev.tracedown.realtime.auth.SessionValidator
import dev.tracedown.realtime.ws.ClientSession
import dev.tracedown.realtime.ws.ConnectionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.realtime.routes.RealtimeRoutes")

/**
 * Registers the WebSocket and health routes for the realtime-service.
 */
fun Route.realtimeRoutes(connectionManager: ConnectionManager) {

    /** Health check endpoint. */
    get("/ping") {
        call.respondText("pong")
    }

    /** Returns connection count for monitoring. */
    get("/status") {
        call.respond(mapOf("connections" to connectionManager.connectionCount()))
    }

    /**
     * WebSocket endpoint. Authenticates via (in order of preference) the
     * `Authorization: Bearer` header, the `Sec-WebSocket-Protocol` subprotocol
     * (`bearer.<token>`), then a `token` query parameter (legacy fallback).
     *
     * Preferring header/subprotocol keeps the bearer token out of the request
     * URL — and therefore out of proxy access logs and browser history. The
     * fronting proxy also strips the query string from /ws access logs so the
     * fallback path cannot leak the token either.
     *
     * Protocol:
     * - Client sends: {"type":"subscribe","channel":"..."} / {"type":"unsubscribe","channel":"..."} / {"type":"ping"}
     *                 / {"type":"relay","channel":"svc-edit:{id}","event":"...","data":{}} (collaborative editing)
     * - Server sends: {"type":"event","channel":"...","event":"...","data":{}} / {"type":"subscribed","channel":"..."} / {"type":"pong"} / {"type":"error","message":"..."}
     */
    webSocket("/ws") {
        val token = resolveToken(call)
        if (token.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing token"))
            return@webSocket
        }

        val auth = SessionValidator.validate(token)
        if (auth == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid session"))
            return@webSocket
        }

        // Open-time gate. Core registers no authorizer, so the default is allow
        // and this is a no-op for standalone Core; a host can register one to add
        // its own policy at socket-open without any of it living here.
        val decision = ConnectionAuthorizer.authorize(
            ConnectionAuthorizer.ConnectionContext(
                userId = auth.userId,
                sessionId = auth.sessionId,
                orgId = auth.orgId,
            ),
        )
        if (decision is ConnectionAuthorizer.Decision.Deny) {
            log.debug("connection refused by authorizer for session={}: {}", auth.sessionId, decision.reason)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not authorized"))
            return@webSocket
        }

        val client = ClientSession(
            connectionId = java.util.UUID.randomUUID(),
            wsSession = this,
            userId = auth.userId,
            sessionId = auth.sessionId,
            orgId = auth.orgId,
        )
        connectionManager.add(client)

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    handleClientMessage(frame.readText(), client.connectionId, connectionManager)
                }
            }
        } catch (e: Exception) {
            log.debug("ws error for session={}: {}", auth.sessionId, e.message)
        } finally {
            connectionManager.remove(client.connectionId)
        }
    }
}

/** Subprotocol marker preceding the bearer token: `bearer.<token>` or `bearer, <token>`. */
const val BEARER_SUBPROTOCOL = "bearer"

/**
 * Resolves the session token from the least-leaky source available: the
 * Authorization header, then the Sec-WebSocket-Protocol subprotocol, then the
 * legacy `token` query parameter.
 */
private fun resolveToken(call: ApplicationCall): String? {
    call.request.headers["Authorization"]?.let { header ->
        if (header.startsWith("Bearer ", ignoreCase = true)) {
            return header.substring(7).trim()
        }
    }
    call.request.headers["Sec-WebSocket-Protocol"]?.let { proto ->
        val parts = proto.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // Form 1: "bearer.<token>" as a single subprotocol token.
        parts.firstOrNull { it.startsWith("$BEARER_SUBPROTOCOL.") }
            ?.let { return it.removePrefix("$BEARER_SUBPROTOCOL.") }
        // Form 2: "bearer, <token>" as two subprotocol tokens.
        val idx = parts.indexOf(BEARER_SUBPROTOCOL)
        if (idx >= 0 && idx + 1 < parts.size) return parts[idx + 1]
    }
    return call.request.queryParameters["token"]
}

private suspend fun handleClientMessage(text: String, connectionId: java.util.UUID, manager: ConnectionManager) {
    try {
        val json = Json.parseToJsonElement(text).jsonObject
        val type = json["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "subscribe" -> {
                val channel = json["channel"]?.jsonPrimitive?.content ?: return
                manager.subscribe(connectionId, channel)
            }
            "unsubscribe" -> {
                val channel = json["channel"]?.jsonPrimitive?.content ?: return
                manager.unsubscribe(connectionId, channel)
            }
            "ping" -> {
                manager.pong(connectionId)
            }
            "relay" -> {
                val channel = json["channel"]?.jsonPrimitive?.content ?: return
                val event = json["event"]?.jsonPrimitive?.content ?: return
                val data = json["data"]?.jsonObject ?: buildJsonObject {}
                manager.relay(connectionId, channel, event, data)
            }
        }
    } catch (e: Exception) {
        log.debug("failed to parse client message: {}", e.message)
    }
}
