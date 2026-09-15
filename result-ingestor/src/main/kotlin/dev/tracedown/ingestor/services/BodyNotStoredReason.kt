package dev.tracedown.ingestor.services

import dev.tracedown.common.models.BodyNotStoredReasons
import dev.tracedown.common.storage.BodyStoreService

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
     * outside the part of that store the agent writes to — nothing is recorded
     * rather than trusting the path.
     */
    const val OUTSIDE_ASSIGNED_STORE = "outsideAssignedStore"

    /**
     * The agent's store belongs to another organization than the result does. It
     * is never used: a store holds one organization's bodies and no other's, and
     * an assignment that crossed that line is a misconfiguration to fix, not a
     * body to file away in the wrong place.
     */
    const val STORE_ORG_MISMATCH = "storeOrgMismatch"

    /** The store the body lived in was removed with its bodies (`forgetBodies`). */
    const val STORE_REMOVED = BodyStoreService.REASON_STORE_REMOVED

    /**
     * The body outlived the body-retention window while its result did not
     * reach the result window — written by the aggregate-worker's retention
     * body pass, never by ingestion.
     */
    const val BODY_EXPIRED = BodyNotStoredReasons.BODY_EXPIRED

    /**
     * [reported] is the executor's `bodyNotCapturedReason` (spec §9), [withheld]
     * the scheduler's own reason when it overrode the service's setting,
     * [relocationFailed] whether a captured body could not be taken into
     * server-owned storage, [outsideAssignedStore] whether the agent's store
     * refused the location it reported, and [storeOrgMismatch] whether the
     * agent's store belongs to another organization.
     */
    fun resolve(
        reported: String?,
        withheld: String?,
        relocationFailed: Boolean,
        outsideAssignedStore: Boolean = false,
        storeOrgMismatch: Boolean = false,
    ): String? = when {
        reported == NOT_REQUESTED && !withheld.isNullOrBlank() -> withheld
        reported != null -> reported
        storeOrgMismatch -> STORE_ORG_MISMATCH
        outsideAssignedStore -> OUTSIDE_ASSIGNED_STORE
        relocationFailed -> STORAGE_UNAVAILABLE
        else -> null
    }
}
