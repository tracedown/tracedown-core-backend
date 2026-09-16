package dev.tracedown.scheduler.dispatch

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.domain.TargetOptOut
import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.QuartzManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A target that publishes `_tracedown-noprobe` is never dispatched to: the tick
 * is recorded as skipped and the agents are not asked to touch the host.
 *
 * The exception is a host the org has *proven* it operates — there the record
 * is the org's own and cannot be a third party declining, so it is not even
 * looked up. Running the whole dispatch path (not just the checker) is the
 * point: the ordering against the address policy, the ownership question and
 * the skipped row are what this feature is.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetOptOutDispatchTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:8-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
    }

    /** Records what the pipeline asked for; runs nothing. */
    private class CapturingBackend : ProbeExecutionBackend {
        val requests = CopyOnWriteArrayList<ProbeExecutionBackend.Request>()

        override suspend fun execute(
            request: ProbeExecutionBackend.Request,
        ): List<ProbeExecutionBackend.Execution> {
            requests.add(request)
            return emptyList()
        }
    }

    /** A resolver that answers for the named zones, and counts what it was asked. */
    private class FakeResolver(private val optedOut: String) : (String) -> List<String> {
        val asked = CopyOnWriteArrayList<String>()
        override fun invoke(name: String): List<String> {
            asked.add(name)
            return if (name == TargetOptOut.recordName(optedOut)) listOf("no probes please") else emptyList()
        }
    }

    private lateinit var redisSync: io.lettuce.core.api.sync.RedisCommands<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()

        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)
        redisSync = RedisFactory.createConnection("redis://${redis.host}:${redis.getMappedPort(6379)}").sync()
        VariableCrypto.init(AES_KEY)

        quartzManager = QuartzManager(1)
        queuePolicy = QueuePolicyManager(redisSync)
        resultPublisher = ResultPublisher(redisSync)

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "opt-out-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, "Test1234!".toCharArray())
                it[displayName] = "Opt Out Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Opt Out Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Opt Out Workspace"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@TargetOptOutDispatchTest.workspaceId
                it[name] = "Opt Out Project"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    @Test
    fun `a target that publishes the record is not probed`() {
        val serviceId = seedService("""get("https://api.optout.example/health")""")
        val resolver = FakeResolver("optout.example")

        val outcome = dispatch(serviceId, TargetOptOutChecker(redisSync, resolver))

        assertTrue(outcome.requests.isEmpty(), "nothing may be dispatched to an agent")
        val skipped = outcome.skipped
        assertNotNull(skipped, "the withheld tick must be visible in the history")
        assertEquals("skipped", skipped!!["outcome"]!!.jsonPrimitive.content)
        assertEquals(SKIP_TARGET_OPTED_OUT, skipped["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a record at the apex covers the subdomain actually probed`() {
        val serviceId = seedService("""get("https://api.eu.apex.example/health")""")
        val resolver = FakeResolver("apex.example")

        val outcome = dispatch(serviceId, TargetOptOutChecker(redisSync, resolver))

        assertTrue(outcome.requests.isEmpty())
        assertEquals(SKIP_TARGET_OPTED_OUT, outcome.skipped!!["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a domain the org has proven it owns is never asked about`() {
        seedVerifiedDomain("owned.example", wildcard = false)
        val serviceId = seedService("""get("https://owned.example/health")""")
        val resolver = FakeResolver("owned.example")

        val outcome = dispatch(serviceId, TargetOptOutChecker(redisSync, resolver))

        assertEquals(1, outcome.requests.size, "the org operates this zone — it is probed")
        assertTrue(resolver.asked.isEmpty(), "and no lookup is made about it at all")
    }

    @Test
    fun `a wildcard domain covers its subdomains`() {
        seedVerifiedDomain("wild.example", wildcard = true)
        val serviceId = seedService("""get("https://api.wild.example/health")""")
        val resolver = FakeResolver("wild.example")

        val outcome = dispatch(serviceId, TargetOptOutChecker(redisSync, resolver))

        assertEquals(1, outcome.requests.size)
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun `ownership that has lapsed proves nothing`() {
        seedVerifiedDomain("lapsed.example", wildcard = true, lapsed = true)
        val serviceId = seedService("""get("https://api.lapsed.example/health")""")
        val resolver = FakeResolver("lapsed.example")

        val outcome = dispatch(serviceId, TargetOptOutChecker(redisSync, resolver))

        assertTrue(outcome.requests.isEmpty(), "a lapsed domain is not proof of anything")
        assertEquals(SKIP_TARGET_OPTED_OUT, outcome.skipped!!["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a host assembled at runtime names no zone to ask`() {
        val serviceId = seedService("""get("${'$'}o_endpoint/health")""")
        val resolver = FakeResolver("optout.example")

        val outcome = dispatch(serviceId, TargetOptOutChecker(redisSync, resolver))

        assertEquals(1, outcome.requests.size, "the address policy owns that case, not this one")
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun `with the check off nothing is looked up`() {
        val serviceId = seedService("""get("https://api.optout.example/health")""")

        val outcome = dispatch(serviceId, targetOptOut = null)

        // The host publishes the record; with the check off nobody asks, and
        // the tick reaches the backend like any other.
        assertEquals(1, outcome.requests.size)
    }

    @Test
    fun `a resolver that fails leaves the probe running`() {
        val serviceId = seedService("""get("https://api.servfail.example/health")""")
        val checker = TargetOptOutChecker(redisSync, { throw RuntimeException("SERVFAIL") })

        val outcome = dispatch(serviceId, checker)

        assertEquals(1, outcome.requests.size, "a DNS outage must not stop monitoring")
    }

    // ── Harness ──

    private data class Outcome(
        val requests: List<ProbeExecutionBackend.Request>,
        /** The raw result of the skipped row this tick produced, if any. */
        val skipped: JsonObject?,
    )

    private fun seedService(script: String): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@TargetOptOutDispatchTest.projectId
                it[name] = "svc-${serviceId.toString().take(8)}"
                it[Services.script] = script
                it[schedule] = "*/5 * * * *"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    private fun seedVerifiedDomain(domain: String, wildcard: Boolean, lapsed: Boolean = false) {
        transaction {
            OrgDomains.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[OrgDomains.domain] = domain
                it[challenge] = "irrelevant"
                it[verificationType] = "dns-01"
                it[status] = "verified"
                it[verifiedAt] = Instant.now()
                it[wildcardEnabled] = wildcard
                it[OrgDomains.lapsed] = lapsed
                it[deleted] = false
            }
        }
    }

    /** Runs one tick through the real pipeline and reports what came of it. */
    private fun dispatch(serviceId: UUID, targetOptOut: TargetOptOutChecker?): Outcome = runBlocking {
        val backend = CapturingBackend()
        // Each tick starts from an empty result queue and an empty answer cache,
        // so one test's answers can never stand in for another's.
        redisSync.del(ResultPublisher.QUEUE_KEY)
        redisSync.keys("${TargetOptOutChecker.CACHE_PREFIX}*").forEach { redisSync.del(it) }
        val queue = DispatchQueue(
            capacity = 4,
            workers = 1,
            quartzManager = quartzManager,
            executionBackend = backend,
            queuePolicy = queuePolicy,
            resultPublisher = resultPublisher,
            probeConfig = SchedulerConfig.ProbeConfig(defaultTimeoutMs = 30_000, maxTimeoutMs = 30_000, maxRedirects = 5),
            // The unverified-domain rule is a separate policy with its own
            // tests; trusting the domains keeps it out of this one.
            trustedDomainMode = true,
            targetOptOut = targetOptOut,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            assertTrue(queue.enqueue(serviceId), "service should enqueue")
            val deadline = System.currentTimeMillis() + 10_000
            var skipped: JsonObject? = null
            while (System.currentTimeMillis() < deadline) {
                skipped = skippedResult()
                if (backend.requests.isNotEmpty() || skipped != null) break
                delay(50)
            }
            Outcome(backend.requests.toList(), skipped)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    /** The skipped envelope on the result queue, if the tick produced one. */
    private fun skippedResult(): JsonObject? {
        val envelope = redisSync.lindex(ResultPublisher.QUEUE_KEY, 0) ?: return null
        val raw = Json.parseToJsonElement(envelope).jsonObject["rawResult"]?.jsonObject ?: return null
        return if (raw["outcome"]?.jsonPrimitive?.content == "skipped") raw else null
    }
}
