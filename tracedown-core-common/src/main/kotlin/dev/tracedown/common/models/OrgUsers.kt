package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

object OrgUsers : Table("org_users") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val userId = javaUUID("user_id").references(Users.id)
    val joinedAt = timestamp("joined_at").nullable()
    val status = varchar("status", 16).default("invited")
    val isActive = bool("is_active").default(true)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val orgUserList = short("org_user_list").default(0)
    val orgSettings = short("org_settings").default(0)
    val orgDomains = short("org_domains").default(0)
    val orgWebhooks = short("org_webhooks").default(0)
    val orgNotifications = short("org_notifications").default(0)
    val orgAdmin = short("org_admin").default(0)
    val orgWorkspaces = short("org_workspaces").default(0)
    val inviteToken = varchar("invite_token", 128)
    val invitedAt = timestamp("invited_at").nullable()
    val invitedBy = javaUUID("invited_by").references(Users.id).nullable()
    val inviteExpiresAt = timestamp("invite_expires_at").nullable()
    val lastInviteSentAt = timestamp("last_invite_sent_at").nullable()
    val permissionCache = jsonb<JsonObject>(
        "permission_cache",
        { Json.encodeToString(JsonObject.serializer(), it) },
        { Json.decodeFromString(JsonObject.serializer(), it) }
    ).nullable()
    val orgExtraPerms = jsonb<JsonObject>(
        "org_extra_perms",
        { Json.encodeToString(JsonObject.serializer(), it) },
        { Json.decodeFromString(JsonObject.serializer(), it) }
    ).default(JsonObject(emptyMap()))

    override val primaryKey = PrimaryKey(id)
}
