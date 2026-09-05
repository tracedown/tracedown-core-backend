package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.json.jsonb

object OrgGroups : Table("org_groups") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val name = varchar("name", 64)
    val totpRequired = bool("totp_required").default(false)
    val orgUserList = short("org_user_list").default(0)
    val orgSettings = short("org_settings").default(0)
    val orgDomains = short("org_domains").default(0)
    val orgWebhooks = short("org_webhooks").default(0)
    val orgNotifications = short("org_notifications").default(0)
    val orgAdmin = short("org_admin").default(0)
    val orgWorkspaces = short("org_workspaces").default(0)
    val orgExtraPerms = jsonb<JsonObject>(
        "org_extra_perms",
        { Json.encodeToString(JsonObject.serializer(), it) },
        { Json.decodeFromString(JsonObject.serializer(), it) }
    ).default(JsonObject(emptyMap()))

    override val primaryKey = PrimaryKey(id)
}
