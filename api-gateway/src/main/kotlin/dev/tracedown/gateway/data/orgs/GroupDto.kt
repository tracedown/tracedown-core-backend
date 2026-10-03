package dev.tracedown.gateway.data.orgs

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class CreateGroupRequest(val name: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 64)?.let(::add)
    }
}

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    val users: Short? = null,
    val settings: Short? = null,
    val domains: Short? = null,
    val webhooks: Short? = null,
    val notifications: Short? = null,
    val admin: Short? = null,
    val workspaces: Short? = null,
    /** Enrolment enforcement: members of this group must have TOTP set up. */
    val totpRequired: Boolean? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 64)?.let(::add)
    }
}

@Serializable
data class GroupSummary(
    val id: String,
    val name: String,
    val users: Short,
    val settings: Short,
    val domains: Short,
    val webhooks: Short,
    val notifications: Short,
    val admin: Short,
    val workspaces: Short,
    val totpRequired: Boolean,
    val memberCount: Int,
)

@Serializable
data class GroupMember(
    val userId: String,
    val email: String,
    val displayName: String,
)

@Serializable
data class AddMemberRequest(val userId: String) : Validatable {
    override fun validate() = buildList {
        Validators.uuid("userId", userId)?.let(::add)
    }
}

@Serializable
data class SyncMembersRequest(val userIds: List<String>) : Validatable {
    override fun validate() = buildList {
        Validators.each(userIds) { Validators.uuid("userId", it) }?.let(::add)
    }
}

/**
 * A group as the key-authenticated API lists it: enough to grant it access to
 * a resource, and nothing of the organization permissions it carries.
 */
@Serializable
data class PublicGroupSummary(
    val id: String,
    val name: String,
    val memberCount: Int,
)
