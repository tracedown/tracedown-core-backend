package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStoreInput
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.ingestor.TestMinio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Ingest follows the body store of the agent that ran the probe.
 *
 * No store: today's relocation within the default store. `import`: the body is
 * copied out of the agent's store into the default store and recorded there.
 * `in_place`: the agent's location is kept verbatim with the store's id — but
 * only when it lies inside that store; anything else is `outsideAssignedStore`.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BodyStoreIngestTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_body_store_ingest_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        private const val IMPORT_BUCKET = "agent-import"
        private const val IN_PLACE_BUCKET = "agent-in-place"
    }

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()
    private val serviceId: UUID = UUID.randomUUID()
    private var defaultAgent: Long = 0
    private var importAgent: Long = 0
    private var inPlaceAgent: Long = 0
    private lateinit var inPlaceStore: UUID
    private lateinit var defaultRoot: Path

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)
        VariableCrypto.init("0".repeat(64))
        BodyStoreRegistry.configure(deploymentEnvironment = "dev")

        // The default store: a filesystem root, relocated into as today.
        defaultRoot = Files.createTempDirectory("ingest-default-bodies").toRealPath()
        ResultPersistenceService.init(
            BodyRelocator(BodyStorageClient(confinement = BodyConfinement(filesystemRoot = defaultRoot))),
        )

        TestMinio.bucket(IMPORT_BUCKET)
        TestMinio.bucket(IN_PLACE_BUCKET)
        val importStore = BodyStoreService.create(s3Store("import-store", "import", IMPORT_BUCKET, prefix = null))
        inPlaceStore = UUID.fromString(
            BodyStoreService.create(s3Store("in-place-store", "in_place", IN_PLACE_BUCKET, prefix = "bodies")).id,
        )

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "body-store-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Body Store"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Body Store Org"
                it[ownerId] = userId
                it[createdAt] = NOW
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Workspace"
                it[createdAt] = NOW
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@BodyStoreIngestTest.workspaceId
                it[name] = "Project"
                it[createdAt] = NOW
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@BodyStoreIngestTest.projectId
                it[name] = "Service"
                it[createdAt] = NOW
            }
            defaultAgent = agent("default-agent", null)
            importAgent = agent("import-agent", UUID.fromString(importStore.id))
            inPlaceAgent = agent("in-place-agent", inPlaceStore)
        }
    }

    private fun s3Store(name: String, mode: String, bucket: String, prefix: String?) = BodyStoreInput(
        name = name, kind = "s3", mode = mode, endpoint = TestMinio.endpoint, bucket = bucket, prefix = prefix,
        accessKeyId = TestMinio.USER, secretAccessKey = TestMinio.PASSWORD,
    )

    private fun agent(slug: String, store: UUID?): Long = ProbeAgents.insert {
        it[ProbeAgents.slug] = slug
        it[label] = slug
        it[agentUri] = "https://$slug.example.test:8443"
        it[publicKey] = "x"
        it[lastPing] = NOW
        it[lastStatus] = "success"
        it[lastPingDelayMs] = 0
        it[lastPongDeltaMs] = 0
        it[createdAt] = NOW
        it[bodyStoreId] = store
    }[ProbeAgents.id]

    /** One successful run by [agentId] with a single call whose body the agent stored at [bodyPath]. */
    private fun persistRun(agentId: Long, bodyPath: String): UUID {
        val resultId = UUID.randomUUID()
        val envelope = Json.parseToJsonElement(
            """
            {
              "resultId": "$resultId",
              "serviceId": "$serviceId",
              "probeAgentId": $agentId,
              "projectId": "$projectId",
              "workspaceId": "$workspaceId",
              "organizationId": "$orgId",
              "startedAt": "$NOW",
              "rawResult": {
                "outcome": "success",
                "elapsedMs": 40,
                "calls": [{
                  "request": {"url": "https://api.example.test/", "method": "get"},
                  "response": {"status": 200, "responseTimeMs": 30, "bodyPath": "$bodyPath"},
                  "assertions": []
                }]
              }
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, ResultPersistenceService.persist(envelope))
        return resultId
    }

    private fun stepOf(resultId: UUID) = transaction {
        ProbeSteps.selectAll().where { ProbeSteps.probeResultId eq resultId }.single()
    }

    @Test
    fun `an agent on the default store is relocated as before`() {
        val agentBody = defaultRoot.resolve("agent-upload/call_0.json")
        Files.createDirectories(agentBody.parent)
        Files.writeString(agentBody, "default")

        val resultId = persistRun(defaultAgent, "file://$agentBody")

        val step = stepOf(resultId)
        val expected = defaultRoot.resolve("$orgId/$serviceId/$resultId/call_0_response.json")
        assertEquals("file://$expected", step[ProbeSteps.responseBodyStorageUrl])
        assertNull(step[ProbeSteps.bodyStoreId])
        assertNull(step[ProbeSteps.bodyNotStoredReason])
        assertEquals("default", Files.readString(expected))
        assertFalse(Files.exists(agentBody))
    }

    @Test
    fun `an import store's body is moved into the default store`() {
        TestMinio.put(IMPORT_BUCKET, "uploads/call_0.json", "imported".toByteArray(), "application/json")

        val resultId = persistRun(importAgent, "s3://$IMPORT_BUCKET/uploads/call_0.json")

        val step = stepOf(resultId)
        val expected = defaultRoot.resolve("$orgId/$serviceId/$resultId/call_0_response.json")
        assertEquals("file://$expected", step[ProbeSteps.responseBodyStorageUrl], "recorded in the default store")
        assertNull(step[ProbeSteps.bodyStoreId], "the platform owns an imported body")
        assertNull(step[ProbeSteps.bodyNotStoredReason])
        assertEquals("imported", Files.readString(expected))
        assertFalse(TestMinio.exists(IMPORT_BUCKET, "uploads/call_0.json"), "the source is removed")
    }

    @Test
    fun `an import that cannot be read is recorded as unavailable`() {
        val step = stepOf(persistRun(importAgent, "s3://$IMPORT_BUCKET/uploads/never-written.json"))

        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
    }

    @Test
    fun `an import store never lends its client to a location outside it`() {
        TestMinio.put(IN_PLACE_BUCKET, "bodies/not-the-import-store.json", "x".toByteArray())

        val step = stepOf(persistRun(importAgent, "s3://$IN_PLACE_BUCKET/bodies/not-the-import-store.json"))

        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
        assertTrue(TestMinio.exists(IN_PLACE_BUCKET, "bodies/not-the-import-store.json"), "the foreign object is untouched")
    }

    @Test
    fun `an in_place body inside the store is kept verbatim with the store's id`() {
        TestMinio.put(IN_PLACE_BUCKET, "bodies/run-1/call_0.json", "kept".toByteArray())

        val resultId = persistRun(inPlaceAgent, "s3://$IN_PLACE_BUCKET/bodies/run-1/call_0.json")

        val step = stepOf(resultId)
        assertEquals("s3://$IN_PLACE_BUCKET/bodies/run-1/call_0.json", step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(inPlaceStore, step[ProbeSteps.bodyStoreId])
        assertNull(step[ProbeSteps.bodyNotStoredReason])
        assertTrue(TestMinio.exists(IN_PLACE_BUCKET, "bodies/run-1/call_0.json"), "the body stays where it is")
        assertFalse(Files.exists(defaultRoot.resolve("$orgId/$serviceId/$resultId")), "nothing is copied")
    }

    @Test
    fun `an in_place location outside the store is refused`() {
        for (outside in listOf(
            "s3://$IN_PLACE_BUCKET/elsewhere/call_0.json",
            "s3://$IMPORT_BUCKET/bodies/call_0.json",
            "file:///etc/passwd",
        )) {
            val step = stepOf(persistRun(inPlaceAgent, outside))
            assertNull(step[ProbeSteps.responseBodyStorageUrl], outside)
            assertNull(step[ProbeSteps.bodyStoreId], outside)
            assertEquals(BodyNotStoredReason.OUTSIDE_ASSIGNED_STORE, step[ProbeSteps.bodyNotStoredReason], outside)
        }
    }
}
