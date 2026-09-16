package dev.tracedown.ingestor.services

import dev.tracedown.common.alerts.SystemAlertService

/**
 * Which system alert, if any, a `skipped` tick's reason deserves.
 *
 * Not every skip is a fault, and not every fault is the same one. A tick the
 * platform withheld on purpose — an anti-abuse rule, a target that asked not to
 * be probed — is the platform working, and a banner telling the org to reduce
 * its probe frequency would send them somewhere there is nothing to fix. The
 * skipped row already says why.
 */
object SkippedProbeAlert {

    /** Prefix of the reasons the unverified-domain rule writes (spec §18.4). */
    const val UNVERIFIED_PREFIX = "unverified_"

    /** The target publishes the do-not-probe record. */
    const val TARGET_OPTED_OUT = "target_opted_out"

    /** The alert type for [reason], or null when the skip warrants none. */
    fun alertType(reason: String): String? = when {
        // A tick the unverified-domain policy withheld (§18.4).
        reason.startsWith(UNVERIFIED_PREFIX) -> null
        // The target's own operator declined. Nothing is broken, nobody needs
        // paging, and the org's remedy — verify the domain, if the zone is
        // theirs — is not an incident response.
        reason == TARGET_OPTED_OUT -> null
        // A tick that found no executor to run on is a fleet-health problem;
        // telling the org to "reduce probe frequency" would send them the wrong
        // way.
        reason == "no_eligible_agent" -> SystemAlertService.NO_ELIGIBLE_AGENT
        // Agents were there and none of them took the run. Neither a capacity
        // problem nor an empty fleet — telling the org to reduce probe
        // frequency or check allowlists would send them past the actual fault.
        reason == "agent_unreachable" || reason == "agent_rejected" ->
            SystemAlertService.AGENT_DISPATCH_FAILED
        // The scheduler itself faulted (its database was unreachable, its
        // trigger was dropped). Nothing about the fleet or the org's own
        // settings would explain it.
        reason == "dispatch_error" || reason == "trigger_misfired" ->
            SystemAlertService.SCHEDULER_ERROR
        // What is left is the platform being over dispatch capacity.
        else -> SystemAlertService.DISPATCH_CAPACITY
    }
}
