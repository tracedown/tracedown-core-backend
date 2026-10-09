package dev.tracedown.gateway.data.services

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

@Serializable
data class CreateServiceRequest(
    val projectId: String,
    @JsonSchema.MaxLength(128)
    val name: String,
    @JsonSchema.MaxLength(32)
    val label: String? = null,
    @JsonSchema.MaxLength(16)
    @JsonSchema.Description("A five-field cron expression; `*/5 * * * *` when left out.")
    val schedule: String? = null,
    val saveResponseBodies: Boolean? = null,
    /**
     * The service's Lace script. Validated as a script save is; saving one
     * switches the service on, as the first script save does, unless
     * [isActive] says otherwise.
     */
    @JsonSchema.MaxLength(65536)
    val script: String? = null,
    /** Whether the service starts switched on. True needs a [script] or a [presetId]. */
    val isActive: Boolean? = null,
    /**
     * A script preset whose script the service starts with, instead of a
     * [script] — copied, as the editor copies it: a later change to the
     * preset does not reach the service. Not both.
     */
    val presetId: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("script", script, 65536)?.let(::add)
        Validators.uuid("presetId", presetId)?.let(::add)
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
    @JsonSchema.MaxLength(128)
    val name: String? = null,
    @JsonSchema.MaxLength(32)
    val label: String? = null,
    @JsonSchema.MaxLength(16)
    @JsonSchema.Description("A five-field cron expression.")
    val schedule: String? = null,
    @JsonSchema.Description("`consecutive`, `simultaneous` or `random`: how the service's agents take turns.")
    val probeMode: String? = null,
    @JsonSchema.Description("`skip` or `enqueue_once`: what a run does while the previous one is still going.")
    val queuePolicy: String? = null,
    @JsonSchema.MaxLength(256)
    @JsonSchema.Description("A maintenance window, `RRULE/minutes/Zone`: an RFC 5545 rule, 1–1440 minutes, an IANA zone. Empty clears it.")
    val serviceWindow: String? = null,
    val saveResponseBodies: Boolean? = null,
    /**
     * The service's Lace script, when this save changes it. Config and script
     * travel together so an editor's save is one transaction — see
     * `ServiceController.update`. Requires [version].
     */
    val script: String? = null,
    /**
     * The version the editor loaded, if it wants the save version-checked; the
     * save is refused with `version_conflict` when the service has moved on.
     *
     * Optional so a non-interactive caller changing one field need not read the
     * service first, but any save carrying a [script] must supply it: a script
     * is the thing two people edit at once, and an unchecked script write is how
     * one of them silently loses their work.
     */
    val version: Int? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.maxLen("label", label, 32)?.let(::add)
        Validators.maxLen("schedule", schedule, 16)?.let(::add)
        Validators.oneOf("probeMode", probeMode, setOf("consecutive", "simultaneous", "random"))?.let(::add)
        Validators.oneOf("queuePolicy", queuePolicy, setOf("skip", "enqueue_once"))?.let(::add)
        Validators.maxLen("serviceWindow", serviceWindow, 256)?.let(::add)
        // script is a text column (no length cap); generous bound guards against abuse.
        Validators.maxLen("script", script, 65536)?.let(::add)
        version?.let { Validators.inRange("version", it, 1..Int.MAX_VALUE)?.let(::add) }
    }
}

/**
 * Updates the service's Lace script. Validates before saving.
 *
 * A narrower spelling of [UpdateServiceRequest] with the script and version
 * required — it keeps the script-only endpoint honest, and delegates.
 */
@Serializable
data class UpdateScriptRequest(
    @JsonSchema.MaxLength(65536)
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
 * A run that was asked for: [requestedAt] is when, as an ISO-8601 instant, and
 * [runId] the handle to follow it by — the id its result will be filed under.
 */
@Serializable
data class RunRequested(
    val ok: Boolean = true,
    val requestedAt: String,
    val runId: String,
)

/**
 * A script to judge as a save would, without saving it: against the service
 * named by [serviceId] — its variables, its schedule — when given, and against
 * none otherwise.
 */
@Serializable
data class ValidateScriptRequest(
    @JsonSchema.MaxLength(65536)
    val script: String,
    @JsonSchema.Description("The service whose variables and schedule to judge the script with. Read access to it suffices.")
    val serviceId: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("script", script, 65536)?.let(::add)
        serviceId?.let { Validators.uuid("serviceId", it)?.let(::add) }
    }
}

/**
 * What a save of a script would make of it, as far as its caller can judge.
 *
 * [valid] is true when nothing this caller can judge refuses it — exactly a
 * save's verdict when [complete] is true too. [errors] lists every reason it
 * would not: the Lace validator's findings (`code`, `callIndex`,
 * `field`, `detail`), then the platform's — `blocked_probe_target` for each
 * call whose target this installation does not probe, and the
 * unverified-domain rules (`unverified_domain_includes`,
 * `unverified_domain_call_limit`, `unverified_domain_interval`) where they
 * apply. [targets] and [limits] say what those were judged from.
 */
