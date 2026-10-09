package dev.tracedown.gateway.routes.publicapi

import dev.tracedown.common.pfs.Page
import dev.tracedown.common.runs.RunTrigger
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.VariableHierarchyResponse
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.util.EventPollSlots
import dev.tracedown.gateway.controllers.alerts.SystemAlertController
import dev.tracedown.gateway.controllers.events.EventFeedController
import dev.tracedown.gateway.controllers.events.EventTypes
import dev.tracedown.gateway.data.agents.PublicAgentSummary
import dev.tracedown.gateway.data.alerts.PublicSystemAlert
import dev.tracedown.gateway.data.events.EventPage
import dev.tracedown.gateway.data.notifications.CreateNotificationTemplateRequest
import dev.tracedown.gateway.data.notifications.NotificationTemplateSummary
import dev.tracedown.gateway.data.notifications.UpdateNotificationTemplateRequest
import dev.tracedown.gateway.data.presets.CreateRulePresetRequest
import dev.tracedown.gateway.data.presets.RulePresetSummary
import dev.tracedown.gateway.data.presets.UpdateRulePresetRequest
import dev.tracedown.gateway.data.apikeys.ApiKeyInfo
import dev.tracedown.gateway.data.metrics.AssertionFailuresDto
import dev.tracedown.gateway.data.metrics.EndpointSeriesDto
import dev.tracedown.gateway.data.metrics.FailureHeatmapDto
import dev.tracedown.gateway.data.metrics.HourlyBucket
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import dev.tracedown.gateway.data.metrics.ServiceStatisticsDto
import dev.tracedown.gateway.data.orgs.PublicGroupSummary
import dev.tracedown.gateway.data.orgs.PublicMemberSummary
import dev.tracedown.gateway.data.orgs.ResourceAccessEntry
import dev.tracedown.gateway.data.orgs.UpsertAccessRequest
import dev.tracedown.gateway.data.publicapi.PublicApiError
import dev.tracedown.gateway.data.projects.CreateProjectRequest
import dev.tracedown.gateway.data.projects.ProjectSummary
import dev.tracedown.gateway.data.projects.UpdateProjectRequest
import dev.tracedown.gateway.data.results.ProbeResultDetail
import dev.tracedown.gateway.data.results.ProbeResultSummary
import dev.tracedown.gateway.data.results.RunStatus
import dev.tracedown.gateway.data.results.StepBodyContent
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.services.RunRequested
import dev.tracedown.gateway.data.services.ScopedToggleResult
import dev.tracedown.gateway.data.services.ScriptValidation
import dev.tracedown.gateway.data.services.ServiceSnapshot
import dev.tracedown.gateway.data.services.ServiceSummary
import dev.tracedown.gateway.data.services.SetAllowedAgentsRequest
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.services.UpdateScriptRequest
import dev.tracedown.gateway.data.services.UpdateServiceRequest
import dev.tracedown.gateway.data.services.ValidateScriptRequest
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.data.silences.SilenceSummary
import dev.tracedown.gateway.data.silences.UpdateSilenceRequest
import dev.tracedown.gateway.data.webhooks.PublicWebhookSummary
import dev.tracedown.gateway.data.webhooks.WebhookBindingRequest
import dev.tracedown.gateway.data.webhooks.WebhookBindingSummary
import dev.tracedown.gateway.data.workspaces.CreateWorkspaceRequest
import dev.tracedown.gateway.data.workspaces.UpdateWorkspaceRequest
import dev.tracedown.gateway.data.workspaces.WorkspaceSummary
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** A query parameter an operation reads. */
data class QueryParameter(
    val name: String,
    val type: KType,
    val description: String,
    val required: Boolean = false,
    /** The values it takes, when it is held to a set — from the constant the handler checks against. */
    val values: List<String>? = null,
)

/**
 * One endpoint of the key-authenticated API as its description gives it: the
 * route (relative to [PublicApi.V1]), what it is called, what it reads and
 * what it answers.
 *
 * [request] and [response] are the classes the handler receives and responds
 * with; the description's schemas and the contract test's pinned types are
 * both taken from them, so the two cannot list different things.
 */
data class PublicOperation(
    val method: HttpMethod,
    val path: String,
    val operationId: String,
    val tag: String,
    val summary: String,
    val description: String? = null,
    val query: List<QueryParameter> = emptyList(),
    val request: KType? = null,
    val response: KType? = null,
    val status: HttpStatusCode = HttpStatusCode.OK,
    /** Set when the operation also answers 204, with what that means. */
    val noContent: String? = null,
    /**
     * Error statuses beyond the ones every operation can answer (400, 401,
     * 403, 429), 404 on a path with an id and 413 on one with a body.
     */
    val errors: List<HttpStatusCode> = emptyList(),
    /**
     * Whether the operation takes an `Idempotency-Key` (see `Idempotency`):
     * every POST, except one that changes nothing and so has nothing to
     * repeat.
     */
    val idempotent: Boolean = method == HttpMethod.Post,
    /** Answers the stored bytes (`application/octet-stream`, as an attachment) rather than JSON. */
    val binary: Boolean = false,
) {
    val key: String get() = "${method.value} $path"

    /** Every error status this operation can answer. */
    val errorStatuses: List<HttpStatusCode>
        get() = (
            COMMON_ERRORS +
                (if ('{' in path) listOf(HttpStatusCode.NotFound) else emptyList()) +
                // A request body past the gateway's size limit: 413 `request_body_too_large`.
                (if (request != null) listOf(HttpStatusCode.PayloadTooLarge) else emptyList()) +
                (if (idempotent) IDEMPOTENCY_ERRORS else emptyList()) +
                errors
            ).distinct().sortedBy { it.value }

    private companion object {
        val COMMON_ERRORS = listOf(
            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests,
        )

        /** `idempotency_in_progress`, `idempotency_key_reused`, `idempotency_unavailable`. */
        val IDEMPOTENCY_ERRORS = listOf(
            HttpStatusCode.Conflict, HttpStatusCode.UnprocessableEntity, HttpStatusCode.ServiceUnavailable,
        )
    }
}

