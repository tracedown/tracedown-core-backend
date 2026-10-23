package dev.tracedown.gateway

import dev.tracedown.gateway.routes.publicapi.PublicApi
import dev.tracedown.gateway.routes.publicapi.PublicApiOperations
import dev.tracedown.gateway.routes.publicapi.publicApiRoutes
import io.ktor.http.HttpMethod
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KType

/**
 * Holds the key-authenticated API to its v1 contract: the routes and the
 * serialized shapes in `public-api-v1.routes.txt` and `public-api-v1.types.txt`
 * are a **baseline** — what v1 promised when it was published — and the API
 * may grow from it but not move away from it.
 *
 * Passes: a new route; a new optional query parameter on a route; a new type;
 * a new field in a response; a new optional field in a request; a required
 * request field becoming optional.
 * Fails: a baseline route that is no longer mounted, or whose operation id,
 * success status, request type or response type changed, or that lost or
 * newly requires a query parameter; a
 * baseline field removed, renamed, retyped or made nullable; a class whose
 * serial name changed (it is what its schema is called); a request field that
 * became required, or a new required one — a client written against the
 * baseline does not send it.
 *
 * The types are shared with the dashboard's API, which is free to change them;
 * this is what stops such a change from reaching the key-authenticated API
 * unnoticed. Anything the baseline refuses belongs in a new version, or in a
 * class of the public API's own that keeps the old shape.
 *
 * The current routes and shapes are written under `build/public-api-contract/`
 * on every run, for review; the baseline files are only ever appended to, by
 * hand. A route that gains a parameter gets a second line beside its first,
 * and a type that gains a field a second block after its first: each line and
 * block is what v1 promised when it was written, and all of them hold.
 */
class PublicApiContractTest {

