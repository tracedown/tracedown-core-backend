package dev.tracedown.gateway.util

/**
 * Who may read a variable's plaintext back out of the database.
 *
 * Variables come in three kinds sharing one storage shape:
 *
 * - **secret** — encrypted, and never returned in plaintext to anyone through
 *   the API. It is write-only material: you can replace it, you cannot read it.
 * - **variable** — encrypted at rest, and revealable, because the whole point is
 *   that a human can check what value a probe will actually send.
 * - **metric** — stored plain; there is nothing to reveal.
 *
 * The defect this exists to state plainly: reveal was gated on READ. Every one
 * of the five reveal endpoints (org, webhook, workspace, project, service)
 * checked read access on its scope and then decrypted unconditionally. So the
 * encryption bought nothing against the account that most obviously should not
 * have it — a read-only member, an integration issued a read-only key, a
 * viewer-grade guest — all of whom could walk the variable list and decrypt
 * every entry. Encrypting a value and then handing the plaintext to anyone who
 * can list it is a stored-at-rest measure with no access-control half.
 *
 * Reveal is now a WRITE-level operation. The rule is that seeing a configured
 * credential in the clear is the same privilege as being able to change it: if
 * you may set the token a probe authenticates with, reading it back tells you
 * nothing you could not have overwritten anyway, and if you may not, it is not
 * yours to read. That also matches how the value is masked everywhere else —
 * lists, the inherited-variable hierarchy — where it has always come back as
 * `••••••` regardless of grant.
 */
object VariableRevealPolicy {

    /** What a reveal request is entitled to. */
    enum class Decision {
        /** Decrypt and return the value. */
        REVEAL,

        /** The variable is a secret: refused for everyone, at every grant. */
        REFUSED_SECRET,

        /** The caller may see that the variable exists, but not its value. */
        REFUSED_READ_ONLY,
    }

    /**
     * Decides a reveal, given whether the variable is a secret and whether the
     * caller holds write access on the scope that owns it.
     *
     * Secret-ness is checked FIRST, deliberately: the answer for a secret is
     * the same for every caller, so a writer and a reader get the identical
     * refusal and neither one's grant is disclosed by the difference.
     */
    fun decide(secret: Boolean, callerCanWrite: Boolean): Decision = when {
        secret -> Decision.REFUSED_SECRET
        !callerCanWrite -> Decision.REFUSED_READ_ONLY
        else -> Decision.REVEAL
    }
}
