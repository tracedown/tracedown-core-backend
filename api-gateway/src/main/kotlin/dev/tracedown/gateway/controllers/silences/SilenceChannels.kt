package dev.tracedown.gateway.controllers.silences

/**
 * The notification channels a silence row may name.
 *
 * ## Why `webhook` is not one of them
 *
 * A silence is a **personal** control: the row hangs off an `org_user`, and the
 * dispatcher consults it in exactly one place — `RecipientResolver.resolve`,
 * which resolves the human beings who should be emailed about a service. That
 * is the only call site, and it passes `channel = "email"`.
 *
 * Webhooks are not resolved that way and cannot be. A webhook is bound to a
 * *resource* (`resource_webhook_access`), not to a person, and
 * `WebhookDeliveryService` finds its own bindings without ever reading
 * `notification_silences`. There is no per-user webhook to silence, so there is
 * nothing a `channel = "webhook"` row could correctly do:
 *
 * - Honouring it in the delivery path would mean one member's personal setting
 *   silently switching off a delivery the whole organization depends on — the
 *   opposite of what "silences are personal, they never change what anyone else
 *   receives" promises, and undetectable from the binding's own UI.
 * - Leaving it stored and unread — what happened until now — is worse in a
 *   quieter way: the API accepted the row, echoed it back, and listed it, so a
 *   user could set a control, see it in place, and be paged anyway.
 *
 * The documented product agrees: the operator guide's *What silences do not
 * cover* section states that silences and quiet hours apply to email, that
 * webhook deliveries are bound to resources rather than people, and that the way
 * to quieten one is to pause its binding — which affects everyone, deliberately.
 *
 * So the channel is refused at the edge. A user who wants a webhook quiet pauses
 * the binding; a user who wants their own mail quiet silences it here.
 *
 * `all` stays accepted and means all of *this user's* channels, which today is
 * mail. It does not and must not reach webhook deliveries.
 */
object SilenceChannels {

    /** The user's own notification mail. */
    const val EMAIL = "email"

    /** Every channel resolved per user — mail today. */
    const val ALL = "all"

    /**
     * A carrier, not a mute: it matches no dispatch channel, so the row never
     * silences anything — it only holds the user's quietHours window (the
     * dispatcher reads quietHours from any of the user's rows).
     */
    const val QUIET_HOURS = "quiet-hours"

    /** Channels a silence row may be created or updated with. */
    val ACCEPTED = setOf(EMAIL, ALL, QUIET_HOURS)

    /** True when [channel] is a control this platform can actually enforce. */
    fun isAccepted(channel: String): Boolean = channel in ACCEPTED
}
