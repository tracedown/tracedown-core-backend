package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.alerts.SystemAlertService.DEGRADED_RTT_MS
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.routes.v1.agents.agentRoutes
import dev.tracedown.gateway.util.ApiException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * The fleet roster re-resolves membership on every request.
 *
 * `/api/v1/agents/health` reads the org straight off the principal, and the
 * principal's org is only what was true when the session was scoped. Removing a
 * membership now detaches the live sessions from that org rather than killing
 * them (see `MembershipAccess.unbindSessions`), which fixed the leak at source —
 * but it also means a session with **no** org reaches handlers that never used
 * to receive one, and it does nothing for a membership that ends by any other
 * route. So the check belongs here too, per request.
 *
 * Two refusals, deliberately different:
 *
 *  - **No org on the session at all → 400 `no_org_selected`.** Nothing is being
 *    refused; the request simply does not name the thing it would be authorized
 *    against. This is what `requireAuthWithOrg` already returns everywhere, and
 *    it is the state a signed-in account with no memberships legitimately sits
 *    in — the app's "no organizations" screen. A 403 there would tell a user
 *    they were denied something they never asked for.
 *  - **Org named, membership gone → 403 `not_org_member`.** Authenticated,
 *    well-formed, and refused: the textbook 403, and the answer every sibling
 *    route already gives for this exact condition via `requireOrgRead` /
 *    `requireCachedPermissions`. A 409 was considered and rejected — nothing
 *    here is a state conflict the caller could resolve by retrying, and making
 *    these routes the only ones in the API answering differently for
 *    "not a member" would be its own bug.
 *
 * Without the check the failure was not even an error: a stale org narrows to
 * nothing and the caller gets an empty roster, which reads as "the fleet is
 * empty" rather than "you do not belong here".
 */
