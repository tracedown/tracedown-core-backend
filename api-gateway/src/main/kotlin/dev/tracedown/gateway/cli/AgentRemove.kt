package dev.tracedown.gateway.cli

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ServiceAllowedAgents
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * CLI entry point for `--remove-agent`.
 *
 * Lists all active probe agents in an indexed table and prompts the
 * operator to select one for removal.  Removal deactivates the agent,
 * revokes its certificates, and removes service-agent bindings.
 *
 * Usage:
 *   java -jar api-gateway.jar --remove-agent
 */
object AgentRemove {

    private val TIMESTAMP_FMT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.UTC)

    /** Parses CLI args and runs the removal flow. Returns true if handled. */
    fun handle(args: Array<String>): Boolean {
        if (!args.contains("--remove-agent")) return false
        run()
        return true
    }

    private data class AgentRow(
        val id: Long,
        val slug: String,
        val label: String,
        val lastStatus: String,
        val lastPing: Instant,
    )

    private fun run() {
        val dbUrl = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/tracedown"
        val dbUser = System.getenv("DATABASE_USER") ?: "tracedown"
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""

        val ds = DatabaseFactory.init(dbUrl, dbUser, dbPassword, maximumPoolSize = 2)

        try {
            val agents = transaction {
                ProbeAgents.selectAll()
                    .where { ProbeAgents.isActive eq true }
                    .orderBy(ProbeAgents.slug, SortOrder.ASC)
                    .map { row ->
                        AgentRow(
                            id = row[ProbeAgents.id],
                            slug = row[ProbeAgents.slug],
                            label = row[ProbeAgents.label],
                            lastStatus = row[ProbeAgents.lastStatus],
                            lastPing = row[ProbeAgents.lastPing],
                        )
                    }
            }

            if (agents.isEmpty()) {
                println("No active agents found.")
                return
            }

            println()
            println("Active agents:")
            println()

            val idxW = agents.size.toString().length
            val slugW = maxOf("Slug".length, agents.maxOf { it.slug.length })
            val labelW = maxOf("Label".length, agents.maxOf { it.label.length })
            val statusW = maxOf("Status".length, agents.maxOf { it.lastStatus.length })

            println(
                "  ${"#".padStart(idxW)}  " +
                    "${"Slug".padEnd(slugW)}  " +
                    "${"Label".padEnd(labelW)}  " +
                    "${"Status".padEnd(statusW)}  " +
                    "Last health check"
            )

            agents.forEachIndexed { i, a ->
                println(
                    "  ${(i + 1).toString().padStart(idxW)}  " +
                        "${a.slug.padEnd(slugW)}  " +
                        "${a.label.padEnd(labelW)}  " +
                        "${a.lastStatus.padEnd(statusW)}  " +
                        "${TIMESTAMP_FMT.format(a.lastPing)} UTC"
                )
            }

            println()
            print("Enter the number of the agent to remove (or 'q' to cancel): ")
            System.out.flush()

            val input = readlnOrNull()?.trim()
            if (input == null || input.equals("q", ignoreCase = true)) {
                println("Cancelled.")
                return
            }

            val index = input.toIntOrNull()
            if (index == null || index < 1 || index > agents.size) {
                System.err.println("Invalid selection.")
                exitProcess(1)
            }

            val target = agents[index - 1]

            transaction {
                // Deactivate the agent.
                ProbeAgents.update({ ProbeAgents.id eq target.id }) {
                    it[isActive] = false
                }

                // Revoke all active certificates.
                val now = Instant.now()
                AgentCertificates.update({
                    (AgentCertificates.probeAgentId eq target.id) and
                        (AgentCertificates.revoked eq false)
                }) {
                    it[revoked] = true
                    it[revokedAt] = now
                    it[revokedReason] = "Agent removed via CLI"
                }

                // Remove service-agent bindings.
                ServiceAllowedAgents.deleteWhere {
                    ServiceAllowedAgents.probeAgentId eq target.id
                }
            }

            println()
            println("Agent removed.")
            println("  Slug:   ${target.slug}")
            println("  Label:  ${target.label}")
            println("  Certificates revoked, service bindings cleared.")
        } finally {
            ds.close()
        }
    }
}
