package dev.tracedown.common.variables

/**
 * How many variables one resource may hold.
 *
 * Variables are cheap to create and are read on every probe dispatch, so an
 * unbounded set is both a storage and a hot-path cost — and nothing in the
 * product needs thousands on a single service. The cap guards against runaway
 * or automated creation: it is the same number for every organization, set by
 * the operator (`MAX_VARS_PER_RESOURCE`).
 *
 * "Per resource" means per org, per workspace, per project, per service and per
 * webhook, counted separately — a project with the cap does not stop its
 * services having their own.
 *
 * System-managed variables are outside it: seeded defaults and the companion
 * variables a config toggle creates are ours, not the user's, and refusing them
 * would break a feature the user already turned on.
 */
object VariableLimits {

    /** Used until [init] runs — a generous ceiling that still bounds abuse. */
    const val DEFAULT_MAX_PER_RESOURCE = 100

    @Volatile
    private var maxPerResource: Int = DEFAULT_MAX_PER_RESOURCE

    /** Wires the configured cap. Values below 1 are ignored as misconfiguration. */
    fun init(max: Int) {
        if (max >= 1) maxPerResource = max
    }

    fun max(): Int = maxPerResource

    /**
     * True when a resource already holding [current] variables has no room for
     * another. Callers pass the count of live (non-deleted) variables for that
     * one resource.
     */
    fun isFull(current: Long): Boolean = current >= maxPerResource
}
