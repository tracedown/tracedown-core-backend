package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.pfs.PfsFilter
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.PfsSorter
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses PFS query parameters from an HTTP request.
 *
 * Query params:
 * - `page` (int, default 1) — 1-indexed page number
 * - `pageSize` (int, default 50, max 100) — items per page
 * - `filters` (JSON array) — `[{"table":"...","column":"...","operator":"eq","value":"..."}]`
 * - `sorters` (JSON array) — `[{"table":"...","column":"...","order":"asc"}]`
 *
 * Returns default PfsParams when no params are present (backward compatible).
 */
fun parsePfsParams(call: ApplicationCall): PfsParams {
    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
    val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 50

    val filters = call.request.queryParameters["filters"]?.let { raw ->
        try {
            json.decodeFromString<List<PfsFilter>>(raw)
        } catch (e: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    } ?: emptyList()

    val sorters = call.request.queryParameters["sorters"]?.let { raw ->
        try {
            json.decodeFromString<List<PfsSorter>>(raw)
        } catch (e: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    } ?: emptyList()

    if (page < 1) throw BadRequestException(ErrorCodes.FIELD_INVALID)
    if (pageSize < 1 || pageSize > 1000) throw BadRequestException(ErrorCodes.FIELD_INVALID)

    return PfsParams(page = page, pageSize = pageSize, filters = filters, sorters = sorters)
}

/** The largest page the key-authenticated API serves. */
const val PUBLIC_MAX_PAGE_SIZE = 100

/**
 * Paging for the key-authenticated API: `page` (default 1) and `pageSize`
 * (default 50, at most [PUBLIC_MAX_PAGE_SIZE]) and nothing else.
 *
 * `filters` and `sorters` are refused (400 `field_invalid`) rather than
 * ignored: they name internal tables and columns, which that API does not make
 * part of its contract, and a caller who sent one would otherwise get an
 * unfiltered list back believing it filtered. A value that is not a number, or
 * out of range, is refused the same way — every refusal names the parameter in
 * `details.field` (and the limit in `details.max` where there is one). A page
 * so far out that its offset would not fit in an Int is refused rather than
 * answered with an empty page that took a scan to produce.
 */
fun publicPaging(call: ApplicationCall): PfsParams {
    val params = call.request.queryParameters
    for (name in listOf("filters", "sorters")) {
        if (params.contains(name)) throw fieldError(name)
    }
    val pageSize = params["pageSize"]?.let { it.toIntOrNull() ?: throw fieldError("pageSize") { put("max", JsonPrimitive(PUBLIC_MAX_PAGE_SIZE)) } } ?: 50
    if (pageSize < 1 || pageSize > PUBLIC_MAX_PAGE_SIZE) {
        throw fieldError("pageSize") { put("max", JsonPrimitive(PUBLIC_MAX_PAGE_SIZE)) }
    }
    val maxPage = Int.MAX_VALUE / pageSize
    val page = params["page"]?.let { it.toIntOrNull() ?: throw fieldError("page") { put("max", JsonPrimitive(maxPage)) } } ?: 1
    if (page < 1 || page > maxPage) throw fieldError("page") { put("max", JsonPrimitive(maxPage)) }
    return PfsParams(page = page, pageSize = pageSize)
}
