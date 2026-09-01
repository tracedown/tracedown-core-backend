package dev.tracedown.common.realtime

import java.util.UUID

/**
 * A generic seam for allowing or refusing a realtime connection at the moment
 * the socket opens — after the session has authenticated, before any data flows.
 *
 * Core registers nothing and the default decision is [Decision.Allow], so a
 * standalone Core streams to every authenticated, org-scoped session exactly as
 * it always has. A host that needs an additional open-time gate registers one
 * [authorize] function; Core calls it and honours the verdict without knowing
 * anything about what the gate decides on. This is the same pattern as
 * [dev.tracedown.common.errors.ErrorReporter]: a no-op observer/decider that a
 * host may fill in, never a Core branch on any particular host.
 *
 * Keep the seam vocabulary-free: it carries only the connection's identity and a
 * yes/no with an optional opaque reason. Whatever a host's policy is called is
 * the host's business and must not leak into these names.
 */
object ConnectionAuthorizer {

    /** The identity of the connection being opened. */
    data class ConnectionContext(
        val userId: UUID,
        val sessionId: UUID,
        val orgId: UUID,
    )

    /** An authorizer's verdict. A denial may carry a short, opaque reason for logs. */
    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String? = null) : Decision
    }

    @Volatile
    private var authorizer: ((ConnectionContext) -> Decision)? = null

    /** Registers the open-time authorizer. A later registration replaces the prior one. */
    fun register(authorizer: (ConnectionContext) -> Decision) {
        this.authorizer = authorizer
    }

    /**
     * The open-time decision for [context]. Returns [Decision.Allow] when no
     * authorizer is registered (standalone Core). A registered authorizer that
     * throws is treated as allow and swallowed: a buggy host gate must not take
     * Core's realtime plane down for every connection. A host that means to
     * refuse a connection returns [Decision.Deny] rather than throwing.
     */
    fun authorize(context: ConnectionContext): Decision =
        try {
            authorizer?.invoke(context) ?: Decision.Allow
        } catch (e: Exception) {
            Decision.Allow
        }

    /** Removes any registered authorizer. Intended for testing only. */
    fun clear() {
        authorizer = null
    }
}
