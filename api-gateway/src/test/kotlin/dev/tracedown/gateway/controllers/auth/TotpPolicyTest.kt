package dev.tracedown.gateway.controllers.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The two properties that make a TOTP code an actual second factor.
 *
 * The defects these cover:
 *
 * 1. The failed-attempt counter lived on the pending session row, and a pending
 *    session is minted by every `POST /auth/login`. An attacker holding the
 *    password therefore got five guesses, then simply logged in again for five
 *    more — a limit that resets on demand is not a limit. Counting on the
 *    account is what makes the ceiling real.
 * 2. A code that had already been accepted could be presented again while its
 *    window stood. `verifyCode` only asked "does this match the current or
 *    previous step", and `totp_last_used_at` was written but never read, so
 *    nothing at all enforced single use.
 */
class TotpPolicyTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    // ---- replay: a spent code stays spent ----

    @Test
    fun `a code is accepted once and never again`() {
        val step = 1_800_000L
        assertTrue(TotpPolicy.consumes(step, null), "nothing consumed yet — first code is fresh")
        assertFalse(TotpPolicy.consumes(step, step), "the very same step is a replay")
    }

    @Test
    fun `the drift-tolerance step behind a spent one is also spent`() {
        // The verifier accepts the current step AND the one before it. Without
        // strictly-newer, an attacker who saw the code for step N could present
        // the step N-1 code in the same window and be let straight through.
        val spent = 1_800_000L
        assertFalse(TotpPolicy.consumes(spent - 1, spent))
        assertFalse(TotpPolicy.consumes(spent - 5, spent))
    }

    @Test
    fun `the next window's code is accepted`() {
        val spent = 1_800_000L
        assertTrue(TotpPolicy.consumes(spent + 1, spent))
    }

    // ---- lockout: counted on the account, time-boxed ----

    @Test
    fun `the account locks on the fifth consecutive failure, not before`() {
        for (priorFailures in 0 until TotpPolicy.MAX_ATTEMPTS - 1) {
            val f = TotpPolicy.afterFailure(priorFailures, t0)
            assertEquals(priorFailures + 1, f.attempts)
            assertNull(f.lockedUntil, "must not lock at ${f.attempts} attempts")
        }

        val tripping = TotpPolicy.afterFailure(TotpPolicy.MAX_ATTEMPTS - 1, t0)
        assertEquals(TotpPolicy.MAX_ATTEMPTS, tripping.attempts)
        assertNotNull(tripping.lockedUntil)
        assertEquals(t0.plus(TotpPolicy.LOCKOUT), tripping.lockedUntil)
    }

    @Test
    fun `attempts against a locked account push the lock out rather than letting it lapse`() {
        val later = t0.plusSeconds(60)
        val f = TotpPolicy.afterFailure(TotpPolicy.MAX_ATTEMPTS + 3, later)
        assertEquals(TotpPolicy.MAX_ATTEMPTS + 4, f.attempts)
        assertEquals(later.plus(TotpPolicy.LOCKOUT), f.lockedUntil)
    }

    @Test
    fun `a lock refuses codes until it passes, then stops refusing`() {
        val until = t0.plus(TotpPolicy.LOCKOUT)
        assertTrue(TotpPolicy.isLocked(until, t0))
        assertTrue(TotpPolicy.isLocked(until, until.minusSeconds(1)))
        assertFalse(TotpPolicy.isLocked(until, until), "expires at the instant it names")
        assertFalse(TotpPolicy.isLocked(until, until.plusSeconds(1)))
    }

    @Test
    fun `an account that has never locked is not locked`() {
        assertFalse(TotpPolicy.isLocked(null, t0))
    }

    @Test
    fun `the lock is time-boxed so guessing cannot deny the owner their account`() {
        // A permanent lock would hand anyone who reaches the login form a way to
        // freeze an account until an operator intervenes.
        assertFalse(TotpPolicy.LOCKOUT.isZero)
        assertFalse(TotpPolicy.LOCKOUT.isNegative)
        assertTrue(TotpPolicy.isLocked(t0.plus(TotpPolicy.LOCKOUT), t0))
        assertFalse(TotpPolicy.isLocked(t0.plus(TotpPolicy.LOCKOUT), t0.plus(TotpPolicy.LOCKOUT)))
    }
}
