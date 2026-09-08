package dev.tracedown.gateway.data.orgs

import dev.tracedown.common.auth.PermissionSections
import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ResourceGrant(
    val resourceType: String,
    val resourceId: String,
    val permissions: Short,
) : Validatable {
    override fun validate() = buildList {
        Validators.oneOf("resourceType", resourceType, setOf("workspace", "project", "service"))?.let(::add)
        Validators.uuid("resourceId", resourceId)?.let(::add)
    }
}

/**
 * Org-level section access levels.
 *
 * Built-in sections are fixed fields; sections registered by additional modules
 * carry their levels in [extra]. On the wire the whole thing is a single flat
 * object — extension keys appear as siblings of the built-in keys, so the
 * representation matches the permission cache. With no registered sections the
 * output is byte-identical to the built-in-only form.
 *
 * Every section registered with [PermissionSections] is always written, at 0
 * when the row stores nothing for it. A row created before a section was
 * registered — or by a seed that only fills the sections it cares about — has
 * no entry in its extension map, and a client that renders the matrix from the
 * registry would otherwise meet an absent key where it expects a level.
 */
@Serializable(with = OrgSectionPermissionsSerializer::class)
data class OrgSectionPermissions(
    val users: Short,
    val settings: Short,
    val domains: Short,
    val webhooks: Short,
    val notifications: Short,
    val admin: Short,
    val workspaces: Short,
    val extra: Map<String, Short> = emptyMap(),
)

/** Serializes [OrgSectionPermissions] as a flat object with extension keys as siblings. */
object OrgSectionPermissionsSerializer : KSerializer<OrgSectionPermissions> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("OrgSectionPermissions")

    override fun serialize(encoder: Encoder, value: OrgSectionPermissions) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("OrgSectionPermissions only supports JSON serialization")
        val obj = buildJsonObject {
            put("users", value.users.toInt())
            put("settings", value.settings.toInt())
            put("domains", value.domains.toInt())
            put("webhooks", value.webhooks.toInt())
            put("notifications", value.notifications.toInt())
            put("admin", value.admin.toInt())
            put("workspaces", value.workspaces.toInt())
            for (key in PermissionSections.registered()) {
                put(key, (value.extra[key] ?: 0).toInt())
            }
            // Levels stored under keys no module registers in this process are
            // still relayed, as they always were: the data is the row's, not ours.
            for ((key, level) in value.extra) {
                if (key !in PermissionSections.registered()) put(key, level.toInt())
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): OrgSectionPermissions {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("OrgSectionPermissions only supports JSON deserialization")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        fun level(key: String): Short = obj[key]?.jsonPrimitive?.int?.toShort() ?: 0
        val extra = obj.entries
            .filter { it.key !in PermissionSections.BUILTIN }
            .associate { it.key to it.value.jsonPrimitive.int.toShort() }
        return OrgSectionPermissions(
            users = level("users"),
            settings = level("settings"),
            domains = level("domains"),
            webhooks = level("webhooks"),
            notifications = level("notifications"),
            admin = level("admin"),
            workspaces = level("workspaces"),
            extra = extra,
        )
    }
}

/**
 * Full permission view for a user or group.
 * Org sections + resource-level grants.
 */
@Serializable
data class PermissionSet(
    val org: OrgSectionPermissions,
    val resources: List<ResourceGrant>,
)

/**
 * PATCH body — updates org sections and replaces resource grants (diff-based).
 * Null org fields are left unchanged.
 */
@Serializable
data class UpdatePermissionsRequest(
    val org: OrgSectionPermissions? = null,
    val resources: List<ResourceGrant>? = null,
) : Validatable {
    override fun validate() = buildList {
        resources?.forEach { grant -> addAll(grant.validate()) }
    }
}

/** One organization member as shown in the user management list. */
@Serializable
data class OrgUserSummary(
    val userId: String,
    val email: String,
    val displayName: String,
    val isOwner: Boolean,
    val isActive: Boolean = true,
    val org: OrgSectionPermissions,
    /** Ids of the groups this member belongs to. */
    val groupIds: List<String> = emptyList(),
)

@Serializable
data class ToggleOrgUserRequest(
    val isActive: Boolean,
)
