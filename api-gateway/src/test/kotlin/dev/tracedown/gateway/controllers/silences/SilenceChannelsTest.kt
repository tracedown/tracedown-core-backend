package dev.tracedown.gateway.controllers.silences

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A silence must be a control that does something. These pin the one rule that
 * makes that true: only channels the dispatcher actually consults are storable.
 *
 * The regression: `webhook` used to be accepted, persisted, echoed back and
 * listed, while nothing on the delivery side ever read it —
 * `WebhookDeliveryService` resolves its own bindings and never touches
 * `notification_silences`. A user could mute a webhook, see the mute in place,
 * and keep being paged.
 */
class SilenceChannelsTest {

    @Test
    fun `email is a channel the dispatcher resolves per user`() {
        assertTrue(SilenceChannels.isAccepted(SilenceChannels.EMAIL))
    }

    @Test
    fun `all is accepted and means every per-user channel`() {
        assertTrue(SilenceChannels.isAccepted(SilenceChannels.ALL))
    }

    @Test
    fun `the quiet-hours carrier is accepted`() {
        assertTrue(SilenceChannels.isAccepted(SilenceChannels.QUIET_HOURS))
    }

    @Test
    fun `webhook is refused because nothing enforces it`() {
        assertFalse(
            SilenceChannels.isAccepted("webhook"),
            "webhooks are bound to resources, not people — pause the binding instead",
        )
    }

    @Test
    fun `unknown channels are refused`() {
        assertFalse(SilenceChannels.isAccepted("sms"))
        assertFalse(SilenceChannels.isAccepted(""))
        assertFalse(SilenceChannels.isAccepted("Email"), "matching is exact, not case-folded")
    }

    @Test
    fun `the accepted set is exactly these three`() {
        assertTrue(
            SilenceChannels.ACCEPTED == setOf(
                SilenceChannels.EMAIL,
                SilenceChannels.ALL,
                SilenceChannels.QUIET_HOURS,
            ),
        )
    }
}