@Serializable
data class ScriptValidation(
    val valid: Boolean,
    val errors: List<ScriptValidationError>,
    val targets: ScriptTargets,
    val limits: ScriptLimits,
    @JsonSchema.Description(
        "Whether the verified-domain rules were judged: false when the installation does not ask for verified " +
            "domains, or when the caller may not read the organization's domains. When " +
            "false, `targets.unverified` is empty and `limits` carries no domain limits.",
    )
    val domainsChecked: Boolean = false,
    @JsonSchema.Description(
        "Whether everything a save would judge was judged: false when a call's host is in `targets.unresolved`, when " +
            "the verified-domain rules apply and were not checked, or when they apply and no `serviceId` was given " +
            "(there is no schedule to judge the interval rule against). `valid` with `complete` is a save's verdict.",
    )
    val complete: Boolean = false,
)

/**
 * The calls a script makes, as judged. Every target is named as the script
 * writes it, never with a variable's value in it.
 */
@Serializable
data class ScriptTargets(
    /** Calls whose target this installation does not probe, with the reason (`target_*`). */
    val blocked: List<BlockedTarget>,
    /**
     * Hosts no verified domain of the organization covers — or the call as
     * written, where its host comes from a variable that is not set or may not
     * be shown. Empty when the installation does not ask for verified domains.
     */
    val unverified: List<String>,
    /**
     * Calls whose host is built from a variable with no value here: none at
     * all without `serviceId`, and none that had to be decrypted for a caller
     * without write on the service. Neither policy judges them.
     */
    val unresolved: List<String>,
)

@Serializable
data class BlockedTarget(
    val source: String,
    val reason: String,
)

/**
 * The limits a script is held to. [maxCalls] and [minIntervalMinutes] are set
 * when the unverified-domain limits apply to it — some target is not on a
 * verified domain — and null when they do not.
 */
@Serializable
data class ScriptLimits(
    val callCount: Int,
    val maxCalls: Int? = null,
    val minIntervalMinutes: Int? = null,
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
    @JsonSchema.Enum("forbidden", "script_missing", "script_invalid")
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
    /**
     * Covered by the scope but not acted on — see [SkippedService.reason].
     *
     * A SAMPLE, capped at [SKIPPED_DETAIL_LIMIT]. Nothing bounds how many
     * services a scope holds — Core gates none of it — so a workspace of five
     * thousand scriptless services would otherwise put five thousand rows in
     * this response and five thousand nodes in the dialog that renders it.
     * [skippedTotal] carries the real figure.
     */
    val skipped: List<SkippedService>,
    /**
     * How many were skipped in total. Equals `skipped.size` until the sample is
     * capped, after which it keeps counting — so the caller can say "and 4,950
     * more" instead of implying the list is complete.
     */
    val skippedTotal: Int,
    /**
     * Skip count per reason, over ALL skips rather than the capped sample.
     *
     * This is what stays useful as the scope grows: fifty names out of five
     * thousand tells the reader almost nothing, while "4,950 have no script
     * yet, 2 you cannot edit" tells them exactly what to go and do.
     */
    val skippedByReason: Map<String, Int>,
)

/**
 * How many skipped services a scoped toggle names individually.
 *
 * Enough to act on — a handful of broken scripts is the case worth listing by
 * name — without letting the response scale with the size of the scope. Past
 * this, the count is the useful information, not another thousand names.
 */
const val SKIPPED_DETAIL_LIMIT = 50

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
    @JsonSchema.Description("A five-field cron expression.")
    val schedule: String,
    @JsonSchema.Enum("consecutive", "simultaneous", "random")
    val probeMode: String,
    @JsonSchema.Enum("skip", "enqueue_once")
    val queuePolicy: String,
    @JsonSchema.Description("A maintenance window, `RRULE/minutes/Zone`; null when there is none.")
    val serviceWindow: String?,
    /** When false, runs are dispatched with body saving off — no stored body to inspect. */
    val saveResponseBodies: Boolean,
    /**
     * Target hosts no verified domain of the organization covers (raw URL when
     * the host cannot be resolved, or is built from an encrypted variable —
     * a value this read may not reveal). Non-empty means the unverified-domain rule
     * applies to this service: bodies are never saved whatever
     * [saveResponseBodies] says, so the client locks that setting and says why.
     * Filled on the single-service read only; empty on list rows and in
     * trusted-domain mode.
     */
    val unverifiedTargets: List<String> = emptyList(),
    val isActive: Boolean,
    @JsonSchema.Description("`success`, `failure`, `timeout`, `skipped` or `error`; null before the first run.")
    val lastStatus: String?,
    val lastStatusSince: String?,
    val version: Int,
    val createdAt: String,
    val metrics: ServiceMetricsDto? = null,
    val lastFailure: LastFailureInfo? = null,
)

@Serializable
data class ProbePoint(
    @JsonSchema.Enum("success", "failure", "timeout", "skipped", "error")
    val status: String,
    val avgResponseMs: Int,
    val callCount: Int,
    val failedCalls: Int,
    /** When the run started, in epoch seconds. */
    @JsonSchema.Format("int64")
    @JsonSchema.Description("When the run started, in epoch seconds.")
    val timestamp: Long,
)

@Serializable
data class LastFailureInfo(
    val assertions: List<FailedAssertion>,
)

@Serializable
data class FailedAssertion(
    @JsonSchema.Description("The asserted scope (`status`, `body`, ...), or `assert` for an `.assert()` condition.")
    val scope: String,
    @JsonSchema.Description("The expected value of a scope assertion; null for a condition.")
    val expected: String?,
    @JsonSchema.Description("What the target answered; for a condition, its resolved left operand.")
    val actual: String?,
    @JsonSchema.Description("An `.assert()` condition rendered back to source; null for a scope assertion.")
    val expression: String? = null,
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