    companion object {
        const val ROUTES_FILE = "public-api-v1.routes.txt"
        const val TYPES_FILE = "public-api-v1.types.txt"

        private const val DELIBERATE =
            "The key-authenticated API is a published contract: within v1 a change must be deliberate and " +
                "additive only — a new route, a new response field, a new optional request field. Removing, " +
                "renaming or retyping anything, or requiring something new of a request, belongs in a new " +
                "version, or in a public class of its own that keeps the old shape."

        /** Every type a public handler receives or responds with — the same list the description is built from. */
        val PUBLIC_TYPES: List<KType> = PublicApiOperations.types

        /** The request bodies: a field they gain must be optional. */
        private val REQUEST_TYPES: List<KType> = PublicApiOperations.all.mapNotNull { it.request }.distinctBy { it.toString() }

        /**
         * Golden JSON of a sample value, for every type whose descriptor
         * lists no elements (a hand-written serializer): its shape cannot be
         * read off the descriptor, so a sample pins it instead. None is
         * public today; one that becomes public fails until it is added here.
         */
        val SAMPLES: Map<String, Pair<() -> String, String>> = emptyMap()

        /** `METHOD /path` for every endpoint under [PublicApi.V1] in [root], sorted. */
        fun publicRoutes(root: RoutingNode): List<String> =
            root.getAllRoutes()
                .mapNotNull { route ->
                    val method = (route.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
                    val path = route.parent?.toString() ?: return@mapNotNull null
                    if (path != PublicApi.V1 && !path.startsWith("${PublicApi.V1}/")) return@mapNotNull null
                    "${method.value} $path"
                }
                .distinct()
                .sorted()

        /**
         * One class's serialized shape, under [key] — its serial name, with its
         * type arguments for a generic class (`…Page<…WorkspaceSummary>`) — its
         * fields by name, each as `type` (`?` when it may be null) and whether
         * it may be left out (`optional`).
         */
        data class Shape(val key: String, val fields: Map<String, Field>) {
            /**
             * One field: its type, whether it may be left out, and — for a
             * string held to a set of values (`@JsonSchema.Enum`) — the values.
             */
            data class Field(val type: String, val optional: Boolean, val values: List<String>? = null)

            /** The class's serial name. A change of it is a break: it is what a schema is called. */
            val name: String get() = key.substringBefore('<')

            fun render(): String = buildString {
                append(key).append('\n')
                fields.forEach { (field, f) ->
                    append("  ").append(field).append(": ").append(f.type)
                    if (f.optional) append(" optional")
                    f.values?.let { append(" enum ").append(it.joinToString(", ", "[", "]")) }
                    append('\n')
                }
            }
        }

        /**
         * Every class reachable from [types], as [Shape]s keyed by [Shape.key],
         * and the names of the types whose shape the descriptor cannot give.
         */
        fun shapes(types: List<KType>): Pair<Map<String, Shape>, Set<String>> {
            val shapes = sortedMapOf<String, Shape>()
            val unreadable = sortedSetOf<String>()
            val seen = mutableSetOf<SerialDescriptor>()

            fun collect(descriptor: SerialDescriptor, key: String?) {
                val name = descriptor.serialName.removeSuffix("?")
                if (isOpaque(name) || !seen.add(descriptor)) return
                val kind = descriptor.kind
                if (kind is PrimitiveKind || kind is SerialKind.ENUM) return
                if (kind == StructureKind.CLASS || kind == StructureKind.OBJECT) {
                    if (descriptor.elementsCount == 0 && kind == StructureKind.CLASS) unreadable += name
                    val fields = linkedMapOf<String, Shape.Field>()
                    for (i in 0 until descriptor.elementsCount) {
                        val values = descriptor.getElementAnnotations(i)
                            .filterIsInstance<io.ktor.openapi.JsonSchema.Enum>().firstOrNull()?.value?.toList()
                        fields[descriptor.getElementName(i)] =
                            Shape.Field(typeName(descriptor.getElementDescriptor(i)), descriptor.isElementOptional(i), values)
                    }
                    Shape(key ?: name, fields).let { shapes[it.key] = it }
                } else if (kind != StructureKind.LIST && kind != StructureKind.MAP) {
                    // Contextual, polymorphic or hand-built: nothing to read.
                    unreadable += name
                }
                descriptor.elementDescriptors.forEach { collect(it, null) }
            }
            for (type in types) {
                val descriptor = serializer(type).descriptor
                // A generic class is one name with a shape per type argument;
                // each is keyed by the arguments, so adding a field to it is
                // a change to that shape, not a new one.
                val arguments = type.arguments.mapNotNull { it.type }
                val key = if (arguments.isEmpty() || descriptor.kind != StructureKind.CLASS) null
                else descriptor.serialName + arguments.joinToString(", ", "<", ">") { serializer(it).descriptor.serialName }
                collect(descriptor, key)
            }
            return shapes to unreadable
        }

        /** The types file: the entry points, then every class shape. */
        fun describeTypes(types: List<KType>): String {
            val out = StringBuilder("# Entry points\n")
            for (type in types) {
                out.append(type.toString().replace("dev.tracedown.", "")).append(" = ")
                    .append(typeName(serializer(type).descriptor)).append('\n')
            }
            shapes(types).first.values.forEach { out.append('\n').append(it.render()) }
            return out.toString()
        }

        /**
         * Reads the class shapes back out of a types file (any line endings).
         * A class with more than one block — it gained fields after it was
         * first pinned — is every field of all of them, the earliest block's
         * word standing for a field two of them name.
         */
        fun parseShapes(text: String): Map<String, Shape> {
            val normalized = text.replace("\r\n", "\n")
            val blocks = normalized.substringAfter("\n\n", "").split("\n\n").filter { it.isNotBlank() }
            val shapes = linkedMapOf<String, Shape>()
            for (block in blocks) {
                val lines = block.trim('\n').lines()
                val fields = linkedMapOf<String, Shape.Field>()
                for (line in lines.drop(1)) {
                    val (field, described) = line.trim().split(": ", limit = 2)
                    val values = described.substringAfter(" enum [", "").takeIf { it.isNotEmpty() }
                        ?.removeSuffix("]")?.split(", ")
                    val rest = described.substringBefore(" enum [")
                    val optional = rest.endsWith(" optional")
                    fields[field] = Shape.Field(rest.removeSuffix(" optional"), optional, values)
                }
                val key = lines.first()
                // The earliest block's word stands for a field two of them
                // name; a later block can add the field's values to it.
                val earlier = shapes[key]?.fields ?: emptyMap()
                // Values accumulate: a later block may pin a set the earlier did
                // not, or widen one (append-only, as everything here).
                val merged = fields + earlier.mapValues { (name, f) ->
                    val later = fields[name]?.values
                    when {
                        later == null -> f
                        f.values == null -> f.copy(values = later)
                        else -> f.copy(values = (f.values + later).distinct())
                    }
                }
                shapes[key] = Shape(key, merged)
            }
            return shapes
        }

        /**
         * One line per endpoint under [PublicApi.V1] in [root], sorted: the
         * route, and for this module's endpoints what the baseline pins of
         * them — operation id, success status, query parameters (`!` when
         * required), request type and response type.
         */
        fun operationLines(root: RoutingNode): List<String> = publicRoutes(root).map { route ->
            val (method, path) = route.split(' ', limit = 2)
            val operation = PublicApiOperations.find(HttpMethod.parse(method), path.removePrefix(PublicApi.V1))
                ?: return@map route
            // `name!` when required, `name(a|b)` when held to a set of values.
            val query = operation.query.joinToString(" ", "[", "]") { q ->
                (if (q.required) "${q.name}!" else q.name) + (q.values?.joinToString("|", "(", ")") ?: "")
            }
            fun typeOf(type: KType?) = type?.toString()?.replace("dev.tracedown.", "") ?: "-"
            "$route ${operation.operationId} ${operation.status.value} $query -> ${typeOf(operation.request)} / ${typeOf(operation.response)}"
        }

        /** A JSON value of any shape: named, never expanded. */
        private fun isOpaque(serialName: String) = serialName.startsWith("kotlinx.serialization.json.")

        private fun typeName(descriptor: SerialDescriptor): String {
            val nullable = if (descriptor.isNullable) "?" else ""
            val name = descriptor.serialName.removeSuffix("?")
            val shape = when {
                isOpaque(name) -> name.substringAfterLast('.')
                descriptor.kind == StructureKind.LIST -> "List<${typeName(descriptor.getElementDescriptor(0))}>"
                descriptor.kind == StructureKind.MAP ->
                    "Map<${typeName(descriptor.getElementDescriptor(0))}, ${typeName(descriptor.getElementDescriptor(1))}>"
                descriptor.kind is SerialKind.ENUM -> "enum $name ${(0 until descriptor.elementsCount).map(descriptor::getElementName)}"
                else -> name
            }
            return shape + nullable
        }

        fun baseline(name: String): String =
            PublicApiContractTest::class.java.classLoader.getResource(name)?.readText()
                ?: fail("The v1 baseline $name is missing from src/test/resources")

        /** Writes the current state under `build/` for review, and returns where. */
        fun writeCurrent(name: String, text: String): Path {
            val written = Path.of("build", "public-api-contract", name)
            Files.createDirectories(written.parent)
            Files.writeString(written, text)
            return written
        }

        /**
         * The baseline route lines [current] no longer keeps: a route that
         * went, or one whose operation id, status or types changed, or that
         * lost a query parameter, or requires one it did not. A route
         * [current] adds is no change, and neither is an optional query
         * parameter it adds to one.
         */
        fun routeChanges(baseline: List<String>, current: List<String>): List<String> {
            val now = current.map(::parseRouteLine)
            return baseline.filterNot { line ->
                val was = parseRouteLine(line)
                now.any { it.keeps(was) }
            }
        }

        /** A route line taken apart: everything but the query parameters, and the parameters by name. */
        /** One query parameter of a route line: whether it is required, and the values it takes when pinned. */
        private data class Param(val required: Boolean, val values: Set<String>?)

        private data class RouteLine(val head: String, val tail: String, val parameters: Map<String, Param>) {
            /**
             * Whether this line keeps every promise of [was]: the same
             * parameters, none newly required, and every value [was] pinned
             * still taken (a parameter may take more).
             */
            fun keeps(was: RouteLine): Boolean =
                head == was.head && tail == was.tail &&
                    was.parameters.all { (name, p) ->
                        val now = parameters[name]
                        now != null && now.required == p.required &&
                            (p.values == null || (now.values != null && now.values.containsAll(p.values)))
                    } &&
                    parameters.filterKeys { it !in was.parameters }.values.none { it.required }
        }

        private fun parseRouteLine(line: String): RouteLine {
            val open = line.indexOf(" [")
            val close = line.indexOf("] ", open)
            if (open < 0 || close < 0) return RouteLine(line, "", emptyMap())
            val parameters = line.substring(open + 2, close).split(' ').filter { it.isNotBlank() }.associate { token ->
                val values = token.substringAfter('(', "").takeIf { it.isNotEmpty() }?.removeSuffix(")")?.split('|')?.toSet()
                val name = token.substringBefore('(')
                name.removeSuffix("!") to Param(name.endsWith("!"), values)
            }
            return RouteLine(line.substring(0, open), line.substring(close + 1), parameters)
        }

        /** Every way [current] moves away from [baseline] (see the class KDoc), as readable lines. */
        fun breaks(baseline: Map<String, Shape>, current: Map<String, Shape>, requestClasses: Set<String>): List<String> {
            val out = mutableListOf<String>()
            for ((key, was) in baseline) {
                val now = current[key]
                if (now == null) {
                    out += "${was.name} (${key}) is gone"
                    continue
                }
                for ((field, f) in was.fields) {
                    val g = now.fields[field]
                    when {
                        g == null -> out += "${was.name}.$field was removed"
                        g.type != f.type -> out += "${was.name}.$field changed type: ${f.type} -> ${g.type}"
                        f.values != null && !(g.values ?: emptyList()).containsAll(f.values) ->
                            out += "${was.name}.$field lost values: ${f.values - (g.values ?: emptyList()).toSet()}"
                        f.optional && !g.optional && was.name in requestClasses ->
                            out += "${was.name}.$field became required in a request"
                    }
                }
                if (was.name in requestClasses) {
                    for ((field, g) in now.fields) {
                        if (field !in was.fields && !g.optional) out += "${was.name}.$field is a new required request field"
                    }
                }
            }
            return out
        }
    }

    @BeforeEach
    fun clearHostRoutes() = PublicApi.clearAll()

    @AfterEach
    fun clearAfter() = PublicApi.clearAll()

    @Test
    fun `every baseline route is still mounted`() = testApplication {
        lateinit var root: Route
        application {
            routing {
                root = this
                publicApiRoutes()
            }
        }
        startApplication()
        val current = operationLines(root as RoutingNode)
        val written = writeCurrent(ROUTES_FILE, current.joinToString("\n", postfix = "\n"))
        val pinned = baseline(ROUTES_FILE).replace("\r\n", "\n").lines().filter { it.isNotBlank() }
        assertTrue(pinned.isNotEmpty(), "The routes baseline is empty")
        val changed = routeChanges(pinned, current)
        assertTrue(changed.isEmpty(), "Baseline routes gone or changed:\n${changed.joinToString("\n")}\n$DELIBERATE (Current routes: $written)")
    }

    @Test
    fun `every baseline type keeps its shape`() {
        val text = describeTypes(PUBLIC_TYPES)
        val written = writeCurrent(TYPES_FILE, text)
        val requestClasses = shapes(REQUEST_TYPES).first.values.map { it.name }.toSet()
        val pinned = parseShapes(baseline(TYPES_FILE))
        assertTrue(pinned.isNotEmpty(), "The types baseline is empty")
        val problems = breaks(pinned, shapes(PUBLIC_TYPES).first, requestClasses)
        assertTrue(problems.isEmpty(), problems.joinToString("\n", postfix = "\n") + "$DELIBERATE (Current shapes: $written)")
    }

    @Test
    fun `a type whose shape the descriptor cannot give is pinned by a sample`() {
        val unreadable = shapes(PUBLIC_TYPES).second
        val unpinned = unreadable - SAMPLES.keys
        assertTrue(unpinned.isEmpty(), "No sample pins the serialized shape of $unpinned. $DELIBERATE")
        for ((name, sample) in SAMPLES) {
            val (encode, golden) = sample
            kotlin.test.assertEquals(golden, encode(), "The serialized shape of $name changed. $DELIBERATE")
        }
    }

    @Test
    fun `the route check refuses a removed or changed route and passes an added one`() {
        val line = "GET /api/public/v1/things listThings 200 [page pageSize] -> - / Page<Thing>"
        val baseline = listOf(line)
        assertEquals(emptyList<String>(), routeChanges(baseline, listOf(line, "POST /api/public/v1/things createThing 200 [] -> New / Thing")))
        assertEquals(baseline, routeChanges(baseline, emptyList()), "a removed route")
        for (changed in listOf(
            line.replace("listThings", "getThings"),
            line.replace(" 200 ", " 202 "),
            line.replace("[page pageSize]", "[page pageSize kind!]"),
            line.replace("[page pageSize]", "[page! pageSize]"),
            line.replace("[page pageSize]", "[pageSize]"),
            line.replace("Page<Thing>", "List<Thing>"),
            line.replace("-> -", "-> Filter"),
        )) {
            assertEquals(baseline, routeChanges(baseline, listOf(changed)), "changed: $changed")
        }
        // An optional parameter added is kept; so is the line that pinned it,
        // beside the one before it.
        val grown = line.replace("[page pageSize]", "[since page pageSize]")
        assertEquals(emptyList<String>(), routeChanges(baseline + grown, listOf(grown)))
        assertEquals(listOf(grown), routeChanges(baseline + grown, listOf(line)), "the parameter went again")
    }

    @Test
    fun `the baseline check refuses what breaks a client and passes what does not`() {
        val baseline = mapOf(
            "R" to Shape("R", mapOf("a" to Shape.Field("kotlin.String", false), "b" to Shape.Field("kotlin.Int", true))),
            "Q" to Shape("Q", mapOf("x" to Shape.Field("kotlin.String", false))),
        )
        fun current(r: Map<String, Shape.Field>, q: Map<String, Shape.Field>) = mapOf("R" to Shape("R", r), "Q" to Shape("Q", q))
        val req = setOf("R")
        val keep = baseline.getValue("R").fields
        // Additions that a client written against the baseline does not notice.
        kotlin.test.assertEquals(
            emptyList(),
            breaks(baseline, current(keep + ("c" to Shape.Field("kotlin.String", true)), mapOf("x" to Shape.Field("kotlin.String", false), "y" to Shape.Field("kotlin.Int", false))), req),
        )
        // Removed, retyped, newly required.
        assertTrue(breaks(baseline, current(keep - "a", baseline.getValue("Q").fields), req).single().contains("removed"))
        assertTrue(breaks(baseline, current(keep + ("a" to Shape.Field("kotlin.String?", false)), baseline.getValue("Q").fields), req).single().contains("type"))
        assertTrue(breaks(baseline, current(keep + ("b" to Shape.Field("kotlin.Int", false)), baseline.getValue("Q").fields), req).single().contains("required"))
        assertTrue(breaks(baseline, current(keep + ("n" to Shape.Field("kotlin.Int", false)), baseline.getValue("Q").fields), req).single().contains("new required"))
    }

    @Test
    fun `a type pinned in two blocks is held to both`() {
        val text = "# Entry points\nA = A\n\nA\n  a: kotlin.String\n\nA\n  a: kotlin.String\n  b: kotlin.Int\n"
        val pinned = parseShapes(text)
        assertEquals(listOf("a", "b"), pinned.getValue("A").fields.keys.sorted())
        val lost = breaks(pinned, mapOf("A" to Shape("A", mapOf("a" to Shape.Field("kotlin.String", false)))), emptySet())
        assertTrue(lost.single().contains("A.b was removed"), lost.toString())
    }

    @Test
    fun `everything the API answers today is pinned in the baseline`() = testApplication {
        // The baseline is only of use if it is kept up with: a route, a field
        // or a set of values the API has and the baseline does not is a
        // promise nobody holds it to. Append it (see PublicApiOperations).
        lateinit var root: Route
        application {
            routing {
                root = this
                publicApiRoutes()
            }
        }
        startApplication()
        val pinnedRoutes = baseline(ROUTES_FILE).replace("\r\n", "\n").lines().filter { it.isNotBlank() }.toSet()
        val unpinnedRoutes = operationLines(root as RoutingNode).filter { it !in pinnedRoutes }
        assertTrue(unpinnedRoutes.isEmpty(), "Routes not in the baseline:\n${unpinnedRoutes.joinToString("\n")}")

        val pinned = parseShapes(baseline(TYPES_FILE))
        val unpinned = shapes(PUBLIC_TYPES).first.values.flatMap { shape ->
            val was = pinned[shape.key] ?: return@flatMap listOf("${shape.key} is not pinned")
            shape.fields.mapNotNull { (name, f) ->
                if (was.fields[name] == f) null else "${shape.key}.$name is pinned as ${was.fields[name]}, is $f"
            }
        }
        assertTrue(unpinned.isEmpty(), "Not in the baseline:\n${unpinned.joinToString("\n")}")
    }

    @Test
    fun `a value taken out of a pinned set is a break`() {
        val baseline = mapOf("A" to Shape("A", mapOf("s" to Shape.Field("kotlin.String", false, listOf("a", "b")))))
        assertEquals(emptyList<String>(), breaks(baseline, mapOf("A" to Shape("A", mapOf("s" to Shape.Field("kotlin.String", false, listOf("a", "b", "c"))))), emptySet()))
        assertTrue(breaks(baseline, mapOf("A" to Shape("A", mapOf("s" to Shape.Field("kotlin.String", false, listOf("a"))))), emptySet()).single().contains("lost values"))
        val text = "# Entry points\nA = A\n\nA\n  s: kotlin.String\n\nA\n  s: kotlin.String enum [a, b]\n\nA\n  s: kotlin.String enum [a, b, c]\n"
        assertEquals(listOf("a", "b", "c"), parseShapes(text).getValue("A").fields.getValue("s").values, "a widened set is pinned whole")
        // A query parameter's values: more is fine, fewer is a break.
        val line = "GET /api/public/v1/things listThings 200 [kind(a|b) page] -> - / Page<Thing>"
        assertEquals(emptyList<String>(), routeChanges(listOf(line), listOf(line.replace("kind(a|b)", "kind(a|b|c)"))))
        assertEquals(listOf(line), routeChanges(listOf(line), listOf(line.replace("kind(a|b)", "kind(a)"))))
    }
}
