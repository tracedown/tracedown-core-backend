package dev.tracedown.scheduler.dispatch

import at.favre.lib.crypto.bcrypt.BCrypt
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.domain.TargetOptOut
import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.net.ProbeTargetPolicy
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How a run's variables reach dispatch: read exactly as the save-time checks
 * read them, and never sent without one the script needs.
 *
 * A variable the script uses that exists but will not decrypt used to throw
 * out of the resolver, and since every variable of every scope was loaded, one
 * unreadable row errored every tick of every service beneath it. Running
 * without the value instead would send `null` in its place — a `Bearer null`
 * that fails as if the target were down, a host that is not the configured
 * one. So the tick is skipped, with a reason of its own, and only variables
 * the script uses are read at all.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UndecryptableVariableDispatchTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val D = "$"

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

    private lateinit var redisSync: io.lettuce.core.api.sync.RedisCommands<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()

    /** An organization that has never had an encryption key. */
    private val keylessOrgId: UUID = UUID.randomUUID()
    private val keylessWorkspaceId: UUID = UUID.randomUUID()

    private val logs = ListAppender<ILoggingEvent>()
    private val tracedownLogger = LoggerFactory.getLogger("dev.tracedown") as Logger
    private var previousLevel: Level? = null

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
                it[email] = "undecryptable-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(4, "Test1234!".toCharArray())
                it[displayName] = "Undecryptable Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            for ((org, ws) in listOf(orgId to workspaceId, keylessOrgId to keylessWorkspaceId)) {
                Organizations.insert {
                    it[id] = org
                    it[name] = "Org $org"
                    it[ownerId] = userId
                    it[deleted] = false
                    it[createdAt] = Instant.now()
                }
                Workspaces.insert {
                    it[id] = ws
                    it[organizationId] = org
                    it[name] = "Workspace $ws"
                    it[deleted] = false
                    it[createdAt] = Instant.now()
                }
            }
            OrgDomains.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[domain] = "verified.example"
                it[challenge] = "irrelevant"
                it[verificationType] = "dns-01"
                it[status] = "verified"
                it[verifiedAt] = Instant.now()
                it[wildcardEnabled] = true
                it[deleted] = false
            }
        }
    }

    @BeforeEach
    fun captureLogs() {
        logs.list.clear()
        logs.start()
        previousLevel = tracedownLogger.level
        tracedownLogger.level = Level.DEBUG
        tracedownLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        tracedownLogger.detachAppender(logs)
        tracedownLogger.level = previousLevel
        logs.stop()
    }

    // ── A variable the script uses will not decrypt ──

    @Test
    fun `a referenced variable that will not decrypt skips the tick, whatever the policy`() {
        for (mode in ProbeTargetPolicy.Mode.entries) {
            val projectId = seedProject()
            storeMovedSecret(projectId, "host", "http://10.0.0.5")
            storeMovedSecret(projectId, "token", "s3cret-token")
            for (script in listOf(
                """get("${D}p.host/health")""",
                """get("https://api.example.com/x?token=${D}p.token")""",
            )) {
                val outcome = dispatch(seedService(projectId, script), mode)

                assertTrue(outcome.requests.isEmpty(), "$mode $script: nothing may be sent")
                assertEquals(SKIP_VARIABLE_UNREADABLE, outcome.skipReason, "$mode $script")
            }
        }
    }

    @Test
    fun `an organization with no encryption key, and a legacy value with no IV, are unreadable too`() {
        // An envelope value copied in from an organization that has a key.
        val keylessProject = seedProject(keylessWorkspaceId)
        val envelope = transaction { VariableCrypto.encrypt(orgId, "https://example.org", "project", "host") }
        insertVariable(keylessProject, "host", envelope, null, secret = true)
        assertEquals(
            SKIP_VARIABLE_UNREADABLE,
            dispatch(seedService(keylessProject, """get("${D}p.host/health")"""), ProbeTargetPolicy.Mode.ALLOW_PRIVATE).skipReason,
        )

        val legacyProject = seedProject()
        val (legacy, _) = VariableCrypto.encrypt("https://example.org")
        insertVariable(legacyProject, "host", legacy, null, secret = false)
        assertEquals(
            SKIP_VARIABLE_UNREADABLE,
            dispatch(seedService(legacyProject, """get("${D}p.host/health")"""), ProbeTargetPolicy.Mode.ALLOW_PRIVATE).skipReason,
        )
    }

    @Test
    fun `an unreadable variable the script does not use affects nothing`() {
        val projectId = seedProject()
        // Keys sorting before and after the one in use.
        storeMovedSecret(projectId, "aaa", "x")
        storeMovedSecret(projectId, "zzz", "x")
        storeVariable(projectId, "baseUrl", "https://example.org")
        val serviceId = seedService(projectId, """get("${D}p.baseUrl/health")""")

        val outcome = dispatch(serviceId, ProbeTargetPolicy.Mode.PUBLIC_ONLY)

        assertEquals(1, outcome.requests.size, "the tick runs")
        val vars = outcome.requests.single().variables
        assertEquals("https://example.org", vars["p_baseUrl"]!!.jsonPrimitive.content)
        assertFalse(vars.containsKey("p_aaa"))
        assertFalse(vars.containsKey("p_zzz"))
        // Never read, so never reported.
        assertTrue(warnings().none { "aaa" in it || "zzz" in it }, warnings().toString())
    }

    @Test
    fun `the warning names the variable and the remedy, never the value, at most hourly`() {
        val projectId = seedProject()
        val key = "tok${UUID.randomUUID().toString().take(6)}"
        storeMovedSecret(projectId, key, "plaintext-never-logged")
        val serviceId = seedService(projectId, """get("https://api.example.com/?t=${D}p.$key")""")

        dispatch(serviceId, ProbeTargetPolicy.Mode.ALLOW_PRIVATE)
        dispatch(serviceId, ProbeTargetPolicy.Mode.ALLOW_PRIVATE)

        val mine = warnings().filter { key in it }
        assertEquals(1, mine.size, "one warning per variable per hour: $mine")
        val line = mine.single()
        assertTrue("project" in line && orgId.toString() in line && "PLATFORM_AES_KEY" in line, line)
        assertNoneContains("plaintext-never-logged", "Tag mismatch", "AEADBadTag")
    }

    // ── Decrypted targets never reach a log or the cache ──

    @Test
    fun `a refused target is logged as written, not as decrypted`() {
        val projectId = seedProject()
        storeSecret(projectId, "host", "http://10.0.0.5")
        val serviceId = seedService(projectId, """get("${D}p.host/health")""")

        val outcome = dispatch(serviceId, ProbeTargetPolicy.Mode.PUBLIC_ONLY)

        assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, outcome.skipReason)
        assertTrue(allMessages().any { "${D}p_host/health" in it }, allMessages().toString())
        assertNoneContains("10.0.0.5")
    }

    @Test
    fun `an opted-out target is logged as written and cached under a digest`() {
        val projectId = seedProject()
        storeSecret(projectId, "host", "https://api.secret-optout.example")
        val serviceId = seedService(projectId, """get("${D}p.host/health")""")
        val checker = TargetOptOutChecker(redisSync, { name ->
            if (name == TargetOptOut.recordName("secret-optout.example")) listOf("no") else emptyList()
        })

        val outcome = dispatch(serviceId, ProbeTargetPolicy.Mode.ALLOW_PRIVATE, targetOptOut = checker)

        assertEquals(SKIP_TARGET_OPTED_OUT, outcome.skipReason)
        assertNoneContains("secret-optout")
        val keys = redisSync.keys("${TargetOptOutChecker.CACHE_PREFIX}*")
        assertTrue(TargetOptOutChecker.cacheKey("api.secret-optout.example") in keys, keys.toString())
        assertTrue(keys.none { "secret-optout" in it }, keys.toString())
    }

    // ── Save and dispatch read references the same way ──

    @Test
    fun `a braced host is judged on its value`() {
        val projectId = seedProject()
        storeSecret(projectId, "private", "http://10.0.0.5")
        storeSecret(projectId, "public", "https://example.org")

        val refused = dispatch(
            seedService(projectId, """get("${D}{${D}p.private}/health")"""),
            ProbeTargetPolicy.Mode.PUBLIC_ONLY,
        )
        assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, refused.skipReason)

        val sent = dispatch(
            seedService(projectId, """get("${D}{${D}p.public}/health")"""),
            ProbeTargetPolicy.Mode.PUBLIC_ONLY,
        )
        assertEquals(1, sent.requests.size)
        // The braces survive the rewrite, so Lace still reads the reference.
        assertTrue("${D}{${D}p_public}" in sent.requests.single().script, sent.requests.single().script)
    }

    @Test
    fun `a subdomain built from a variable is judged on its value`() {
        val projectId = seedProject()
        val serviceId = UUID.randomUUID()
        // Four calls: over the unverified-domain limit, so the tick runs only
        // when every host is seen to be on the verified domain.
        val script = listOf(
            """get("https://${D}{${D}s.sub}.verified.example/a")""",
            """get("https://${D}s.sub.verified.example/b")""",
            """get("https://${D}{${D}s.sub}.verified.example/c")""",
            """get("https://${D}s.sub.verified.example/d")""",
        ).joinToString("\n")
        seedService(projectId, script, serviceId)
        val (stored, iv) = VariableCrypto.encrypt("api")
        transaction {
            ServiceVariables.insert {
                it[id] = UUID.randomUUID()
                it[ServiceVariables.serviceId] = serviceId
                it[key] = "sub"
                it[value] = stored
                it[valueIv] = iv
                it[secret] = false
                it[encrypted] = true
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }

        val outcome = dispatch(serviceId, ProbeTargetPolicy.Mode.ALLOW_PRIVATE, trustedDomainMode = false)

        // Sent to the backend (which, capturing only, then reports no agent).
        assertEquals(1, outcome.requests.size, "not withheld: ${outcome.skipReason}")
        assertEquals("api", outcome.requests.single().variables["s_sub"]!!.jsonPrimitive.content)
    }

    // ── Harness ──

    private data class Outcome(
        val requests: List<ProbeExecutionBackend.Request>,
        val skipReason: String?,
    )

    private fun warnings(): List<String> =
        logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun allMessages(): List<String> =
        logs.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }

    private fun assertNoneContains(vararg needles: String) {
        for (needle in needles) {
            val hit = allMessages().firstOrNull { needle in it }
            assertNull(hit, "a log line carries '$needle'")
        }
    }

    private fun seedProject(workspace: UUID = workspaceId): UUID {
        val id = UUID.randomUUID()
        transaction {
            Projects.insert {
                it[Projects.id] = id
                it[Projects.workspaceId] = workspace
                it[name] = "proj-${id.toString().take(8)}"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return id
    }

    private fun seedService(projectId: UUID, script: String, serviceId: UUID = UUID.randomUUID()): UUID {
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = projectId
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

    private fun storeSecret(projectId: UUID, key: String, value: String) {
        val stored = transaction { VariableCrypto.encrypt(orgId, value, "project", key) }
        insertVariable(projectId, key, stored, null, secret = true)
    }

    /** A secret that will not decrypt: encrypted for another key, so its AAD fails. */
    private fun storeMovedSecret(projectId: UUID, key: String, value: String) {
        val stored = transaction { VariableCrypto.encrypt(orgId, value, "project", "$key-elsewhere") }
        insertVariable(projectId, key, stored, null, secret = true)
    }

    private fun storeVariable(projectId: UUID, key: String, value: String) {
        val (stored, iv) = VariableCrypto.encrypt(value)
        insertVariable(projectId, key, stored, iv, secret = false)
    }

    private fun insertVariable(projectId: UUID, key: String, value: String, iv: String?, secret: Boolean) {
        transaction {
            ProjectVariables.insert {
                it[id] = UUID.randomUUID()
                it[ProjectVariables.projectId] = projectId
                it[ProjectVariables.key] = key
                it[ProjectVariables.value] = value
                it[valueIv] = iv
                it[ProjectVariables.secret] = secret
                it[encrypted] = true
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    /** Runs one tick through the real pipeline and reports what came of it. */
    private fun dispatch(
        serviceId: UUID,
        mode: ProbeTargetPolicy.Mode,
        targetOptOut: TargetOptOutChecker? = null,
        trustedDomainMode: Boolean = true,
    ): Outcome = runBlocking {
        val backend = CapturingBackend()
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
            trustedDomainMode = trustedDomainMode,
            targetPolicy = mode,
            targetOptOut = targetOptOut,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            assertTrue(queue.enqueue(serviceId), "service should enqueue")
            val deadline = System.currentTimeMillis() + 10_000
            var raw: JsonObject? = null
            while (System.currentTimeMillis() < deadline) {
                raw = queuedResult()
                if (backend.requests.isNotEmpty() || raw != null) break
                delay(50)
            }
            val outcome = raw?.get("outcome")?.jsonPrimitive?.content
            assertTrue(outcome == null || outcome == "skipped", "no error result, got $raw")
            Outcome(backend.requests.toList(), raw?.get("reason")?.jsonPrimitive?.content)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    private fun queuedResult(): JsonObject? {
        val envelope = redisSync.lindex(ResultPublisher.QUEUE_KEY, 0) ?: return null
        return Json.parseToJsonElement(envelope).jsonObject["rawResult"]?.jsonObject
    }
}
