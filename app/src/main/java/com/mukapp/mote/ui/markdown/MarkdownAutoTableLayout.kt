package com.mukapp.mote.ui.markdown

import kotlin.math.max
import kotlin.math.min

internal object MarkdownAutoTableLayout {

    data class ColumnIntrinsic(
        val min: Float,
        val max: Float,
        val intrinsicPercent: Float = 0f,
        val constrained: Boolean = false,
        val hasOriginatingCell: Boolean = true
    )

    data class Result(
        val columnWidths: FloatArray,
        val tableWidth: Float,
        val gridMin: Float,
        val gridMax: Float
    )

    private enum class ColumnType {
        AUTO,
        PIXEL,
        PERCENT
    }

    private data class Column(
        val min: Float,
        val max: Float,
        val intrinsicPercent: Float,
        val constrained: Boolean,
        val hasOriginatingCell: Boolean,
        val type: ColumnType
    )

    fun compute(
        columns: List<ColumnIntrinsic>,
        parentWidth: Float?,
        fillParentWhenPossible: Boolean = false
    ): Result {
        val normalizedColumns = columns.map { column ->
            val minWidth = column.min.coerceAtLeast(0f)
            val maxWidth = max(column.max, minWidth)
            val percent = column.intrinsicPercent.coerceAtLeast(0f)
            val type = when {
                percent > 0f -> ColumnType.PERCENT
                column.constrained -> ColumnType.PIXEL
                else -> ColumnType.AUTO
            }
            Column(
                min = minWidth,
                max = maxWidth,
                intrinsicPercent = percent,
                constrained = column.constrained,
                hasOriginatingCell = column.hasOriginatingCell,
                type = type
            )
        }

        if (normalizedColumns.isEmpty()) {
            return Result(FloatArray(0), tableWidth = 0f, gridMin = 0f, gridMax = 0f)
        }

        val gridMin = normalizedColumns.sumOf { it.min.toDouble() }.toFloat()
        val gridMax = normalizedColumns.sumOf { it.max.toDouble() }.toFloat()
        val availableWidth = parentWidth?.takeIf { it > 0f }
        val tableWidth = when {
            availableWidth == null -> gridMax
            fillParentWhenPossible -> max(availableWidth, gridMin)
            else -> max(min(gridMax, availableWidth), gridMin)
        }

        val columnWidths = computeColumnWidths(normalizedColumns, tableWidth)
        return Result(
            columnWidths = columnWidths,
            tableWidth = columnWidths.sum(),
            gridMin = gridMin,
            gridMax = gridMax
        )
    }

    private fun computeColumnWidths(columns: List<Column>, assignableWidth: Float): FloatArray {
        val count = columns.size
        val result = FloatArray(count)
        if (count == 0) return result

        val g0 = FloatArray(count)
        val g1 = FloatArray(count)
        val g2 = FloatArray(count)
        val g3 = FloatArray(count)

        for (index in 0 until count) {
            val column = columns[index]
            val percentWidth = max(column.intrinsicPercent * assignableWidth, column.min)

            g0[index] = column.min
            g1[index] = if (column.type == ColumnType.PERCENT) percentWidth else column.min
            g2[index] = when {
                column.type == ColumnType.PERCENT -> percentWidth
                column.constrained -> column.max
                else -> column.min
            }
            g3[index] = if (column.type == ColumnType.PERCENT) percentWidth else column.max
        }

        val guesses = arrayOf(g0, g1, g2, g3)
        val sums = FloatArray(guesses.size) { index -> guesses[index].sum() }

        if (assignableWidth <= sums[0]) {
            return g0.copyOf()
        }

        if (assignableWidth <= sums[3]) {
            for (index in 0 until guesses.lastIndex) {
                val fromSum = sums[index]
                val toSum = sums[index + 1]
                if (assignableWidth > toSum) continue

                val range = toSum - fromSum
                val ratio = if (range <= 0f) 1f else (assignableWidth - fromSum) / range
                for (columnIndex in 0 until count) {
                    result[columnIndex] = guesses[index][columnIndex] +
                        (guesses[index + 1][columnIndex] - guesses[index][columnIndex]) * ratio
                }
                return result
            }
            return g3.copyOf()
        }

        for (index in 0 until count) {
            result[index] = g3[index]
        }
        distributeExcessWidth(result, columns, assignableWidth - sums[3])
        return result
    }

    private fun distributeExcessWidth(widths: FloatArray, columns: List<Column>, extra: Float) {
        if (extra <= 0f || columns.isEmpty()) return

        fun growByWeight(indices: List<Int>, weightOf: (Column) -> Float): Boolean {
            if (indices.isEmpty()) return false
            val totalWeight = indices.sumOf { weightOf(columns[it]).toDouble() }.toFloat()
            if (totalWeight <= 0f) return false
            for (index in indices) {
                widths[index] += extra * weightOf(columns[index]) / totalWeight
            }
            return true
        }

        fun growEqually(indices: List<Int>): Boolean {
            if (indices.isEmpty()) return false
            val each = extra / indices.size
            for (index in indices) {
                widths[index] += each
            }
            return true
        }

        val autoNonPercentWithContent = columns.indices.filter { index ->
            val column = columns[index]
            !column.constrained &&
                column.intrinsicPercent == 0f &&
                column.hasOriginatingCell &&
                column.max > 0f
        }
        if (growByWeight(autoNonPercentWithContent) { it.max }) return

        val autoNonPercent = columns.indices.filter { index ->
            val column = columns[index]
            !column.constrained &&
                column.intrinsicPercent == 0f &&
                column.hasOriginatingCell
        }
        if (growEqually(autoNonPercent)) return

        val constrainedNonPercent = columns.indices.filter { index ->
            val column = columns[index]
            column.constrained &&
                column.intrinsicPercent == 0f &&
                column.hasOriginatingCell &&
                column.max > 0f
        }
        if (growByWeight(constrainedNonPercent) { it.max }) return

        val percentColumns = columns.indices.filter { index ->
            val column = columns[index]
            column.intrinsicPercent > 0f && column.hasOriginatingCell
        }
        if (growByWeight(percentColumns) { it.intrinsicPercent }) return

        val columnsWithOriginatingCells = columns.indices.filter { columns[it].hasOriginatingCell }
        if (growEqually(columnsWithOriginatingCells)) return

        growEqually(columns.indices.toList())
    }
}
