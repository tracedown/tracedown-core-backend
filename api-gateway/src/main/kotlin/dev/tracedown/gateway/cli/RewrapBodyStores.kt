package dev.tracedown.gateway.cli

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.storage.BodyStoreCrypto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.system.exitProcess

/**
 * CLI entry point for `--rewrap-body-stores` — rotation of `BODY_STORE_AES_KEY`,
 * the key a body store's secret access key is encrypted with.
 *
 * Re-encrypts every `body_stores` row from the old key to the new one and moves
 * `updated_at` forward, which is what makes the gateway and the result-ingestor
 * drop their cached clients and rebuild them from the rotated row. Without this
 * a key rotation leaves every S3 store silently unopenable: the credentials are
 * still there, they simply no longer decrypt, and each store reports
 * `secret_undecryptable` one call at a time.
 *
 * Idempotent: a row that already decrypts under the new key is left alone, so
 * the command is safe to re-run after a partial failure. Nothing is written
 * until every row has been read, so a wrong old key changes nothing.
 *
 * The sibling `--rewrap-org-keys` does the same for the platform key's org
 * data-encryption keys; the two keys rotate independently.
 *
 * Usage:
 *   BODY_STORE_AES_KEY=<new key> BODY_STORE_AES_KEY_OLD=<old key> \
 *     java -jar api-gateway.jar --rewrap-body-stores
 */
object RewrapBodyStores {

    /** Parses CLI args and runs the re-wrap. Returns true if handled. */
    fun handle(args: Array<String>): Boolean {
        if (!args.contains("--rewrap-body-stores")) return false
        run()
        return true
    }

    private class Rewrapped(val id: UUID, val enc: String, val iv: String)

    private fun run() {
        val dbUrl = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/tracedown"
        val dbUser = System.getenv("DATABASE_USER") ?: "tracedown"
        val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""

        val newKey = System.getenv("BODY_STORE_AES_KEY") ?: run {
            System.err.println("ERROR: BODY_STORE_AES_KEY (the new key) is not set")
            exitProcess(1)
        }
        val oldKey = System.getenv("BODY_STORE_AES_KEY_OLD") ?: run {
            System.err.println("ERROR: BODY_STORE_AES_KEY_OLD (the key being rotated out) is not set")
            exitProcess(1)
        }

        BodyStoreCrypto.init(newKey)
        val ds = DatabaseFactory.init(dbUrl, dbUser, dbPassword, maximumPoolSize = 2)

        try {
            val pending = mutableListOf<Rewrapped>()
            var alreadyCurrent = 0
            val failed = mutableListOf<UUID>()

            transaction {
                BodyStores.selectAll().forEach { row ->
                    val id = row[BodyStores.id]
                    val enc = row[BodyStores.secretEnc] ?: return@forEach
                    val iv = row[BodyStores.secretIv] ?: return@forEach
                    val context = BodyStoreCrypto.context(id)
                    // Already on the new key? Nothing to do — this is what makes
                    // a re-run after a partial failure safe.
                    if (runCatching { BodyStoreCrypto.decryptBound(enc, iv, context) }.isSuccess) {
                        alreadyCurrent++
                        return@forEach
                    }
                    val plain = runCatching { BodyStoreCrypto.decryptBoundWith(oldKey, enc, iv, context) }.getOrNull()
                    if (plain == null) {
                        failed.add(id)
                        return@forEach
                    }
                    val (newEnc, newIv) = BodyStoreCrypto.encryptBound(plain, context)
                    pending.add(Rewrapped(id, newEnc, newIv))
                }
            }

            if (failed.isNotEmpty()) {
                System.err.println("ERROR: these stores decrypt under neither key (wrong old key?):")
                failed.forEach { System.err.println("  $it") }
                System.err.println("Nothing was written.")
                exitProcess(1)
            }

            // Strictly forward, so every cached client of the old row is
            // rebuilt on its next read — the same rule the API's update follows.
            val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            transaction {
                pending.forEach { r ->
                    BodyStores.update({ BodyStores.id eq r.id }) {
                        it[secretEnc] = r.enc
                        it[secretIv] = r.iv
                        it[updatedAt] = now
                    }
                }
            }

            println()
            println("Body store credentials re-wrapped.")
            println("  Re-wrapped:      ${pending.size}")
            println("  Already current: $alreadyCurrent")
            println()
            println("Set BODY_STORE_AES_KEY to the new key on the api-gateway and the result-ingestor.")
        } finally {
            ds.close()
        }
    }
}
