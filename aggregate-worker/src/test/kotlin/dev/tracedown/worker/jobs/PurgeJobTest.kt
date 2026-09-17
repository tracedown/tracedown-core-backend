package dev.tracedown.worker.jobs

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.NotificationLog
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgEncryptionKeys
import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.PasswordResetTokens
import dev.tracedown.common.models.PendingBodyDeletions
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.ResourceWebhookAccess
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.TotpRecoveryCodes
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.config.RetentionConfig
import dev.tracedown.common.storage.BodyDeleteOutcome
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.worker.data.JobWatermarks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * DB-backed tests for [PurgeJob] and the retention jobs, against the real
 * schema (full Flyway migrations of every module, copied into test resources
 * by the build).
 */
@Testcontainers
class PurgeJobTest {

    /** Records deletions instead of touching storage; optionally fails. */
    private class FakeStorage(private val failWith: Exception? = null) : BodyStorageClient() {
        val deleted = mutableListOf<String>()
        override fun delete(uri: String): Boolean {
            failWith?.let { throw it }
            deleted.add(uri)
            return true
        }
        override fun deleteAll(uris: Collection<String>): BodyDeleteOutcome = BodyDeleteOutcome(
            failed = uris.mapNotNull { uri ->
                runCatching { delete(uri) }.exceptionOrNull()?.let { uri to it.message }
            }.toMap(),
        )
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_purge_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now()
        private val PAST: Instant = NOW.minus(1, ChronoUnit.HOURS)

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            // Default connection — PurgeJob's transactions resolve against it.
            Database.connect(HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = "org.postgresql.Driver"
            }))
        }

        // ── Insert helpers ──

        private fun insertUser(purge: Boolean = false): UUID {
            val id = UUID.randomUUID()
            Users.insert {
                it[Users.id] = id
                it[email] = "u-$id@t.dev"
                it[passwordHash] = "x"
                it[displayName] = "u"
                it[createdAt] = NOW
                if (purge) {
                    it[deleted] = true
                    it[deletedAt] = PAST
                    it[purgeAfter] = PAST
                }
            }
            return id
        }

        private fun insertOrg(ownerId: UUID, purge: Boolean = false, id: UUID = UUID.randomUUID()): UUID {
            Organizations.insert {
                it[Organizations.id] = id
                it[name] = "org"
                it[Organizations.ownerId] = ownerId
                it[createdAt] = NOW
                if (purge) {
                    it[deleted] = true
                    it[deletedAt] = PAST
                    it[purgeAfter] = PAST
                }
            }
            return id
        }

        private fun insertWorkspace(orgId: UUID): UUID {
            val id = UUID.randomUUID()
            Workspaces.insert {
                it[Workspaces.id] = id
                it[organizationId] = orgId
                it[name] = "ws"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertProject(wsId: UUID): UUID {
            val id = UUID.randomUUID()
            Projects.insert {
                it[Projects.id] = id
                it[workspaceId] = wsId
                it[name] = "proj"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertService(projectId: UUID, purge: Boolean = false): UUID {
            val id = UUID.randomUUID()
            Services.insert {
                it[Services.id] = id
                it[Services.projectId] = projectId
                it[name] = "svc"
                it[createdAt] = NOW
                if (purge) {
                    it[deleted] = true
                    it[deletedAt] = PAST
                    it[purgeAfter] = PAST
                }
            }
            return id
        }

        private fun insertResult(serviceId: UUID, projectId: UUID, wsId: UUID, orgId: UUID): UUID {
            val id = UUID.randomUUID()
            ProbeResults.insert {
                it[ProbeResults.id] = id
                it[ProbeResults.serviceId] = serviceId
                it[startedAt] = NOW
                it[status] = "success"
                it[runDurationMs] = 10
                it[rawResult] = JsonObject(emptyMap())
                it[ProbeResults.projectId] = projectId
                it[workspaceId] = wsId
                it[organizationId] = orgId
            }
            return id
        }

        /** A default-store step with an explicit step number. */
        private fun insertStepAt(resultId: UUID, bodyUrl: String?, stepNumber: Short): UUID {
            val id = UUID.randomUUID()
            ProbeSteps.insert {
                it[ProbeSteps.id] = id
                it[probeResultId] = resultId
                it[stepNum] = stepNumber
                it[requestUrl] = "https://example.test/"
                it[responseBodyStorageUrl] = bodyUrl
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertStep(resultId: UUID, bodyUrl: String?): UUID {
            val id = UUID.randomUUID()
            ProbeSteps.insert {
                it[ProbeSteps.id] = id
                it[probeResultId] = resultId
                it[stepNum] = 1
                it[requestUrl] = "https://example.test/"
                it[responseBodyStorageUrl] = bodyUrl
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertMembership(orgId: UUID, userId: UUID, invitedBy: UUID? = null): UUID {
            val id = UUID.randomUUID()
            OrgUsers.insert {
                it[OrgUsers.id] = id
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[status] = "active"
                it[inviteToken] = ""
                it[OrgUsers.invitedBy] = invitedBy
            }
            return id
        }

        /** A never-accepted invite membership with an explicit expiry. */
        private fun insertInvite(orgId: UUID, userId: UUID, expiresAt: Instant): UUID {
            val id = UUID.randomUUID()
            OrgUsers.insert {
                it[OrgUsers.id] = id
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[status] = "invited"
                it[isActive] = false
                it[inviteToken] = UUID.randomUUID().toString()
                it[invitedAt] = expiresAt.minusSeconds(86_400)
                it[inviteExpiresAt] = expiresAt
            }
            return id
        }

        /** A stub account exactly as an invite pre-creates it (no password). */
        private fun insertStubUser(): UUID {
            val id = UUID.randomUUID()
            Users.insert {
                it[Users.id] = id
                it[email] = "stub-$id@example.com"
                it[passwordHash] = ""
                it[displayName] = "stub-$id"
                it[isActive] = false
                it[createdAt] = NOW.minus(30, ChronoUnit.DAYS)
            }
            return id
        }

        private fun insertGroup(orgId: UUID): UUID {
            val id = UUID.randomUUID()
            OrgGroups.insert {
                it[OrgGroups.id] = id
                it[organizationId] = orgId
                it[name] = "grp"
            }
            return id
        }

        private fun insertUserGroup(orgUserId: UUID, groupId: UUID): UUID {
            val id = UUID.randomUUID()
            OrgUserGroups.insert {
                it[OrgUserGroups.id] = id
                it[OrgUserGroups.orgUserId] = orgUserId
                it[orgGroupId] = groupId
            }
            return id
        }

        private fun insertSilence(orgUserId: UUID, serviceId: UUID? = null): UUID {
            val id = UUID.randomUUID()
            NotificationSilences.insert {
                it[NotificationSilences.id] = id
                it[NotificationSilences.orgUserId] = orgUserId
                it[NotificationSilences.serviceId] = serviceId
                it[channel] = "email"
            }
            return id
        }

        private fun insertPermission(orgId: UUID): UUID =
            insertPermissionFor(orgId, "org_user", UUID.randomUUID())

        private fun insertPermissionFor(orgId: UUID, principalType: String, principalId: UUID): UUID {
            val id = UUID.randomUUID()
            ResourcePermissions.insert {
                it[ResourcePermissions.id] = id
                it[ResourcePermissions.orgId] = orgId
                it[ResourcePermissions.principalType] = principalType
                it[ResourcePermissions.principalId] = principalId
                it[resourceType] = "workspace"
                it[resourceId] = UUID.randomUUID()
            }
            return id
        }

        private fun insertDelivery(orgId: UUID, purge: Boolean = false): UUID {
            val id = UUID.randomUUID()
            WebhookDeliveries.insert {
                it[WebhookDeliveries.id] = id
                it[organizationId] = orgId
                it[name] = "wh"
                it[url] = "https://example.test/hook"
                it[createdAt] = NOW
                if (purge) {
                    it[deleted] = true
                    it[deletedAt] = PAST
                    it[purgeAfter] = PAST
                }
            }
            return id
        }

        private fun insertWebhookAccess(orgId: UUID, deliveryId: UUID): UUID {
            val id = UUID.randomUUID()
            ResourceWebhookAccess.insert {
                it[ResourceWebhookAccess.id] = id
                it[ResourceWebhookAccess.orgId] = orgId
                it[resourceType] = "service"
                it[resourceId] = UUID.randomUUID()
                it[webhookDeliveryId] = deliveryId
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertAudit(
            orgId: UUID,
            userId: UUID?,
            createdAt: Instant = NOW,
            entityType: String? = null,
            entityId: String? = null,
            entityDisplayName: String? = null,
            comment: String? = null,
            diff: JsonObject? = null,
        ): UUID {
            val id = UUID.randomUUID()
            val type = entityType
            val entity = entityId
            val display = entityDisplayName
            val note = comment
            val diffJson = diff
            OrgAuditLog.insert {
                it[OrgAuditLog.id] = id
                it[organizationId] = orgId
                it[OrgAuditLog.userId] = userId
                it[action] = "test.action"
                it[OrgAuditLog.entityType] = type
                it[OrgAuditLog.entityId] = entity
                it[OrgAuditLog.entityDisplayName] = display
                it[OrgAuditLog.comment] = note
                it[OrgAuditLog.diff] = diffJson
                it[OrgAuditLog.createdAt] = createdAt
            }
            return id
        }

        private fun insertApiKey(orgId: UUID, createdBy: UUID): UUID {
            val id = UUID.randomUUID()
            ApiKeys.insert {
                it[ApiKeys.id] = id
                it[organizationId] = orgId
                it[ApiKeys.createdBy] = createdBy
                it[name] = "key"
                it[keyHash] = "h"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertOrgVariable(orgId: UUID, createdBy: UUID): UUID {
            val id = UUID.randomUUID()
            OrgVariables.insert {
                it[OrgVariables.id] = id
                it[organizationId] = orgId
                it[OrgVariables.createdBy] = createdBy
                it[key] = "k-$id"
                it[value] = "v"
                it[secret] = false
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
            return id
        }

        private fun insertResetToken(userId: UUID, expiresAt: Instant = NOW.plusSeconds(3600)): UUID {
            val id = UUID.randomUUID()
            PasswordResetTokens.insert {
                it[PasswordResetTokens.id] = id
                it[PasswordResetTokens.userId] = userId
                it[tokenHash] = "t"
                it[PasswordResetTokens.expiresAt] = expiresAt
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertRecoveryCode(userId: UUID): UUID {
            val id = UUID.randomUUID()
            TotpRecoveryCodes.insert {
                it[TotpRecoveryCodes.id] = id
                it[TotpRecoveryCodes.userId] = userId
                it[codeHash] = "c"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertSession(userId: UUID, orgId: UUID? = null): UUID {
            val id = UUID.randomUUID()
            Sessions.insert {
                it[Sessions.id] = id
                it[Sessions.userId] = userId
                it[organizationId] = orgId
                it[sessionTokenHash] = id.toString()
                it[expiresAt] = NOW.plusSeconds(3600)
                it[lastActiveAt] = NOW
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertNotificationLog(
            orgId: UUID,
            serviceId: UUID? = null,
            resultId: UUID? = null,
            createdAt: Instant = NOW,
            recipient: String = "r@t.dev",
        ): UUID {
            val id = UUID.randomUUID()
            val to = recipient
            NotificationLog.insert {
                it[NotificationLog.id] = id
                it[organizationId] = orgId
                it[NotificationLog.serviceId] = serviceId
                it[probeResultId] = resultId
                it[channel] = "email"
                it[NotificationLog.recipient] = to
                it[status] = "sent"
                it[NotificationLog.createdAt] = createdAt
            }
            return id
        }

        private fun emailOf(userId: UUID): String =
            Users.selectAll().where { Users.id eq userId }.single()[Users.email]

        private fun count(table: Table, where: org.jetbrains.exposed.v1.core.Op<Boolean>): Long =
            table.selectAll().where { where }.count()

        private const val AES_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /**
         * Fresh crypto engine under the test platform key. Re-initialized per
         * use so an in-memory DEK cache can never mask a shredded key row.
         */
        private fun freshCrypto() = VariableCrypto.init(AES_KEY)

        private fun runPurge(storage: BodyStorageClient = FakeStorage()) =
            runBlocking { PurgeJob(storageClient = storage).execute() }
    }

    // ── (a) User erasure ──

    @Test
    fun `user purge cascades tokens codes memberships and anonymizes what remains`() {
        lateinit var ids: List<UUID>
        lateinit var uid: UUID
        lateinit var auditId: UUID
        lateinit var apiKeyId: UUID
        lateinit var varId: UUID
        lateinit var ownerMembership: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            uid = insertUser(purge = true)

            val membership = insertMembership(org, uid)
            val group = insertGroup(org)
            insertUserGroup(membership, group)
            insertSilence(membership)
            // The purging user invited the owner (tests invited_by anonymization).
            ownerMembership = insertMembership(org, owner, invitedBy = uid)

            insertResetToken(uid)
            insertRecoveryCode(uid)
            insertSession(uid)
            auditId = insertAudit(org, uid)
            apiKeyId = insertApiKey(org, createdBy = uid)
            varId = insertOrgVariable(org, createdBy = uid)
            ids = listOf(org, membership, group)
        }

        runPurge()

        transaction {
            val (org, membership, group) = ids
            assertEquals(0, count(Users, Users.id eq uid), "purged account is gone")
            assertEquals(0, count(PasswordResetTokens, PasswordResetTokens.userId eq uid))
            assertEquals(0, count(TotpRecoveryCodes, TotpRecoveryCodes.userId eq uid))
            assertEquals(0, count(Sessions, Sessions.userId eq uid))
            assertEquals(0, count(OrgUsers, OrgUsers.id eq membership))
            assertEquals(0, count(OrgUserGroups, OrgUserGroups.orgUserId eq membership))

            // Kept, with the account link cleared.
            val audit = OrgAuditLog.selectAll().where { OrgAuditLog.id eq auditId }.single()
            assertNull(audit[OrgAuditLog.userId], "audit history kept, actor anonymized")
            val key = ApiKeys.selectAll().where { ApiKeys.id eq apiKeyId }.single()
            assertNull(key[ApiKeys.createdBy], "api key outlives its creator")
            val variable = OrgVariables.selectAll().where { OrgVariables.id eq varId }.single()
            assertNull(variable[OrgVariables.createdBy], "variable outlives its creator")
            val invited = OrgUsers.selectAll().where { OrgUsers.id eq ownerMembership }.single()
            assertNull(invited[OrgUsers.invitedBy], "membership outlives its inviter")

            // Untouched bystanders.
            assertEquals(1, count(Organizations, Organizations.id eq org))
            assertEquals(1, count(OrgGroups, OrgGroups.id eq group))
        }
    }

    @Test
    fun `user purge removes the account's dangling resource permissions`() {
        lateinit var uid: UUID
        lateinit var membership: UUID
        lateinit var ownerMembership: UUID
        lateinit var byMembership: UUID
        lateinit var bystander: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)                 // org lives on (not purging)
            uid = insertUser(purge = true)
            membership = insertMembership(org, uid)
            ownerMembership = insertMembership(org, owner)
            // A grant keyed to the purging account's membership.
            byMembership = insertPermissionFor(org, "org_user", membership)
            // A grant to a surviving member's membership — must remain.
            bystander = insertPermissionFor(org, "org_user", ownerMembership)
        }

        runPurge()

        transaction {
            assertEquals(0, count(Users, Users.id eq uid), "purged account is gone")
            assertEquals(0, count(ResourcePermissions, ResourcePermissions.id eq byMembership),
                "membership-principal grant removed")
            assertEquals(1, count(ResourcePermissions, ResourcePermissions.id eq bystander),
                "a surviving member's grant is untouched")
        }
    }

    @Test
    fun `expired never-accepted invite is swept and its stub account erased`() {
        lateinit var stubUid: UUID
        lateinit var inviteMembership: UUID
        lateinit var realUid: UUID
        lateinit var staleInvite: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val otherOrg = insertOrg(insertUser())

            // A stub account whose only tie is an invite that expired long ago.
            stubUid = insertStubUser()
            inviteMembership = insertInvite(org, stubUid, expiresAt = NOW.minus(10, ChronoUnit.DAYS))

            // A real account that is active elsewhere but has a stale invite to a
            // second org — only the dead invite should go, the account must live on.
            realUid = insertUser()
            insertMembership(otherOrg, realUid)
            staleInvite = insertInvite(org, realUid, expiresAt = NOW.minus(10, ChronoUnit.DAYS))
        }

        // The sweep runs in its own transaction (as the job does).
        val swept = transaction { dev.tracedown.common.onboarding.AccountLifecycle.sweepExpiredInvites() }
        assertEquals(2, swept, "both expired invites swept")

        transaction {
            // The stub account is scheduled for deletion, its invite soft-deleted.
            val stub = Users.selectAll().where { Users.id eq stubUid }.single()
            assertTrue(stub[Users.deleted])
            assertFalse(stub[Users.isActive])
            assertTrue(stub[Users.purgeAfter] != null)
            assertTrue(OrgUsers.selectAll().where { OrgUsers.id eq inviteMembership }.single()[OrgUsers.deleted])

            // The real account keeps its row; only the stale invite membership dies.
            val real = Users.selectAll().where { Users.id eq realUid }.single()
            assertFalse(real[Users.deleted])
            assertTrue(OrgUsers.selectAll().where { OrgUsers.id eq staleInvite }.single()[OrgUsers.deleted])

            // Bring the stub's purge_after into the past so the purge finishes it now.
            Users.update({ Users.id eq stubUid }) { it[purgeAfter] = PAST }
        }

        runPurge()

        transaction {
            assertEquals(0, count(Users, Users.id eq stubUid), "stub account (and its email) purged")
            assertEquals(0, count(OrgUsers, OrgUsers.id eq inviteMembership), "invite membership gone")
            assertEquals(1, count(Users, Users.id eq realUid), "real account survives")
        }
    }

    @Test
    fun `user purge strips the erased account's identifiers from audit entries about it`() {
        lateinit var uid: UUID
        lateinit var email: String
        lateinit var inviteRow: UUID
        lateinit var userEntityRow: UUID
        lateinit var byActorOnly: UUID
        lateinit var bystander: UUID
        val inviteId = UUID.randomUUID().toString()
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            uid = insertUser(purge = true)
            email = emailOf(uid)
            insertMembership(org, uid)

            // The shape nothing reached: the ACTOR is the inviter and the ENTITY
            // is the invite, so neither link is the invitee's — but the address
            // is right there in the display name, the comment and the diff.
            inviteRow = insertAudit(
                org, owner, entityType = "invite", entityId = inviteId,
                entityDisplayName = email,
                comment = "Invited $email",
                diff = buildJsonObject {
                    put("email", buildJsonObject { put("old", JsonPrimitive(email)); put("new", JsonPrimitive("x@t.dev")) })
                    put("isActive", buildJsonObject { put("from", JsonPrimitive("false")); put("to", JsonPrimitive("true")) })
                },
            )
            // Entity IS the account: entity_id alone identifies the subject, so
            // this one is scrubbed even though it never spells out the address.
            userEntityRow = insertAudit(
                org, owner, entityType = "user", entityId = uid.toString(),
                entityDisplayName = "Purged Person",
            )
            // Acted on something else — only the actor link is theirs to lose.
            byActorOnly = insertAudit(org, uid, entityType = "workspace", entityDisplayName = "some workspace")
            // Somebody else's entry entirely, and their address is not erasing.
            bystander = insertAudit(
                org, owner, entityType = "user", entityId = owner.toString(),
                entityDisplayName = emailOf(owner),
            )
        }

        runPurge()

        transaction {
            assertEquals(0, count(Users, Users.id eq uid), "purged account is gone")

            val invite = OrgAuditLog.selectAll().where { OrgAuditLog.id eq inviteRow }.single()
            assertNull(invite[OrgAuditLog.entityDisplayName], "the invitee's address is gone from the display name")
            assertEquals("Invited", invite[OrgAuditLog.comment],
                "the address is stripped out of the comment, the note itself survives")
            // The audit trail proper survives: what happened, to what, and when.
            assertEquals("test.action", invite[OrgAuditLog.action])
            assertEquals("invite", invite[OrgAuditLog.entityType])
            assertEquals(inviteId, invite[OrgAuditLog.entityId], "what was acted on is still recorded")
            // The diff keeps its non-identity field and loses the address.
            val diff = invite[OrgAuditLog.diff]!!.jsonObject
            assertFalse(diff.containsKey("email"), "the email diff is gone")
            assertTrue(diff.containsKey("isActive"), "a non-identity change is preserved")

            val userEntity = OrgAuditLog.selectAll().where { OrgAuditLog.id eq userEntityRow }.single()
            assertNull(userEntity[OrgAuditLog.entityDisplayName],
                "an entry whose entity IS the account is found by entity_id, address or not")
            assertEquals(uid.toString(), userEntity[OrgAuditLog.entityId], "the entity reference itself is kept")

            val actorOnly = OrgAuditLog.selectAll().where { OrgAuditLog.id eq byActorOnly }.single()
            assertNull(actorOnly[OrgAuditLog.userId], "actor anonymized as before")
            assertEquals("some workspace", actorOnly[OrgAuditLog.entityDisplayName],
                "an entry merely ACTED on by the account keeps its own entity name")

            val other = OrgAuditLog.selectAll().where { OrgAuditLog.id eq bystander }.single()
            assertEquals(emailOf(other[OrgAuditLog.userId]!!), other[OrgAuditLog.entityDisplayName],
                "somebody else's entry is untouched")

            // The clinching assertion: the address is nowhere in the audit log,
            // in any of the three columns that ever carry one.
            val leaks = OrgAuditLog.selectAll().count { row ->
                listOf(
                    row[OrgAuditLog.entityDisplayName],
                    row[OrgAuditLog.comment],
                    row[OrgAuditLog.diff]?.toString(),
                ).any { it?.contains(email, ignoreCase = true) == true }
            }
            assertEquals(0, leaks, "no erased address may survive anywhere in the audit log")
        }
    }

    @Test
    fun `user purge deletes the delivery log addressed to the erased account`() {
        lateinit var uid: UUID
        lateinit var theirs: UUID
        lateinit var theirsOtherOrg: UUID
        lateinit var somebodyElses: UUID
        transaction {
            val owner = insertUser()
            val orgA = insertOrg(owner)
            val orgB = insertOrg(insertUser())
            uid = insertUser(purge = true)
            insertMembership(orgA, uid)
            val email = emailOf(uid)

            // notification_log has no user FK, so nothing linked these rows to
            // the account; they were cleared only when the whole ORG purged.
            theirs = insertNotificationLog(orgA, recipient = email)
            // Case differs — the address is still theirs.
            theirsOtherOrg = insertNotificationLog(orgB, recipient = email.uppercase())
            somebodyElses = insertNotificationLog(orgA, recipient = emailOf(owner))
        }

        runPurge()

        transaction {
            assertEquals(0, count(Users, Users.id eq uid))
            assertEquals(0, count(NotificationLog, NotificationLog.id eq theirs),
                "delivery history carrying the erased address is deleted")
            assertEquals(0, count(NotificationLog, NotificationLog.id eq theirsOtherOrg),
                "in every org they were alerted in, whatever the case")
            assertEquals(1, count(NotificationLog, NotificationLog.id eq somebodyElses),
                "another recipient's history is untouched")
        }
    }

    @Test
    fun `a purging account still owning an organization is skipped, not erased`() {
        lateinit var uid: UUID
        transaction {
            uid = insertUser(purge = true)
            insertOrg(uid) // live org owned by the purging account
            insertResetToken(uid)
        }

        runPurge()

        transaction {
            assertEquals(1, count(Users, Users.id eq uid), "account kept while it owns an organization")
            assertEquals(1, count(PasswordResetTokens, PasswordResetTokens.userId eq uid), "cascade not started either")
        }
    }

    // ── (b) Organization erasure ──

    @Test
    fun `org purge cascades groups permissions webhook access audit and clears org links`() {
        lateinit var org: UUID
        lateinit var owner: UUID
        lateinit var sessionId: UUID
        val fake = FakeStorage()
        transaction {
            owner = insertUser()
            org = insertOrg(owner, purge = true)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            val result = insertResult(svc, proj, ws, org)
            insertStep(result, "file:///tmp/org-body-1")

            val membership = insertMembership(org, owner)
            val group = insertGroup(org)
            insertUserGroup(membership, group)
            insertSilence(membership, serviceId = svc)
            insertPermission(org)
            val delivery = insertDelivery(org)
            insertWebhookAccess(org, delivery)
            insertAudit(org, owner)
            insertNotificationLog(org, svc, result)
            insertApiKey(org, owner)

            sessionId = insertSession(owner, orgId = org)
            Users.update({ Users.id eq owner }) { it[selectedOrgId] = org }
        }

        runPurge(fake)

        transaction {
            assertEquals(0, count(Organizations, Organizations.id eq org))
            assertEquals(0, count(Workspaces, Workspaces.organizationId eq org))
            assertEquals(0, count(OrgUsers, OrgUsers.organizationId eq org))
            assertEquals(0, count(OrgGroups, OrgGroups.organizationId eq org))
            assertEquals(0, count(ResourcePermissions, ResourcePermissions.orgId eq org))
            assertEquals(0, count(WebhookDeliveries, WebhookDeliveries.organizationId eq org))
            assertEquals(0, count(ResourceWebhookAccess, ResourceWebhookAccess.orgId eq org))
            assertEquals(0, count(OrgAuditLog, OrgAuditLog.organizationId eq org))
            assertEquals(0, count(NotificationLog, NotificationLog.organizationId eq org))
            assertEquals(0, count(ApiKeys, ApiKeys.organizationId eq org))

            // The member's account and session survive; only the org link is cleared.
            assertEquals(1, count(Users, Users.id eq owner))
            val session = Sessions.selectAll().where { Sessions.id eq sessionId }.single()
            assertNull(session[Sessions.organizationId], "session outlives the purged org selection")
            val user = Users.selectAll().where { Users.id eq owner }.single()
            assertNull(user[Users.selectedOrgId], "persisted org selection cleared")

            assertEquals(listOf("file:///tmp/org-body-1"), fake.deleted, "org purge removed stored bodies")
        }
    }

    @Test
    fun `org purge crypto-shreds the org's secrets by deleting its encryption key`() {
        lateinit var org: UUID
        lateinit var stored: String
        freshCrypto()
        transaction {
            org = insertOrg(insertUser(), purge = true)
            // Envelope-encrypting a secret mints the org's DEK row.
            stored = VariableCrypto.encrypt(org, "hunter2", "org", "apiKey")
            assertEquals(1, count(OrgEncryptionKeys, OrgEncryptionKeys.orgId eq org))
            assertEquals("hunter2", VariableCrypto.decrypt(org, stored, null, "org", "apiKey"))
        }

        runPurge()

        freshCrypto() // no cached DEK — mirrors any process after the purge
        transaction {
            assertEquals(0, count(Organizations, Organizations.id eq org))
            assertEquals(0, count(OrgEncryptionKeys, OrgEncryptionKeys.orgId eq org), "DEK row shredded")
            val e = assertThrows(IllegalStateException::class.java) {
                VariableCrypto.decrypt(org, stored, null, "org", "apiKey")
            }
            assertTrue(e.message!!.contains("unrecoverable"), "ciphertext is unrecoverable after the shred")
        }
    }

    @Test
    fun `the encryption key is shredded first, even when the org cascade fails`() {
        lateinit var org: UUID
        freshCrypto()
        transaction {
            org = insertOrg(insertUser(), purge = true)
            VariableCrypto.encrypt(org, "hunter2", "org", "apiKey")

            // An out-of-schema FK makes the organizations cascade fail and roll
            // back — the key delete runs in its own earlier transaction and
            // must stick regardless.
            exec("CREATE TABLE org_purge_blocker (id UUID PRIMARY KEY, org_id UUID NOT NULL REFERENCES organizations(id))")
            exec("INSERT INTO org_purge_blocker (id, org_id) VALUES ('${UUID.randomUUID()}', '$org')")
        }

        try {
            runPurge()

            transaction {
                assertEquals(1, count(Organizations, Organizations.id eq org), "blocked org purge rolled back")
                assertEquals(
                    0, count(OrgEncryptionKeys, OrgEncryptionKeys.orgId eq org),
                    "key row already shredded despite the failed cascade",
                )
            }
        } finally {
            transaction { exec("DROP TABLE IF EXISTS org_purge_blocker") }
            runPurge() // let the unblocked cascade finish so later tests see a clean slate
        }
    }

    // ── (c) Failure isolation ──

    @Test
    fun `one failing entity group does not block the others and is retried next run`() {
        lateinit var svc: UUID
        lateinit var uid: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            svc = insertService(proj, purge = true)
            uid = insertUser(purge = true)

            // An out-of-schema table the cascade cannot know about: purging the
            // service must fail on its FK, purging the user must still succeed.
            exec("CREATE TABLE purge_blocker (id UUID PRIMARY KEY, service_id UUID NOT NULL REFERENCES services(id))")
            exec("INSERT INTO purge_blocker (id, service_id) VALUES ('${UUID.randomUUID()}', '$svc')")
        }

        try {
            runPurge()

            transaction {
                assertEquals(1, count(Services, Services.id eq svc), "blocked service purge rolled back")
                assertEquals(0, count(Users, Users.id eq uid), "user purge unaffected by the failing group")
            }

            // Unblock and prove the failed group succeeds on the next run.
            transaction { exec("DROP TABLE purge_blocker") }
            runPurge()
            transaction {
                assertEquals(0, count(Services, Services.id eq svc), "service purge retried and completed")
            }
        } finally {
            transaction { exec("DROP TABLE IF EXISTS purge_blocker") }
        }
    }

    // ── (d) Stored bodies ──

    @Test
    fun `service purge deletes stored response bodies before the rows`() {
        lateinit var svc: UUID
        lateinit var result: UUID
        lateinit var logId: UUID
        val fake = FakeStorage()
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            svc = insertService(proj, purge = true)
            result = insertResult(svc, proj, ws, org)
            insertStep(result, "file:///tmp/body-1")
            insertStep(result, "s3://bodies/body-2")
            insertStep(result, null)
            // The service's own last-run pointer must not block the purge.
            Services.update({ Services.id eq svc }) { it[lastRunId] = result }
            // Delivery history referencing the purged service/result survives, unlinked.
            logId = insertNotificationLog(org, svc, result)
        }

        runPurge(fake)

        transaction {
            assertEquals(0, count(Services, Services.id eq svc))
            assertEquals(0, count(ProbeResults, ProbeResults.id eq result))
            assertEquals(0, count(ProbeSteps, ProbeSteps.probeResultId eq result))
            assertEquals(setOf("file:///tmp/body-1", "s3://bodies/body-2"), fake.deleted.toSet())

            val logRow = NotificationLog.selectAll().where { NotificationLog.id eq logId }.single()
            assertNull(logRow[NotificationLog.serviceId])
            assertNull(logRow[NotificationLog.probeResultId])
        }
    }

    @Test
    fun `a failing storage backend does not stop the database purge`() {
        lateinit var svc: UUID
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            svc = insertService(proj, purge = true)
            result = insertResult(svc, proj, ws, org)
            insertStep(result, "s3://bodies/unreachable")
        }

        runPurge(FakeStorage(failWith = RuntimeException("bucket down")))

        transaction {
            assertEquals(0, count(Services, Services.id eq svc), "rows purged despite storage failure")
            assertEquals(0, count(ProbeSteps, ProbeSteps.probeResultId eq result))

            // The row naming the object is gone, so without this the URI — the
            // only record that the object exists — would be gone with it, and
            // nothing sweeps body storage. It has to survive the purge.
            val pending = PendingBodyDeletions.selectAll()
                .where { PendingBodyDeletions.storageUrl eq "s3://bodies/unreachable" }
                .single()
            assertEquals(1, pending[PendingBodyDeletions.attempts])
            assertEquals("bucket down", pending[PendingBodyDeletions.lastError])
        }
    }

    @Test
    fun `the retry job finishes a deletion the purge could not, and keeps the ones it still cannot`() {
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj, purge = true)
            val result = insertResult(svc, proj, ws, org)
            insertStep(result, "s3://bodies/retry-me")
        }

        runPurge(FakeStorage(failWith = RuntimeException("bucket down")))
        transaction {
            assertEquals(1, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "s3://bodies/retry-me"))
        }

        // Still broken: the row stays, with the failure counted. Nothing is ever
        // given up on — dropping the row recreates the orphan it exists to stop.
        val brokenStorage = FakeStorage(failWith = RuntimeException("still down"))
        runBlocking { BodyDeletionRetryJob(storageClient = brokenStorage).execute() }
        transaction {
            val row = PendingBodyDeletions.selectAll()
                .where { PendingBodyDeletions.storageUrl eq "s3://bodies/retry-me" }
                .single()
            assertEquals(2, row[PendingBodyDeletions.attempts], "the failed attempt is counted")
            assertEquals("still down", row[PendingBodyDeletions.lastError])
        }

        // Bucket back: the object is deleted and stops being pending.
        val healthy = FakeStorage()
        runBlocking { BodyDeletionRetryJob(storageClient = healthy).execute() }
        assertTrue(healthy.deleted.contains("s3://bodies/retry-me"), "the orphaned object is finally deleted")
        transaction {
            assertEquals(0, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "s3://bodies/retry-me"))
        }
    }

    @Test
    fun `retention records a body it could not delete instead of losing the reference`() {
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            val result = insertResult(svc, proj, ws, org)
            // Older than the retention window below.
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(40, ChronoUnit.DAYS)
            }
            insertStep(result, "s3://bodies/expired-unreachable")
        }

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 30,
                storageClient = FakeStorage(failWith = RuntimeException("bucket down")),
            ).execute()
        }

        transaction {
            val pending = PendingBodyDeletions.selectAll()
                .where { PendingBodyDeletions.storageUrl eq "s3://bodies/expired-unreachable" }
                .single()
            assertEquals("bucket down", pending[PendingBodyDeletions.lastError])
        }
    }

    @Test
    fun `a body outside platform storage is skipped, not failed, and the purge completes`() {
        val root = java.nio.file.Files.createTempDirectory("purge-confined").toRealPath()
        val outside = java.nio.file.Files.createTempFile("kept-at-source", ".json")
            .also { java.nio.file.Files.writeString(it, "not the platform's") }
        val platformBody = root.resolve("platform.json").also { java.nio.file.Files.writeString(it, "{}") }
        lateinit var svc: UUID
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            svc = insertService(proj, purge = true)
            result = insertResult(svc, proj, ws, org)
            insertStep(result, "file://$platformBody")
            insertStep(result, "file://$outside")
            insertStep(result, "s3://host-bucket/kept/body.json")
        }

        // The real client, confined to [root] and with no S3 backend at all: the
        // two foreign URIs are refused on confinement before anything is touched.
        runPurge(
            BodyStorageClient(
                confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = root),
            ),
        )

        transaction {
            assertEquals(0, count(Services, Services.id eq svc), "the purge completed")
            assertEquals(0, count(ProbeSteps, ProbeSteps.probeResultId eq result))
            for (uri in listOf("file://$outside", "s3://host-bucket/kept/body.json")) {
                assertEquals(
                    0, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq uri),
                    "a refused URI is not queued for retry: $uri",
                )
            }
        }
        assertFalse(java.nio.file.Files.exists(platformBody), "the platform's own body is deleted")
        assertTrue(java.nio.file.Files.exists(outside), "a body outside platform storage is left alone")
        java.nio.file.Files.deleteIfExists(outside)
    }

    /** A filesystem in_place body store rooted at [root], and a step whose body it holds. */
    private fun insertInPlaceStep(
        orgId: UUID,
        resultId: UUID,
        root: java.nio.file.Path,
        bodyUrl: String,
        stepNum: Short = 2,
    ): UUID {
        val storeId = UUID.randomUUID()
        BodyStores.insert {
            it[id] = storeId
            it[organizationId] = orgId
            it[name] = "in-place-$storeId"
            it[kind] = "filesystem"
            it[mode] = "in_place"
            it[rootPath] = root.toString()
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        ProbeSteps.insert {
            it[id] = UUID.randomUUID()
            it[probeResultId] = resultId
            it[ProbeSteps.stepNum] = stepNum
            it[requestUrl] = "https://example.test/"
            it[responseBodyStorageUrl] = bodyUrl
            it[bodyStoreId] = storeId
            it[createdAt] = NOW
        }
        return storeId
    }

    @Test
    fun `purge leaves an in_place body in its store and still deletes default ones`() {
        // Both bodies sit inside the default root, so only body_store_id stands
        // between the in_place one and the delete — confinement would not.
        val root = java.nio.file.Files.createTempDirectory("purge-in-place").toRealPath()
        val defaultBody = root.resolve("default.json").also { java.nio.file.Files.writeString(it, "{}") }
        val inPlaceBody = root.resolve("kept.json").also { java.nio.file.Files.writeString(it, "store owner's") }
        lateinit var svc: UUID
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            svc = insertService(proj, purge = true)
            result = insertResult(svc, proj, ws, org)
            insertStep(result, "file://$defaultBody")
            insertInPlaceStep(org, result, root, "file://$inPlaceBody")
        }

        runPurge(BodyStorageClient(confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = root)))

        transaction {
            assertEquals(0, count(Services, Services.id eq svc), "the purge completed")
            assertEquals(0, count(ProbeSteps, ProbeSteps.probeResultId eq result), "both rows are gone")
            assertEquals(0, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "file://$inPlaceBody"))
        }
        assertFalse(java.nio.file.Files.exists(defaultBody), "the default store's body is deleted")
        assertTrue(java.nio.file.Files.exists(inPlaceBody), "the in_place body is left to its store")
    }

    @Test
    fun `purge leaves an in_place body in a store of its own and queues nothing for it`() {
        // The realistic shape: the store is a directory of its own, nowhere near
        // platform storage, and the worker is not mounted there at all. Both the
        // body_store_id and the confinement say hands off — and the URI must not
        // land in the deletion retry table, where it would be retried forever.
        val platformRoot = java.nio.file.Files.createTempDirectory("purge-platform").toRealPath()
        val storeRoot = java.nio.file.Files.createTempDirectory("purge-store").toRealPath()
        val defaultBody = platformRoot.resolve("default.json").also { java.nio.file.Files.writeString(it, "{}") }
        val inPlaceBody = storeRoot.resolve("eu/kept.json")
        java.nio.file.Files.createDirectories(inPlaceBody.parent)
        java.nio.file.Files.writeString(inPlaceBody, "store owner's")
        lateinit var svc: UUID
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            svc = insertService(proj, purge = true)
            result = insertResult(svc, proj, ws, org)
            insertStep(result, "file://$defaultBody")
            insertInPlaceStep(org, result, storeRoot, "file://$inPlaceBody")
        }

        runPurge(
            BodyStorageClient(confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = platformRoot)),
        )

        transaction {
            assertEquals(0, count(Services, Services.id eq svc), "the purge completed")
            assertEquals(0, count(ProbeSteps, ProbeSteps.probeResultId eq result), "both rows are gone")
            assertEquals(
                0, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "file://$inPlaceBody"),
                "a store owner's body is never queued for retry",
            )
        }
        assertFalse(java.nio.file.Files.exists(defaultBody), "the default store's body is deleted")
        assertTrue(java.nio.file.Files.exists(inPlaceBody), "the in_place body is left to its store")
        java.nio.file.Files.deleteIfExists(inPlaceBody)
    }

    @Test
    fun `retention leaves an in_place body in its store and still deletes default ones`() {
        val root = java.nio.file.Files.createTempDirectory("retention-in-place").toRealPath()
        val defaultBody = root.resolve("default.json").also { java.nio.file.Files.writeString(it, "{}") }
        val inPlaceBody = root.resolve("kept.json").also { java.nio.file.Files.writeString(it, "store owner's") }
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            result = insertResult(svc, proj, ws, org)
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(40, ChronoUnit.DAYS)
            }
            insertStep(result, "file://$defaultBody")
            insertInPlaceStep(org, result, root, "file://$inPlaceBody")
        }

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 30,
                storageClient = BodyStorageClient(
                    confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = root),
                ),
            ).execute()
        }

        transaction { assertEquals(0, count(ProbeResults, ProbeResults.id eq result)) }
        assertFalse(java.nio.file.Files.exists(defaultBody), "the default store's body is deleted")
        assertTrue(java.nio.file.Files.exists(inPlaceBody), "the in_place body is left to its store")
    }

    @Test
    fun `retention skips a body outside platform storage and still drops the rows`() {
        val root = java.nio.file.Files.createTempDirectory("retention-confined").toRealPath()
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            result = insertResult(svc, proj, ws, org)
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(40, ChronoUnit.DAYS)
            }
            insertStep(result, "s3://host-bucket/kept/expired.json")
        }

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 30,
                storageClient = BodyStorageClient(
                    confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = root),
                ),
            ).execute()
        }

        transaction {
            assertEquals(0, count(ProbeResults, ProbeResults.id eq result))
            assertEquals(
                0,
                count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "s3://host-bucket/kept/expired.json"),
            )
        }
    }

    @Test
    fun `the retry job clears a pending body that is outside platform storage`() {
        val root = java.nio.file.Files.createTempDirectory("retry-confined").toRealPath()
        transaction { PendingBodyDeletion.record(listOf("s3://host-bucket/kept/stale.json"), "recorded before") }

        runBlocking {
            BodyDeletionRetryJob(
                storageClient = BodyStorageClient(
                    confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = root),
                ),
            ).execute()
        }

        transaction {
            assertEquals(
                0,
                count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "s3://host-bucket/kept/stale.json"),
                "a URI the platform will never delete is not retried forever",
            )
        }
    }

    // ── Retention jobs ──

    @Test
    fun `audit log retention trims only entries past the window`() {
        lateinit var oldId: UUID
        lateinit var freshId: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            oldId = insertAudit(org, owner, createdAt = NOW.minus(100, ChronoUnit.DAYS))
            freshId = insertAudit(org, owner)
        }

        runBlocking { AuditLogRetentionJob(retentionDays = 90).execute() }

        transaction {
            assertEquals(0, count(OrgAuditLog, OrgAuditLog.id eq oldId))
            assertEquals(1, count(OrgAuditLog, OrgAuditLog.id eq freshId))
        }
    }

    @Test
    fun `notification log retention trims only entries past the window`() {
        lateinit var oldId: UUID
        lateinit var freshId: UUID
        transaction {
            val owner = insertUser()
            val org = insertOrg(owner)
            oldId = insertNotificationLog(org, createdAt = NOW.minus(100, ChronoUnit.DAYS))
            freshId = insertNotificationLog(org)
        }

        runBlocking { NotificationLogRetentionJob(retentionDays = 90).execute() }

        transaction {
            assertEquals(0, count(NotificationLog, NotificationLog.id eq oldId))
            assertEquals(1, count(NotificationLog, NotificationLog.id eq freshId))
        }
    }

    @Test
    fun `expired password reset tokens are deleted, live ones kept`() {
        lateinit var expired: UUID
        lateinit var live: UUID
        transaction {
            val user = insertUser()
            expired = insertResetToken(user, expiresAt = NOW.minusSeconds(60))
            live = insertResetToken(user, expiresAt = NOW.plusSeconds(3600))
        }

        runBlocking { ExpiredTokenCleanupJob().execute() }

        transaction {
            assertEquals(0, count(PasswordResetTokens, PasswordResetTokens.id eq expired))
            assertEquals(1, count(PasswordResetTokens, PasswordResetTokens.id eq live))
        }
    }


    // ── Retention body pass (the body window, separate from the result window) ──

    /**
     * Every probe row in the database, gone.
     *
     * The body pass walks *every* organization with results older than a day,
     * so a test that asserts "nothing was deleted" or "the tick stopped after
     * the first organization" is otherwise reasoning about whatever the tests
     * before it happened to leave behind — which changes the moment a test is
     * added, renamed or reordered. Each body-pass test starts from an empty
     * probe table and therefore owns every organization the job can see.
     */
    private fun onlyThisTestsProbeData() {
        transaction {
            ProbeSteps.deleteAll()
            ProbeResults.deleteAll()
            PendingBodyDeletions.deleteAll()
            JobWatermarks.deleteAll()
        }
    }

    /**
     * An org, a result aged [resultAgeDays] days, and one default-store body on
     * it. Returns the org id, the result id and the step id.
     */
    private fun agedResultWithBody(
        resultAgeDays: Long,
        bodyUrl: String,
        orgId: UUID = UUID.randomUUID(),
    ): Triple<UUID, UUID, UUID> {
        lateinit var org: UUID
        lateinit var result: UUID
        lateinit var step: UUID
        transaction {
            val owner = insertUser()
            org = insertOrg(owner, id = orgId)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            result = insertResult(svc, proj, ws, org)
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(resultAgeDays, ChronoUnit.DAYS)
            }
            step = insertStep(result, bodyUrl)
        }
        return Triple(org, result, step)
    }

    private fun stepRow(stepId: UUID) =
        ProbeSteps.selectAll().where { ProbeSteps.id eq stepId }.single()

    /** The organization of a result row, for keying its watermark. */
    private fun orgOf(resultId: UUID): UUID =
        ProbeResults.selectAll().where { ProbeResults.id eq resultId }.single()[ProbeResults.organizationId]

    private fun bodyUrlOf(stepId: UUID): String? =
        transaction { stepRow(stepId)[ProbeSteps.responseBodyStorageUrl] }

    /** Installs [config] for the duration of [block], then puts the default back. */
    private fun <T> withRetentionConfig(config: RetentionConfig, block: () -> T): T {
        val previous = PlatformDefaults.retentionConfig
        PlatformDefaults.retentionConfig = config
        try {
            return block()
        } finally {
            PlatformDefaults.retentionConfig = previous
        }
    }

    /** A seam that answers [body] for [orgId] and [otherBody] for everyone else. */
    private fun bodyWindowFor(orgId: UUID, body: Int?, otherBody: Int? = null) =
        object : RetentionConfig {
            override fun bodyRetentionDays(orgId2: UUID): Int? = if (orgId2 == orgId) body else otherBody
        }

    @Test
    fun `the body pass expires a body and records the reason while the result stays`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        // 40 days old: past the 7-day body window, well inside the 365-day result window.
        val (_, result, step) = agedResultWithBody(40, "s3://bodies/expired-by-body-window")

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 365,
                storageClient = storage,
                defaultBodyRetentionDays = 7,
            ).execute()
        }

        assertTrue(storage.deleted.contains("s3://bodies/expired-by-body-window"), "the object is deleted")
        transaction {
            assertEquals(1, count(ProbeResults, ProbeResults.id eq result), "the result itself is kept")
            val row = stepRow(step)
            assertNull(row[ProbeSteps.responseBodyStorageUrl], "the body URL is cleared")
            assertEquals("bodyExpired", row[ProbeSteps.bodyNotStoredReason])
            assertEquals(
                0, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "s3://bodies/expired-by-body-window"),
                "a body that went nowhere near a failure is not queued",
            )
        }
    }

    @Test
    fun `a body younger than the body window is kept`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        val (_, _, step) = agedResultWithBody(3, "s3://bodies/still-young")

        runBlocking {
            RetentionJob(defaultRetentionDays = 365, storageClient = storage, defaultBodyRetentionDays = 7).execute()
        }

        assertFalse(storage.deleted.contains("s3://bodies/still-young"), "this body is not deleted")
        transaction {
            assertEquals("s3://bodies/still-young", stepRow(step)[ProbeSteps.responseBodyStorageUrl])
            assertNull(stepRow(step)[ProbeSteps.bodyNotStoredReason])
        }
    }

    @Test
    fun `the body window is off unless the caller sets one`() {
        onlyThisTestsProbeData()
        // The constructor default is the upgrade path: a worker built the way
        // Application builds it, with nothing configured, must not start
        // shedding bodies that an install has been keeping for its result
        // window. The default is -1, so the body pass does not run at all.
        val storage = FakeStorage()
        val (_, result, step) = agedResultWithBody(400, "s3://bodies/upgrade-keeps-this")

        runBlocking {
            RetentionJob(defaultRetentionDays = 3650, storageClient = storage).execute()
        }

        assertTrue(storage.deleted.isEmpty(), "nothing is deleted")
        transaction {
            assertEquals(1, count(ProbeResults, ProbeResults.id eq result))
            assertEquals("s3://bodies/upgrade-keeps-this", stepRow(step)[ProbeSteps.responseBodyStorageUrl])
        }
    }

    @Test
    fun `the body pass leaves an in_place body and its row alone`() {
        onlyThisTestsProbeData()
        val root = java.nio.file.Files.createTempDirectory("body-window-in-place").toRealPath()
        val inPlaceBody = root.resolve("kept.json").also { java.nio.file.Files.writeString(it, "store owner's") }
        val defaultBody = root.resolve("expired.json").also { java.nio.file.Files.writeString(it, "{}") }
        lateinit var org: UUID
        lateinit var result: UUID
        lateinit var inPlaceStep: UUID
        lateinit var defaultStep: UUID
        transaction {
            val owner = insertUser()
            org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            result = insertResult(svc, proj, ws, org)
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(40, ChronoUnit.DAYS)
            }
            defaultStep = insertStep(result, "file://$defaultBody")
            insertInPlaceStep(org, result, root, "file://$inPlaceBody")
            inPlaceStep = ProbeSteps.selectAll()
                .where { (ProbeSteps.probeResultId eq result) and ProbeSteps.bodyStoreId.isNotNull() }
                .single()[ProbeSteps.id]
        }

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 365,
                storageClient = BodyStorageClient(
                    confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = root),
                ),
                defaultBodyRetentionDays = 7,
            ).execute()
        }

        transaction {
            assertEquals(1, count(ProbeResults, ProbeResults.id eq result), "the result is kept")
            assertNull(stepRow(defaultStep)[ProbeSteps.responseBodyStorageUrl], "the default-store body expires")
            assertEquals(
                "file://$inPlaceBody", stepRow(inPlaceStep)[ProbeSteps.responseBodyStorageUrl],
                "the in_place body keeps its URL",
            )
            assertNull(stepRow(inPlaceStep)[ProbeSteps.bodyNotStoredReason])
        }
        assertFalse(java.nio.file.Files.exists(defaultBody), "the default store's body is deleted")
        assertTrue(java.nio.file.Files.exists(inPlaceBody), "the in_place body is left to its store")
    }

    @Test
    fun `the body window off expires nothing by age but the result pass still takes bodies with rows`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        val (_, oldResult, oldStep) = agedResultWithBody(400, "s3://bodies/past-the-result-window")
        val (_, youngResult, youngStep) = agedResultWithBody(40, "s3://bodies/inside-the-result-window")

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 90,
                storageClient = storage,
                defaultBodyRetentionDays = -1,
            ).execute()
        }

        transaction {
            assertEquals(0, count(ProbeResults, ProbeResults.id eq oldResult), "the result pass took the old result")
            assertEquals(0, count(ProbeSteps, ProbeSteps.id eq oldStep), "and its step row")
            assertEquals(1, count(ProbeResults, ProbeResults.id eq youngResult))
            assertEquals(
                "s3://bodies/inside-the-result-window", stepRow(youngStep)[ProbeSteps.responseBodyStorageUrl],
                "with no body window, a body lives exactly as long as its result",
            )
        }
        assertTrue(storage.deleted.contains("s3://bodies/past-the-result-window"))
        assertFalse(storage.deleted.contains("s3://bodies/inside-the-result-window"))
    }

    @Test
    fun `the result window off keeps every result while bodies still expire on their own`() {
        onlyThisTestsProbeData()
        // The dangerous direction. A negative result window means "never expire
        // by age"; arithmetic on it would produce a cutoff in the future and
        // delete the organization's entire history. The result pass must not
        // run at all, while the body pass still does its own job.
        val storage = FakeStorage()
        val (_, ancientResult, ancientStep) = agedResultWithBody(4000, "s3://bodies/ancient")
        val (_, recentResult, recentStep) = agedResultWithBody(2, "s3://bodies/recent")

        runBlocking {
            RetentionJob(
                defaultRetentionDays = -1,
                storageClient = storage,
                defaultBodyRetentionDays = 7,
            ).execute()
        }

        transaction {
            assertEquals(1, count(ProbeResults, ProbeResults.id eq ancientResult), "results never expire by age")
            assertEquals(1, count(ProbeResults, ProbeResults.id eq recentResult))
            assertNull(stepRow(ancientStep)[ProbeSteps.responseBodyStorageUrl], "its body still expires")
            assertEquals("bodyExpired", stepRow(ancientStep)[ProbeSteps.bodyNotStoredReason])
            assertEquals(
                "s3://bodies/recent", stepRow(recentStep)[ProbeSteps.responseBodyStorageUrl],
                "a body inside the body window is untouched",
            )
        }
    }

    @Test
    fun `a per-org body window overrides the global one`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        val (expiringOrg, _, expiringStep) = agedResultWithBody(40, "s3://bodies/org-window-7")
        val (_, _, keptStep) = agedResultWithBody(40, "s3://bodies/global-window-365")

        withRetentionConfig(bodyWindowFor(expiringOrg, body = 7)) {
            runBlocking {
                RetentionJob(
                    defaultRetentionDays = 3650,
                    storageClient = storage,
                    defaultBodyRetentionDays = 365,
                ).execute()
            }
        }

        transaction {
            assertNull(stepRow(expiringStep)[ProbeSteps.responseBodyStorageUrl], "the org's own 7-day window applies")
            assertEquals("bodyExpired", stepRow(expiringStep)[ProbeSteps.bodyNotStoredReason])
            assertEquals(
                "s3://bodies/global-window-365", stepRow(keptStep)[ProbeSteps.responseBodyStorageUrl],
                "an org with no override falls back to the global 365-day window",
            )
        }
    }

    @Test
    fun `an org window of never survives a global window that expires`() {
        onlyThisTestsProbeData()
        // The sentinel collision this seam exists to prevent: a plan sold as
        // "bodies never expire" read as "no opinion" and lost its bodies to the
        // global 90-day window instead.
        val storage = FakeStorage()
        val (org, _, step) = agedResultWithBody(400, "s3://bodies/never-expires")

        withRetentionConfig(bodyWindowFor(org, body = -1)) {
            runBlocking {
                RetentionJob(
                    defaultRetentionDays = 3650,
                    storageClient = storage,
                    defaultBodyRetentionDays = 90,
                ).execute()
            }
        }

        assertTrue(storage.deleted.isEmpty(), "nothing is deleted")
        assertEquals("s3://bodies/never-expires", bodyUrlOf(step), "the org's own answer wins over the global window")
    }

    @Test
    fun `an org with no opinion follows a global window that is off`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        val (_, _, step) = agedResultWithBody(400, "s3://bodies/no-opinion")

        // The seam answers null for every org — the shipped default — so the
        // global -1 stands and nothing expires by age.
        withRetentionConfig(RetentionConfig.Default) {
            runBlocking {
                RetentionJob(
                    defaultRetentionDays = 3650,
                    storageClient = storage,
                    defaultBodyRetentionDays = -1,
                ).execute()
            }
        }

        assertTrue(storage.deleted.isEmpty(), "nothing is deleted")
        assertEquals("s3://bodies/no-opinion", bodyUrlOf(step))
    }

    @Test
    fun `an org window applies even where the global window is off`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        val (org, _, expiringStep) = agedResultWithBody(40, "s3://bodies/org-says-7")
        val (_, _, keptStep) = agedResultWithBody(40, "s3://bodies/global-says-never")

        withRetentionConfig(bodyWindowFor(org, body = 7)) {
            runBlocking {
                RetentionJob(
                    defaultRetentionDays = 3650,
                    storageClient = storage,
                    defaultBodyRetentionDays = -1,
                ).execute()
            }
        }

        assertNull(bodyUrlOf(expiringStep), "the org's 7-day window is honoured with no global window at all")
        assertEquals("s3://bodies/global-says-never", bodyUrlOf(keptStep), "every other org keeps its bodies")
    }

    @Test
    fun `a body the storage backend fails to delete keeps its URL and is queued for retry`() {
        onlyThisTestsProbeData()
        val (_, result, step) = agedResultWithBody(40, "s3://bodies/store-is-down")

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 365,
                storageClient = FakeStorage(failWith = RuntimeException("bucket down")),
                defaultBodyRetentionDays = 7,
            ).execute()
        }

        transaction {
            assertEquals(1, count(ProbeResults, ProbeResults.id eq result))
            assertEquals(
                "s3://bodies/store-is-down", stepRow(step)[ProbeSteps.responseBodyStorageUrl],
                "the row is the only reference to a body still in the bucket — it is kept",
            )
            assertNull(stepRow(step)[ProbeSteps.bodyNotStoredReason], "nothing expired, so no reason is recorded")
            val pending = PendingBodyDeletions.selectAll()
                .where { PendingBodyDeletions.storageUrl eq "s3://bodies/store-is-down" }
                .single()
            assertEquals("bucket down", pending[PendingBodyDeletions.lastError])
            assertNull(
                JobWatermarks.read(RetentionJob.bodyWatermarkKey(orgOf(result))),
                "a pass that left work behind does not advance its watermark past it",
            )
        }
    }

    @Test
    fun `a body outside platform storage stops the org's body pass and writes nothing`() {
        onlyThisTestsProbeData()
        // A real confinement refusal, not a stubbed one: the worker's root does
        // not contain the body, which is what a STORAGE_FILESYSTEM_ROOT that
        // does not match the ingestor's looks like. The object is not the
        // platform's to delete, so clearing the reference would orphan it —
        // permanently, since nothing else knows it exists.
        val platformRoot = java.nio.file.Files.createTempDirectory("body-window-platform").toRealPath()
        val elsewhere = java.nio.file.Files.createTempDirectory("body-window-elsewhere").toRealPath()
        val foreign = elsewhere.resolve("body.json").also { java.nio.file.Files.writeString(it, "{}") }
        val (_, result, step) = agedResultWithBody(40, "file://$foreign")

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 3650,
                storageClient = BodyStorageClient(
                    confinement = dev.tracedown.common.storage.BodyConfinement(filesystemRoot = platformRoot),
                ),
                defaultBodyRetentionDays = 7,
            ).execute()
        }

        assertTrue(java.nio.file.Files.exists(foreign), "the object is untouched")
        transaction {
            assertEquals(1, count(ProbeResults, ProbeResults.id eq result), "the result is kept")
            assertEquals("file://$foreign", stepRow(step)[ProbeSteps.responseBodyStorageUrl], "the reference is kept")
            assertNull(stepRow(step)[ProbeSteps.bodyNotStoredReason], "and nothing is stamped on it")
            assertEquals(
                0, count(PendingBodyDeletions, PendingBodyDeletions.storageUrl eq "file://$foreign"),
                "a refusal is not a pending deletion: no retry will ever make it deletable",
            )
            assertNull(
                JobWatermarks.read(RetentionJob.bodyWatermarkKey(orgOf(result))),
                "the pass stopped, so it does not record having swept this window",
            )
        }
    }

    @Test
    fun `the body pass stops after a round that could expire nothing`() {
        onlyThisTestsProbeData()
        // One body per round and every delete failing: without the no-progress
        // guard the same page comes back for the whole tick budget. The guard
        // stops after the first fruitless round, so the second body is not even
        // attempted this tick.
        val storage = FakeStorage(failWith = RuntimeException("bucket down"))
        lateinit var result: UUID
        lateinit var org: UUID
        transaction {
            val owner = insertUser()
            org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            result = insertResult(svc, proj, ws, org)
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(40, ChronoUnit.DAYS)
            }
            insertStepAt(result, "s3://bodies/no-progress-1", 1)
            insertStepAt(result, "s3://bodies/no-progress-2", 2)
        }

        runBlocking {
            RetentionJob(
                defaultRetentionDays = 3650,
                storageClient = storage,
                defaultBodyRetentionDays = 7,
                batchSize = 1,
            ).execute()
        }

        transaction {
            assertEquals(
                2,
                ProbeSteps.selectAll()
                    .where { (ProbeSteps.probeResultId eq result) and ProbeSteps.responseBodyStorageUrl.isNotNull() }
                    .count(),
                "both bodies keep their URL",
            )
            assertEquals(
                1, PendingBodyDeletions.selectAll().count(),
                "exactly one round was attempted before the pass gave up",
            )
        }
    }

    @Test
    fun `the body pass stops on the tick budget and resumes on the next tick`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        lateinit var org: UUID
        lateinit var result: UUID
        transaction {
            val owner = insertUser()
            org = insertOrg(owner)
            val ws = insertWorkspace(org)
            val proj = insertProject(ws)
            val svc = insertService(proj)
            result = insertResult(svc, proj, ws, org)
            ProbeResults.update({ ProbeResults.id eq result }) {
                it[startedAt] = NOW.minus(40, ChronoUnit.DAYS)
            }
            repeat(4) { i -> insertStepAt(result, "s3://bodies/budget-$i", (i + 1).toShort()) }
        }

        // One body per round against a clock that advances 300 ms per reading.
        // The readings are fixed — tick start, the org-loop budget check, the
        // two cutoffs, then one per round — so the round the one-second budget
        // falls on is fixed too: the first body goes and three are left.
        var readings = 0L
        runBlocking {
            RetentionJob(
                defaultRetentionDays = 365,
                storageClient = storage,
                defaultBodyRetentionDays = 7,
                batchSize = 1,
                tickBudget = java.time.Duration.ofSeconds(1),
                clock = { NOW.plusMillis(readings++ * 300) },
            ).execute()
        }

        transaction {
            val remaining = ProbeSteps.selectAll()
                .where { (ProbeSteps.probeResultId eq result) and ProbeSteps.responseBodyStorageUrl.isNotNull() }
                .count()
            assertEquals(3, remaining, "the tick stopped on its budget after exactly one body")
            assertEquals(1, count(ProbeResults, ProbeResults.id eq result), "the result is inside its own window")
            assertNull(
                JobWatermarks.read(RetentionJob.bodyWatermarkKey(org)),
                "an unfinished window is not recorded as swept",
            )
        }

        // The next tick, with a real budget, finishes the rest.
        runBlocking {
            RetentionJob(
                defaultRetentionDays = 365,
                storageClient = storage,
                defaultBodyRetentionDays = 7,
                batchSize = 1,
            ).execute()
        }
        transaction {
            assertEquals(
                0,
                ProbeSteps.selectAll()
                    .where { (ProbeSteps.probeResultId eq result) and ProbeSteps.responseBodyStorageUrl.isNotNull() }
                    .count(),
                "the remainder is expired on the next tick",
            )
        }
    }

    @Test
    fun `a tick that runs out of budget resumes at the org it stopped on`() {
        onlyThisTestsProbeData()
        // Two orgs, one body each, and a budget that only reaches the first.
        // Without a resume cursor the next tick starts from the top again and
        // the second org is never swept — the organizations at the end of the
        // list starve while the ones at the front are swept every hour.
        val storage = FakeStorage()
        // The two ids straddle the sign bit of the most-significant 64 bits, so
        // they are in *opposite* order under PostgreSQL's unsigned `uuid`
        // comparison (which is what `ORDER BY organization_id` gives the job)
        // and under `java.util.UUID.compareTo`. Reasoning about this list with
        // Kotlin's `<` rotated the resume cursor to the wrong organization; see
        // RetentionResumeOrderTest. Fixed rather than random so the case is
        // covered on every run instead of every other one.
        val (orgA, _, stepA) =
            agedResultWithBody(40, "s3://bodies/org-a", UUID.fromString("7fffffff-0000-4000-8000-000000000000"))
        val (orgB, _, stepB) =
            agedResultWithBody(40, "s3://bodies/org-b", UUID.fromString("80000000-0000-4000-8000-000000000000"))
        val aIsFirst = RetentionJob.ORG_ID_ORDER.compare(orgA, orgB) < 0
        val first = if (aIsFirst) stepA else stepB
        val second = if (aIsFirst) stepB else stepA

        // The clock advances 300 ms per reading and the budget is 400 ms, so
        // the first org's passes finish and the budget check in front of the
        // second one fails. The cursor is then the only thing that gets the
        // second org swept at all.
        val job = RetentionJob(
            defaultRetentionDays = 3650,
            storageClient = storage,
            defaultBodyRetentionDays = 7,
            tickBudget = java.time.Duration.ofMillis(400),
            clock = object : () -> Instant {
                var readings = 0L
                override fun invoke(): Instant = NOW.plusMillis(readings++ * 300)
            },
        )

        runBlocking { job.execute() }
        assertNull(bodyUrlOf(first), "the first org in id order is swept")
        assertEquals("s3://bodies/${if (aIsFirst) "org-b" else "org-a"}", bodyUrlOf(second), "the second is not")

        runBlocking { job.execute() }
        assertNull(bodyUrlOf(second), "the next tick starts where the last one stopped")
    }

    @Test
    fun `the body pass records a watermark and scans from it on the next tick`() {
        onlyThisTestsProbeData()
        val storage = FakeStorage()
        val (org, _, step) = agedResultWithBody(40, "s3://bodies/watermarked")

        runBlocking {
            RetentionJob(defaultRetentionDays = 3650, storageClient = storage, defaultBodyRetentionDays = 7).execute()
        }

        assertNull(bodyUrlOf(step))
        val mark = transaction { JobWatermarks.read(RetentionJob.bodyWatermarkKey(org)) }
        assertNotNull(mark, "a drained window is recorded, so the next tick does not re-scan it")
        assertTrue(
            Duration.between(NOW.minus(7, ChronoUnit.DAYS), mark).abs() < Duration.ofMinutes(5),
            "the watermark is the body cutoff it reached, not the wall clock: $mark",
        )

        // A body older than the watermark, inserted afterwards, is below the
        // window the next tick scans — this is the trade the watermark makes,
        // and the result pass is what eventually takes it.
        val belowWatermark = transaction {
            val result = ProbeResults.selectAll()
                .where { ProbeResults.organizationId eq org }
                .single()[ProbeResults.id]
            insertStepAt(result, "s3://bodies/below-the-watermark", 9)
        }
        runBlocking {
            RetentionJob(defaultRetentionDays = 3650, storageClient = storage, defaultBodyRetentionDays = 7).execute()
        }
        assertEquals(
            "s3://bodies/below-the-watermark", bodyUrlOf(belowWatermark),
            "the second tick scans only the window above the watermark",
        )
    }

    @Test
    fun `the expirable-body index the body pass plans against exists`() {
        // The pass joins probe_steps — the largest table in the schema — on
        // every tick. Without this partial index the planner has nothing better
        // than a sequential scan of it, per organization, forever.
        val definition = transaction {
            exec(
                "SELECT indexdef FROM pg_indexes " +
                    "WHERE tablename = 'probe_steps' AND indexname = 'idx_probe_steps_expirable_body'",
            ) { rs -> if (rs.next()) rs.getString(1) else null }
        }
        assertNotNull(definition, "idx_probe_steps_expirable_body is missing")
        assertTrue(definition!!.contains("probe_result_id"), definition)
        assertTrue(
            definition.contains("response_body_storage_url IS NOT NULL") &&
                definition.contains("body_store_id IS NULL"),
            "the index must be partial over exactly the rows the body pass selects: $definition",
        )
    }


    // ── Construction sanity (kept from the original test) ──

    @Test
    fun `default interval is 300 seconds`() {
        assertEquals(300L, PurgeJob().intervalSeconds)
        assertTrue(PurgeJob() is ScheduledJob)
        assertFalse(PurgeJob().name.isEmpty())
    }
}
