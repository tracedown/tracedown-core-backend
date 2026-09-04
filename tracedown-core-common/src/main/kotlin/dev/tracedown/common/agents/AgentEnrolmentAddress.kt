package dev.tracedown.common.agents

/**
 * The base URL a probe agent is told to dial for enrolment — what the
 * dashboard and the CLI print as `PROBE_AGENT_SCHEDULER_URL` next to a fresh
 * bootstrap token.
 *
 * ## What Core knows
 *
 * The gateway serves `/internal/agents/register` and its siblings, but it does
 * not know the address the outside world reaches it by: behind a reverse proxy
 * the request host is the proxy's, on a private network it is a container
 * name, and a deployment may publish enrolment on a host of its own. So the
 * value is configuration — `GATEWAY_PUBLIC_URL` — and when it is unset Core
 * has no answer and says so with `null`, which the dashboard renders as a
 * placeholder the operator has to fill in. Guessing from the request would
 * print an address that works from the browser and not from the agent.
 *
 * ## What Core deliberately does not know
 *
 * A deployment may hold that address somewhere other than the environment —
 * a settings table an operator edits at runtime, a per-region choice. It
 * installs a [Source] at startup and every place that prints the address asks
 * here. The source is consulted per call, so a runtime edit is visible on the
 * next token without a restart.
 */
object AgentEnrolmentAddress {

    /** Answers the base URL, or null when the deployment has none configured. */
    fun interface Source {
        fun resolve(): String?
    }

    @Volatile
    private var source: Source? = null

    /**
     * Installs the deployment's source. Called once at startup, before routes
     * are serving. A second call replaces the first (tests rely on this).
     */
    fun install(source: Source?) {
        this.source = source
    }

    /** A source that answers one configured value; blank means unconfigured. */
    fun fixed(url: String?): Source = Source { url?.trim()?.trimEnd('/')?.ifBlank { null } }

    /**
     * The base URL agents should dial, without a trailing slash, or null when
     * nothing is configured. Never blank.
     */
    fun resolve(): String? = source?.resolve()?.trim()?.trimEnd('/')?.ifBlank { null }
}
