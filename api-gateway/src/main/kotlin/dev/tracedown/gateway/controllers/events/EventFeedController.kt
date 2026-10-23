package dev.tracedown.gateway.controllers.events

import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.auth.ApiKeyAuthenticator
import dev.tracedown.common.auth.ApiKeyResult
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canRead
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.OutboxRetention
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.RunState
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.gateway.context.SessionAuth
import dev.tracedown.gateway.context.apiKeyRefusal
import dev.tracedown.gateway.data.events.EventPage
import dev.tracedown.gateway.data.events.EventResource
import dev.tracedown.gateway.data.events.FeedEvent
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.EventCursor
import dev.tracedown.gateway.util.EventCursor.Position
import dev.tracedown.gateway.util.TooManyRequestsException
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Workspaces
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import dev.tracedown.gateway.util.EventWakeups
import dev.tracedown.gateway.util.FeedHighWater
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.fieldError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Duration
import java.util.UUID

/**
 * The feed's event types — the public names, not the outbox's — what each is
 * about, and the fields each one's `data` carries, exactly. Pinned, append
 * only, by `public-api-v1.events.txt`.
 */
object EventTypes {
    const val RESULT_RECORDED = "result.recorded"
    const val SERVICE_STATUS_CHANGED = "service.status_changed"
    const val ALERT_RAISED = "alert.raised"
    const val RUN_SETTLED = "run.settled"

    /** `<resource>.created|updated|deleted` for these resources. */
    val RESOURCES = listOf("workspace", "project", "service", "variable")
    private val CHANGES = listOf("created", "updated", "deleted")

    val ALL: List<String> =
        listOf(RESULT_RECORDED, SERVICE_STATUS_CHANGED) +
            RESOURCES.flatMap { r -> CHANGES.map { "$r.$it" } } +
            ALERT_RAISED + RUN_SETTLED

    /** The `resource.type` of each event type. */
    val RESOURCE: Map<String, String> = ALL.associateWith { type ->
        when (type.substringBefore('.')) {
            "result", "run" -> "service"
            else -> type.substringBefore('.')
        }
    }

    /** Each type's `data` fields, as the description lists them and the contract pins them. */
    val DATA: Map<String, List<String>> =
        mapOf(
            RESULT_RECORDED to listOf("resultId", "status", "runDurationMs", "reason", "projectId", "workspaceId"),
            SERVICE_STATUS_CHANGED to listOf("status", "previousStatus", "resultId"),
            ALERT_RAISED to listOf("type", "subject", "severity"),
            RUN_SETTLED to listOf("runId", "serviceId", "state", "status", "reason", "superseded"),
        ) + CHANGES.flatMap { change ->
            listOf(
                "workspace.$change" to emptyList(),
                "project.$change" to listOf("workspaceId"),
                "service.$change" to listOf("projectId", "workspaceId"),
                "variable.$change" to listOf("scope", "scopeId", "key"),
            )
        }
}

/**
 * The event feed: one organization's outbox rows, read in order, as the events
 * a caller may see.
 *
 * **Order and position.** Rows are read in (xid, seq) order — the writing
 * transaction, then the row — and only those written by transactions below the
 * oldest one still open (`pg_snapshot_xmin`), read in the same statement as
 * the rows. Every such transaction has committed or rolled back, and none to
 * come can be given a lower xid, so no row can ever appear behind a position
 * once a reader has passed it — however late its transaction commits, and
 * without waiting out holes a rollback leaves in `seq`. The price is that an
 * open transaction anywhere on the server holds readers back for as long as it
 * stays open; [warnOfOldTransaction] says so in the log. A cursor is a
 * position, sealed for the organization ([EventCursor]). Each read moves it
 * over every row it looked at — a type the caller did not ask for, a resource
 * they may not see — and, when it read all there was, up to the horizon, so a
 * quiet organization's cursor keeps up with the purge.
 *
 * **Retention.** The outbox is trimmed; [OutboxRetention] says how far. A
 * position before the mark may have missed rows and is refused — 410
 * `cursor_expired` with the oldest cursor that has missed nothing — checked on
 * every look, after its rows are read. A cursor that will not open (another
 * organization's, or sealed under a platform key since changed) is refused the
 * same way, and so is every cursor once the database has gone back in history
 * (a restore, a point-in-time recovery, a dump loaded elsewhere): a position
 * past the horizon, or a horizon below the highest one seen ([FeedHighWater]),
 * says so, and the feed then starts again from the present ([rebase]).
 *
 * **Who sees what** is decided on every look, from the user's permissions as
 * they are then — the checks the dashboard's reads of the same resource make
 * ([visible]) — and the key, its user, the second-factor rule and the host's
 * guards are looked at again each time too, so a grant withdrawn, a member
 * removed or a key revoked stops delivery from the next look, even within one
 * long-poll. The resources an event names are read from the database, and only
 * inside the caller's organization.
 *
 * **Cost.** A read does at most [MAX_LOOKS] looks of database work, then
 * answers with what it has; every look after the first, and every answer given
 * without having waited, spends a unit of the key's request budget. At most a
 * third of the process's connection pool is ever reading the feed. Between
 * looks a read suspends on a signal from [EventWakeups], holding nothing; when
 * none comes it looks again on a schedule that spreads its looks over the wait.
 */
