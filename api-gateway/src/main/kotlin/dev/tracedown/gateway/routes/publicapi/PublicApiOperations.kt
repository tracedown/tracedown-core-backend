package dev.tracedown.gateway.routes.publicapi

import dev.tracedown.common.pfs.Page
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.VariableHierarchyResponse
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.data.agents.PublicAgentSummary
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
import dev.tracedown.gateway.data.results.StepBodyContent
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.services.RunRequested
import dev.tracedown.gateway.data.services.ScopedToggleResult
import dev.tracedown.gateway.data.services.ServiceSnapshot
import dev.tracedown.gateway.data.services.ServiceSummary
import dev.tracedown.gateway.data.services.SetAllowedAgentsRequest
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.services.UpdateScriptRequest
import dev.tracedown.gateway.data.services.UpdateServiceRequest
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
) {
    val key: String get() = "${method.value} $path"

    /** Every error status this operation can answer. */
    val errorStatuses: List<HttpStatusCode>
        get() = (
            COMMON_ERRORS +
                (if ('{' in path) listOf(HttpStatusCode.NotFound) else emptyList()) +
                // A request body past the gateway's size limit: 413 `request_body_too_large`.
                (if (request != null) listOf(HttpStatusCode.PayloadTooLarge) else emptyList()) +
                errors
            ).distinct().sortedBy { it.value }

    private companion object {
        val COMMON_ERRORS = listOf(
            HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests,
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
 */
object PublicApiOperations {

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
                "(https://tracedown.dev/guide/api/).",
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
            "Outside its schedule. Answers 202 once queued, with `requestedAt` (whole seconds, UTC). To wait for the " +
                "run, list `/services/{id}/results?since=<requestedAt>`: the earliest result there that is not " +
                "`skipped` is the answer (the list is newest first; a scheduled run that started after `requestedAt` " +
                "matches too). Allow a few seconds of clock difference between the agent and the gateway; a request " +
                "can be shed when the service's queue is full, so if nothing has arrived after a few minutes, ask " +
                "again. 409 `script_missing` or `service_inactive` when it cannot run.",
            response = typeOf<RunRequested>(), status = HttpStatusCode.Accepted, errors = CONFLICT),
        PublicOperation(get, "/services/{id}/agents", "listServiceAgents", "Services", "Lists the agents a service may run on",
            "By slug; an empty list means any agent.", response = typeOf<List<String>>()),
        PublicOperation(put, "/services/{id}/agents", "setServiceAgents", "Services", "Sets the agents a service may run on",
            "An empty list means any agent. Slugs are listed by `GET /agents`; an unknown one is refused with " +
                "`details.unknown`.", request = typeOf<SetAllowedAgentsRequest>(), response = typeOf<List<String>>()),

        // Agents
        PublicOperation(get, "/agents", "listAgents", "Agents", "Lists the agents a service can be set to run on",
            "By slug, ordered by slug: the names `PUT /services/{id}/agents` takes.", response = typeOf<List<PublicAgentSummary>>()),

        // Results
        PublicOperation(get, "/services/{id}/results", "listServiceResults", "Results", "Lists a service's runs",
            "Most recent first. Run times are kept to the second; `since` is floored to the second before it is compared.",
            query = listOf(QueryParameter("since", typeOf<String>(), "An ISO-8601 instant: only runs started at or after it (to the second).")) + PAGING,
            response = typeOf<Page<ProbeResultSummary>>()),
        PublicOperation(get, "/services/{id}/results/{resultId}", "getServiceResult", "Results", "Returns a run with all of its steps",
            "`rawResult` is the run's ProbeResult as the Lace specification defines it; `calls[].response.bodyPath` is " +
                "always null here — a stored body is reached through `steps[].hasBody` and `getStepBody`.",
            response = typeOf<ProbeResultDetail>()),
        PublicOperation(get, "/services/{id}/results/{resultId}/steps/{stepId}/body", "getStepBody", "Results",
            "Returns a step's stored response body",
            "As text in the response: `content`, `contentType` (null when not known) and `encoding` (`base64` when the " +
                "bytes are not UTF-8 text or the text carries control characters, null otherwise). Bodies over 4 MiB are " +
                "refused with 413 `body_too_large` (`details.maxBytes`); a raw download endpoint is planned. 410 " +
                "`body_gone` when the step recorded a body that is no longer there; 204 when it stored none " +
                "(`hasBody: false`). 503 `body_store_unavailable` (with `Retry-After`) is worth retrying with backoff; " +
                "a read can take up to about 10 seconds to be refused that way.",
            response = typeOf<StepBodyContent>(), noContent = "The step stored no body.",
            errors = listOf(HttpStatusCode.Gone, HttpStatusCode.PayloadTooLarge, HttpStatusCode.ServiceUnavailable)),

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
    )
}
