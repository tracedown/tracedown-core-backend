package dev.tracedown.gateway.cli

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.gateway.controllers.agents.CaService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.system.exitProcess

/**
 * CLI entry point for `--agent-bootstrap <slug>`.
 *
 * Creates a one-time bootstrap token for a probe agent and prints it
 * to stdout.  The operator pastes it into the agent's
 * `PROBE_AGENT_BOOTSTRAP_TOKEN` env var.
 *
 * Usage:
 *   java -jar api-gateway.jar --agent-bootstrap <slug> [--label <label>]
 */
object AgentBootstrap {

    private const val TOKEN_BYTES = 32
    private const val TOKEN_TTL_HOURS = 1L

    /** Parses CLI args and runs the bootstrap. Returns true if handled. */
    fun handle(args: Array<String>): Boolean {
        val idx = args.indexOf("--agent-bootstrap")
        if (idx == -1) return false

        val slug = args.getOrNull(idx + 1)
        if (slug == null || slug.startsWith("--")) {
            System.err.println("Usage: --agent-bootstrap <slug> [--label <label>]")
            exitProcess(1)
        }

        val labelIdx = args.indexOf("--label")
        val label = if (labelIdx != -1) args.getOrNull(labelIdx + 1) ?: slug else slug

        run(slug, label)
        return true
    }

    private fun run(slug: String, label: String) {
        val dbUrl = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/tracedown"
        val dbUser = System.getenv("DATABASE_USER") ?: "tracedown"
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""

        val aesKey = System.getenv("PLATFORM_AES_KEY")
            ?: throw RuntimeException("Missing PLATFORM_AES_KEY environment variable")

        val ds = DatabaseFactory.init(dbUrl, dbUser, dbPassword, maximumPoolSize = 2)
        CaService.init(aesKey)

        try {
            val token = generateToken()
            val tokenHash = BCrypt.withDefaults().hashToString(12, token.toCharArray())

            transaction {
                // Ensure CA root exists (generated on first bootstrap).
                CaService.ensureCaRoot()

                // A fresh token supersedes any outstanding one for the slug
                // (the one-outstanding invariant is index-enforced; the CLI is
                // also re-run on every dev-stack boot).
                AgentBootstrapTokens.deleteWhere {
                    (AgentBootstrapTokens.slug eq slug) and (AgentBootstrapTokens.used eq false)
                }
                AgentBootstrapTokens.insert {
                    it[id] = UUID.randomUUID()
                    it[AgentBootstrapTokens.slug] = slug
                    it[AgentBootstrapTokens.label] = label
                    it[AgentBootstrapTokens.tokenHash] = tokenHash
                    // Indexed locator — enrolment looks the row up by this
                    // digest instead of bcrypting every outstanding token.
                    it[tokenLookup] = TokenHasher.sha256Hex(token)
                    it[expiresAt] = Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS)
                    it[createdAt] = Instant.now()
                }
            }

            println()
            println("Agent bootstrap token created.")
            println("  Slug:    $slug")
            println("  Label:   $label")
            println("  Expires: ${TOKEN_TTL_HOURS}h")
            println()
            println("  Token: $token")
            println()
            println("Set this as PROBE_AGENT_BOOTSTRAP_TOKEN on the agent.")
            // The CLI runs before the application (and its seam) is wired, so it reads
            // the same variable the gateway config does, straight from the environment.
            val enrolAt = dev.tracedown.common.agents.AgentEnrolmentAddress
                .fixed(System.getenv("GATEWAY_PUBLIC_URL")).resolve()
            if (enrolAt != null) {
                println("Set PROBE_AGENT_SCHEDULER_URL to $enrolAt (GATEWAY_PUBLIC_URL).")
            } else {
                println("Set PROBE_AGENT_SCHEDULER_URL to the address the agent reaches this gateway by.")
                println("(Set GATEWAY_PUBLIC_URL on the gateway to have it printed here.)")
            }
            println("This token is single-use and will not be shown again.")
        } finally {
            ds.close()
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
