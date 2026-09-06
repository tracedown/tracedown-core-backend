package dev.tracedown.gateway.data.orgs

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class OrgSettings(
    val id: String,
    val name: String,
    val ownerId: String,
    val totpRequired: Boolean,
    /** Org-wide default IANA timezone (maintenance windows etc.). */
    val defaultTimezone: String,
    /** Org-wide date format, one of [DateFormats.ALL]; applies to every member. */
    val dateFormat: String,
)

object DateFormats {
    /** dd.mm.yyyy */
    const val EU = "eu"
    /** mm/dd/yyyy */
    const val US = "us"
    val ALL: Set<String> = setOf(EU, US)
}

@Serializable
data class UpdateOrgSettingsRequest(
    val name: String? = null,
    val totpRequired: Boolean? = null,
    val defaultTimezone: String? = null,
    val dateFormat: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.maxLen("defaultTimezone", defaultTimezone, 64)?.let(::add)
        Validators.oneOf("dateFormat", dateFormat, DateFormats.ALL)?.let(::add)
    }
}

@Serializable
data class TransferOwnershipRequest(
    val newOwnerId: String,
    val password: String,
    val code: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("newOwnerId", newOwnerId)?.let(::add)
        Validators.uuid("newOwnerId", newOwnerId)?.let(::add)
        Validators.notBlank("password", password)?.let(::add)
        Validators.maxLen("password", password, 255)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}

@Serializable
data class DeleteOrgRequest(
    val password: String,
    /** TOTP or recovery code; required when the owner has 2FA enrolled. */
    val code: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("password", password)?.let(::add)
        Validators.maxLen("password", password, 255)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}