object EventFeedController {

    const val MAX_WAIT_SECONDS = 30
    const val MAX_LIMIT = 100

    /** Looks of database work one read may do. */
    const val MAX_LOOKS = 3

    /** The organization's outbox rows read per look. */
    internal const val SCAN_ROWS = 500

    /** A pause after a wake-up, so a burst of writes is read in one look. */
    private val SETTLE: Duration = Duration.ofMillis(200)

    /** How old the oldest open transaction may be before the log says it holds the feed back. */
    private val OLD_TRANSACTION: Duration = Duration.ofSeconds(60)

    /** The oldest transaction still open, as the position just before everything it and later ones write. */
    private const val HORIZON = "pg_snapshot_xmin(pg_current_snapshot())::text::bigint"

    private val log = LoggerFactory.getLogger(EventFeedController::class.java)

    /** At most a third of the connection pool reads the feed at once. */
    private val gate: Semaphore by lazy { Semaphore((DatabaseFactory.poolSize / 3).coerceAtLeast(1)) }

    /** When [warnOfOldTransaction] last looked, in epoch millis. */
    private val lastTransactionCheck = AtomicLong(0)

    /** For tests: runs before each look, on the read's coroutine. */
    @Volatile
    internal var beforeEachLook: (suspend () -> Unit)? = null

