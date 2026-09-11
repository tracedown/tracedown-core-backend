package dev.tracedown.ingestor.services

/**
 * What `probe_steps.body_not_stored_reason` records for a step without a
 * stored body.
 *
 * The executor only knows whether it was asked to save bodies, so a body the
 * scheduler withheld (an unverified target, spec §18.4) comes back as the same
 * `notRequested` as a service that has body saving switched off. The scheduler
 * says in the envelope when the decision was its own (`bodiesWithheld`), and
 * that is the reason worth showing: "body saving is off" sends the operator to
 * a setting that is already on.
 */
object BodyNotStoredReason {

    const val NOT_REQUESTED = "notRequested"
    const val STORAGE_UNAVAILABLE = "storageUnavailable"

    /**
     * The agent writes to an `in_place` body store, but reported a body location
     * outside that store — nothing is recorded rather than trusting the path.
     */
    const val OUTSIDE_ASSIGNED_STORE = "outsideAssignedStore"

    /**
     * [reported] is the executor's `bodyNotCapturedReason` (spec §9), [withheld]
     * the scheduler's own reason when it overrode the service's setting, and
     * [relocationFailed] whether a captured body could not be taken into
     * server-owned storage, and [outsideAssignedStore] whether the agent's
     * `in_place` store refused the location it reported.
     */
    fun resolve(
        reported: String?,
        withheld: String?,
        relocationFailed: Boolean,
        outsideAssignedStore: Boolean = false,
    ): String? = when {
        reported == NOT_REQUESTED && !withheld.isNullOrBlank() -> withheld
        reported != null -> reported
        outsideAssignedStore -> OUTSIDE_ASSIGNED_STORE
        relocationFailed -> STORAGE_UNAVAILABLE
        else -> null
    }
}
