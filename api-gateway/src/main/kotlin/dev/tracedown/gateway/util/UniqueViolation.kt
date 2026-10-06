package dev.tracedown.gateway.util

/**
 * Whether [e] is Postgres refusing a duplicate key (SQLSTATE 23505), anywhere
 * in its causes.
 *
 * A create that checks for a taken name and then inserts does not stop two
 * creates that race it; the unique index does. Callers turn this refusal into
 * the same 409 the check gives, rather than letting it surface as a 500.
 */
fun isUniqueViolation(e: Throwable): Boolean =
    generateSequence(e) { cause -> cause.cause?.takeIf { it !== cause } }
        .any { (it as? java.sql.SQLException)?.sqlState == "23505" }
