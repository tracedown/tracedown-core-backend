package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStoreCrypto
import dev.tracedown.common.storage.BodyStoreInput
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.ingestor.TestMinio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
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
 * copied out of the agent's store into the default store and recorded there,
 * and the source goes only once the row has committed. `in_place`: the agent's
 * location is kept verbatim with the store's id — but only when it lies inside
 * the agent's own sub-prefix of that store; anything else is
 * `outsideAssignedStore`. A store of another organization is never used at all.
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
        private const val IMPORT_PREFIX = "uploads"
        private const val IN_PLACE_PREFIX = "bodies"
    }

    private val orgId: UUID = UUID.randomUUID()
    private val otherOrgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()
    private val serviceId: UUID = UUID.randomUUID()
    private var defaultAgent: Long = 0
    private var importAgent: Long = 0
    private var inPlaceAgent: Long = 0
    private var foreignAgent: Long = 0
    private var fsAgent: Long = 0
    private lateinit var importStore: UUID
    private lateinit var inPlaceStore: UUID
    private lateinit var foreignStore: UUID
    private lateinit var fsStore: UUID
    private lateinit var defaultRoot: Path
    private lateinit var storeBase: Path
    private lateinit var fsStoreRoot: Path

    /** Where the agent [slug] writes in an S3 store with [prefix]. */
    private fun agentKey(prefix: String, slug: String, name: String) = "$prefix/$slug/$name"

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)
        BodyStoreCrypto.init("0".repeat(64))

        // The default store: a filesystem root, relocated into as today. Store
        // roots live in a separate base — a store may never overlap the default.
        defaultRoot = Files.createTempDirectory("ingest-default-bodies").toRealPath()
        storeBase = Files.createTempDirectory("ingest-store-base").toRealPath()
        fsStoreRoot = storeBase.resolve("eu-store").also { Files.createDirectories(it) }
        BodyStoreRegistry.configure(filesystemBases = storeBase.toString(), allowPrivateEndpoints = true)
        BodyStoreService.configureDefault(
            dev.tracedown.common.storage.DefaultBodyStore(
                kind = "filesystem", rootPath = defaultRoot.toString(), filesystemRoot = defaultRoot.toString(),
            ),
        )
        ResultPersistenceService.init(
            BodyRelocator(BodyStorageClient(confinement = BodyConfinement(filesystemRoot = defaultRoot))),
        )

        TestMinio.bucket(IMPORT_BUCKET)
        TestMinio.bucket(IN_PLACE_BUCKET)

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "body-store-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Body Store"
                it[createdAt] = NOW
            }
            for ((id, name) in listOf(orgId to "Body Store Org", otherOrgId to "Another Org")) {
                Organizations.insert {
                    it[Organizations.id] = id
                    it[Organizations.name] = name
                    it[ownerId] = userId
                    it[createdAt] = NOW
                }
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
        }

        importStore = UUID.fromString(
            BodyStoreService.create(orgId, s3Store("import-store", "import", IMPORT_BUCKET, IMPORT_PREFIX)).id,
        )
        inPlaceStore = UUID.fromString(
            BodyStoreService.create(orgId, s3Store("in-place-store", "in_place", IN_PLACE_BUCKET, IN_PLACE_PREFIX)).id,
        )
        // Same shape, another organization's.
        foreignStore = UUID.fromString(
            BodyStoreService.create(
                otherOrgId,
                s3Store("foreign-store", "in_place", IN_PLACE_BUCKET, "foreign"),
            ).id,
        )
        fsStore = UUID.fromString(
            BodyStoreService.create(
                orgId,
                BodyStoreInput(name = "fs-store", kind = "filesystem", mode = "in_place", rootPath = fsStoreRoot.toString()),
            ).id,
        )

        transaction {
            defaultAgent = agent("default-agent", null)
            importAgent = agent("import-agent", importStore)
            inPlaceAgent = agent("in-place-agent", inPlaceStore)
            foreignAgent = agent("foreign-agent", foreignStore)
            fsAgent = agent("fs-agent", fsStore)
        }
    }

    @AfterEach
    fun clearHooks() {
        Interceptors.clearAll()
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

    /** One successful run by [agentId] with one call per body path. */
    private fun persistRun(agentId: Long, vararg bodyPaths: String): UUID {
        val resultId = UUID.randomUUID()
        val calls = bodyPaths.joinToString(",") { path ->
            """
            {
              "request": {"url": "https://api.example.test/", "method": "get"},
              "response": {"status": 200, "responseTimeMs": 30, "bodyPath": "$path"},
              "assertions": []
            }
            """.trimIndent()
        }
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
              "rawResult": {"outcome": "success", "elapsedMs": 40, "calls": [$calls]}
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, ResultPersistenceService.persist(envelope))
        return resultId
    }

    private fun stepOf(resultId: UUID) = transaction {
        ProbeSteps.selectAll().where { ProbeSteps.probeResultId eq resultId }.orderBy(ProbeSteps.stepNum).first()
    }

    private fun stepsOf(resultId: UUID) = transaction {
        ProbeSteps.selectAll().where { ProbeSteps.probeResultId eq resultId }.orderBy(ProbeSteps.stepNum).toList()
    }

    // ── The default store ───────────────────────────────────────────────────

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
    fun `a body already in the default store is relocated whatever store the agent is assigned`() {
        // An agent keeps writing where its own environment says until it is
        // redeployed, so every reassignment has a window that looks like this.
        // Nothing written in it should be lost.
        for (agentId in listOf(inPlaceAgent, importAgent)) {
            val agentBody = defaultRoot.resolve("stale-upload-$agentId/call_0.json")
            Files.createDirectories(agentBody.parent)
            Files.writeString(agentBody, "still ours")

            val resultId = persistRun(agentId, "file://$agentBody")

            val step = stepOf(resultId)
            val expected = defaultRoot.resolve("$orgId/$serviceId/$resultId/call_0_response.json")
            assertEquals("file://$expected", step[ProbeSteps.responseBodyStorageUrl], "agent $agentId")
            assertNull(step[ProbeSteps.bodyStoreId], "agent $agentId")
            assertNull(step[ProbeSteps.bodyNotStoredReason], "agent $agentId")
            assertEquals("still ours", Files.readString(expected))
        }
    }

    // ── import ──────────────────────────────────────────────────────────────

    @Test
    fun `an import store's body is moved into the default store`() {
        val key = agentKey(IMPORT_PREFIX, "import-agent", "call_0.json")
        TestMinio.put(IMPORT_BUCKET, key, "imported".toByteArray(), "application/json")

        val resultId = persistRun(importAgent, "s3://$IMPORT_BUCKET/$key")

        val step = stepOf(resultId)
        val expected = defaultRoot.resolve("$orgId/$serviceId/$resultId/call_0_response.json")
        assertEquals("file://$expected", step[ProbeSteps.responseBodyStorageUrl], "recorded in the default store")
        assertNull(step[ProbeSteps.bodyStoreId], "the platform owns an imported body")
        assertNull(step[ProbeSteps.bodyNotStoredReason])
        assertEquals("imported", Files.readString(expected))
        assertFalse(TestMinio.exists(IMPORT_BUCKET, key), "the source is removed once the row has committed")
    }

    @Test
    fun `an import that cannot be read is recorded as unavailable`() {
        val step = stepOf(persistRun(importAgent, "s3://$IMPORT_BUCKET/${agentKey(IMPORT_PREFIX, "import-agent", "never.json")}"))

        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
    }

    @Test
    fun `an import store never lends its client to a location outside it`() {
        TestMinio.put(IN_PLACE_BUCKET, "$IN_PLACE_PREFIX/not-the-import-store.json", "x".toByteArray())

        val step = stepOf(persistRun(importAgent, "s3://$IN_PLACE_BUCKET/$IN_PLACE_PREFIX/not-the-import-store.json"))

        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
        assertTrue(TestMinio.exists(IN_PLACE_BUCKET, "$IN_PLACE_PREFIX/not-the-import-store.json"), "untouched")
    }

    @Test
    fun `an import store never lends its client to another agent's sub-prefix`() {
        val someoneElse = agentKey(IMPORT_PREFIX, "another-agent", "call_0.json")
        TestMinio.put(IMPORT_BUCKET, someoneElse, "not yours".toByteArray())

        val step = stepOf(persistRun(importAgent, "s3://$IMPORT_BUCKET/$someoneElse"))

        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
        assertTrue(TestMinio.exists(IMPORT_BUCKET, someoneElse), "the other agent's body is untouched")
    }

    @Test
    fun `an import source is kept when the copy cannot be written`() {
        val key = agentKey(IMPORT_PREFIX, "import-agent", "unwritable.json")
        TestMinio.put(IMPORT_BUCKET, key, "keep me".toByteArray())
        // A relocator whose default store refuses every write: a root whose own
        // parent is a regular file, so creating a directory under it fails.
        val blocker = Files.createTempFile("not-a-directory", ".txt")
        val unwritable = blocker.resolve("gone")
        ResultPersistenceService.init(
            BodyRelocator(BodyStorageClient(confinement = BodyConfinement(filesystemRoot = unwritable))),
        )
        try {
            val step = stepOf(persistRun(importAgent, "s3://$IMPORT_BUCKET/$key"))

            assertNull(step[ProbeSteps.responseBodyStorageUrl])
            assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
            assertTrue(TestMinio.exists(IMPORT_BUCKET, key), "a body that was not copied is never removed")
        } finally {
            ResultPersistenceService.init(
                BodyRelocator(BodyStorageClient(confinement = BodyConfinement(filesystemRoot = defaultRoot))),
            )
            Files.deleteIfExists(blocker)
        }
    }

    @Test
    fun `only so many bodies are imported for one result`() {
        val keys = (0..20).map { agentKey(IMPORT_PREFIX, "import-agent", "burst_$it.json") }
        keys.forEach { TestMinio.put(IMPORT_BUCKET, it, "b".toByteArray()) }

        val resultId = persistRun(importAgent, *keys.map { "s3://$IMPORT_BUCKET/$it" }.toTypedArray())

        val steps = stepsOf(resultId)
        assertEquals(keys.size, steps.size)
        val imported = steps.count { it[ProbeSteps.responseBodyStorageUrl] != null }
        val refused = steps.count { it[ProbeSteps.bodyNotStoredReason] == BodyNotStoredReason.STORAGE_UNAVAILABLE }
        assertTrue(imported in 1..16, "at most the per-result cap was imported, got $imported")
        assertEquals(keys.size - imported, refused, "the rest say why")
    }

    // ── in_place ────────────────────────────────────────────────────────────

    @Test
    fun `an in_place body inside the agent's own prefix is kept verbatim with the store's id`() {
        val key = agentKey(IN_PLACE_PREFIX, "in-place-agent", "run-1/call_0.json")
        TestMinio.put(IN_PLACE_BUCKET, key, "kept".toByteArray())

        val resultId = persistRun(inPlaceAgent, "s3://$IN_PLACE_BUCKET/$key")

        val step = stepOf(resultId)
        assertEquals("s3://$IN_PLACE_BUCKET/$key", step[ProbeSteps.responseBodyStorageUrl])
        assertEquals(inPlaceStore, step[ProbeSteps.bodyStoreId])
        assertNull(step[ProbeSteps.bodyNotStoredReason])
        assertTrue(TestMinio.exists(IN_PLACE_BUCKET, key), "the body stays where it is")
        assertFalse(Files.exists(defaultRoot.resolve("$orgId/$serviceId/$resultId")), "nothing is copied")
    }

    @Test
    fun `an in_place location outside the agent's own prefix is refused`() {
        for (outside in listOf(
            // Another agent's corner of the same store.
            "s3://$IN_PLACE_BUCKET/${agentKey(IN_PLACE_PREFIX, "another-agent", "call_0.json")}",
            // The store's prefix, but above the agent's own.
            "s3://$IN_PLACE_BUCKET/$IN_PLACE_PREFIX/call_0.json",
            // A prefix that merely starts with the same letters.
            "s3://$IN_PLACE_BUCKET/$IN_PLACE_PREFIX-evil/in-place-agent/call_0.json",
            // Elsewhere entirely.
            "s3://$IN_PLACE_BUCKET/elsewhere/call_0.json",
            "s3://$IMPORT_BUCKET/$IMPORT_PREFIX/in-place-agent/call_0.json",
            "file:///etc/passwd",
        )) {
            val step = stepOf(persistRun(inPlaceAgent, outside))
            assertNull(step[ProbeSteps.responseBodyStorageUrl], outside)
            assertNull(step[ProbeSteps.bodyStoreId], outside)
            assertEquals(BodyNotStoredReason.OUTSIDE_ASSIGNED_STORE, step[ProbeSteps.bodyNotStoredReason], outside)
        }
    }

    @Test
    fun `a filesystem in_place store keeps the body under the agent's own directory`() {
        val body = fsStoreRoot.resolve("fs-agent/run-1/call_0.json")
        Files.createDirectories(body.parent)
        Files.writeString(body, "on disk")
        val neighbour = fsStoreRoot.resolve("other-agent/call_0.json")
        Files.createDirectories(neighbour.parent)
        Files.writeString(neighbour, "not yours")

        val kept = stepOf(persistRun(fsAgent, "file://$body"))
        assertEquals("file://$body", kept[ProbeSteps.responseBodyStorageUrl])
        assertEquals(fsStore, kept[ProbeSteps.bodyStoreId])
        assertTrue(Files.exists(body), "the platform never moves an in_place body")

        val refused = stepOf(persistRun(fsAgent, "file://$neighbour"))
        assertNull(refused[ProbeSteps.responseBodyStorageUrl])
        assertEquals(BodyNotStoredReason.OUTSIDE_ASSIGNED_STORE, refused[ProbeSteps.bodyNotStoredReason])
        assertEquals("not yours", Files.readString(neighbour))
    }

    // ── Ownership and lifetime ──────────────────────────────────────────────

    @Test
    fun `a store of another organization is never used`() {
        val key = "foreign/foreign-agent/call_0.json"
        TestMinio.put(IN_PLACE_BUCKET, key, "another org's".toByteArray())

        val step = stepOf(persistRun(foreignAgent, "s3://$IN_PLACE_BUCKET/$key"))

        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertNull(step[ProbeSteps.bodyStoreId])
        assertEquals(BodyNotStoredReason.STORE_ORG_MISMATCH, step[ProbeSteps.bodyNotStoredReason])
        assertTrue(TestMinio.exists(IN_PLACE_BUCKET, key), "and nothing of theirs is touched")
    }

    @Test
    fun `a body whose store is deleted mid-ingest is dropped, not dead-lettered`() {
        val doomed = UUID.fromString(
            BodyStoreService.create(orgId, s3Store("doomed-store", "in_place", IN_PLACE_BUCKET, "doomed")).id,
        )
        val slug = "doomed-agent"
        val agentId = transaction { agent(slug, doomed) }
        val key = agentKey("doomed", slug, "call_0.json")
        TestMinio.put(IN_PLACE_BUCKET, key, "about to be forgotten".toByteArray())
        // The row goes between the placement pass and the insert — the same race
        // a delete with forgetBodies runs against a result already in flight.
        transaction {
            ProbeAgents.deleteWhere { ProbeAgents.id eq agentId }
            BodyStores.deleteWhere { BodyStores.id eq doomed }
        }
        BodyStoreRegistry.invalidate(doomed)

        // The agent is gone with its store, so this run carries no assignment at
        // all: the body is simply not the platform's, and the result still lands.
        val resultId = UUID.randomUUID()
        val envelope = Json.parseToJsonElement(
            """
            {
              "resultId": "$resultId", "serviceId": "$serviceId",
              "projectId": "$projectId", "workspaceId": "$workspaceId", "organizationId": "$orgId",
              "startedAt": "$NOW",
              "rawResult": {"outcome": "success", "elapsedMs": 40, "calls": [{
                "request": {"url": "https://api.example.test/", "method": "get"},
                "response": {"status": 200, "responseTimeMs": 30, "bodyPath": "s3://$IN_PLACE_BUCKET/$key"},
                "assertions": []
              }]}
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, ResultPersistenceService.persist(envelope))

        val step = stepOf(resultId)
        assertNull(step[ProbeSteps.responseBodyStorageUrl])
        assertNull(step[ProbeSteps.bodyStoreId])
        assertEquals(BodyNotStoredReason.STORAGE_UNAVAILABLE, step[ProbeSteps.bodyNotStoredReason])
    }
}
