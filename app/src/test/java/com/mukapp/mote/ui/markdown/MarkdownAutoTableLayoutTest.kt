package com.mukapp.mote.ui.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAutoTableLayoutTest {

    @Test
    fun usesMaxContentWhenParentCanContainIdealWidth() {
        val result = MarkdownAutoTableLayout.compute(
            columns = listOf(
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 10f, max = 50f),
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 20f, max = 30f)
            ),
            parentWidth = 200f
        )

        assertEquals(80f, result.tableWidth, 0.001f)
        assertArrayEquals(floatArrayOf(50f, 30f), result.columnWidths, 0.001f)
    }

    @Test
    fun interpolatesBetweenMinAndMaxWhenParentIsBetweenIntrinsicWidths() {
        val result = MarkdownAutoTableLayout.compute(
            columns = listOf(
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 10f, max = 50f),
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 20f, max = 30f)
            ),
            parentWidth = 60f
        )

        assertEquals(60f, result.tableWidth, 0.001f)
        assertArrayEquals(floatArrayOf(34f, 26f), result.columnWidths, 0.001f)
    }

    @Test
    fun usesMinContentWhenParentIsTooNarrow() {
        val result = MarkdownAutoTableLayout.compute(
            columns = listOf(
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 10f, max = 50f),
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 20f, max = 30f)
            ),
            parentWidth = 20f
        )

        assertEquals(30f, result.tableWidth, 0.001f)
        assertArrayEquals(floatArrayOf(10f, 20f), result.columnWidths, 0.001f)
    }

    @Test
    fun distributesExtraWidthWhenFillParentIsRequested() {
        val result = MarkdownAutoTableLayout.compute(
            columns = listOf(
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 10f, max = 50f),
                MarkdownAutoTableLayout.ColumnIntrinsic(min = 20f, max = 30f)
            ),
            parentWidth = 100f,
            fillParentWhenPossible = true
        )

        assertEquals(100f, result.tableWidth, 0.001f)
        assertEquals(100f, result.columnWidths.sum(), 0.001f)
        assertTrue(result.columnWidths[0] > 50f)
        assertTrue(result.columnWidths[1] > 30f)
    }
}
