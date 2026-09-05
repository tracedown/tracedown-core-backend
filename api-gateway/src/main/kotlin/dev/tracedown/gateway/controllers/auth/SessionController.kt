package dev.tracedown.gateway.controllers.auth

import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.realtime.RealtimePublisher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.auth.SessionSummary
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

object SessionController {

    /** Lists active sessions for the current user with PFS. */
    fun listSessions(userId: UUID, currentSessionId: UUID, pfs: PfsParams): Page<SessionSummary> {
        return transaction {
            val query = Sessions.selectAll()
                .where {
                    (Sessions.userId eq userId) and
                    (Sessions.status eq SessionStatus.ACTIVE) and
                    (Sessions.revoked eq false) and
                    (Sessions.expiresAt greater Instant.now())
                }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { row ->
                SessionSummary(
                    id = row[Sessions.id].toString(),
                    ipAddress = row[Sessions.ipAddress],
                    userAgent = row[Sessions.userAgent],
                    createdAt = row[Sessions.createdAt].toString(),
                    lastActiveAt = row[Sessions.lastActiveAt].toString(),
                    expiresAt = row[Sessions.expiresAt].toString(),
                    current = row[Sessions.id] == currentSessionId,
                )
            }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    fun revokeSession(sessionId: UUID, userId: UUID, currentSessionId: UUID, orgId: UUID? = null) {
        if (sessionId == currentSessionId) {
            throw ForbiddenException()
        }

        transaction {
            val updated = Sessions.update({
                (Sessions.id eq sessionId) and
                (Sessions.userId eq userId) and
                (Sessions.revoked eq false)
            }) {
                it[revoked] = true
            }

            if (updated == 0) throw NotFoundException()
        }
        if (orgId != null) {
            RealtimePublisher.publish("session:$sessionId", orgId, "session.revoked",
                buildJsonObject { put("reason", "revoked") })
        }
    }

    fun revokeAllOtherSessions(userId: UUID, currentSessionId: UUID, orgId: UUID? = null): Int {
        val revokedIds = transaction {
            val ids = Sessions.selectAll()
                .where {
                    (Sessions.userId eq userId) and
                    (Sessions.id neq currentSessionId) and
                    (Sessions.revoked eq false)
                }
                .map { it[Sessions.id] }

            Sessions.update({
                (Sessions.userId eq userId) and
                (Sessions.id neq currentSessionId) and
                (Sessions.revoked eq false)
            }) {
                it[revoked] = true
            }
            ids
        }
        if (orgId != null) {
            for (sid in revokedIds) {
                RealtimePublisher.publish("session:$sid", orgId, "session.revoked",
                    buildJsonObject { put("reason", "revoked_all") })
            }
        }
        return revokedIds.size
    }
}
