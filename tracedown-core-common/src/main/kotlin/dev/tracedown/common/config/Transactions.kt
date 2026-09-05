package dev.tracedown.common.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Opens a transaction and runs [block] in it on [Dispatchers.IO].
 *
 * Exposed's `suspendTransaction` takes no `CoroutineContext`: it acquires the
 * connection and runs the — blocking — JDBC calls on whatever dispatcher the
 * caller is already on. Every call site in this repo is a scheduled job, an
 * outbox consumer or a scrape/auth path running on a small fixed pool, so
 * blocking those threads on the database is precisely what [Dispatchers.IO]
 * exists to avoid. The dispatcher switch therefore wraps the transaction here
 * instead of being passed into it, which is what the deprecated
 * `newSuspendedTransaction(Dispatchers.IO)` used to do.
 *
 * @param db a non-default [Database] to run against, as `suspendTransaction`
 *   accepts; `null` uses the connection registered by [DatabaseFactory].
 */
suspend fun <T> ioTransaction(
    db: Database? = null,
    block: suspend JdbcTransaction.() -> T,
): T = withContext(Dispatchers.IO) { suspendTransaction(db = db, statement = block) }
