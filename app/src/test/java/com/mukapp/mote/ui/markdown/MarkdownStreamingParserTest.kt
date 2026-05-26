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
    fun staticSetextHeadingSupportsLevelOneAndLevelTwo() {
        val blocks = BlockParser().parse(
            "一级标题\n===\n\n二级标题\n---",
            isStreaming = false
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        val levelOne = blocks[0] as MdBlock.Heading
        assertEquals(1, levelOne.level)
        assertEquals("一级标题", levelOne.text)
        assertTrue(blocks[1] is MdBlock.Heading)
        val levelTwo = blocks[1] as MdBlock.Heading
        assertEquals(2, levelTwo.level)
        assertEquals("二级标题", levelTwo.text)
    }

    @Test
    fun blankLineBeforeUnderlineDoesNotBecomeSetextHeading() {
        val blocks = BlockParser().parse(
            "普通段落\n\n---",
            isStreaming = false
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals("普通段落", (blocks[0] as MdBlock.Paragraph).text)
        assertTrue(blocks[1] is MdBlock.HorizontalRule)
    }

    @Test
    fun unorderedListSupportsNestedItems() {
        val blocks = BlockParser().parse(
            "- 第一项\n- 第二项\n  - 子项 1\n  - 子项 2\n* 第三项\n+ 第四项",
            isStreaming = false
        )

        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.UnorderedList
        assertEquals(4, list.items.size)
        assertEquals(1, list.items[0].size)
        assertEquals("第一项", (list.items[0][0] as MdBlock.Paragraph).text)
        assertEquals(2, list.items[1].size)
        assertEquals("第二项", (list.items[1][0] as MdBlock.Paragraph).text)

        val nested = list.items[1][1] as MdBlock.UnorderedList
        assertEquals(2, nested.items.size)
        assertEquals("子项 1", (nested.items[0][0] as MdBlock.Paragraph).text)
        assertEquals("子项 2", (nested.items[1][0] as MdBlock.Paragraph).text)
    }

    @Test
    fun orderedListSupportsNestedItems() {
        val blocks = BlockParser().parse(
            "1. 第一项\n2. 第二项\n   1. 子项 1\n   2. 子项 2",
            isStreaming = false
        )

        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.OrderedList
        assertEquals(2, list.items.size)
        assertEquals(1, list.items[0].size)
        assertEquals("第一项", (list.items[0][0] as MdBlock.Paragraph).text)
        assertEquals(2, list.items[1].size)
        assertEquals("第二项", (list.items[1][0] as MdBlock.Paragraph).text)

        val nested = list.items[1][1] as MdBlock.OrderedList
        assertEquals(2, nested.items.size)
        assertEquals("子项 1", (nested.items[0][0] as MdBlock.Paragraph).text)
        assertEquals("子项 2", (nested.items[1][0] as MdBlock.Paragraph).text)
    }

    @Test
    fun inlineMathParsesBackslashParenBeforeEscapes() {
        val elements = InlineParser().parse("公式 \\(E = mc^2\\) 完成", isStreaming = false)

        assertEquals(
            listOf(
                InlineElement.Text("公式 "),
                InlineElement.Math("E = mc^2", "\\(", display = false),
                InlineElement.Text(" 完成")
            ),
            elements
        )
    }

    @Test
    fun streamingInlineMathKeepsUnclosedTailAsPlainText() {
        val elements = InlineParser().parse("公式 \\(E = mc", isStreaming = true)

        assertEquals(
            listOf(
                InlineElement.Text("公式 "),
                InlineElement.Text("\\(E = mc")
            ),
            elements
        )
    }

    @Test
    fun inlineCodeDoesNotParseMath() {
        val elements = InlineParser().parse("代码 `\\(x\\)`", isStreaming = false)

        assertEquals(
            listOf(
                InlineElement.Text("代码 "),
                InlineElement.InlineCode("\\(x\\)")
            ),
            elements
        )
    }

    @Test
    fun blockMathParsesDollarDelimiter() {
        val blocks = BlockParser().parse("$$\na^2 + b^2 = c^2\n$$", isStreaming = false)

        assertEquals(1, blocks.size)
        val mathBlock = blocks[0] as MdBlock.MathBlock
        assertEquals("a^2 + b^2 = c^2", mathBlock.formula)
        assertEquals("$$", mathBlock.delimiter)
        assertEquals(true, mathBlock.closed)
    }

    @Test
    fun streamingBlockMathKeepsUnclosedMathBlock() {
        val blocks = BlockParser().parse("$$\na^2 + b^2", isStreaming = true)

        assertEquals(1, blocks.size)
        val mathBlock = blocks[0] as MdBlock.MathBlock
        assertEquals("a^2 + b^2", mathBlock.formula)
        assertEquals(false, mathBlock.closed)
    }

    @Test
    fun blockMathParsesBackslashBracketDelimiter() {
        val blocks = BlockParser().parse("\\[\n\\int_0^1 x dx\n\\]", isStreaming = false)

        assertEquals(1, blocks.size)
        val mathBlock = blocks[0] as MdBlock.MathBlock
        assertEquals("\\int_0^1 x dx", mathBlock.formula)
        assertEquals("\\[", mathBlock.delimiter)
        assertEquals(true, mathBlock.closed)
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
