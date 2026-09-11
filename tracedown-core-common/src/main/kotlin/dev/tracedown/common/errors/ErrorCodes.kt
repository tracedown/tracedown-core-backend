package dev.tracedown.common.errors

/**
 * Canonical error codes returned by the API.
 *
 * The frontend maps these to localized user-facing strings via i18n.
 * The backend MUST NOT return user-facing text — only these codes.
 */
object ErrorCodes {

    // ── Auth ──
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val ACCOUNT_DEACTIVATED = "account_deactivated"
    /** The account is not a member of any active organization, so it cannot sign in. */
    const val ACCOUNT_NO_ACTIVE_ORG = "account_no_active_org"
    const val SESSION_EXPIRED = "session_expired"
    const val MISSING_AUTH_HEADER = "missing_auth_header"
    const val INVALID_TOKEN = "invalid_token"
    const val INVALID_TOTP_CODE = "invalid_totp_code"
    const val TOTP_NOT_CONFIGURED = "totp_not_configured"
    const val SETUP_TOKEN_EXPIRED = "setup_token_expired"
    const val INVALID_SETUP_TOKEN = "invalid_setup_token"

    // ── Invites ──
    const val INVALID_INVITE_TOKEN = "invalid_invite_token"
    const val INVITE_EXPIRED = "invite_expired"
    const val INVITE_COOLDOWN = "invite_cooldown"

    // ── Validation ──
    const val FIELD_REQUIRED = "field_required"
    const val FIELD_TOO_LONG = "field_too_long"
    const val FIELD_INVALID = "field_invalid"
    const val UNVERIFIED_DOMAIN_CALL_LIMIT = "unverified_domain_call_limit"
    const val UNVERIFIED_DOMAIN_INTERVAL = "unverified_domain_interval"
    const val UNVERIFIED_DOMAIN_INCLUDES = "unverified_domain_includes"
    const val INVALID_REQUEST_BODY = "invalid_request_body"
    /** The request body exceeds the size the API accepts (413). */
    const val REQUEST_BODY_TOO_LARGE = "request_body_too_large"
    /**
     * The script targets an address this installation does not permit a probe to
     * reach (a private, loopback or internal-only address, or a non-HTTP scheme).
     */
    const val BLOCKED_PROBE_TARGET = "blocked_probe_target"
    const val INVALID_UUID = "invalid_uuid"
    // A PFS filter/sort referenced a table or column that endpoint does not
    // expose for filtering/sorting (per-table allowlist rejection).
    const val UNKNOWN_COLUMN = "unknown_column"

    // ── Resources ──
    const val NOT_FOUND = "not_found"
    const val ALREADY_EXISTS = "already_exists"
    const val VERSION_CONFLICT = "version_conflict"
    /** A stored response body the step still names can no longer be read at its location (410). */
    const val BODY_GONE = "body_gone"
    /** A bootstrap token was requested for a slug an agent is already registered under. */
    const val AGENT_SLUG_TAKEN = "agent_slug_taken"
    /**
     * A stored response body is larger than the API serves inline (413). Only
     * bodies read through a body store are capped this way; the default store
     * hands out a URL instead.
     */
    const val BODY_TOO_LARGE = "body_too_large"

    // ── Body stores ──
    const val BODY_STORE_NOT_FOUND = "body_store_not_found"
    /** A store still named by an agent, an outstanding bootstrap token or a stored body (409). */
    const val BODY_STORE_IN_USE = "body_store_in_use"
    const val BODY_STORE_NAME_TAKEN = "body_store_name_taken"
    const val INVALID_STORE_KIND = "invalid_store_kind"
    const val INVALID_STORE_MODE = "invalid_store_mode"
    /** A field the store's kind needs is missing; `details.field` names it. */
    const val STORE_FIELD_REQUIRED = "store_field_required"

    // ── Variables ──
    /** The resource already holds as many variables as the operator allows. */
    const val VARIABLE_LIMIT_REACHED = "variable_limit_reached"
    const val SYSTEM_VARIABLE = "system_variable"
    const val RESERVED_KEY = "reserved_key"
    const val READONLY_VARIABLE = "readonly_variable"

    // ── Permissions ──
    const val FORBIDDEN = "forbidden"
    const val NOT_ORG_MEMBER = "not_org_member"
    const val INSUFFICIENT_PERMISSIONS = "insufficient_permissions"
    const val NO_ORG_SELECTED = "no_org_selected"

    // ── Password ──
    const val PASSWORD_TOO_SHORT = "password_too_short"
    const val PASSWORD_TOO_WEAK = "password_too_weak"
    const val INCORRECT_PASSWORD = "incorrect_password"

    // ── Rate limit ──
    const val RATE_LIMITED = "rate_limited"

    // ── Profile ──
    const val PROFILE_EDIT_DISABLED = "profile_edit_disabled"
    const val EMAIL_TAKEN = "email_taken"

    // ── Account closure ──
    /** Self-service account closure is switched off on this platform. */
    const val ACCOUNT_CLOSURE_DISABLED = "account_closure_disabled"
    /**
     * The account still owns organizations, so it cannot be closed. The error
     * carries their names in `details.organizations`: hand each one to another
     * owner, or ask for it to be deleted along with the account.
     */
    const val ACCOUNT_OWNS_ORGANIZATIONS = "account_owns_organizations"

    // ── General ──
    const val INTERNAL_ERROR = "internal_error"
    const val NOT_SUPPORTED = "not_supported"
}
