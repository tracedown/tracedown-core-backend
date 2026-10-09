package dev.tracedown.common.models

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Emits durable events onto the transactional [Outbox].
 *
 * These helpers MUST be called inside the caller's existing `transaction { }`
 * so the event row is committed atomically with the write it describes — either
 * both land or neither does. The row is written with `published = false`; each
 * consumer decides independently whether it cares (the notification consumer
 * filters on its own event type, cursor consumers track their own offset), so
 * adding new event types never disturbs existing consumers.
 */
object OutboxEmit {

    /**
     * The pub/sub channel a process that installed [onCommitted] tells
     * readers on: the message is the organization the new rows belong to.
     * Best-effort — a reader that misses one finds the rows on its next look.
     */
    const val NUDGE_CHANNEL = "outbox:nudge"

    /**
     * The event of a skipped run. Not `probe_result.created`: that is what the
     * notification consumer claims, and a run that never happened notifies
     * nobody.
     */
    const val PROBE_RESULT_SKIPPED = "probe_result.skipped"

    private val log = LoggerFactory.getLogger(OutboxEmit::class.java)

    @Volatile
    private var nudge: ((UUID) -> Unit)? = null

    /** The organizations a transaction has emitted for, nudged once it commits. */
    private val pendingNudges = Key<MutableSet<UUID>>()

    /**
     * Installs what runs after a transaction that emitted has committed, once
     * per organization it emitted for — never before the commit, so a reader
     * it wakes can see the rows. Unset, nothing is sent and readers find new
     * rows on their own schedule.
     */
    fun onCommitted(nudge: ((orgId: UUID) -> Unit)?) {
        this.nudge = nudge
    }

    /**
     * Inserts a single outbox row. [aggregateType] is a short kind tag (e.g.
     * "workspace"), [aggregateId] the id of the affected entity, [eventType] a
     * dotted name (e.g. "resource.workspace.created"), and [payload] a compact
     * JSON body. Call within an open transaction.
     *
     * [organizationId] is the organization the row is about; left out, it is
     * read from the payload's `orgId` (or `organizationId`).
     */
    fun emitResourceEvent(
        eventType: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: JsonObject,
        createdAt: Instant = Instant.now(),
        organizationId: UUID? = null,
    ) {
        val orgId = organizationId ?: orgOf(payload)
        Outbox.insert {
            it[Outbox.organizationId] = orgId
            it[id] = UUID.randomUUID()
            it[Outbox.aggregateType] = aggregateType
            it[Outbox.aggregateId] = aggregateId
            it[Outbox.eventType] = eventType
            it[Outbox.payload] = payload
            it[published] = false
            it[Outbox.createdAt] = createdAt
        }
        if (orgId != null) nudgeAfterCommit(orgId)
    }

    /**
     * Sends nudges off the committing thread: fire and forget, one at a time,
     * and dropped when too many are waiting. The committing thread may still
     * hold its connection, and a nudge that waits on a slow or absent Redis
     * must not hold it too. A dropped nudge costs a reader a few seconds.
     */
    private val sender = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1_000),
        { runnable -> Thread(runnable, "outbox-nudge").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    private fun send(notify: (UUID) -> Unit, org: UUID) {
        sender.execute {
            try {
                notify(org)
            } catch (e: Exception) {
                log.debug("outbox nudge for {} not sent: {}", org, e.message)
            }
        }
    }

    private fun orgOf(payload: JsonObject): UUID? =
        (payload["orgId"] ?: payload["organizationId"])?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun nudgeAfterCommit(orgId: UUID) {
        val notify = nudge ?: return
        val transaction = TransactionManager.currentOrNull() ?: return
        val pending = transaction.getUserData(pendingNudges) ?: mutableSetOf<UUID>().also { orgs ->
            transaction.putUserData(pendingNudges, orgs)
            transaction.registerInterceptor(object : StatementInterceptor {
                override fun afterCommit(transaction: Transaction) {
                    val sent = synchronized(orgs) { orgs.toList().also { orgs.clear() } }
                    for (org in sent) send(notify, org)
                }
            })
        }
        synchronized(pending) { pending += orgId }
    }
}
