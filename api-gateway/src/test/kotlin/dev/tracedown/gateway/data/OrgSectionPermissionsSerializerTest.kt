package dev.tracedown.gateway.data

import dev.tracedown.common.auth.PermissionSections
import dev.tracedown.gateway.data.orgs.OrgSectionPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrgSectionPermissionsSerializerTest {

    private val builtInOnly = OrgSectionPermissions(2, 1, 0, 0, 0, 0, 1)

    @Test
    fun `a registered section missing from the row is written as level 0`() {
        PermissionSections.register("reports")
        val obj = Json.encodeToJsonElement(OrgSectionPermissions.serializer(), builtInOnly).jsonObject
        assertEquals(0, obj["reports"]!!.jsonPrimitive.int, "a registered section is never absent on the wire")
        assertEquals(2, obj["users"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a stored level for a registered section is written as stored`() {
        PermissionSections.register("reports")
        val withLevel = builtInOnly.copy(extra = mapOf("reports" to 2))
        val obj = Json.encodeToJsonElement(OrgSectionPermissions.serializer(), withLevel).jsonObject
        assertEquals(2, obj["reports"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a stored level under an unregistered key is relayed, and nothing is invented for it`() {
        val stray = builtInOnly.copy(extra = mapOf("legacy" to 1))
        val obj = Json.encodeToJsonElement(OrgSectionPermissions.serializer(), stray).jsonObject
        assertEquals(1, obj["legacy"]!!.jsonPrimitive.int)
        assertNull(obj["nothing"])
    }

    @Test
    fun `the flat object round-trips through deserialization`() {
        PermissionSections.register("reports")
        val json = Json.encodeToString(OrgSectionPermissions.serializer(), builtInOnly.copy(extra = mapOf("reports" to 1)))
        val back = Json.decodeFromString(OrgSectionPermissions.serializer(), json)
        assertEquals(1, back.extra["reports"])
        assertEquals(builtInOnly.users, back.users)
    }
}
