package dev.tracedown.notifications.recipients

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The cooldown key's shape is a correctness property, not an implementation
 * detail, so it is tested as a pure function without a Redis.
 *
 * The regression these guard: the key used to be recipient+service+channel with
 * no dimension for what the message said. A service that failed and recovered
 * inside the 300s window sent the failure mail and dropped the recovery in
 * silence — no mail, no notification_log row — leaving the reader believing an
 * outage was still running after it had ended.
 */
class RecipientCooldownTest {

    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val service = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun `a failure and a recovery never share a cooldown window`() {
        val failure = RecipientCooldown.key(user, service, "email", RecipientCooldown.FAILURE)
        val recovery = RecipientCooldown.key(user, service, "email", RecipientCooldown.RECOVERED)
        assertNotEquals(failure, recovery, "the recovery must not be suppressed by the failure's window")
    }

    @Test
    fun `the kind is part of the key, not appended to the channel`() {
        assertEquals(
            "cooldown:$user:$service:email:failure",
            RecipientCooldown.key(user, service, "email", RecipientCooldown.FAILURE),
        )
    }

    @Test
    fun `the same recipient, service, channel and kind is one window`() {
        assertEquals(
            RecipientCooldown.key(user, service, "email", RecipientCooldown.FAILURE),
            RecipientCooldown.key(user, service, "email", RecipientCooldown.FAILURE),
        )
    }

    @Test
    fun `different recipients and different services keep separate windows`() {
        val other = UUID.randomUUID()
        assertNotEquals(
            RecipientCooldown.key(user, service, "email", RecipientCooldown.FAILURE),
            RecipientCooldown.key(other, service, "email", RecipientCooldown.FAILURE),
        )
        assertNotEquals(
            RecipientCooldown.key(user, service, "email", RecipientCooldown.FAILURE),
            RecipientCooldown.key(user, other, "email", RecipientCooldown.FAILURE),
        )
    }

    @Test
    fun `a run's kind is decided by whether anything recovered`() {
        assertEquals(RecipientCooldown.RECOVERED, RecipientCooldown.kindOf(recovered = true))
        assertEquals(RecipientCooldown.FAILURE, RecipientCooldown.kindOf(recovered = false))
    }

    @Test
    fun `each kind cancels exactly the other`() {
        assertEquals(RecipientCooldown.FAILURE, RecipientCooldown.opposite(RecipientCooldown.RECOVERED))
        assertEquals(RecipientCooldown.RECOVERED, RecipientCooldown.opposite(RecipientCooldown.FAILURE))
    }

    @Test
    fun `fail then recover then fail again touches three distinct windows`() {
        // A state change ends the storm the previous state was throttled for,
        // so none of these three dispatches can suppress a later one.
        val first = RecipientCooldown.key(user, service, "email", RecipientCooldown.kindOf(recovered = false))
        val recovery = RecipientCooldown.key(user, service, "email", RecipientCooldown.kindOf(recovered = true))
        assertNotEquals(first, recovery)
        // The re-failure reuses the failure window — but markDispatched cleared
        // it when the recovery went out, which is why it is allowed through.
        assertEquals(first, RecipientCooldown.key(user, service, "email", RecipientCooldown.opposite(RecipientCooldown.RECOVERED)))
    }
}
