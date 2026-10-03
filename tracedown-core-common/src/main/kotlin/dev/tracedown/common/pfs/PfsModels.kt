package dev.tracedown.common.pfs

import kotlinx.serialization.Serializable

@Serializable
enum class FilterOperator {
    eq, neq, greater, less, greaterEq, lessEq, like, notLike, inList, isNull, notNull
}

@Serializable
enum class PfsSortOrder {
    asc, desc
}

@Serializable
data class PfsFilter(
    val table: String,
    val column: String,
    val operator: FilterOperator,
    val value: String = "",
    val ignoreCase: Boolean = false,
)

@Serializable
data class PfsSorter(
    val table: String,
    val column: String,
    val order: PfsSortOrder = PfsSortOrder.asc,
)

data class PfsParams(
    val page: Int = 1,
    val pageSize: Int = 50,
    val filters: List<PfsFilter> = emptyList(),
    val sorters: List<PfsSorter> = emptyList(),
) {
    // Offset must stride by the same clamped size as limit, or pages past the
    // cap silently skip rows (limit 100 rows, offset advancing by the raw size).
    val limit: Int get() = pageSize.coerceIn(1, 100)
    // In Long before the multiplication: a large page number times the page
    // size overflows an Int into a negative offset.
    val offset: Long get() = (page - 1).coerceAtLeast(0).toLong() * limit
}

@Serializable
data class Page<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)
