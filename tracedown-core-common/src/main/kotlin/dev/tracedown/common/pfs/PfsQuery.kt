package dev.tracedown.common.pfs

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

/**
 * Applies full PFS pipeline: filters → count → sort → paginate.
 * Returns the paginated query and the total count (before pagination).
 */
fun Query.applyPfs(params: PfsParams): Pair<Query, Long> {
    applyFilters(params)
    val total = this.count()
    applySorters(params)
    this.limit(params.limit).offset(params.offset)
    return this to total
}

/** Applies PFS filters to the query. Mutates in place. */
fun Query.applyFilters(params: PfsParams): Query {
    for (filter in params.filters) {
        val col = TableRegistry.resolveColumn(filter.table, filter.column)
        this.andWhere { buildFilterOp(col, filter) }
    }
    return this
}

/** Applies PFS sorters to the query. Mutates in place. */
fun Query.applySorters(params: PfsParams): Query {
    for (sorter in params.sorters) {
        val col = TableRegistry.resolveColumn(sorter.table, sorter.column)
        val order = if (sorter.order == PfsSortOrder.asc) SortOrder.ASC else SortOrder.DESC
        this.orderBy(col to order)
    }
    return this
}

/**
 * Paginates an in-memory list. Used for endpoints where post-query permission
 * filtering makes SQL-level COUNT inaccurate.
 */
fun <T> List<T>.toPage(params: PfsParams): Page<T> {
    val total = this.size.toLong()
    val start = params.offset.toInt().coerceAtMost(this.size)
    val end = (start + params.limit).coerceAtMost(this.size)
    val items = if (start < this.size) this.subList(start, end) else emptyList()
    return Page(items = items, total = total, page = params.page, pageSize = params.pageSize)
}

// ── Filter builder ──

@Suppress("UNCHECKED_CAST")
private fun buildFilterOp(col: Column<*>, filter: PfsFilter): Op<Boolean> {
    // Null checks are type-agnostic — handle before the type dispatch.
    when (filter.operator) {
        FilterOperator.isNull -> return IsNullOp(col)
        FilterOperator.notNull -> return IsNotNullOp(col)
        else -> {}
    }
    val colType = col.columnType
    return when {
        colType is VarCharColumnType || colType is TextColumnType -> {
            buildStringFilterOp(col as Column<String>, filter)
        }
        colType.sqlType().startsWith("UUID", ignoreCase = true) -> {
            buildUuidFilterOp(col as Column<UUID>, filter)
        }
        colType is BooleanColumnType -> {
            val typedCol = col as Column<Boolean>
            val value = filter.value.toBoolean()
            when (filter.operator) {
                FilterOperator.eq -> typedCol eq value
                FilterOperator.neq -> typedCol neq value
                else -> throw IllegalArgumentException("Operator ${filter.operator} not supported for boolean columns")
            }
        }
        colType is IntegerColumnType -> {
            buildComparableFilterOp(col as Column<Int>, filter) { it.toInt() }
        }
        colType is ShortColumnType -> {
            buildComparableFilterOp(col as Column<Short>, filter) { it.toShort() }
        }
        colType is LongColumnType -> {
            buildComparableFilterOp(col as Column<Long>, filter) { it.toLong() }
        }
        colType is JavaInstantColumnType -> {
            buildComparableFilterOp(col as Column<Instant>, filter) { Instant.parse(it) }
        }
        else -> {
            // Fallback: cast to text and do string comparison
            buildStringFilterOp(col.castTo(VarCharColumnType(256)), filter)
        }
    }
}

private fun buildStringFilterOp(col: ExpressionWithColumnType<String>, filter: PfsFilter): Op<Boolean> {
    val value = filter.value
    val ic = filter.ignoreCase
    val expr: ExpressionWithColumnType<String> = if (ic) col.lowerCase() else col
    val cmpValue = if (ic) value.lowercase() else value

    return when (filter.operator) {
        FilterOperator.eq -> if (value.isEmpty()) (expr eq "") or (IsNullOp(expr)) else expr eq cmpValue
        FilterOperator.neq -> if (value.isEmpty()) (expr neq "") and (IsNotNullOp(expr)) else expr neq cmpValue
        FilterOperator.like -> expr like "%${escapeLike(cmpValue)}%"
        FilterOperator.notLike -> expr notLike "%${escapeLike(cmpValue)}%"
        FilterOperator.greater -> expr greater cmpValue
        FilterOperator.less -> expr less cmpValue
        FilterOperator.greaterEq -> expr greaterEq cmpValue
        FilterOperator.lessEq -> expr lessEq cmpValue
        FilterOperator.inList -> expr inList value.split(",").map { if (ic) it.trim().lowercase() else it.trim() }
        FilterOperator.isNull, FilterOperator.notNull ->
            throw IllegalStateException("null-check operators are handled before type dispatch")
    }
}

private fun buildUuidFilterOp(col: Column<UUID>, filter: PfsFilter): Op<Boolean> {
    return when (filter.operator) {
        FilterOperator.eq -> col eq UUID.fromString(filter.value)
        FilterOperator.neq -> col neq UUID.fromString(filter.value)
        FilterOperator.inList -> col inList filter.value.split(",").map { UUID.fromString(it.trim()) }
        else -> throw IllegalArgumentException("Operator ${filter.operator} not supported for UUID columns")
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Comparable<T>> buildComparableFilterOp(
    col: Column<T>,
    filter: PfsFilter,
    parse: (String) -> T,
): Op<Boolean> {
    val value = parse(filter.value)
    return when (filter.operator) {
        FilterOperator.eq -> col eq value
        FilterOperator.neq -> col neq value
        FilterOperator.greater -> col greater value
        FilterOperator.less -> col less value
        FilterOperator.greaterEq -> col greaterEq value
        FilterOperator.lessEq -> col lessEq value
        FilterOperator.inList -> col inList filter.value.split(",").map { parse(it.trim()) }
        FilterOperator.like, FilterOperator.notLike ->
            throw IllegalArgumentException("like/notLike not supported for ${col.columnType.sqlType()} columns")
        FilterOperator.isNull, FilterOperator.notNull ->
            throw IllegalStateException("null-check operators are handled before type dispatch")
    }
}

/** Escapes SQL LIKE wildcard characters in user input. */
private fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
