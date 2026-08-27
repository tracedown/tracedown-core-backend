package dev.tracedown.gateway.controllers.auth

import java.time.Duration
import java.time.Instant

/**
 * The two decisions that make a TOTP code an actual second factor, kept as pure
 * functions so they can be reasoned about (and tested) without a database.
 *
 * ## Replay
 *
 * A TOTP code is single-use by definition, but nothing enforced that: the
 * verifier accepted any code matching the current or previous 30-second step,
 * and `totp_last_used_at` was written on success and never read. A code
 * observed once — shoulder-surfed, phished into a proxy, read out of a support
 * chat — stayed good for the rest of its window on every endpoint that takes a
 * second factor. [consumes] settles it: acceptance requires a STRICTLY newer
 * time step than the one the account last consumed, so using a code burns it
 * and every earlier one with it.
 *
 * ## Lockout
 *
 * The attempt counter lived on the pending session row, and a pending session
 * is created by `POST /auth/login`. An attacker holding the password therefore
 * got five guesses per login and could start a new login at will — five
 * guesses, unlimited times, which is not a limit at all. The counter belongs to
 * the account being guessed. [afterFailure] counts it there and, at
 * [MAX_ATTEMPTS], returns a lock instant; [isLocked] refuses codes until it
 * passes.
 *
 * The lock is time-boxed rather than permanent on purpose. A permanent lock
 * turns a guessing attempt into a denial of service against the account —
 * whoever can reach the login form can freeze the owner out until an operator
 * intervenes. [LOCKOUT] is long enough that guessing a six-digit code is
 * hopeless (5 attempts per 15 minutes is ~1 in 200,000 per hour against a
 * 1,000,000-code space) and short enough that a legitimate owner who fumbled
 * their codes waits rather than files a ticket.
 */
object TotpPolicy {

    /** Consecutive failed second factors an account may accrue before it locks. */
    const val MAX_ATTEMPTS = 5

    /** How long the account refuses second factors once [MAX_ATTEMPTS] is reached. */
    val LOCKOUT: Duration = Duration.ofMinutes(15)

    /** True while [lockedUntil] is still in the future — second factors are refused. */
    fun isLocked(lockedUntil: Instant?, now: Instant): Boolean =
        lockedUntil != null && lockedUntil.isAfter(now)

    /**
     * Whether presenting [step] consumes it, given the last step this account
     * consumed. Strictly-newer, so replaying an accepted code fails and so does
     * reaching back to the drift-tolerance step behind it.
     *
     * [lastConsumedStep] null means nothing has been consumed yet — the account
     * has never completed a TOTP verification, so any valid step is fresh.
     */
    fun consumes(step: Long, lastConsumedStep: Long?): Boolean =
        lastConsumedStep == null || step > lastConsumedStep

    /**
     * The account's counter state after a failed attempt: the new consecutive
     * count, and the instant it is locked until (null while below the limit).
     *
     * Counting continues past the limit rather than pinning at it, so a run of
     * attempts against a locked account keeps pushing the lock out instead of
     * letting it lapse while the guessing is still going.
     */
    fun afterFailure(attempts: Int, now: Instant): Failure {
        val next = attempts + 1
        return Failure(next, if (next >= MAX_ATTEMPTS) now.plus(LOCKOUT) else null)
    }

    /** Result of [afterFailure]: the stored counter and lock instant to write. */
    data class Failure(val attempts: Int, val lockedUntil: Instant?)
}