    /**
     * Reads the events after [after] that [userId] may see in [orgId] through
     * key [keyId]: those already there, or — waiting up to [waitSeconds] —
     * the first that arrive. Without [after], reads from now on.
     *
     * [spend] takes one more unit of the key's request budget and says
     * whether there was one; [beforeLook] runs the host's guards again.
     */
    suspend fun read(
        orgId: UUID,
        userId: UUID,
        keyId: UUID,
        after: String?,
        waitSeconds: Int,
        types: Set<String>?,
        limit: Int,
        spend: () -> Boolean = { true },
        beforeLook: suspend () -> Unit = {},
    ): EventPage {
        var position: Position.At? = after?.let { cursor ->
            EventCursor.decode(orgId, cursor) ?: run {
                if (!EventCursor.isSealed(cursor)) throw fieldError("after")
                // A refusal that reads the mark is a request's worth of work too.
                if (!spend()) throw TooManyRequestsException()
                throw expired(orgId, onIo { mark() ?: start() })
            }
        }
        // An answer that will not wait costs a request, as any other does —
        // spent before the work, not after.
        if (waitSeconds == 0 && !spend()) throw TooManyRequestsException()

        val deadline = System.nanoTime() + Duration.ofSeconds(waitSeconds.toLong()).toNanos()
        var looks = 0
        var waited = false
        while (true) {
            // Registered before looking, so a nudge during the look is kept.
            val signal = EventWakeups.register(orgId)
            try {
                beforeLook()
                beforeEachLook?.invoke()
                if (looks > 0 && !spend()) throw TooManyRequestsException()
                val look = try {
                    gate.withPermit { onIo { look(orgId, userId, keyId, position, types, limit) } }
                } catch (e: ApiException) {
                    // A first look that was free and ends in a refusal is paid for
                    // after all: a read that is refused cannot be made a free scan.
                    if (e.code == ErrorCodes.CURSOR_EXPIRED && looks == 0 && waitSeconds > 0 && !spend()) {
                        throw TooManyRequestsException()
                    }
                    throw e
                }
                looks++
                // A history the positions do not belong to — a cursor past the
                // horizon, or a horizon below one seen before: move the feed
                // to the present one, refuse the cursor, and start from now.
                if (look.beyond || FeedHighWater.wentBack(look.horizon)) {
                    val start = Position.At(look.horizon - 1, Long.MAX_VALUE)
                    onIo { rebase(look.xmax, start) }
                    FeedHighWater.reset(look.horizon)
                    if (after != null) throw expired(orgId, start)
                    return EventPage(emptyList(), EventCursor.encode(orgId, start), more = false)
                }
                position = look.position
                val remaining = Duration.ofNanos(deadline - System.nanoTime())
                val done = look.items.isNotEmpty() || remaining.isNegative || remaining.isZero || looks >= MAX_LOOKS
                if (done) {
                    // Answered without waiting, though it could have: that is a
                    // request's worth of work as well. (Counted, not refused: the
                    // work is done.)
                    if (!waited && waitSeconds > 0) spend()
                    return EventPage(look.items, EventCursor.encode(orgId, look.position), look.more)
                }
                // Read everything there was and found nothing for the caller:
                // there may be more, so look on at once.
                if (look.more) continue
                // Spread the looks left over the time left.
                val pause = remaining.dividedBy((MAX_LOOKS - looks).toLong())
                waited = true
                val woken = withTimeoutOrNull(pause.toMillis().coerceAtLeast(1)) { signal.await() } != null
                if (woken) delay(minOf(SETTLE, Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))).toMillis())
            } finally {
                EventWakeups.release(orgId, signal)
            }
        }
    }

    /**
     * Whether permissions [cached] could see anything in the feed at all: some
     * read somewhere in the organization. Decided from what the key's
     * admission already resolved — no database work.
     */
    fun hasAnythingToSee(cached: CachedPermissions?): Boolean {
        if (cached == null) return false
        return cached.org.isOwner || cached.org.workspaces.canRead() || cached.org.settings.canRead() ||
            cached.resources.values.any { it.canRead() }
    }

    private fun expired(orgId: UUID, oldest: Position.At) = ApiException(
        HttpStatusCode.Gone, ErrorCodes.CURSOR_EXPIRED,
        details = buildJsonObject { put("oldest", EventCursor.encode(orgId, oldest)) },
    )

    /** Blocking database work, off the caller's thread, in a transaction of its own. */
    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { transaction { block() } }

    private fun connection(): Connection = TransactionManager.current().connection.connection as Connection

    /** Where a reader starting now starts: just before everything not yet settled. */
    private fun start(): Position.At = connection().prepareStatement("SELECT $HORIZON").use { stmt ->
        stmt.executeQuery().use { rs -> rs.next(); Position.At(rs.getLong(1) - 1, Long.MAX_VALUE) }
    }

    /** The purge's mark, when it has moved off the beginning. */
    private fun mark(): Position.At? = OutboxRetention.selectAll().where { OutboxRetention.id eq 1 }.firstOrNull()
        ?.let { Position.At(it[OutboxRetention.purgedXid], it[OutboxRetention.purgedSeq]) }
        ?.takeIf { it > Position.At(0, 0) }

    /** One look's outcome: what it found, how far it read, and whether more may be there already. */
    internal data class Look(
        val items: List<FeedEvent>,
        val position: Position.At,
        val more: Boolean,
        /** The oldest transaction open when the look read, and the next id to be given out. */
        val horizon: Long = 0,
        val xmax: Long = 0,
        /** The position read from lies at or past the horizon: impossible in one history. */
        val beyond: Boolean = false,
    )

    /** One outbox row as a look reads it. */
    private data class Row(
        val position: Position.At,
        val id: UUID,
        val eventType: String,
        val aggregateId: UUID,
        val createdAt: java.time.Instant,
        val payload: JsonObject,
    )

    /** What a look read: the rows, the horizon they were read below, and the next transaction id. */
    private class Read(val rows: List<Row>, val horizon: Long, val xmax: Long)

    /** One look, in the caller's transaction. [after] null reads from now. */
    internal fun look(orgId: UUID, userId: UUID, keyId: UUID, after: Position.At?, types: Set<String>?, limit: Int): Look {
        // The key, its user and their permissions as they are now, not as they
        // were when the request came in: a long-poll outlives that moment.
        val key = when (val result = ApiKeyAuthenticator.recheck(keyId)) {
            is ApiKeyResult.Invalid -> throw UnauthorizedException(apiKeyRefusal(result.reason))
            is ApiKeyResult.Valid -> result.context
        }
        if (!key.totpEnabled && SessionAuth.isTotpEnforcedForOrg(userId, orgId)) {
            throw ForbiddenException(ErrorCodes.TOTP_ENROLLMENT_REQUIRED)
        }
        val cached = key.permissions ?: throw UnauthorizedException(ErrorCodes.API_KEY_OWNER_INACTIVE)
        warnOfOldTransaction()

        val read = readRows(orgId, after)
        val start = Position.At(read.horizon - 1, Long.MAX_VALUE)
        // A position at or past the oldest open transaction cannot have been
        // handed out in this database's history: it was taken on another.
        if (after != null && after.xid >= read.horizon) {
            return Look(emptyList(), start, more = false, horizon = read.horizon, xmax = read.xmax, beyond = true)
        }
        checkRetention(orgId, after)
        if (after == null) return Look(emptyList(), start, more = false, horizon = read.horizon, xmax = read.xmax)

        val rows = read.rows
        val context = Context.load(orgId, rows)
        val items = mutableListOf<FeedEvent>()
        var position: Position.At = after
        var stoppedShort = false
        for (row in rows) {
            val events = eventsOf(row, cached, context).filter { types == null || it.type in types }
            if (items.isNotEmpty() && items.size + events.size > limit) {
                stoppedShort = true
                break
            }
            items += events
            position = row.position
            if (items.size >= limit && row !== rows.last()) {
                stoppedShort = true
                break
            }
        }
        val readAll = !stoppedShort && rows.size < SCAN_ROWS
        // Every row of the organization below the horizon has been read: the
        // position can move up to it, so a cursor that sees nothing still
        // keeps up with the purge.
        if (readAll && position < start) position = start
        return Look(items, position, more = !readAll, horizon = read.horizon, xmax = read.xmax)
    }

    /** Refuses a position the purge has passed. */
    private fun checkRetention(orgId: UUID, after: Position.At?) {
        if (after == null) return
        val mark = OutboxRetention.selectAll().where { OutboxRetention.id eq 1 }.firstOrNull()
            ?.let { Position.At(it[OutboxRetention.purgedXid], it[OutboxRetention.purgedSeq]) } ?: return
        // After the read: a purge that passed the position meanwhile may have
        // taken rows the read never saw.
        if (after < mark) throw expired(orgId, mark)
    }

    /**
     * Moves the feed to the history the database now has: rows whose
     * transaction id it has not reached yet were written in another history
     * and lose their place (their `xid` is cleared, so they are neither
     * delivered nor counted by the purge), and the mark starts at the
     * present, so every position taken before is refused.
     */
    private fun rebase(xmax: Long, start: Position.At) {
        log.warn(
            "The event feed's positions do not belong to this database's history (restored, or rewound): " +
                "every cursor is refused, and the feed starts again from the present",
        )
        connection().prepareStatement("UPDATE outbox SET xid = NULL WHERE xid >= ?").use { stmt ->
            stmt.setLong(1, xmax)
            stmt.executeUpdate()
        }
        OutboxRetention.update({ OutboxRetention.id eq 1 }) {
            it[purgedXid] = start.xid
            it[purgedSeq] = start.seq
            it[updatedAt] = java.time.Instant.now()
        }
    }

    /**
     * Says in the log, at most once a minute, when the oldest transaction
     * open on the server — which holds the feed back — is older than
     * [OLD_TRANSACTION]: its process, application and database, or the
     * prepared transaction's name.
     */
    private fun warnOfOldTransaction() {
        val now = System.currentTimeMillis()
        val last = lastTransactionCheck.get()
        if (now - last < 60_000 || !lastTransactionCheck.compareAndSet(last, now)) return
        val sql = """
            SELECT 'session' AS kind, pid::text AS who, application_name AS app, datname AS db,
                   EXTRACT(EPOCH FROM now() - xact_start)::bigint AS age
            FROM pg_stat_activity WHERE backend_xid IS NOT NULL AND xact_start IS NOT NULL
            UNION ALL
            SELECT 'prepared', gid, NULL, database, EXTRACT(EPOCH FROM now() - prepared)::bigint
            FROM pg_prepared_xacts
            ORDER BY age DESC
            LIMIT 1
        """.trimIndent()
        runCatching {
            connection().prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next() && rs.getLong("age") > OLD_TRANSACTION.seconds) {
                        log.warn(
                            "A transaction has been open for {}s ({} {}, application '{}', database '{}'): the event " +
                                "feed delivers nothing written since it began until it ends",
                            rs.getLong("age"), rs.getString("kind"), rs.getString("who"), rs.getString("app"), rs.getString("db"),
                        )
                    }
                }
            }
        }.onFailure { log.debug("could not look for old transactions: {}", it.message) }
    }

    /**
     * The organization's rows after [after] (none when it is null), up to the
     * oldest open transaction, and that horizon — read in one statement, so
     * the two agree.
     */
    private fun readRows(orgId: UUID, after: Position.At?): Read {
        val sql = """
            SELECT h.x AS horizon, h.xmax, o.seq, o.xid, o.id, o.event_type, o.aggregate_id, o.created_at, o.payload::text AS payload
            FROM (SELECT $HORIZON AS x, pg_snapshot_xmax(pg_current_snapshot())::text::bigint AS xmax) h
            LEFT JOIN LATERAL (
                SELECT seq, xid, id, event_type, aggregate_id, created_at, payload
                FROM outbox
                WHERE ? AND organization_id = ? AND (xid, seq) > (?, ?) AND xid < h.x
                ORDER BY xid, seq
                LIMIT ?
            ) o ON true
        """.trimIndent()
        val rows = mutableListOf<Row>()
        var horizon = 0L
        var xmax = 0L
        connection().prepareStatement(sql).use { stmt ->
            stmt.setBoolean(1, after != null)
            stmt.setObject(2, orgId)
            stmt.setLong(3, after?.xid ?: 0)
            stmt.setLong(4, after?.seq ?: 0)
            stmt.setInt(5, SCAN_ROWS)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    horizon = rs.getLong("horizon")
                    xmax = rs.getLong("xmax")
                    if (rs.getObject("seq") == null) continue
                    rows += Row(
                        position = Position.At(rs.getLong("xid"), rs.getLong("seq")),
                        id = rs.getObject("id") as UUID,
                        eventType = rs.getString("event_type"),
                        aggregateId = rs.getObject("aggregate_id") as UUID,
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        payload = Json.parseToJsonElement(rs.getString("payload")).jsonObject,
                    )
                }
            }
        }
        return Read(rows, horizon, xmax)
    }

    private val RESULT_EVENTS = setOf("probe_result.created", OutboxEmit.PROBE_RESULT_SKIPPED)
    private val SERVICE_EVENTS = setOf("resource.service.created", "resource.service.updated", "resource.service.deleted")
    private val PROJECT_EVENTS = setOf("resource.project.created", "resource.project.updated", "resource.project.deleted")
    private val WORKSPACE_EVENTS = setOf("resource.workspace.created", "resource.workspace.updated", "resource.workspace.deleted")
    private val VARIABLE_EVENTS = setOf("resource.variable.created", "resource.variable.updated", "resource.variable.deleted")

    /**
     * What a look needs to know about the resources its rows name, read once
     * per look — and only inside the organization, whatever a row says.
     */
    private class Context(
        val workspaces: Set<UUID>,
        val projectWorkspace: Map<UUID, UUID>,
        val serviceParents: Map<UUID, Pair<UUID, UUID>>,
        val variableKeys: Map<UUID, String>,
    ) {
        companion object {
            fun load(orgId: UUID, rows: List<Row>): Context {
                val workspaces = mutableSetOf<UUID>()
                val projects = mutableSetOf<UUID>()
                val services = mutableSetOf<UUID>()
                val variables = mutableMapOf<String, MutableSet<UUID>>()
                for (row in rows) {
                    val parent = row.payload.uuid("parentId")
                    when (row.eventType) {
                        in RESULT_EVENTS, RunState.SETTLED_EVENT -> row.payload.uuid("serviceId")?.let(services::add)
                        in WORKSPACE_EVENTS -> workspaces += row.aggregateId
                        in PROJECT_EVENTS -> parent?.let(workspaces::add)
                        in SERVICE_EVENTS -> parent?.let(projects::add)
                        in VARIABLE_EVENTS -> {
                            val scope = row.payload.text("scope") ?: continue
                            variables.getOrPut(scope) { mutableSetOf() } += row.aggregateId
                            when (scope) {
                                "workspace" -> parent?.let(workspaces::add)
                                "project" -> parent?.let(projects::add)
                                "service" -> parent?.let(services::add)
                            }
                        }
                    }
                }
                val inOrg = if (workspaces.isEmpty()) emptySet() else
                    Workspaces.select(Workspaces.id)
                        .where { (Workspaces.id inList workspaces) and (Workspaces.organizationId eq orgId) }
                        .map { it[Workspaces.id] }.toSet()
                val projectWorkspace = if (projects.isEmpty()) emptyMap() else
                    Projects.join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
                        .select(Projects.id, Projects.workspaceId)
                        .where { (Projects.id inList projects) and (Workspaces.organizationId eq orgId) }
                        .associate { it[Projects.id] to it[Projects.workspaceId] }
                val serviceParents = if (services.isEmpty()) emptyMap() else
                    Services.join(Projects, JoinType.INNER, Services.projectId, Projects.id)
                        .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
                        .select(Services.id, Services.projectId, Projects.workspaceId)
                        .where { (Services.id inList services) and (Workspaces.organizationId eq orgId) }
                        .associate { it[Services.id] to (it[Services.projectId] to it[Projects.workspaceId]) }
                val keys = mutableMapOf<UUID, String>()
                for ((scope, ids) in variables) {
                    val (table, id, key) = when (scope) {
                        "org" -> Triple(OrgVariables, OrgVariables.id, OrgVariables.key)
                        "workspace" -> Triple(WorkspaceVariables, WorkspaceVariables.id, WorkspaceVariables.key)
                        "project" -> Triple(ProjectVariables, ProjectVariables.id, ProjectVariables.key)
                        "service" -> Triple(ServiceVariables, ServiceVariables.id, ServiceVariables.key)
                        else -> continue
                    }
                    keys += keysOf(table, id, key, ids)
                }
                return Context(inOrg, projectWorkspace, serviceParents, keys)
            }

            private fun keysOf(table: Table, id: Column<UUID>, key: Column<String>, ids: Set<UUID>): Map<UUID, String> =
                table.select(id, key).where { id inList ids }.associate { it[id] to it[key] }
        }
    }

    /** The id of the status change a result row carries: stable, and a UUID of its own. */
    internal fun statusEventId(rowId: UUID): UUID = UUID.nameUUIDFromBytes("$rowId:status".toByteArray())

    /**
     * The events [row] is, as the caller may see them: none for a kind the
     * feed does not carry, or a resource the caller may not read.
     */
    private fun eventsOf(row: Row, cached: CachedPermissions, context: Context): List<FeedEvent> {
        val payload = row.payload
        val at = row.createdAt.toString()
        val change = row.eventType.substringAfterLast('.')
        fun event(type: String, resource: String, id: UUID, data: JsonObject, eventId: UUID = row.id) =
            FeedEvent(eventId.toString(), type, at, EventResource(resource, id.toString()), data)

        return when (row.eventType) {
            in RESULT_EVENTS -> {
                // Where the result's service lives now, from the database: the
                // row is not trusted to say whose it is.
                val service = payload.uuid("serviceId") ?: return emptyList()
                val (project, workspace) = context.serviceParents[service] ?: return emptyList()
                if (!visible(cached, "service", service, project, workspace)) return emptyList()
                val status = payload.text("status")
                buildList {
                    add(event(EventTypes.RESULT_RECORDED, "service", service, buildJsonObject {
                        put("resultId", payload.text("resultId"))
                        put("status", status)
                        put("runDurationMs", payload["runDurationMs"]?.jsonPrimitive?.longOrNull)
                        put("reason", payload.text("reason"))
                        put("projectId", project.toString())
                        put("workspaceId", workspace.toString())
                    }))
                    if (payload["statusChanged"]?.jsonPrimitive?.booleanOrNull == true) {
                        add(event(EventTypes.SERVICE_STATUS_CHANGED, "service", service, buildJsonObject {
                            put("status", status)
                            put("previousStatus", payload["previousStatus"] ?: JsonNull)
                            put("resultId", payload.text("resultId"))
                        }, eventId = statusEventId(row.id)))
                    }
                }
            }
            in WORKSPACE_EVENTS -> {
                val workspace = row.aggregateId
                if (workspace !in context.workspaces) return emptyList()
                if (!visible(cached, "workspace", workspace, null, workspace)) return emptyList()
                listOf(event("workspace.$change", "workspace", workspace, JsonObject(emptyMap())))
            }
            in PROJECT_EVENTS -> {
                val workspace = payload.uuid("parentId")?.takeIf { it in context.workspaces } ?: return emptyList()
                if (!visible(cached, "project", row.aggregateId, row.aggregateId, workspace)) return emptyList()
                listOf(event("project.$change", "project", row.aggregateId, buildJsonObject {
                    put("workspaceId", workspace.toString())
                }))
            }
            in SERVICE_EVENTS -> {
                val project = payload.uuid("parentId") ?: return emptyList()
                val workspace = context.projectWorkspace[project] ?: return emptyList()
                if (!visible(cached, "service", row.aggregateId, project, workspace)) return emptyList()
                listOf(event("service.$change", "service", row.aggregateId, buildJsonObject {
                    put("projectId", project.toString())
                    put("workspaceId", workspace.toString())
                }))
            }
            in VARIABLE_EVENTS -> {
                val scope = payload.text("scope") ?: return emptyList()
                val parent = payload.uuid("parentId") ?: return emptyList()
                val allowed = when (scope) {
                    // Organization variables are read under the settings section.
                    "org" -> cached.org.settings.canRead()
                    "workspace" -> parent in context.workspaces && visible(cached, "workspace", parent, null, parent)
                    "project" -> context.projectWorkspace[parent]?.let { visible(cached, "project", parent, parent, it) } == true
                    "service" -> context.serviceParents[parent]?.let { (p, w) -> visible(cached, "service", parent, p, w) } == true
                    // A webhook's variables are not part of the feed.
                    else -> false
                }
                if (!allowed) return emptyList()
                // Its key, never its value: the value is not in the outbox, and
                // is not looked up here.
                listOf(event("variable.$change", "variable", row.aggregateId, buildJsonObject {
                    put("scope", scope)
                    put("scopeId", parent.toString())
                    put("key", context.variableKeys[row.aggregateId])
                }))
            }
            RunState.SETTLED_EVENT -> {
                // A run is read as its service's results are.
                val service = payload.uuid("serviceId") ?: return emptyList()
                val (project, workspace) = context.serviceParents[service] ?: return emptyList()
                if (!visible(cached, "service", service, project, workspace)) return emptyList()
                listOf(event(EventTypes.RUN_SETTLED, "service", service, buildJsonObject {
                    put("runId", payload.text("runId"))
                    put("serviceId", service.toString())
                    put("state", payload.text("state"))
                    put("status", payload.text("status"))
                    put("reason", payload.text("reason"))
                    put("superseded", payload["superseded"]?.jsonPrimitive?.booleanOrNull == true)
                }))
            }
            SystemAlertService.ALERT_RAISED_EVENT -> {
                // The warning log's permission: settings write.
                if (!cached.org.settings.canWrite()) return emptyList()
                listOf(event(EventTypes.ALERT_RAISED, "alert", row.aggregateId, buildJsonObject {
                    put("type", payload.text("alertType"))
                    put("subject", payload.text("subject"))
                    put("severity", payload.text("severity"))
                }))
            }
            else -> emptyList()
        }
    }

    /**
     * Whether the caller may read the workspace, project or service — the
     * check the dashboard's read of it makes, with the same inheritance: a
     * service through its project and workspace, a project through its
     * workspace.
     */
    internal fun visible(cached: CachedPermissions, type: String, id: UUID, project: UUID?, workspace: UUID): Boolean =
        when (type) {
            "workspace" -> canAccessResource(cached, "workspace", id)
            "project" -> canAccessResource(cached, "project", id, listOf("workspace::$workspace"))
            "service" -> canAccessResource(cached, "service", id, listOf("project::$project", "workspace::$workspace"))
            else -> false
        }

    private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.uuid(field: String): UUID? = text(field)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
