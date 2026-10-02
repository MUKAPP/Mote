package com.mukapp.mote.ui.markdown

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailFormatterTest {
    @Test
    fun blankArgumentsHaveNoPreview() {
        for (raw in listOf("", " \r\n\t")) {
            val preview = ToolDetailFormatter.arguments(raw)

            assertEquals("", preview.text)
            assertEquals(0, preview.totalChars)
            assertFalse(preview.isTruncated)
        }
    }

    @Test
    fun smallArgumentsAreFormattedWithoutTruncation() {
        val raw = "{\"text\":\"你好\"}"
        val formatted = JSONObject(raw).toString(2)

        val preview = ToolDetailFormatter.arguments(raw)

        assertEquals(formatted, preview.text)
        assertEquals(formatted.length, preview.totalChars)
        assertNotEquals(raw, preview.text)
        assertFalse(preview.isTruncated)
    }

    @Test
    fun argumentsOfExactly5000CharsAreFormattedThenLimited() {
        val raw = jsonWithTotalChars(5_000)
        val formatted = JSONObject(raw).toString(2)
        assertEquals(5_000, raw.length)
        assertTrue(formatted.length > 5_000)

        val preview = ToolDetailFormatter.arguments(raw)

        assertEquals(formatted.take(5_000), preview.text)
        assertEquals(formatted.length, preview.totalChars)
        assertTrue(preview.isTruncated)
    }

    @Test
    fun argumentsOfExactly5001CharsUseRawPreview() {
        val raw = jsonWithTotalChars(5_001)
        assertEquals(5_001, raw.length)

        val preview = ToolDetailFormatter.arguments(raw)

        assertEquals(raw.take(5_000), preview.text)
        assertEquals(5_001, preview.totalChars)
        assertTrue(preview.isTruncated)
    }

    @Test
    fun formattingExpansionMarksSmallJsonAsTruncated() {
        val raw = "{\"values\":[" + List(1_000) { "0" }.joinToString(",") + "]}"
        val formatted = JSONObject(raw).toString(2)
        assertTrue(raw.length < 5_000)
        assertTrue(formatted.length > 5_000)

        val preview = ToolDetailFormatter.arguments(raw)

        assertEquals(formatted.take(5_000), preview.text)
        assertEquals(formatted.length, preview.totalChars)
        assertTrue(preview.isTruncated)
    }

    @Test
    fun largeJsonPreservesRawPrefixAndTotalLength() {
        val raw = jsonWithTotalChars(100_000)

        val preview = ToolDetailFormatter.arguments(raw)

        assertEquals(raw.take(5_000), preview.text)
        assertEquals(100_000, preview.totalChars)
        assertTrue(preview.isTruncated)
    }

    @Test
    fun invalidJsonFallsBackToBoundedRawPreview() {
        val shortRaw = "  {\"text\":\r\n"
        val longRaw = shortRaw + "无效参数".repeat(3_000)

        val shortPreview = ToolDetailFormatter.arguments(shortRaw)
        val longPreview = ToolDetailFormatter.arguments(longRaw)

        assertEquals(shortRaw, shortPreview.text)
        assertEquals(shortRaw.length, shortPreview.totalChars)
        assertFalse(shortPreview.isTruncated)
        assertEquals(longRaw.take(5_000), longPreview.text)
        assertEquals(longRaw.length, longPreview.totalChars)
        assertTrue(longPreview.isTruncated)
    }

    @Test
    fun resultPreviewPreservesRawTextAndHasItsOwnBoundary() {
        val rawJson = " {\"text\":\"原文\"}\r\n "
        val exact = rawJson + "结".repeat(5_000 - rawJson.length)
        val over = exact + "尾"

        val shortPreview = ToolDetailFormatter.result(rawJson)
        val exactPreview = ToolDetailFormatter.result(exact)
        val overPreview = ToolDetailFormatter.result(over)
        val argumentsPreview = ToolDetailFormatter.arguments("{}")

        assertEquals(rawJson, shortPreview.text)
        assertEquals(rawJson.length, shortPreview.totalChars)
        assertFalse(shortPreview.isTruncated)
        assertEquals(exact, exactPreview.text)
        assertEquals(5_000, exactPreview.totalChars)
        assertFalse(exactPreview.isTruncated)
        assertEquals(exact, overPreview.text)
        assertEquals(5_001, overPreview.totalChars)
        assertTrue(overPreview.isTruncated)
        assertFalse(argumentsPreview.isTruncated)
    }

    @Test
    fun fullTextPreservesRawArgumentsAndResultIncludingEscapesAndWhitespace() {
        val arguments = " \r\n" +
            """{ "text" : "空格  \"引号\" \\路径 \u4e2d" }""" + "\r\n "
        val result = "  第一行\r\n反斜线\\与引号\" 😀\r\n".repeat(4_000) + "末尾  "
        assertNotEquals(arguments, ToolDetailFormatter.arguments(arguments).text)
        assertTrue(ToolDetailFormatter.result(result).isTruncated)

        val fullText = ToolDetailFormatter.fullText(arguments, result, "参数", "结果")

        assertEquals("参数\n" + arguments + "\n\n结果\n" + result, fullText)
    }

    @Test
    fun fullTextDoesNotTruncateLargeArgumentsOrResult() {
        val arguments = jsonWithTotalChars(5_001)
        val result = "完整结果\r\n😀 ".repeat(10_000)

        val fullText = ToolDetailFormatter.fullText(arguments, result, "Parameters", "Result")

        assertEquals("Parameters\n" + arguments + "\n\nResult\n" + result, fullText)
    }

    @Test
    fun fullTextOmitsBlankArgumentsButPreservesBlankResult() {
        val result = " \r\n\t "
        for (arguments in listOf("", " \r\n\t")) {
            val fullText = ToolDetailFormatter.fullText(arguments, result, "参数", "结果")

            assertEquals("结果\n" + result, fullText)
        }
    }

    private fun jsonWithTotalChars(totalChars: Int): String =
        "{\"text\":\"" + "x".repeat(totalChars - 11) + "\"}"
}
