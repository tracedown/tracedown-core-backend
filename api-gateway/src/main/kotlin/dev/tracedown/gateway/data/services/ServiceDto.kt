package dev.tracedown.gateway.data.services

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import kotlinx.serialization.Serializable

@Serializable
data class CreateServiceRequest(
    val projectId: String,
    val name: String,
    val label: String? = null,
    val schedule: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("projectId", projectId)?.let(::add)
        Validators.uuid("projectId", projectId)?.let(::add)
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.maxLen("label", label, 32)?.let(::add)
        Validators.maxLen("schedule", schedule, 16)?.let(::add)
    }
}

@Serializable
data class UpdateServiceRequest(
    val name: String? = null,
    val label: String? = null,
    val schedule: String? = null,
    val probeMode: String? = null,
    val queuePolicy: String? = null,
    val serviceWindow: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.maxLen("label", label, 32)?.let(::add)
        Validators.maxLen("schedule", schedule, 16)?.let(::add)
        Validators.oneOf("probeMode", probeMode, setOf("consecutive", "simultaneous", "random"))?.let(::add)
        Validators.oneOf("queuePolicy", queuePolicy, setOf("skip", "enqueue_once"))?.let(::add)
        Validators.maxLen("serviceWindow", serviceWindow, 256)?.let(::add)
    }
}

/** Updates the service's Lace script. Validates before saving. */
@Serializable
data class UpdateScriptRequest(
    val script: String,
    val version: Int,
) : Validatable {
    override fun validate() = buildList {
        // script is a text column (no length cap); generous bound guards against abuse.
        Validators.maxLen("script", script, 65536)?.let(::add)
        Validators.inRange("version", version, 1..Int.MAX_VALUE)?.let(::add)
    }
}

/** Enables or disables a service. Enabling requires a valid non-empty script. */
@Serializable
data class ToggleServiceRequest(
    val isActive: Boolean,
)

/**
 * One service a scoped toggle did not act on, and why.
 *
 * A scope is a blunt instrument — it names a project, not the services in it —
 * so some of what it sweeps up will not be actionable. Naming each one is the
 * difference between "34 of 40 enabled" and knowing which six to go and fix.
 */
@Serializable
data class SkippedService(
    val serviceId: String,
    val name: String,
    /** `forbidden`, `script_missing` or `script_invalid`. */
    val reason: String,
)

/** Outcome of enabling or disabling every service in a project or workspace. */
@Serializable
data class ScopedToggleResult(
    /** Services the scope covered, before any were filtered out. */
    val matched: Int,
    /** Services whose `isActive` actually moved. */
    val changed: Int,
    /**
     * Already in the requested state, so left untouched. Counted rather than
     * listed: this is the ordinary case for a re-run, not something to act on.
     */
    val unchanged: Int,
    /** Covered by the scope but not acted on — see [SkippedService.reason]. */
    val skipped: List<SkippedService>,
)

/** Returned when script validation fails. */
@Serializable
data class ScriptValidationError(
    val code: String,
    val callIndex: Int? = null,
    val field: String? = null,
    val detail: String? = null,
)

@Serializable
data class ServiceSummary(
    val id: String,
    val projectId: String,
    val name: String,
    val label: String?,
    val script: String,
    val schedule: String,
    val probeMode: String,
    val queuePolicy: String,
    val serviceWindow: String?,
    val isActive: Boolean,
    val lastStatus: String?,
    val lastStatusSince: String?,
    val version: Int,
    val createdAt: String,
    val metrics: ServiceMetricsDto? = null,
    val lastFailure: LastFailureInfo? = null,
)

@Serializable
data class ProbePoint(
    val status: String,
    val avgResponseMs: Int,
    val callCount: Int,
    val failedCalls: Int,
    val timestamp: Long,
)

@Serializable
data class LastFailureInfo(
    val assertions: List<FailedAssertion>,
)

@Serializable
data class FailedAssertion(
    val scope: String,
    val expected: String?,
    val actual: String?,
)

/** Combined detail + recent probe points, served as one round-trip for the live channel. */
@Serializable
data class ServiceSnapshot(
    val service: ServiceSummary,
    val recentProbes: List<ProbePoint>,
)

@kotlinx.serialization.Serializable
data class SetAllowedAgentsRequest(
    val slugs: List<String>,
) : Validatable {
    override fun validate() = buildList {
        Validators.each(slugs) { s -> Validators.notBlank("slug", s) ?: Validators.maxLen("slug", s, 64) }?.let(::add)
    }
}
