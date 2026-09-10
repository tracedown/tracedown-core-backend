package dev.tracedown.scheduler.dispatch

import java.util.UUID

/**
 * A pluggable hold on whether a service's tick may run at all.
 *
 * Core holds nothing back — every scheduled tick runs, so the default provider
 * allows. This is the neutral seam a host application uses to park a service
 * without leaving a trace in its probe history: the hold is the host's own
 * state and the host shows it, so a `skipped` row per tick would be the wrong
 * story twice over — it would claim the platform tried and failed to observe
 * the target, and it would say so again every tick for as long as the hold
 * lasts. Core stays unaware of why a service is held; it only honors the gate.
 *
 * A refusal is temporary by construction: nothing is unscheduled, so the next
 * tick asks again and a lifted hold resumes probing on its own.
 *
 * Called outside any transaction, so a provider may open its own.
 */
object DispatchGate {

    fun interface Provider {
        /** Whether [serviceId] may dispatch this tick. */
        fun allows(serviceId: UUID): Boolean
    }

    /** The default: nothing is held back. */
    private val ALLOW_ALL = Provider { true }

    @Volatile
    var provider: Provider = ALLOW_ALL
        private set

    /** Installs the hold provider (last registration wins). */
    fun register(p: Provider) {
        provider = p
    }
}
