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
    const val INVALID_UUID = "invalid_uuid"
    // A PFS filter/sort referenced a table or column that endpoint does not
    // expose for filtering/sorting (per-table allowlist rejection).
    const val UNKNOWN_COLUMN = "unknown_column"

    // ── Resources ──
    const val NOT_FOUND = "not_found"
    const val ALREADY_EXISTS = "already_exists"
    const val VERSION_CONFLICT = "version_conflict"

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

    // ── General ──
    const val INTERNAL_ERROR = "internal_error"
    const val NOT_SUPPORTED = "not_supported"
}
