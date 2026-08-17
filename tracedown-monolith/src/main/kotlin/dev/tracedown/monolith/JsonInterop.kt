package dev.tracedown.monolith

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Bridges the scheduler's kotlinx-serialization world and the Lace executor's
 * plain `Map<String, Any?>` AST/result world.
 */
internal object JsonInterop {

    fun toPlain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive ->
            if (element.isString) element.content
            else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        is JsonArray -> element.map { toPlain(it) }
        is JsonObject -> element.mapValues { (_, v) -> toPlain(v) }
    }

    fun toPlainMap(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, v) -> toPlain(v) }

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    fun toJsonObject(map: Map<String, Any?>): JsonObject =
        JsonObject(map.mapValues { (_, v) -> toJson(v) })
}
