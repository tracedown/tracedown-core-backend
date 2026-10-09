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
}