/**
 * The endpoints of [PublicApi.V1] this module provides — one entry per route.
 *
 * Adding an endpoint, in order:
 *  1. the handler, in its area's file under `routes/publicapi/v1/`, calling
 *     the controller function its dashboard twin calls;
 *  2. its entry here (a route without one stops the gateway from starting);
 *  3. a case in `ApiKeyResourcesTest` (a route without one fails it), and the
 *     operation id in `BODILESS_WRITES` there if it is a write without a body;
 *  4. its line appended to `src/test/resources/public-api-v1.routes.txt`,
 *     and any new types' shapes to `public-api-v1.types.txt` — appended, never
 *     edited: those files are the v1 baseline;
 *  5. a note for hosts: a host that pins the public route list (as the
 *     baseline does) will fail on the new route until it looks.
 *
 * A route that gains an optional query parameter appends a line of its own,
 * with the new parameters, beside the one it already has; a type that gains a
 * field appends its whole new block. Earlier lines and blocks stay: each is
 * what v1 promised at the time.
 */
object PublicApiOperations {

    /** Every reason a run asked for can be skipped with, by what to do about it. */
    private const val RUN_SKIP_REASONS =
        "Skip reasons, by what to do. Ask again later: `run_already_running`, `run_already_queued` (a run of the " +
            "service was already under way or waiting; its result is under another id, in `/services/{id}/results`), " +
            "`run_in_service_window`, `dispatch_queue_full`; wait at least 5 minutes or verify the domain: " +
            "`unverified_throttle`. Fix the service or its configuration: `run_service_inactive`, `run_script_missing`, " +
            "`target_*` (an address this installation does not probe, or a target that asked not to be), the other " +
            "`unverified_*` (the verified-domain limits). The platform, for its operator: `run_held` (held until an " +
            "operator clears it — waiting does not), `variable_unreadable`, `run_not_delivered` (no scheduler was " +
            "listening; nothing will run it), `no_eligible_agent`, `agent_unreachable`, `agent_rejected`, " +
            "`dispatch_error`. New reasons can appear: treat an unknown one as a platform problem."

    private val OK = typeOf<Map<String, Boolean>>()

    private val PAGE = QueryParameter("page", typeOf<Int>(), "The page to return, from 1. Default 1.")
    private val PAGE_SIZE = QueryParameter("pageSize", typeOf<Int>(), "Items per page, 1–100. Default 50.")
    private val PAGING = listOf(PAGE, PAGE_SIZE)

    private fun required(name: String, description: String) = QueryParameter(name, typeOf<String>(), description, required = true)

    private val HOURS = QueryParameter("hours", typeOf<Int>(), "How many hours back, 1–168. Default 24.")
    private val WINDOW = QueryParameter("window", typeOf<String>(), "The window: `24h` (default), `7d`, `30d` or `90d`.")
    private val DAYS = QueryParameter("days", typeOf<Int>(), "How many days back, 1–365. Default 90.")
    private val BOUND_RESOURCE = listOf(
        required("resourceType", "`workspace`, `project` or `service`."),
        required("resourceId", "The id of that workspace, project or service."),
    )

    private val get = HttpMethod.Get
    private val post = HttpMethod.Post
    private val patch = HttpMethod.Patch
    private val put = HttpMethod.Put
    private val delete = HttpMethod.Delete

    private val CONFLICT = listOf(HttpStatusCode.Conflict)


    private fun variables(scope: String, prefix: String, tagHierarchy: Boolean): List<PublicOperation> {
        val name = scope.replaceFirstChar { it.uppercase() }
        val a = if (scope.first() in "aeiou") "an" else "a"
        return listOfNotNull(
            PublicOperation(get, "$prefix/variables", "list${name}Variables", "Variables",
                "Lists the $scope's variables",
                "Encrypted values come back masked (`masked: true`) — secrets, and values of the default `variable` " +
                    "type, which is stored encrypted; `metric` values are plain. There is no way to read a masked value back.",
                query = PAGING, response = typeOf<Page<VariableSummary>>()),
            if (tagHierarchy) PublicOperation(get, "$prefix/variables/hierarchy", "get${name}VariableHierarchy", "Variables",
                "Lists every variable the $scope sees", "Its own and every scope above it, each scope with the variables locked there.",
                response = typeOf<VariableHierarchyResponse>()) else null,
            PublicOperation(post, "$prefix/variables", "create${name}Variable", "Variables",
                "Creates $a $scope variable",
                "`type`: `variable` (default; stored encrypted, listed masked), `secret` (never shown again) or `metric` (plain). " +
                    "`key` at most 64 characters, `value` at most 4096.",
                request = typeOf<CreateVariableRequest>(), response = typeOf<VariableSummary>(), errors = CONFLICT),
            PublicOperation(patch, "$prefix/variables/{varId}", "update${name}Variable", "Variables",
                "Changes $a $scope variable's value",
                request = typeOf<UpdateVariableRequest>(), response = typeOf<VariableSummary>()),
            PublicOperation(delete, "$prefix/variables/{varId}", "delete${name}Variable", "Variables",
                "Deletes $a $scope variable", response = OK),
        )
    }

