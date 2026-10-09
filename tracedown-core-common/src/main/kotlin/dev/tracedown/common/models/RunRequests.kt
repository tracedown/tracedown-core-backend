package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * A run somebody asked for, under the id they were handed for it — which is
 * also the id its result is filed under in [ProbeResults] (see `RunTrigger`).
 */
object RunRequests : Table("run_requests") {
    val id = javaUUID("id")
    val serviceId = javaUUID("service_id").references(Services.id)
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    /** Who asked. Cleared (ON DELETE SET NULL) when the account is erased. */
    val requestedBy = javaUUID("requested_by").references(Users.id).nullable()
    /** The API key the request came through, when it came through one. Not a foreign key: it outlives the key. */
    val apiKeyId = javaUUID("api_key_id").nullable()
    val requestedAt = timestamp("requested_at")
    /** `pending`, `done` or `skipped` — see [RunState]. `expired` is never stored. */
    val state = varchar("state", 8).default(RunState.PENDING)
    /** How many results the run publishes (more than one on several agents at once); null until the first is in. */
    val expectedResults = short("expected_results").nullable()
    /** Why a request settled without a result (`run_not_delivered`); null otherwise. */
    val reason = varchar("reason", 64).nullable()
    /** When the row may be deleted; null while results are kept forever. */
    val purgeAfter = timestamp("purge_after").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The states of a [RunRequests] row, and the one a reader derives. */
object RunState {
    const val PENDING = "pending"
    const val DONE = "done"
    const val SKIPPED = "skipped"

    /** Never stored: a pending request older than the reader's bound. */
    const val EXPIRED = "expired"

    /**
     * The outbox event a stored settlement writes, in the transaction that
     * stores it: `{runId, serviceId, orgId, state, status?, reason?}`. An
     * expiry is never stored, so it writes none.
     */
    const val SETTLED_EVENT = "run_request.settled"

    /**
     * How bad each result status is, worst first: a run's status is its worst
     * result's. A skipped result (an agent that did not run it) is worse than
     * a success — the run did not happen everywhere it was meant to.
     */
    val SEVERITY = listOf("failure", "timeout", "error", "skipped", "success")

    /** The worst of [statuses] by [SEVERITY] — a status it does not know counts as the worst; null for none. */
    fun worst(statuses: Collection<String>): String? =
        statuses.minByOrNull { SEVERITY.indexOf(it).let { i -> if (i < 0) -1 else i } }
}
