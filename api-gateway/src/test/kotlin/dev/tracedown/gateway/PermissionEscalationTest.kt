package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID

/**
 * The authorization surface around users, groups and invites, exercised end to
 * end — the wiring the pure [dev.tracedown.gateway.util.GrantPolicyTest] cannot
 * see: which controller actually consults the rule.
 *
 * Three defects are pinned here:
 *  - the invite endpoint took no permission at all, so any member could invite a
 *    second address of their own, pre-assign it the Admins group, and accept
 *    from their own inbox;
 *  - `users.write` was enough to write `admin=2` onto any row — the caller's own
 *    included — and to switch group TOTP enforcement off;
 *  - a removed member's org-section columns survived the removal, so a re-invite
 *    handed their old rank back with nothing in the UI showing it.
 */
@Testcontainers
class PermissionEscalationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("tracedown_escalation_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val OWNER_EMAIL = "admin@tracedown.dev"
        private const val OWNER_PASSWORD = "Down2trace!"

        /** Holds `users` write and nothing else — the escalation's protagonist. */
        private const val MANAGER_EMAIL = "user-manager@tracedown.dev"
        private const val MANAGER_PASSWORD = "Manager123!"

        /** A member with no org sections at all. */
        private const val PLAIN_EMAIL = "plain-member@tracedown.dev"
        private const val PLAIN_PASSWORD = "PlainOne123!"

        /** Removed for cause, then re-invited. */
        private const val EX_ADMIN_EMAIL = "ex-admin@tracedown.dev"

        /** Belongs to a second org too, so removal from this one is not their last. */
        private const val MULTI_ORG_EMAIL = "multi-org@tracedown.dev"
        private const val MULTI_ORG_PASSWORD = "MultiOrg123!"

        private lateinit var orgId: UUID
        private lateinit var adminGroupId: UUID
        private lateinit var plainGroupId: UUID
        private lateinit var managerUserId: UUID
        private lateinit var plainUserId: UUID
        private lateinit var exAdminUserId: UUID
        private lateinit var multiOrgUserId: UUID
        private lateinit var secondOrgId: UUID

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            serverPort = ServerSocket(0).use { it.localPort }

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
            ))
            val mergedConfig = overrides.withFallback(ConfigFactory.load())

            val env = applicationEnvironment {
                config = HoconApplicationConfig(mergedConfig)
            }

            server = embeddedServer(Netty, env, configure = {
                connector { port = serverPort }
            })

            server.start(wait = false)
            Thread.sleep(2000)

            transaction {
                orgId = Organizations.selectAll().first()[Organizations.id]

                adminGroupId = UUID.randomUUID()
                OrgGroups.insert {
                    it[id] = adminGroupId
                    it[organizationId] = orgId
                    it[name] = "Escalation Admins"
                    it[orgUserList] = 2
                    it[orgAdmin] = 2
                }

                // A group carrying nothing. Raising THIS one is the escalation:
                // a section already at the level being written is not a grant
                // (GrantPolicy.deniedSection), so asking the admin-carrying
                // group for the admin level it already holds is a no-op and is
                // correctly allowed.
                plainGroupId = UUID.randomUUID()
                OrgGroups.insert {
                    it[id] = plainGroupId
                    it[organizationId] = orgId
                    it[name] = "Escalation Nobodies"
                }

                managerUserId = createMember(MANAGER_EMAIL, MANAGER_PASSWORD, users = 2)
                plainUserId = createMember(PLAIN_EMAIL, PLAIN_PASSWORD, users = 0)
                exAdminUserId = createMember(EX_ADMIN_EMAIL, "NeverLogsIn123!", users = 2, admin = 2)

                // A member of this org AND a second one. Losing this membership
                // is not losing their last, so nothing else revokes their
                // session on the way out.
                multiOrgUserId = createMember(MULTI_ORG_EMAIL, MULTI_ORG_PASSWORD, users = 0)
                secondOrgId = UUID.randomUUID()
                Organizations.insert {
                    it[id] = secondOrgId
                    it[name] = "Second Org"
                    it[ownerId] = multiOrgUserId
                    it[deleted] = false
                    it[createdAt] = Instant.now()
                }
                OrgUsers.insert {
                    it[id] = UUID.randomUUID()
                    it[organizationId] = secondOrgId
                    it[OrgUsers.userId] = multiOrgUserId
                    it[joinedAt] = Instant.now()
                    it[status] = "active"
                    it[isActive] = true
                    it[deleted] = false
                    it[inviteToken] = ""
                }

                // The ex-admin also carries a group and a direct resource grant,
                // so the removal has all three kinds of access to strip.
                val exAdminMembership = membershipOf(exAdminUserId)
                OrgUserGroups.insert {
                    it[id] = UUID.randomUUID()
                    it[orgUserId] = exAdminMembership
                    it[orgGroupId] = adminGroupId
                }
                // `orgId` is also a column of ResourcePermissions, and the
                // insert lambda's receiver shadows the companion property — an
                // unqualified read here resolves to the Column and is emitted
                // as `VALUES (resource_permissions.org_id)`, which Postgres
                // refuses. Bind it before entering the lambda.
                val grantOrgId = orgId
                ResourcePermissions.insert {
                    it[id] = UUID.randomUUID()
                    it[ResourcePermissions.orgId] = grantOrgId
                    it[principalType] = "org_user"
                    it[principalId] = exAdminMembership
                    it[resourceType] = "workspace"
                    it[resourceId] = UUID.randomUUID()
                    it[permissions] = 2
                }
            }
        }

        /** An active member of the bootstrap org with the given section levels. */
        private fun createMember(
            email: String,
            password: String,
            users: Short = 0,
            admin: Short = 0,
        ): UUID {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[Users.email] = email
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, password.toCharArray())
                it[displayName] = email.substringBefore("@")
                it[isActive] = true
                it[deleted] = false
                it[selectedOrgId] = orgId
                it[createdAt] = Instant.now()
            }
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[joinedAt] = Instant.now()
                it[status] = "active"
                it[isActive] = true
                it[deleted] = false
                it[inviteToken] = ""
                it[orgUserList] = users
                it[orgAdmin] = admin
            }
            return userId
        }

        private fun membershipOf(userId: UUID): UUID =
            OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq userId) }
                .first()[OrgUsers.id]

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
        }
    }

    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun send(method: String, path: String, body: String?, token: String?): Pair<Int, String> {
        val builder = Request.Builder().url("http://localhost:$serverPort$path")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody(jsonType))
        }
        token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun login(email: String, password: String): String {
        val (_, body) = send("POST", "/api/v1/auth/login", """{"email":"$email","password":"$password"}""", null)
        return Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
    }

    private fun managerToken() = login(MANAGER_EMAIL, MANAGER_PASSWORD)

    // ---- the invite endpoint took no permission at all ----

    @Test
    fun `a member with no user permission cannot create an invite`() {
        val (status, _) = send(
            "POST", "/api/v1/invites",
            """{"email":"smuggled-in@example.com"}""",
            login(PLAIN_EMAIL, PLAIN_PASSWORD),
        )
        assertEquals(403, status)

        transaction {
            val created = Users.selectAll().where { Users.email eq "smuggled-in@example.com" }.any()
            assertFalse(created, "the refused invite must not have created a stub account")
        }
    }

    @Test
    fun `an invite cannot pre-assign a group the caller may not grant`() {
        val (status, _) = send(
            "POST", "/api/v1/invites",
            """{"email":"would-be-admin@example.com","groupIds":["$adminGroupId"]}""",
            managerToken(),
        )
        assertEquals(403, status)
    }

    // ---- users.write escalating itself ----

    @Test
    fun `a users-write holder cannot grant another member org admin`() {
        val (status, _) = send(
            "PATCH", "/api/v1/users/$plainUserId/permissions",
            """{"org":{"users":0,"settings":0,"domains":0,"webhooks":0,"notifications":0,"admin":2,"workspaces":0}}""",
            managerToken(),
        )
        assertEquals(403, status)

        transaction {
            val row = OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq plainUserId) }
                .first()
            assertEquals(0.toShort(), row[OrgUsers.orgAdmin])
        }
    }

    @Test
    fun `a users-write holder cannot edit their own row at all`() {
        // requireManageableTarget — the guard its siblings always used.
        val (status, _) = send(
            "PATCH", "/api/v1/users/$managerUserId/permissions",
            """{"org":{"users":2,"settings":0,"domains":0,"webhooks":0,"notifications":0,"admin":2,"workspaces":0}}""",
            managerToken(),
        )
        assertEquals(400, status)

        transaction {
            val row = OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq managerUserId) }
                .first()
            assertEquals(0.toShort(), row[OrgUsers.orgAdmin])
        }
    }

    @Test
    fun `a users-write holder cannot grant org admin through a group`() {
        val (status, _) = send(
            "PATCH", "/api/v1/groups/$plainGroupId",
            """{"admin":2}""",
            managerToken(),
        )
        assertEquals(403, status)

        transaction {
            val group = OrgGroups.selectAll().where { OrgGroups.id eq plainGroupId }.first()
            assertEquals(0.toShort(), group[OrgGroups.orgAdmin])
        }
    }

    @Test
    fun `a users-write holder cannot strip org admin from a group either`() {
        // The admin section is gated in BOTH directions: emptying the org of
        // admins is as much a policy change as minting one.
        val (status, _) = send(
            "PATCH", "/api/v1/groups/$adminGroupId",
            """{"admin":0}""",
            managerToken(),
        )
        assertEquals(403, status)

        transaction {
            val group = OrgGroups.selectAll().where { OrgGroups.id eq adminGroupId }.first()
            assertEquals(2.toShort(), group[OrgGroups.orgAdmin])
        }
    }

    @Test
    fun `a users-write holder cannot join a member to an admin-carrying group`() {
        val (status, _) = send(
            "POST", "/api/v1/groups/$adminGroupId/members",
            """{"userId":"$plainUserId"}""",
            managerToken(),
        )
        assertEquals(403, status)
    }

    @Test
    fun `a users-write holder cannot switch group TOTP enforcement`() {
        val (status, _) = send(
            "PATCH", "/api/v1/groups/$adminGroupId",
            """{"totpRequired":true}""",
            managerToken(),
        )
        assertEquals(403, status)

        transaction {
            val group = OrgGroups.selectAll().where { OrgGroups.id eq adminGroupId }.first()
            assertFalse(group[OrgGroups.totpRequired])
        }
    }

    @Test
    fun `the owner is not constrained by any of it`() {
        val (status, _) = send(
            "PATCH", "/api/v1/users/$plainUserId/permissions",
            """{"org":{"users":0,"settings":0,"domains":0,"webhooks":0,"notifications":1,"admin":0,"workspaces":0}}""",
            login(OWNER_EMAIL, OWNER_PASSWORD),
        )
        assertEquals(200, status)
    }

    // ---- a re-invited ex-member starts from nothing ----

    @Test
    fun `a removed then re-invited member comes back holding nothing`() {
        val ownerToken = login(OWNER_EMAIL, OWNER_PASSWORD)

        val (removeStatus, _) = send("DELETE", "/api/v1/users/$exAdminUserId", null, ownerToken)
        assertEquals(200, removeStatus)

        // The soft-deleted row is already inert — removal, not the re-invite, is
        // where the access ended.
        transaction {
            val row = OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq exAdminUserId) }
                .first()
            assertTrue(row[OrgUsers.deleted])
            assertEquals(0.toShort(), row[OrgUsers.orgAdmin])
            assertEquals(0.toShort(), row[OrgUsers.orgUserList])
        }

        val (inviteStatus, _) = send(
            "POST", "/api/v1/invites", """{"email":"$EX_ADMIN_EMAIL"}""", ownerToken,
        )
        assertEquals(200, inviteStatus)

        transaction {
            val row = OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq exAdminUserId) }
                .first()
            assertFalse(row[OrgUsers.deleted])
            assertEquals("invited", row[OrgUsers.status])

            assertEquals(0.toShort(), row[OrgUsers.orgAdmin], "org admin must not survive a re-invite")
            assertEquals(0.toShort(), row[OrgUsers.orgUserList])
            assertTrue(row[OrgUsers.orgExtraPerms].isEmpty())

            val membershipId = row[OrgUsers.id]
            assertFalse(
                OrgUserGroups.selectAll().where { OrgUserGroups.orgUserId eq membershipId }.any(),
                "groups must not survive a re-invite",
            )
            assertFalse(
                ResourcePermissions.selectAll()
                    .where {
                        (ResourcePermissions.principalType eq "org_user") and
                        (ResourcePermissions.principalId eq membershipId)
                    }
                    .any(),
                "resource grants must not survive a re-invite",
            )
        }
    }

    // ---- a removed membership takes its org-scoped session with it ----

    @Test
    fun `removing a member from one of several orgs unbinds the session from that org`() {
        val memberToken = login(MULTI_ORG_EMAIL, MULTI_ORG_PASSWORD)

        // The session signed in scoped to this org.
        transaction {
            val bound = Sessions.selectAll()
                .where { (Sessions.userId eq multiOrgUserId) and (Sessions.organizationId eq orgId) }
                .count()
            assertTrue(bound > 0, "the session starts out scoped to the org")
        }

        val (removeStatus, removeBody) = send(
            "DELETE", "/api/v1/users/$multiOrgUserId", null, login(OWNER_EMAIL, OWNER_PASSWORD),
        )
        assertEquals(200, removeStatus, removeBody)

        transaction {
            // Nothing still points a session at the org they were removed from.
            // Before this fix the row kept its organization_id and every handler
            // that reads the org straight off the principal — without asking a
            // permission helper — kept answering for the rest of the token's life.
            assertEquals(
                0L,
                Sessions.selectAll()
                    .where { (Sessions.userId eq multiOrgUserId) and (Sessions.organizationId eq orgId) }
                    .count(),
                "no session may stay scoped to an org the member was removed from",
            )
            assertNull(
                Users.selectAll().where { Users.id eq multiOrgUserId }.first()[Users.selectedOrgId],
                "the persisted org selection goes with the membership",
            )
        }

        // The account keeps its other org, so the token itself is still valid —
        // this re-scopes the session, it does not sign the person out.
        val (meStatus, meBody) = send("GET", "/api/v1/auth/me", null, memberToken)
        assertEquals(200, meStatus, meBody)
    }
}