    val all: List<PublicOperation> = listOf(
        PublicOperation(get, "/key", "describeKey", "Key", "Describes the calling key",
            "Its name, prefix, access, expiry, and the organization and user it acts for.",
            response = typeOf<ApiKeyInfo>()),

        // Workspaces
        PublicOperation(get, "/workspaces", "listWorkspaces", "Workspaces", "Lists workspaces",
            "The workspaces the caller may see, oldest first.", query = PAGING, response = typeOf<Page<WorkspaceSummary>>()),
        PublicOperation(post, "/workspaces", "createWorkspace", "Workspaces", "Creates a workspace",
            request = typeOf<CreateWorkspaceRequest>(), response = typeOf<WorkspaceSummary>(), errors = CONFLICT),
        PublicOperation(get, "/workspaces/{id}", "getWorkspace", "Workspaces", "Returns a workspace", response = typeOf<WorkspaceSummary>()),
        PublicOperation(patch, "/workspaces/{id}", "updateWorkspace", "Workspaces", "Renames a workspace",
            request = typeOf<UpdateWorkspaceRequest>(), response = typeOf<WorkspaceSummary>()),
        PublicOperation(delete, "/workspaces/{id}", "deleteWorkspace", "Workspaces", "Deletes a workspace",
            "With its projects and their services.", response = OK),
        PublicOperation(patch, "/workspaces/{id}/services-toggle", "toggleWorkspaceServices", "Workspaces",
            "Switches every service in a workspace on or off",
            "In one transaction. Reports what moved, what was already there, and what was skipped and why.",
            request = typeOf<ToggleServiceRequest>(), response = typeOf<ScopedToggleResult>()),

        // Projects
        PublicOperation(get, "/projects", "listProjects", "Projects", "Lists a workspace's projects",
            "The projects of the workspace the caller may see, oldest first.",
            query = listOf(required("workspaceId", "The workspace whose projects to list.")) + PAGING,
            response = typeOf<Page<ProjectSummary>>(), errors = listOf(HttpStatusCode.NotFound)),
        PublicOperation(post, "/projects", "createProject", "Projects", "Creates a project",
            "In the workspace named by `workspaceId`.",
            request = typeOf<CreateProjectRequest>(), response = typeOf<ProjectSummary>(),
            errors = listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict)),
        PublicOperation(get, "/projects/{id}", "getProject", "Projects", "Returns a project", response = typeOf<ProjectSummary>()),
        PublicOperation(patch, "/projects/{id}", "updateProject", "Projects", "Renames a project",
            request = typeOf<UpdateProjectRequest>(), response = typeOf<ProjectSummary>()),
        PublicOperation(delete, "/projects/{id}", "deleteProject", "Projects", "Deletes a project", "With its services.", response = OK),
        PublicOperation(patch, "/projects/{id}/services-toggle", "toggleProjectServices", "Projects",
            "Switches every service in a project on or off",
            "In one transaction. Reports what moved, what was already there, and what was skipped and why.",
            request = typeOf<ToggleServiceRequest>(), response = typeOf<ScopedToggleResult>()),

