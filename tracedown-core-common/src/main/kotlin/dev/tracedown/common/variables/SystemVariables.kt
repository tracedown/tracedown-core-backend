package dev.tracedown.common.variables

/**
 * Definitions for platform-managed system variables.
 *
 * - **config**: toggles that users can change but not delete.
 * - **storage**: platform-writable data (e.g. written back by the result-ingestor).
 * - **override**: inherited from parent scope (org→ws→proj→svc). Created at org level
 *   as `config`, at lower scopes as `override` only when explicitly overridden.
 *   Values are clamped to platform-configured upper limits.
 */
object SystemVariables {

    data class Definition(
        val key: String,
        val defaultValue: String,
        val description: String,
        val companions: List<String> = emptyList(),
    )

    /** Override variable definition with platform default and max. */
    data class OverrideDefinition(
        val key: String,
        val platformDefault: String,
        val platformMax: Int,
        val description: String,
    )

    /**
     * A read-only, platform-computed variable exposed to scripts as `$<scope>.<key>`
     * (e.g. `$s.name`). Its value is derived from the resource, never stored or
     * editable — the resolver injects it on every run. Kept here so the editor and
     * the [ScriptVariableResolver] agree on exactly which locked keys exist per
     * scope.
     */
    data class Computed(val key: String, val description: String)

    /** Locked variables the resolver injects for `$s.*`. */
    val SERVICE_COMPUTED = listOf(
        Computed("name", "Service name"),
        Computed("lastStatus", "Most recent probe status"),
        Computed("lastStatusSince", "When the current status began (ISO-8601)"),
        Computed("lastStatusConsecutive", "Consecutive runs in the current status"),
    )

    /** Locked variables the resolver injects for `$p.*`. */
    val PROJECT_COMPUTED = listOf(Computed("name", "Project name"))

    /** Locked variables the resolver injects for `$w.*`. */
    val WORKSPACE_COMPUTED = listOf(Computed("name", "Workspace name"))

    /** Locked variables for `$o.*` (none injected today). */
    val ORG_COMPUTED = emptyList<Computed>()

    /** The computed (locked) variables for a scope. */
    fun computed(scope: String): List<Computed> = when (scope) {
        "service" -> SERVICE_COMPUTED
        "project" -> PROJECT_COMPUTED
        "workspace" -> WORKSPACE_COMPUTED
        else -> ORG_COMPUTED
    }

    /** System variables seeded on every new service (config type). */
    val SERVICE = listOf(
        Definition(
            key = "trackBaseline",
            defaultValue = "false",
            description = "Enable response time baseline tracking",
        ),
    )

    /** System variables seeded on every new workspace. */
    val WORKSPACE = emptyList<Definition>()

    /** System variables seeded on every new project. */
    val PROJECT = emptyList<Definition>()

    /** Override variables — currently empty; timeout and redirects are set per-call in scripts. */
    val OVERRIDES = emptyList<OverrideDefinition>()

    /** All reserved keys for a given scope. */
    fun reservedKeys(scope: String): Set<String> {
        val overrideKeys = OVERRIDES.map { it.key }.toSet()
        return when (scope) {
            "service" -> SERVICE.flatMap { listOf(it.key) + it.companions }.toSet() + overrideKeys
            "workspace" -> WORKSPACE.flatMap { listOf(it.key) + it.companions }.toSet() + overrideKeys
            "project" -> PROJECT.flatMap { listOf(it.key) + it.companions }.toSet() + overrideKeys
            "org" -> overrideKeys
            else -> emptySet()
        }
    }
}