@Testcontainers
class AgentHealthMembershipTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_agent_membership_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            db = Database.connect(HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = "org.postgresql.Driver"
            }))

            // Only the TOTP path uses this key; a dummy is enough here.
            AuthController.init("0".repeat(64))

            transaction(db) {
                ProbeAgents.insert {
                    it[slug] = "agent-membership-test"
                    it[label] = "Test agent"
                    it[agentUri] = "https://agent.invalid"
                    it[publicKey] = "-----BEGIN PUBLIC KEY-----"
                    it[isActive] = true
                    it[deleted] = false
                    it[lastPing] = Instant.now()
                    it[lastStatus] = "success"
                    it[lastPingDelayMs] = 0
                    it[lastPongDeltaMs] = 1
                    it[createdAt] = Instant.now()
                }
            }
        }

        private fun newUser(): UUID = transaction(db) {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "u-$userId@t.dev"
                it[passwordHash] = "x"
                it[displayName] = "u"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            userId
        }

        /** An org owned by [ownerId], with [ownerId] as an active member. */
        private fun newOrg(ownerId: UUID): UUID = transaction(db) {
            val orgId = UUID.randomUUID()
            Organizations.insert {
                it[id] = orgId
                it[name] = "Org ${orgId.toString().take(6)}"
                it[Organizations.ownerId] = ownerId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            addMember(orgId, ownerId)
            orgId
        }

        private fun addMember(orgId: UUID, userId: UUID): UUID = transaction(db) {
            val membershipId = UUID.randomUUID()
            OrgUsers.insert {
                it[id] = membershipId
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[status] = "active"
                it[isActive] = true
                it[deleted] = false
                it[inviteToken] = ""
            }
            membershipId
        }

        /**
         * An active agent plus a run of passing health rounds, [rounds] given
         * most recent first — the order the degradation rule reads them in.
         */
        private fun newAgentWithRounds(agentSlug: String, rounds: List<Int>): Long {
            val agentId = transaction(db) {
                ProbeAgents.insert {
                    it[slug] = agentSlug
                    it[label] = agentSlug
                    it[agentUri] = "https://$agentSlug.invalid"
                    it[publicKey] = "-----BEGIN PUBLIC KEY-----"
                    it[isActive] = true
                    it[deleted] = false
                    it[lastPing] = Instant.now()
                    it[lastStatus] = "success"
                    it[lastPingDelayMs] = 0
                    it[lastPongDeltaMs] = rounds.first()
                    it[createdAt] = Instant.now()
                } get ProbeAgents.id
            }
            insertRounds(agentId, rounds, Instant.now().minusSeconds(3600))
            return agentId
        }

        /** Newer rounds on top of an agent's history, again most recent first. */
        private fun appendRounds(agentId: Long, rounds: List<Int>) =
            insertRounds(agentId, rounds, Instant.now())

        private fun insertRounds(agentId: Long, rounds: List<Int>, newest: Instant) = transaction(db) {
            rounds.forEachIndexed { index, ms ->
                val at = newest.minusSeconds(index.toLong())
                AgentHealthChecks.insert {
                    it[id] = UUID.randomUUID()
                    it[probeAgentId] = agentId
                    it[challengeId] = UUID.randomUUID().toString()
                    it[challengedAt] = at
                    it[respondedAt] = at
                    it[roundTripMs] = ms
                    it[result] = "pass"
                    it[createdAt] = at
                }
            }
        }

        /** The roster entry for [agentSlug] out of a `/agents/health` body. */
        private fun statusOf(body: String, agentSlug: String): JsonObject =
            Json.parseToJsonElement(body).jsonObject["statuses"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["agentSlug"]!!.jsonPrimitive.content == agentSlug }

        /** Returns the bearer token for a fresh active session scoped to [orgId]. */
        private fun newSession(userId: UUID, orgId: UUID?): String {
            val token = "tok-${UUID.randomUUID()}"
            transaction(db) {
                Sessions.insert {
                    it[id] = UUID.randomUUID()
                    it[Sessions.userId] = userId
                    it[organizationId] = orgId
                    it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                    it[status] = SessionStatus.ACTIVE
                    it[expiresAt] = Instant.now().plusSeconds(3600)
                    it[lastActiveAt] = Instant.now()
                    it[revoked] = false
                    it[createdAt] = Instant.now()
                }
            }
            return token
        }
    }

    /**
     * The route under test, wired the way `Application.module()` wires it, minus
     * everything it does not touch. StatusPages mirrors production's mapping of
     * [ApiException] — the status and code live on the exception itself, so the
     * assertions below read the same values a real client would.
     */
    private fun health(token: String, assert: suspend (HttpStatusCode, String) -> Unit) = testApplication {
        application {
            install(Resources)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, mapOf("code" to cause.code)) }
            }
            routing { agentRoutes() }
        }
        val response = client.get("/api/v1/agents/health") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assert(response.status, response.bodyAsText())
    }

    @Test
    fun `an active member sees the roster`() {
        val owner = newUser()
        val orgId = newOrg(owner)
        health(newSession(owner, orgId)) { status, body ->
            assertEquals(HttpStatusCode.OK, status)
            assertTrue("agent-membership-test" in body, "the member should see the fleet: $body")
        }
    }

    @Test
    fun `a session scoped to an org the user never joined is refused`() {
        val owner = newUser()
        val orgId = newOrg(owner)
        val stranger = newUser()
        health(newSession(stranger, orgId)) { status, body ->
            assertEquals(HttpStatusCode.Forbidden, status, body)
            assertTrue(ErrorCodes.NOT_ORG_MEMBER in body, "expected not_org_member: $body")
        }
    }

    @Test
    fun `a session outliving its membership is refused rather than answered emptily`() {
        val owner = newUser()
        val orgId = newOrg(owner)
        val member = newUser()
        val membershipId = addMember(orgId, member)
        val token = newSession(member, orgId)

        // Sanity: it worked while the membership stood.
        health(token) { status, _ -> assertEquals(HttpStatusCode.OK, status) }

        // The membership ends without the session being re-scoped — the case
        // `unbindSessions` cannot cover, and the one that used to return an
        // empty roster instead of a refusal.
        transaction(db) {
            OrgUsers.update({ OrgUsers.id eq membershipId }) { it[deleted] = true }
        }

        health(token) { status, body ->
            assertEquals(HttpStatusCode.Forbidden, status, body)
            assertTrue(ErrorCodes.NOT_ORG_MEMBER in body, "expected not_org_member: $body")
        }
    }

    /**
     * The roster carries the platform's own slowness verdict, not just the
     * observation it was reached from.
     *
     * The dashboard used to re-decide this itself, on a fixed ceiling, and so
     * called any agent far enough away to sit near that ceiling every round
     * unhealthy — permanently, while the platform's baseline-aware rule had
     * never convicted it of anything. It now shows what `DegradationRule`
     * concluded, which is only useful if the roster says so.
     */
    @Test
    fun `the roster carries the degradation verdict, not just the round trip`() {
        val owner = newUser()
        val orgId = newOrg(owner)
        // Thirty rounds at 1.5 s and a 1.8 s one on top: every one of them is
        // over the fixed floor, none of them is over this agent's own ceiling.
        val agentId = newAgentWithRounds("agent-far-away", listOf(1800) + List(30) { 1500 })

        health(newSession(owner, orgId)) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            val entry = statusOf(body, "agent-far-away")
            assertEquals(1800, entry["lastResponseMs"]!!.jsonPrimitive.int)
            assertFalse(entry["degraded"]!!.jsonPrimitive.boolean, "1.8 s is ordinary here: $body")
            assertEquals(1500, entry["baselineMs"]!!.jsonPrimitive.int)
            assertEquals(3000, entry["degradedThresholdMs"]!!.jsonPrimitive.int)
        }

        // Two rounds past twice the median is a condition, and the roster says so.
        appendRounds(agentId, listOf(3400, 3200))
        health(newSession(owner, orgId)) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            val entry = statusOf(body, "agent-far-away")
            assertTrue(entry["degraded"]!!.jsonPrimitive.boolean, "two rounds over the ceiling: $body")
            assertEquals(3000, entry["degradedThresholdMs"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `an agent with too few rounds answers to the fixed floor alone`() {
        val owner = newUser()
        val orgId = newOrg(owner)
        newAgentWithRounds("agent-new-here", listOf(1800, 1500))

        health(newSession(owner, orgId)) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            val entry = statusOf(body, "agent-new-here")
            assertTrue(entry["baselineMs"] is JsonNull, "no median from two rounds: $body")
            assertEquals(DEGRADED_RTT_MS, entry["degradedThresholdMs"]!!.jsonPrimitive.int)
            assertTrue(entry["degraded"]!!.jsonPrimitive.boolean, "both rounds are over the floor: $body")
        }
    }

    @Test
    fun `a session with no org at all is a 400, not a 403`() {
        // The state a signed-in account with no memberships legitimately sits
        // in, and the state `unbindSessions` now leaves a removed member's
        // session in. Nothing is being denied — no org was named.
        val user = newUser()
        health(newSession(user, null)) { status, body ->
            assertEquals(HttpStatusCode.BadRequest, status, body)
            assertTrue(ErrorCodes.NO_ORG_SELECTED in body, "expected no_org_selected: $body")
        }
    }
}
