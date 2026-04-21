package com.mukapp.mote.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStreamingParserTest {

    @Test
    fun streamingInlineKeepsUnclosedBoldAsPlainTextTail() {
        val elements = InlineParser().parse("前缀 **粗体", isStreaming = true)

        assertEquals(
            listOf(
                InlineElement.Text("前缀 "),
                InlineElement.Text("**粗体")
            ),
            elements
        )
    }

    @Test
    fun streamingInlinePreservesCompletedBoldAfterLiteralAsterisk() {
        val elements = InlineParser().parse("2 * 3 和 **粗体**", isStreaming = true)

        assertEquals(2, elements.size)
        assertEquals(InlineElement.Text("2 * 3 和 "), elements[0])
        assertEquals(
            InlineElement.Bold(listOf(InlineElement.Text("粗体"))),
            elements[1]
        )
    }

    @Test
    fun streamingInlineKeepsUnfinishedLinkTailAsPlainText() {
        val elements = InlineParser().parse("查看 [文档](https://example.com", isStreaming = true)

        assertEquals(
            listOf(
                InlineElement.Text("查看 "),
                InlineElement.Text("[文档](https://example.com")
            ),
            elements
        )
    }

    @Test
    fun streamingSetextHeadingWaitsForCompleteTailLine() {
        val streamingBlocks = BlockParser().parse("标题\n---", isStreaming = true)

        assertEquals(1, streamingBlocks.size)
        assertTrue(streamingBlocks[0] is MdBlock.Paragraph)
        assertEquals("标题\n---", (streamingBlocks[0] as MdBlock.Paragraph).text)

        val staticBlocks = BlockParser().parse("标题\n---", isStreaming = false)
        assertEquals(1, staticBlocks.size)
        assertTrue(staticBlocks[0] is MdBlock.Heading)
    }

    @Test
    fun streamingTableWaitsForCompleteSeparatorLine() {
        val streamingBlocks = BlockParser().parse("| A |\n| --- |", isStreaming = true)

        assertEquals(1, streamingBlocks.size)
        assertTrue(streamingBlocks[0] is MdBlock.Paragraph)
        assertEquals("| A |\n| --- |", (streamingBlocks[0] as MdBlock.Paragraph).text)

        val staticBlocks = BlockParser().parse("| A |\n| --- |", isStreaming = false)
        assertEquals(1, staticBlocks.size)
        assertTrue(staticBlocks[0] is MdBlock.Table)
    }
}
