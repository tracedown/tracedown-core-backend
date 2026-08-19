package dev.tracedown.gateway.cli

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.OrgService
import dev.tracedown.common.onboarding.DefaultGroupConfig
import dev.tracedown.gateway.util.SeedConfig
import dev.tracedown.common.variables.SystemVariableSeeder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

/**
 * CLI entry point for `--create-org <name>`.
 *
 * Creates an organization with default groups and assigns it to an
 * existing user.  If no `--owner` email is provided, falls back to
 * the demo user (DEMO_USER_EMAIL env var, default admin@tracedown.dev).
 *
 * With `--seed`, also creates a project and an enabled service that
 * pings the local tracedown-testbin (or the configured target URL) on a cron schedule.
 *
 * Usage:
 *   java -jar api-gateway.jar --create-org <name> [--owner <email>] [--seed]
 */
object OrgBootstrap {

    /** Default permission groups created for every new org. */
    // columns: name, users, settings, domains, webhooks, notifications, admin, workspaces
    private val DEFAULT_GROUPS = listOf(
        DefaultGroupConfig("Admins",  2, 2, 2, 2, 2, 2, 2),
        DefaultGroupConfig("Users",   0, 0, 0, 0, 0, 0, 0),
        // settings stays 0: settings read exposes the audit log, agents and
        // org variables — none of which are for read-only members.
        DefaultGroupConfig("Viewers", 1, 0, 1, 1, 1, 0, 1),
        DefaultGroupConfig("DevOps",  2, 2, 2, 2, 2, 0, 2),
    )

    /** Parses CLI args and runs the bootstrap. Returns true if handled. */
    fun handle(args: Array<String>): Boolean {
        val idx = args.indexOf("--create-org")
        if (idx == -1) return false

        val name = args.getOrNull(idx + 1)
        if (name == null || name.startsWith("--")) {
            System.err.println("Usage: --create-org <name> [--owner <email>] [--seed]")
            exitProcess(1)
        }

        val ownerIdx = args.indexOf("--owner")
        val ownerEmail = if (ownerIdx != -1) {
            args.getOrNull(ownerIdx + 1) ?: run {
                System.err.println("Usage: --create-org <name> [--owner <email>] [--seed]")
                exitProcess(1)
            }
        } else {
            System.getenv("DEMO_USER_EMAIL") ?: kotlin.run {
                System.err.println("ERROR: Demo user is not configured")
                exitProcess(1)
            }
        }

        val seed = args.contains("--seed")

        run(name, ownerEmail, seed)
        return true
    }

    private fun run(name: String, ownerEmail: String, seed: Boolean) {
        val dbUrl = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/tracedown"
        val dbUser = System.getenv("DATABASE_USER") ?: "tracedown"
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""

        val seedConfig = loadSeedConfig()

        val ds = DatabaseFactory.init(dbUrl, dbUser, dbPassword, maximumPoolSize = 2)

        // With the platform key available, the new org's data-encryption key is
        // minted at creation; otherwise it is minted lazily on the first secret write.
        System.getenv("PLATFORM_AES_KEY")?.let { dev.tracedown.common.util.VariableCrypto.init(it) }

        try {
            val result = transaction {
                val user = Users.selectAll()
                    .where { Users.email eq ownerEmail }
                    .firstOrNull()

                if (user == null) {
                    System.err.println("ERROR: No user found with email '$ownerEmail'")
                    exitProcess(1)
                }

                OrgService.createOrg(
                    name = name,
                    ownerId = user[Users.id],
                    defaultGroups = DEFAULT_GROUPS,
                )
            }

            println()
            println("Organization created.")
            println("  Name:      $name")
            println("  Owner:     $ownerEmail")
            println("  Org ID:    ${result.orgId}")
            println("  Workspace: ${result.workspaceId}")

            if (seed) {
                val seedResult = transaction {
                    seedData(result.orgId, result.workspaceId, seedConfig)
                }
                println()
                println("Seed data created.")
                println("  Project:   ${seedConfig.projectName} (${seedResult.projectId})")
                println("  Service:   ${seedConfig.serviceName} (${seedResult.serviceId})")
                println("  Target:    ${seedConfig.targetUrl}")
                println("  Schedule:  ${seedConfig.schedule}")
                println("  Status:    enabled")
            }
        } finally {
            ds.close()
        }
    }

    data class SeedResult(
        val projectId: UUID,
        val serviceId: UUID,
    )

    /**
     * Creates a project and an enabled service with a Lace script that
     * GETs the configured target URL and asserts a 200 response.
     */
    fun seedData(orgId: UUID, workspaceId: UUID, config: SeedConfig): SeedResult {
        val now = Instant.now()
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()

        val script = buildLaceScript(config.targetUrl)

        Projects.insert {
            it[id] = projectId
            it[Projects.workspaceId] = workspaceId
            it[Projects.name] = config.projectName
            it[isActive] = true
            it[deleted] = false
            it[createdAt] = now
        }

        Services.insert {
            it[id] = serviceId
            it[Services.projectId] = projectId
            it[Services.name] = config.serviceName
            it[Services.script] = script
            it[schedule] = config.schedule
            it[isActive] = true
            it[deleted] = false
            it[createdAt] = now
        }

        SystemVariableSeeder.seedService(serviceId, now)

        return SeedResult(projectId, serviceId)
    }

    /** Builds a minimal Lace probe script that GETs a URL and asserts status 200. */
    private fun buildLaceScript(targetUrl: String): String {
        return """
            |// Probe: ping $targetUrl
            |get("$targetUrl")
            |.expect(status: 200)
        """.trimMargin()
    }

    /** Loads seed configuration from environment variables with defaults. */
    private fun loadSeedConfig(): SeedConfig {
        return SeedConfig(
            enabled = false, // CLI uses --seed flag, not this field
            projectName = System.getenv("SEED_PROJECT_NAME") ?: "Default",
            serviceName = System.getenv("SEED_SERVICE_NAME") ?: "testbin",
            targetUrl = System.getenv("SEED_TARGET_URL") ?: "http://tracedown-testbin:20780/get",
            schedule = System.getenv("SEED_SCHEDULE") ?: "*/5 * * * *",
        )
    }
}
