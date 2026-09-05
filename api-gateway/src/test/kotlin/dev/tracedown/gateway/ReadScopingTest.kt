package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.OrgPermissions
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgRulePresets
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.gateway.controllers.presets.RulePresetController
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.controllers.silences.SilenceController
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.util.NotFoundException
import kotlinx.serialization.json.JsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Reads that authorize a parent and then look a row up by id alone.
 *
 * Each case here shares one shape: the caller is fully authorized on something
 * — a service they own, their own org membership — and then hands in an id that
 * belongs to somebody else. The authorization passes; the query has to be what
 * refuses. Every attacker below is an **org owner**, so no permission check can
 * possibly stop them: only the SQL scoping can, which is exactly what these
 * assert.
 *
 * - Response bodies: `probe_steps` carries neither `service_id` nor
 *   `organization_id`, so a step id constrains nothing on its own. Bodies hold
 *   tokens and PII.
 * - Silence targets: the project/service being silenced was resolved by id with
 *   no org term, and its display name came straight back in the response.
 * - Script templates: a workspace-scoped preset was listed on membership alone,
 *   so any member could name a workspace they hold nothing on.
 */
@Testcontainers
class ReadScopingTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_read_scoping_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now()

        /** The caller's own org, owned by [ownerA]. */
        private lateinit var orgA: Org

        /** A stranger's org. Nothing in it may ever surface to [ownerA]. */
        private lateinit var orgB: Org

        /** An active member of [orgA] holding no grant on anything. */
        private var bareMemberA: UUID = UUID.randomUUID()

        /** An active member of [orgA] holding a read grant on its workspace only. */
        private var workspaceMemberA: UUID = UUID.randomUUID()

        private val ownerA: UUID get() = orgA.ownerId

        /**
         * Where the fixture's response bodies live, and what the controller's
         * storage client is confined to.
         *
         * [ProbeResultController] is a singleton whose client is installed by
         * whichever Ktor application started first in this JVM — so without
         * this the class either found no client at all (bodies "resolve" by
         * never being read) or inherited the production confinement root and
         * had every read refused, depending purely on class order.
         */
        private lateinit var bodyRoot: Path

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            Database.connect(HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = "org.postgresql.Driver"
            }))

            // toRealPath: confinement compares against a canonical root, and a
            // temp dir reached through a symlinked /tmp would never match.
            bodyRoot = Files.createTempDirectory("read-scoping-bodies").toRealPath()
            ProbeResultController.init(
                BodyStorageClient(confinement = BodyConfinement(filesystemRoot = bodyRoot)),
            )

            transaction {
                // A clean slate: bootstrap may have seeded a starter org.
                NotificationSilences.deleteAll()
                OrgRulePresets.deleteAll()

                orgA = seedOrg("a")
                orgB = seedOrg("b")
                bareMemberA = insertUser("bare")
                insertMembership(orgA.id, bareMemberA)

                workspaceMemberA = insertUser("ws-member")
                insertMembership(
                    orgA.id,
                    workspaceMemberA,
                    grants = mapOf("workspace::${orgA.workspaceId}" to AccessLevel.READ),
                )
            }
        }

        // ── Fixtures ──

        /** One org with a workspace, project, service, probe result and step. */
        data class Org(
            val id: UUID,
            val ownerId: UUID,
            val workspaceId: UUID,
            val projectId: UUID,
            val serviceId: UUID,
            val resultId: UUID,
            val stepId: UUID,
        )

        private fun seedOrg(tag: String): Org {
            val ownerId = insertUser("owner-$tag")
            val orgId = UUID.randomUUID()
            Organizations.insert {
                it[id] = orgId
                it[name] = "org-$tag"
                it[Organizations.ownerId] = ownerId
                it[createdAt] = NOW
            }
            insertMembership(orgId, ownerId)

            val workspaceId = UUID.randomUUID()
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "workspace-$tag"
                it[createdAt] = NOW
            }

            val projectId = UUID.randomUUID()
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = workspaceId
                it[name] = "project-$tag"
                it[createdAt] = NOW
            }

            val serviceId = UUID.randomUUID()
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = projectId
                it[name] = "service-$tag"
                it[createdAt] = NOW
            }

            val resultId = UUID.randomUUID()
            ProbeResults.insert {
                it[id] = resultId
                it[ProbeResults.serviceId] = serviceId
                it[ProbeResults.projectId] = projectId
                it[ProbeResults.workspaceId] = workspaceId
                it[organizationId] = orgId
                it[startedAt] = NOW
                // probe_results_status_check: success/failure/timeout/error/skipped.
                it[status] = "success"
                it[runDurationMs] = 12
                it[rawResult] = JsonObject(emptyMap())
            }

            val bodyFile = bodyRoot.resolve("$tag.txt")
            Files.writeString(bodyFile, bodyOf(tag))

            val stepId = UUID.randomUUID()
            ProbeSteps.insert {
                it[id] = stepId
                it[probeResultId] = resultId
                it[stepNum] = 1
                it[requestUrl] = "https://$tag.example.test/"
                it[responseBodyStorageUrl] = "file://$bodyFile"
                it[createdAt] = NOW
            }

            return Org(orgId, ownerId, workspaceId, projectId, serviceId, resultId, stepId)
        }

        /** The stored bytes for org [tag]'s one step — never readable across orgs. */
        private fun bodyOf(tag: String): String = "secret-body-of-$tag"

        private fun insertUser(tag: String): UUID {
            val id = UUID.randomUUID()
            Users.insert {
                it[Users.id] = id
                it[email] = "$tag-$id@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = tag
                it[createdAt] = NOW
            }
            return id
        }

        /**
         * An active membership with every org section at NONE. [grants] seeds
         * the resource-grant cache, keyed "type::uuid" the way the permission
         * cache stores it.
         */
        private fun insertMembership(orgId: UUID, userId: UUID, grants: Map<String, Short> = emptyMap()) {
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[status] = "active"
                it[joinedAt] = NOW
                it[inviteToken] = "t-${UUID.randomUUID()}"
                if (grants.isNotEmpty()) {
                    it[permissionCache] = CachedPermissions(
                        org = OrgPermissions(0, 0, 0, 0, 0, 0, 0),
                        resources = grants,
                        totpRequired = false,
                    ).toJsonObject()
                }
            }
        }
    }

    // ── Response bodies (probe_steps has no org or service column) ──

    @Test
    fun `a step body from another org is not readable through your own service`() {
        // The owner of org A, asking about a service they genuinely own, but
        // naming org B's result and step. Authorization cannot refuse this —
        // the service argument is theirs — so the query must.
        assertThrows(NotFoundException::class.java) {
            ProbeResultController.getStepBody(
                orgId = orgA.id,
                serviceId = orgA.serviceId,
                resultId = orgB.resultId,
                stepId = orgB.stepId,
                userId = ownerA,
            )
        }
    }

    @Test
    fun `a step id alone does not reach a body under another result`() {
        // Same org, but the step belongs to a different result than the one
        // named: the pair has to agree, not merely exist.
        assertThrows(NotFoundException::class.java) {
            ProbeResultController.getStepBody(
                orgId = orgA.id,
                serviceId = orgA.serviceId,
                resultId = orgA.resultId,
                stepId = orgB.stepId,
                userId = ownerA,
            )
        }
    }

    @Test
    fun `your own step body still resolves`() {
        // The refusals above have to be the scoping, not a lookup that cannot
        // reach any body at all: this one hands back the bytes.
        val body = ProbeResultController.getStepBody(
            orgId = orgA.id,
            serviceId = orgA.serviceId,
            resultId = orgA.resultId,
            stepId = orgA.stepId,
            userId = ownerA,
        )
        assertNotNull(body)
        assertEquals(BodyStorageClient.BodyContent.Inline(bodyOf("a")), body)
    }

    // ── Silence targets ──

    @Test
    fun `a service in another org cannot be silenced`() {
        assertThrows(NotFoundException::class.java) {
            SilenceController.create(
                orgA.id,
                ownerA,
                CreateSilenceRequest(channel = "email", serviceId = orgB.serviceId.toString()),
            )
        }
    }

    @Test
    fun `a project in another org cannot be silenced`() {
        assertThrows(NotFoundException::class.java) {
            SilenceController.create(
                orgA.id,
                ownerA,
                CreateSilenceRequest(channel = "email", projectId = orgB.projectId.toString()),
            )
        }
    }

    @Test
    fun `a workspace in another org cannot be silenced`() {
        assertThrows(NotFoundException::class.java) {
            SilenceController.create(
                orgA.id,
                ownerA,
                CreateSilenceRequest(channel = "email", workspaceId = orgB.workspaceId.toString()),
            )
        }
    }

    @Test
    fun `silencing your own service returns its name`() {
        val summary = SilenceController.create(
            orgA.id,
            ownerA,
            CreateSilenceRequest(channel = "email", serviceId = orgA.serviceId.toString()),
        )
        assertEquals("service-a", summary.resourceName)
        assertEquals(orgA.serviceId.toString(), summary.serviceId)
    }

    @Test
    fun `a member without a grant cannot silence a service`() {
        assertThrows(NotFoundException::class.java) {
            SilenceController.create(
                orgA.id,
                bareMemberA,
                CreateSilenceRequest(channel = "email", serviceId = orgA.serviceId.toString()),
            )
        }
    }

    @Test
    fun `a workspace grant reaches the services beneath it`() {
        // Downward inheritance: the grant names the workspace, the target is a
        // service two levels below it. This is what the parent chain the guard
        // builds is for — a malformed chain would refuse a legitimate silence.
        val summary = SilenceController.create(
            orgA.id,
            workspaceMemberA,
            CreateSilenceRequest(channel = "email", serviceId = orgA.serviceId.toString()),
        )
        assertEquals("service-a", summary.resourceName)
    }

    @Test
    fun `a workspace grant reaches the projects beneath it`() {
        val summary = SilenceController.create(
            orgA.id,
            workspaceMemberA,
            CreateSilenceRequest(channel = "email", projectId = orgA.projectId.toString()),
        )
        assertEquals("project-a", summary.resourceName)
    }

    @Test
    fun `a scopeless silence needs no target`() {
        val summary = SilenceController.create(
            orgA.id,
            bareMemberA,
            CreateSilenceRequest(channel = "quiet-hours", quietHours = "FREQ=DAILY/60/UTC"),
        )
        assertNull(summary.resourceName)
        assertNull(summary.serviceId)
    }

    // ── Script templates ──

    @Test
    fun `a workspace-scoped template is hidden from a member without a grant`() {
        seedPreset(orgA.id, orgA.workspaceId, "scoped-a")
        seedPreset(orgA.id, null, "org-wide-a")

        val names = RulePresetController.list(orgA.id, bareMemberA, orgA.workspaceId).map { it.name }
        assertTrue(names.contains("org-wide-a"), "org-wide templates stay visible to every member")
        assertFalse(names.contains("scoped-a"), "workspace-scoped templates need a grant on the workspace")
    }

    @Test
    fun `a workspace-scoped template is visible to the owner`() {
        seedPreset(orgA.id, orgA.workspaceId, "scoped-owner")

        val names = RulePresetController.list(orgA.id, ownerA, orgA.workspaceId).map { it.name }
        assertTrue(names.contains("scoped-owner"))
    }

    @Test
    fun `naming another org's workspace yields only the org-wide list`() {
        seedPreset(orgA.id, null, "org-wide-only")
        seedPreset(orgB.id, orgB.workspaceId, "scoped-b")

        val names = RulePresetController.list(orgA.id, ownerA, orgB.workspaceId).map { it.name }
        assertTrue(names.contains("org-wide-only"))
        assertFalse(names.contains("scoped-b"))
    }

    private fun seedPreset(orgId: UUID, workspaceId: UUID?, name: String) {
        transaction {
            OrgRulePresets.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[OrgRulePresets.workspaceId] = workspaceId
                it[displayName] = name
                it[script] = "GET https://example.test/\n"
                it[createdAt] = NOW
            }
        }
    }
}