        // Services
        PublicOperation(get, "/services", "listServices", "Services", "Lists a project's services",
            "The services of the project the caller may see, oldest first.",
            query = listOf(required("projectId", "The project whose services to list.")) + PAGING,
            response = typeOf<Page<ServiceSummary>>(), errors = listOf(HttpStatusCode.NotFound)),
        PublicOperation(post, "/services", "createService", "Services", "Creates a service",
            "In the project named by `projectId`, in one transaction. `schedule` is a five-field cron expression " +
                "(default `*/5 * * * *`). A `script`, when given, is validated and saved as a script save is, and " +
                "switches the service on unless `isActive` is false. While the organization works in verified-domain " +
                "mode, a script may only call hosts on a verified domain with a few limits relaxed; see the guide " +
                "(https://tracedown.dev/guide/api/). `presetId` instead of `script` starts it from a preset's script, " +
                "copied: a preset the caller may read, and a workspace's only for a service in that workspace (404 " +
                "naming `presetId` otherwise).",
            request = typeOf<CreateServiceRequest>(), response = typeOf<ServiceSummary>(),
            errors = listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict)),
        PublicOperation(get, "/services/{id}", "getService", "Services", "Returns a service", response = typeOf<ServiceSummary>()),
        PublicOperation(patch, "/services/{id}", "updateService", "Services", "Changes a service's configuration",
            "Fields left out are unchanged. A script change needs the `version` it replaces. `serviceWindow` is a " +
                "maintenance window: `RRULE/minutes/Zone` (an RFC 5545 recurrence rule, a duration of 1–1440 minutes, " +
                "an IANA zone), during which runs are not dispatched.",
            request = typeOf<UpdateServiceRequest>(), response = typeOf<ServiceSummary>(), errors = CONFLICT),
        PublicOperation(delete, "/services/{id}", "deleteService", "Services", "Deletes a service", response = OK),
        PublicOperation(patch, "/services/{id}/script", "updateServiceScript", "Services", "Replaces a service's Lace script",
            "Validated before it is saved; `version` must be the version being replaced (409 `version_conflict` otherwise).",
            request = typeOf<UpdateScriptRequest>(), response = typeOf<ServiceSummary>(), errors = CONFLICT),
        PublicOperation(get, "/services/{id}/snapshot", "getServiceSnapshot", "Services",
            "Returns a service with its most recent runs", "`recentProbes[].timestamp` is in epoch seconds.",
            response = typeOf<ServiceSnapshot>()),
        PublicOperation(patch, "/services/{id}/toggle", "toggleService", "Services", "Switches a service on or off",
            "Switching on needs a valid script.", request = typeOf<ToggleServiceRequest>(), response = typeOf<ServiceSummary>()),
        PublicOperation(post, "/services/{id}/run", "runService", "Services", "Runs a service now",
            "Outside its schedule. Answers 202 once the request is recorded, with `runId` and `requestedAt` (whole " +
                "seconds, UTC). Follow the run at `GET /services/{id}/runs/{runId}` (see there for what it can answer). " +
                "409 `script_missing` or `service_inactive` when it cannot run. Under an `Idempotency-Key`, a repeat " +
                "answers with the same `runId` and runs nothing; after a run that ended `skipped` or `expired`, asking " +
                "again needs a new key. " + RUN_SKIP_REASONS,
            response = typeOf<RunRequested>(), status = HttpStatusCode.Accepted, errors = CONFLICT),
        PublicOperation(get, "/services/{id}/runs/{runId}", "getServiceRun", "Services", "Returns where a run asked for stands",
            "`state`: `pending` until the run is recorded; `done`, with `result`; `skipped`, with `result` (when " +
                "there is one) and the `reason` it was not made; `expired` — no result within the gateway's bound " +
                "(twice the scheduler's run lock and a margin: 10 minutes unless the probe timeout or the operator set " +
                "another). An expired run may still settle: a result that arrives later is filed under the id and the " +
                "state follows it. A service that runs on several agents at once makes one result per agent: " +
                "`results` lists them all (an agent that did not run it as a skipped result), `status` is the worst of them " +
                "(failure, then timeout, error, skipped, success), and the run is `done` once every one is in, or once the bound has passed. Poll every few " +
                "seconds. 404 for an id that is not a run of this service. " + RUN_SKIP_REASONS,
            response = typeOf<RunStatus>()),
        PublicOperation(get, "/services/{id}/agents", "listServiceAgents", "Services", "Lists the agents a service may run on",
            "By slug; an empty list means any agent.", response = typeOf<List<String>>()),
        PublicOperation(put, "/services/{id}/agents", "setServiceAgents", "Services", "Sets the agents a service may run on",
            "An empty list means any agent. Slugs are listed by `GET /agents`; an unknown one is refused with " +
                "`details.unknown`.", request = typeOf<SetAllowedAgentsRequest>(), response = typeOf<List<String>>()),

        // Agents
        PublicOperation(get, "/agents", "listAgents", "Agents", "Lists the agents a service can be set to run on",
            "By slug, ordered by slug: the names `PUT /services/{id}/agents` takes, with each agent's health as the " +
                "dashboard shows it — `status` (`healthy`, `degraded`, `down` or `unknown`) and `lastCheckAt`, when " +
                "its health was last checked.",
            response = typeOf<List<PublicAgentSummary>>()),

        // Results
        PublicOperation(get, "/services/{id}/results", "listServiceResults", "Results", "Lists a service's runs",
            "Most recent first (`order=asc` for oldest first), ties by id in the same direction. Run times are kept to " +
                "the second; `since` and `until` are floored to the second before they are compared, and both are " +
                "inclusive. A refused parameter is named in `details.field`. `order=asc` with `since` is not a cursor " +
                "for following new runs: a run is filed when it is ingested, not when it started, so one can appear " +
                "behind the last one seen — overlap `since` by a few minutes and skip the ids already seen. " +
                "`trigger=manual` finds runs asked for from version 0.4.59 on; earlier ones, and ones made while " +
                "gateways and schedulers of both versions ran side by side, read as `schedule`.",
            query = listOf(
                QueryParameter("since", typeOf<String>(), "An ISO-8601 instant: only runs started at or after it (to the second)."),
                QueryParameter("until", typeOf<String>(), "An ISO-8601 instant: only runs started at or before it (to the second). Not before `since`."),
                QueryParameter("status", typeOf<List<String>>(),
                    "Only runs with one of these statuses. Repeat the parameter (`status=failure&status=timeout`), or " +
                        "give them comma-separated in one (`status=failure,timeout`).",
                    values = ProbeResultController.STATUSES),
                QueryParameter("trigger", typeOf<String>(), "Only runs started by `schedule`, or asked for (`manual`).",
                    values = RunTrigger.TRIGGERS.toList()),
                QueryParameter("order", typeOf<String>(), "`desc` (default, most recent first) or `asc`.",
                    values = ProbeResultController.ORDERS),
            ) + PAGING,
            response = typeOf<Page<ProbeResultSummary>>()),
        PublicOperation(get, "/services/{id}/results/{resultId}", "getServiceResult", "Results", "Returns a run with all of its steps",
            "`rawResult` is the run's ProbeResult as the Lace specification defines it; `calls[].response.bodyPath` is " +
                "always null here — a stored body is reached through `steps[].hasBody` and `getStepBody`.",
            response = typeOf<ProbeResultDetail>()),
        PublicOperation(get, "/services/{id}/results/{resultId}/steps/{stepId}/body", "getStepBody", "Results",
            "Returns a step's stored response body",
            "As text in the response: `content`, `contentType` (null when not known) and `encoding` (`base64` when the " +
                "bytes are not UTF-8 text or the text carries control characters, null otherwise). Bodies over 4 MiB are " +
                "refused with 413 `body_too_large` (`details.maxBytes`); `getStepBodyRaw` serves those. A client that " +
                "takes nothing of the answer for 20 seconds, or has not taken all of it after 10 minutes, is cut off. 410 " +
                "`body_gone` when the step recorded a body that is no longer there; 204 when it stored none " +
                "(`hasBody: false`). 503 `body_store_unavailable` (with `Retry-After`) is worth retrying with backoff; " +
                "a read can take up to about 10 seconds to be refused that way.",
            response = typeOf<StepBodyContent>(), noContent = "The step stored no body.",
            errors = listOf(HttpStatusCode.Gone, HttpStatusCode.PayloadTooLarge, HttpStatusCode.ServiceUnavailable)),
        PublicOperation(get, "/services/{id}/results/{resultId}/steps/{stepId}/body/raw", "getStepBodyRaw", "Results",
            "Downloads a step's stored response body",
            "The bytes as they were stored, not JSON: `Content-Type` is the stored type when the gateway repeats it " +
                "(`application/octet-stream` otherwise), with `Content-Length`, `Content-Disposition: attachment` and " +
                "`X-Content-Type-Options: nosniff`. Up to the store's own limit, 32 MiB (413 `body_too_large`, " +
                "`details.maxBytes`); never a link to where it is kept. HEAD answers the headers alone, from a size " +
                "lookup. A client that takes nothing for 20 seconds, or has not taken the whole body after 10 minutes, is " +
                "cut off. Otherwise as " +
                "`getStepBody`: 204 when no body was stored, 410 `body_gone`, 503 `body_store_unavailable` with " +
                "`Retry-After`. Errors are JSON.",
            noContent = "The step stored no body.", binary = true,
            errors = listOf(HttpStatusCode.Gone, HttpStatusCode.PayloadTooLarge, HttpStatusCode.ServiceUnavailable)),

        // Scripts
        PublicOperation(post, "/scripts/validate", "validateScript", "Scripts", "Judges a script as a save would",
            "Without saving anything: `valid` is true when nothing this caller can judge would refuse a save of it (see `complete`). `errors` lists " +
                "every reason it would not — the Lace validator's (`code`, `callIndex`, `field`, `detail`), then " +
                "`blocked_probe_target` per call whose target this installation does not probe, and the " +
                "unverified-domain rules where they apply. With `serviceId`, judged with that service's variables " +
                "and schedule, as a save of its script; read access to it is enough (404 otherwise). It judges only " +
                "with what the caller may know: values that are stored encrypted are used only for a caller with " +
                "write on the service, and for anyone else a call whose host needs one is listed in " +
                "`targets.unresolved` and judged by neither policy. Without `serviceId` there are no variables (calls " +
                "whose host comes from one are unresolved). Verified-domain coverage is judged only for a caller who " +
                "may read the organization's domains — `domainsChecked` says whether it was — and only over calls whose " +
                "host is known; without `serviceId` there is no schedule, so the interval rule is not judged and " +
                "`complete` is false wherever verified domains are asked for. So `valid` means nothing this caller can " +
                "judge refuses the script; with `complete` " +
                "true as well it is a save's verdict. Targets are always named as the script writes them. It changes nothing, so a read-only key may call it, and it takes no " +
                "`Idempotency-Key`.",
            request = typeOf<ValidateScriptRequest>(), response = typeOf<ScriptValidation>(), idempotent = false,
            errors = listOf(HttpStatusCode.NotFound)),

        // Metrics
        PublicOperation(get, "/services/{id}/metrics", "getServiceMetrics", "Metrics", "Returns a service's current counters and state",
            "`state.lastRunAt` is in epoch seconds.",
            response = typeOf<ServiceMetricsDto>(), noContent = "The service has no metrics yet."),
        PublicOperation(get, "/services/{id}/metrics/history", "getServiceMetricsHistory", "Metrics", "Returns a service's hourly buckets",
            query = listOf(HOURS), response = typeOf<List<HourlyBucket>>()),
        PublicOperation(get, "/services/{id}/metrics/statistics", "getServiceStatistics", "Metrics",
            "Returns uptime, error rate, latency trend and per-endpoint breakdown of a service",
            query = listOf(WINDOW), response = typeOf<ServiceStatisticsDto>()),
        PublicOperation(get, "/services/{id}/metrics/statistics/endpoint-series", "getServiceEndpointSeries", "Metrics",
            "Returns the statistics window per endpoint over time", query = listOf(WINDOW), response = typeOf<EndpointSeriesDto>()),
        PublicOperation(get, "/services/{id}/metrics/statistics/assertions", "getServiceAssertionFailures", "Metrics",
            "Returns the window's most-failing assertions", "Says how far back it actually read (`since`, `truncated`).",
            query = listOf(WINDOW), response = typeOf<AssertionFailuresDto>()),
        PublicOperation(get, "/services/{id}/metrics/statistics/failure-heatmap", "getServiceFailureHeatmap", "Metrics",
            "Returns failed runs by UTC hour of day and ISO weekday", query = listOf(DAYS), response = typeOf<FailureHeatmapDto>()),
        PublicOperation(get, "/projects/{id}/metrics", "getProjectMetrics", "Metrics",
            "Returns current counters across a project's services", "Over the services the caller may see.",
            response = typeOf<ServiceMetricsDto>(), noContent = "None of those services has metrics yet."),
        PublicOperation(get, "/projects/{id}/metrics/history", "getProjectMetricsHistory", "Metrics",
            "Returns hourly buckets across a project's services", query = listOf(HOURS), response = typeOf<List<HourlyBucket>>()),
        PublicOperation(get, "/workspaces/{id}/metrics", "getWorkspaceMetrics", "Metrics",
            "Returns current counters across a workspace's services", "Over the projects the caller may see.",
            response = typeOf<ServiceMetricsDto>(), noContent = "None of those services has metrics yet."),
        PublicOperation(get, "/workspaces/{id}/metrics/history", "getWorkspaceMetricsHistory", "Metrics",
            "Returns hourly buckets across a workspace's services", query = listOf(HOURS), response = typeOf<List<HourlyBucket>>()),

        // Silences
        PublicOperation(get, "/silences", "listSilences", "Silences", "Lists the caller's notification silences",
            query = PAGING, response = typeOf<Page<SilenceSummary>>()),
        PublicOperation(post, "/silences", "createSilence", "Silences", "Creates a silence for the caller",
            "A silence is the acting user's own, not an organization-wide mute. `channel`: `email`, `all` or " +
                "`quiet-hours`; scope it with at most one of `workspaceId`, `projectId`, `serviceId`. `config` is a JSON " +
                "object as text; `quietHours` is `RRULE/minutes/Zone`, as a service's maintenance window.",
            request = typeOf<CreateSilenceRequest>(), response = typeOf<SilenceSummary>(), errors = listOf(HttpStatusCode.NotFound)),
        PublicOperation(get, "/silences/{id}", "getSilence", "Silences", "Returns one of the caller's silences", response = typeOf<SilenceSummary>()),
        PublicOperation(patch, "/silences/{id}", "updateSilence", "Silences", "Changes a silence",
            request = typeOf<UpdateSilenceRequest>(), response = typeOf<SilenceSummary>()),
        PublicOperation(delete, "/silences/{id}", "deleteSilence", "Silences", "Removes a silence", response = OK),

        // Access
        PublicOperation(get, "/access/{resourceType}/{resourceId}", "listResourceAccess", "Access",
            "Lists who may see or change a resource",
            "`resourceType` is `workspace`, `project` or `service`. Needs write on it. Groups first, then users, each by " +
                "name, ties by id.",
            response = typeOf<List<ResourceAccessEntry>>()),
        PublicOperation(put, "/access/{resourceType}/{resourceId}", "grantResourceAccess", "Access",
            "Grants a user or group access to a resource", "Or changes the level of a grant it has: 1 read, 2 write.",
            request = typeOf<UpsertAccessRequest>(), response = OK),
        PublicOperation(delete, "/access/{resourceType}/{resourceId}/{principalType}/{principalId}", "revokeResourceAccess", "Access",
            "Removes a user's or group's grant on a resource", response = OK),

        // Directory
        PublicOperation(get, "/members", "listMembers", "Directory", "Lists the organization's members",
            "Every member who is not still invited, disabled ones included, by name.",
            query = PAGING, response = typeOf<Page<PublicMemberSummary>>()),
        PublicOperation(get, "/groups", "listGroups", "Directory", "Lists the organization's groups",
            "By name.", query = PAGING, response = typeOf<Page<PublicGroupSummary>>()),

        // Webhooks
        PublicOperation(get, "/webhooks", "listWebhooks", "Webhooks", "Lists the organization's webhooks",
            "Enough to bind one; never where it sends or what.", query = PAGING, response = typeOf<Page<PublicWebhookSummary>>()),
        PublicOperation(get, "/webhooks/bindings", "listWebhookBindings", "Webhooks", "Lists the webhooks bound to a resource",
            query = BOUND_RESOURCE + PAGING, response = typeOf<Page<WebhookBindingSummary>>(), errors = listOf(HttpStatusCode.NotFound)),
        PublicOperation(post, "/webhooks/bindings", "createWebhookBinding", "Webhooks", "Binds a webhook to a resource",
            "409 `binding_exists` when it is already bound there.",
            query = BOUND_RESOURCE, request = typeOf<WebhookBindingRequest>(), response = typeOf<WebhookBindingSummary>(),
            errors = listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict)),
        PublicOperation(patch, "/webhooks/bindings/{id}", "updateWebhookBinding", "Webhooks", "Pauses or resumes a binding",
            "`{\"enabled\": false}` or `{\"enabled\": true}`.",
            request = typeOf<Map<String, Boolean>>(), response = typeOf<WebhookBindingSummary>()),
        PublicOperation(delete, "/webhooks/bindings/{id}", "deleteWebhookBinding", "Webhooks", "Removes a binding",
            "The webhook itself stays.", response = OK),

        // Presets
        PublicOperation(get, "/presets", "listPresets", "Presets", "Lists script presets",
            "The organization-wide presets, and — with `workspaceId` — that workspace's too when the caller may see it " +
                "(a workspace they may not see, or one that is not there, lists the organization-wide ones only). By " +
                "name, ties by id.",
            query = listOf(QueryParameter("workspaceId", typeOf<String>(), "Also list this workspace's presets.")) + PAGING,
            response = typeOf<Page<RulePresetSummary>>()),
        PublicOperation(post, "/presets", "createPreset", "Presets", "Saves a script preset",
            "Organization-wide, which needs write on the organization's workspaces, or in the workspace named by " +
                "`workspaceId`, which needs write on it. `name` at most 128 characters, and need not be unique; " +
                "`script` must be valid Lace, at most 16384 characters.",
            request = typeOf<CreateRulePresetRequest>(), response = typeOf<RulePresetSummary>(), errors = listOf(HttpStatusCode.NotFound)),
        PublicOperation(get, "/presets/{id}", "getPreset", "Presets", "Returns a script preset",
            "A workspace's preset is found only by those who may see the workspace; to anyone else it is 404, for a " +
                "change or a delete as well.", response = typeOf<RulePresetSummary>()),
        PublicOperation(patch, "/presets/{id}", "updatePreset", "Presets", "Renames a preset or replaces its script",
            "Fields left out are unchanged; a preset stays in its scope. Needs what saving into that scope needs.",
            request = typeOf<UpdateRulePresetRequest>(), response = typeOf<RulePresetSummary>()),
        PublicOperation(delete, "/presets/{id}", "deletePreset", "Presets", "Deletes a preset",
            "Services made from it keep their script.", response = OK),

        // Notification templates
        PublicOperation(get, "/notification-templates", "listNotificationTemplates", "Notification templates",
            "Lists notification templates", "Oldest first, ties by id. Needs read on the organization's notifications.",
            query = PAGING, response = typeOf<Page<NotificationTemplateSummary>>()),
        PublicOperation(post, "/notification-templates", "createNotificationTemplate", "Notification templates",
            "Creates a notification template",
            "`name` at most 64 characters and unique in the organization (409 otherwise); `text` at most 10000. " +
                "`projectIds` binds it to those projects at once. Needs write on the organization's notifications. " +
                TEMPLATE_TEXT,
            request = typeOf<CreateNotificationTemplateRequest>(), response = typeOf<NotificationTemplateSummary>(),
            errors = listOf(HttpStatusCode.NotFound, HttpStatusCode.Conflict)),
        PublicOperation(get, "/notification-templates/{id}", "getNotificationTemplate", "Notification templates",
            "Returns a notification template", response = typeOf<NotificationTemplateSummary>()),
        PublicOperation(patch, "/notification-templates/{id}", "updateNotificationTemplate", "Notification templates",
            "Renames a template or replaces its text",
            "Fields left out are unchanged. A script names its template by `name`, so a rename breaks the scripts that " +
                "use the old one. " + TEMPLATE_TEXT,
            request = typeOf<UpdateNotificationTemplateRequest>(), response = typeOf<NotificationTemplateSummary>(), errors = CONFLICT),
        PublicOperation(delete, "/notification-templates/{id}", "deleteNotificationTemplate", "Notification templates",
            "Deletes a notification template", response = OK),
        PublicOperation(get, "/projects/{id}/notification-templates", "listProjectNotificationTemplates", "Notification templates",
            "Lists the templates bound to a project", "Oldest first, ties by id.",
            query = PAGING, response = typeOf<Page<NotificationTemplateSummary>>()),
        PublicOperation(put, "/projects/{id}/notification-templates/{templateId}", "bindProjectNotificationTemplate",
            "Notification templates", "Binds a template to a project",
            "Binding one that is bound already changes nothing. Answers the template.",
            response = typeOf<NotificationTemplateSummary>()),
        PublicOperation(delete, "/projects/{id}/notification-templates/{templateId}", "unbindProjectNotificationTemplate",
            "Notification templates", "Unbinds a template from a project", "Answers the template.",
            response = typeOf<NotificationTemplateSummary>()),

        // Alerts
        PublicOperation(get, "/alerts", "listAlerts", "Alerts", "Lists the organization's system alerts",
            "`state=active` (default): the latest alert of each type the caller has not dismissed, as the dashboard's " +
                "banners show them, most recently seen first. `state=all`: every episode, newest first, ties by id, each " +
                "with when the caller dismissed it. Needs write on the organization's settings, as the banners do. " +
                "An alert is never cleared by an event: it ends when the caller dismisses it (for them alone), and a " +
                "condition that returns after a quiet spell is a new episode — so an integration re-reads this list " +
                "rather than tracking alerts from the feed's `alert.raised`.",
            query = listOf(QueryParameter("state", typeOf<String>(), "`active` (default) or `all`.", values = SystemAlertController.STATES)) + PAGING,
            response = typeOf<Page<PublicSystemAlert>>()),
        PublicOperation(post, "/alerts/{id}/dismiss", "dismissAlert", "Alerts", "Dismisses an alert for the caller",
            "For the caller only, as in the dashboard; dismissing one twice changes nothing. A dismissal is the " +
                "caller's own view of the warning log, not a change to the organization, so — as in the dashboard — it " +
                "is not written to the audit log.", response = OK),

        // Events
        PublicOperation(get, "/events", "listEvents", "Events", "Reads the event feed",
            "The events after the cursor `after` that the caller may see now, in the order they were written — " +
                "decided on every read with the checks the dashboard's reads of the same resources make, so a grant " +
                "withdrawn stops delivery at once, and one given shows only what happens from then on (re-list " +
                "periodically to pick up what a new grant reveals). With none there, waits up to `wait` seconds for the " +
                "first, and answers an empty page when none came — or sooner, see **Cost**. Pass `next` as `after` to " +
                "read on; it moves even on an empty page. `more: true` means more may be there already: read on at " +
                "once. A page holds at most `limit` events, or one more when a result and the status change it caused " +
                "arrive together.\n\n" +
                "**Starting.** Read once without `after` and keep `next`; then take your snapshot of what you track " +
                "(the lists); then read from that cursor, applying each event by its `id` — delivery is at least once, " +
                "so an event may come twice, and one read before the snapshot may show what the snapshot already has. " +
                "To wait for a run, take a cursor before `POST /services/{id}/run`, then read for its `run.settled`. " +
                "Events are kept 7 days; a cursor older than that is 410 `cursor_expired` with `details.oldest`, the " +
                "cursor to start again from — after taking a new snapshot. A cursor works only for the organization it " +
                "was given in, and only in the database history it was given in: one from another organization, one " +
                "sealed before the platform key changed, and every cursor once the database has gone back in time (a " +
                "backup restored, a point-in-time recovery, a dump loaded) are 410 the same way, with a fresh start " +
                "in `details.oldest`.\n\n" +
                "**Types** and their `data` (exactly these fields):\n\n" +
                "| type | resource | data |\n|---|---|---|\n" +
                EventTypes.DATA.entries.joinToString("\n") { (type, fields) ->
                    "| `$type` | `${EventTypes.RESOURCE.getValue(type)}` | " +
                        (fields.joinToString(", ") { "`$it`" }.ifEmpty { "—" }) + " |"
                } + "\n\n" +
                "`result.recorded`: `status` is the run's (`success`, `failure`, `timeout`, `error`, `skipped`); " +
                "`runDurationMs` is null for a skipped run and `reason` is set only for one. `service.status_changed` " +
                "follows the result that changed it, `previousStatus` null on a service's first run. A variable's " +
                "events carry its scope (`org`, `workspace`, `project`, `service`), that scope's id and the key — " +
                "never the value; `key` is null when the variable has since been purged. A script's writeback of a " +
                "metric is a `variable.created`, or a `variable.updated` when its value changed. On `service.created`, " +
                "re-list the service's variables: the platform seeds some that are not announced. Deleting a " +
                "workspace or project deletes everything in it, and there is one event, for the container — its " +
                "children are not announced; drop them. `alert.raised` is a new episode of a system alert and needs " +
                "what the warning log needs; nothing announces an alert's end (see `GET /alerts`). `run.settled` is a " +
                "run asked for by `POST /services/{id}/run` reaching `done` or `skipped` (`state`): `status` is the " +
                "run's worst result's, null when it never ran (`reason` `run_not_delivered`: no scheduler took it); " +
                "`reason` is set only when the whole run was skipped — `status` `skipped` with `state` `done` means " +
                "some of its agents did not run it, and carries none. A run settled once can settle again — a " +
                "skip that a result replaces, a late result after `run_not_delivered` — with `superseded: true`: the " +
                "last `run.settled` for a `runId` wins. An `expired` run is never an event; it is what " +
                "`GET /services/{id}/runs/{runId}` says once nothing has come. `occurredAt`: a result's run start, an " +
                "alert's episode start, otherwise when the change was made.\n\n" +
                "**Cost.** A read does at most ${EventFeedController.MAX_LOOKS} looks for events and then answers, even " +
                "before `wait` is up. The first look of a read that waits is free of the request budget; every look " +
                "after it, a read that answers without waiting (`wait=0`, or events already there), and every 410 " +
                "spends one request of it; a key whose budget is spent is refused on arrival (429 `rate_limited`). " +
                "Answers carry no `X-RateLimit-*` headers. A key holds at most ${EventPollSlots.PER_KEY} reads open at once (a user " +
                "${EventPollSlots.PER_USER}, an organization ${EventPollSlots.PER_ORG}); beyond that, 429 " +
                "`too_many_event_polls` with `details.bound` and `Retry-After`. A caller who can see nothing at all " +
                "is answered at once. A transaction left open anywhere on the database server (any database, " +
                "including prepared transactions) holds the feed back until it ends.",
            query = listOf(
                QueryParameter("after", typeOf<String>(), "A cursor: `next` of an earlier read."),
                QueryParameter("wait", typeOf<Int>(), "Seconds to wait for an event when there is none, 0–30. Default 0."),
                QueryParameter("types", typeOf<List<String>>(), "Only these event types: comma-separated, or the parameter repeated.",
                    values = EventTypes.ALL),
                QueryParameter("limit", typeOf<Int>(), "Events per page, 1–100. Default 100."),
            ),
            response = typeOf<EventPage>(), errors = listOf(HttpStatusCode.Gone, HttpStatusCode.TooManyRequests)),
    ) + variables("organization", "", tagHierarchy = false) +
        variables("workspace", "/workspaces/{id}", tagHierarchy = true) +
        variables("project", "/projects/{id}", tagHierarchy = true) +
        variables("service", "/services/{id}", tagHierarchy = true)

    private val byKey: Map<String, PublicOperation> = all.associateBy { it.key }.also {
        check(it.size == all.size) { "Two public operations share a route" }
        check(all.map { op -> op.operationId }.distinct().size == all.size) { "Two public operations share an operationId" }
    }

    /** The operation for [method] on [path] (relative to [PublicApi.V1]), if this module provides it. */
    fun find(method: HttpMethod, path: String): PublicOperation? = byKey["${method.value} $path"]

    /** How a template's text is written — said on each operation that takes one. */
    private const val TEMPLATE_TEXT =
        "The text is plain, with `${'$'}{name}` placeholders filled in by name when a notification is sent — " +
            "`${'$'}{s.name}`, `${'$'}{s.schedule}`, `${'$'}{w.name}`, `${'$'}{p.name}` (service, its schedule, " +
            "workspace, project), `${'$'}{url}`, `${'$'}{status}`, `${'$'}{trigger}`, `${'$'}{conditions}`, " +
            "`${'$'}{expected}`, `${'$'}{actual}`, `${'$'}{ms}`, `${'$'}{downtime}`, `${'$'}{text}`; an unknown name " +
            "renders empty, and `\\${'$'}{` writes a literal `${'$'}{`. It is not checked when saved. A script sends it " +
            "by the template's `name`, and only from a project the template is bound to."

    /** Every type a public handler receives or answers with, and the error body. */
    val types: List<KType> =
        (all.flatMap { listOfNotNull(it.request, it.response) } + typeOf<PublicApiError>()).distinctBy { it.toString() }

    /** The order the areas appear in the description. */
    val tags: List<Pair<String, String>> = listOf(
        "Key" to "The calling API key.",
        "Workspaces" to "Workspaces: the top level of the organization's resources.",
        "Projects" to "Projects: groups of services inside a workspace.",
        "Services" to "Services: a monitored API, its Lace script, its schedule and where it runs.",
        "Agents" to "The agents a service can run on.",
        "Variables" to "Variables at organization, workspace, project and service scope.",
        "Results" to "A service's runs.",
        "Metrics" to "How a service, project or workspace has been doing.",
        "Silences" to "The calling user's own notification silences.",
        "Access" to "Who may see or change a workspace, project or service.",
        "Directory" to "The organization's members and groups.",
        "Webhooks" to "Attaching existing webhooks to resources.",
        "Scripts" to "Lace scripts, judged without being saved.",
        "Presets" to "Script presets: Lace scripts a service can start from.",
        "Notification templates" to "The text notifications are written with, and the projects that use it.",
        "Alerts" to "The warning log: what the platform has noticed about the organization's agents and runs.",
        "Events" to "A feed of what happens to the resources the caller may see.",
    )
}
