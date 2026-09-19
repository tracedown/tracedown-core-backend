package dev.tracedown.scheduler.scheduling

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.agents.DegradationRule
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.ProbeAgents
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * The verdict read back must be the verdict that was reached.
 *
 * Two callers ask the degradation rule about the same agent, and they ask it
 * differently. [HealthChallengeJob] asks once, as a round is written, from the
 * rounds *before* it — that answer decides whether an alert is raised, and then
 * it is gone: nothing about it is stored. Anything that later serialises the
 * agent's health has to ask again, from the rounds as they now stand, with the
 * round itself sitting at the head of the list. If the two ever disagreed the
 * dashboard would contradict the alert, which is exactly the class of defect
 * this rule was shared to end.
 *
 * So the test runs a whole history through both paths, round by round, and
 * requires them to agree on every single one.
 */
@Testcontainers
class DegradationVerdictAgreementTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_degradation_agreement_test")
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
        }
    }

    /**
     * A distant agent's history: a long spell at its own ordinary pace, jitter
     * that never leaves it, then a genuine pair of slow rounds, then recovery.
     * Every round in the first stretch is over the fixed floor, so a rule that
     * only knew the floor would convict almost all of them.
     */
    private val history: List<Int> =
        List(20) { 1500 } + listOf(1700, 1450, 1820, 1500) + listOf(3400, 3300) + listOf(1550, 1500)

    @Test
    fun `every round is read back the way the scheduler decided it`() {
        val agentId = newAgent("agent-agreement")
        val start = Instant.now().minusSeconds(history.size.toLong() * 60)
        val convicted = mutableListOf<Int>()

        history.forEachIndexed { index, roundTripMs ->
            transaction(db) {
                // The scheduler's path: asked before the round is written, so
                // the sample never includes the round being judged.
                val atWrite = DegradationRule.verdictForNewRound(agentId, roundTripMs)
                if (atWrite.degraded) convicted.add(index)

                val at = start.plusSeconds(index.toLong() * 60)
                AgentHealthChecks.insert {
                    it[id] = UUID.randomUUID()
                    it[probeAgentId] = agentId
                    it[challengeId] = UUID.randomUUID().toString()
                    it[challengedAt] = at
                    it[respondedAt] = at
                    it[AgentHealthChecks.roundTripMs] = roundTripMs
                    it[result] = DegradationRule.RESULT_PASS
                    it[createdAt] = at
                }

                // Every reader's path: asked afterwards, off the stored rounds.
                val atRead = DegradationRule.verdictFor(agentId)
                assertEquals(atWrite, atRead, "round #$index at $roundTripMs ms was read back differently")
            }
        }

        // Without this the agreement above could be an agreement on "never",
        // and the list says which rounds it is an agreement about. Rounds 1-4
        // are the fixed floor doing its job while the agent has too few rounds
        // for a median of its own — every pair over 1.2 s counts until then.
        // From round 5 the agent's own pace takes over and only the genuine
        // slow pair convicts, on the second of the two.
        assertEquals(listOf(1, 2, 3, 4, 25), convicted, "the wrong rounds were called degraded")
    }

    @Test
    fun `an agent that has never answered is not judged`() {
        val agentId = newAgent("agent-agreement-silent")
        transaction(db) {
            assertEquals(DegradationRule.UNKNOWN, DegradationRule.verdictFor(agentId))
            // The floor alone, and nothing to convict: there is no prior round.
            val first = DegradationRule.verdictForNewRound(agentId, 9000)
            assertFalse(first.degraded, "a first round has no prior and never convicts")
        }
    }

    private fun newAgent(agentSlug: String): Long = transaction(db) {
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
            it[lastPongDeltaMs] = 0
            it[createdAt] = Instant.now()
        } get ProbeAgents.id
    }
}
