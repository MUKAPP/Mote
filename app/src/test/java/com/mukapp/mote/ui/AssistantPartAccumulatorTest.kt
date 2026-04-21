package com.mukapp.mote.ui

import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantPartAccumulatorTest {

    @Test
    fun markdownAccumulatorPreservesWhitespaceOnlyDelta() {
        val parts = mutableListOf<AssistantPart>()

        appendMarkdown(parts, "# 一级标题")
        appendMarkdown(parts, "\n\n")
        appendMarkdown(parts, "## 二级标题")

        assertEquals(1, parts.size)
        val part = parts.single() as AssistantMarkdownPart
        assertEquals("# 一级标题\n\n## 二级标题", part.text)
    }

    @Test
    fun thinkingAccumulatorPreservesWhitespaceOnlyDelta() {
        val parts = mutableListOf<AssistantPart>()

        appendThinking(parts, "第一行")
        appendThinking(parts, "\n")
        appendThinking(parts, "第二行")

        assertEquals(1, parts.size)
        val part = parts.single() as AssistantThinkingPart
        assertEquals("第一行\n第二行", part.text)
    }

    private fun appendMarkdown(parts: MutableList<AssistantPart>, delta: String) {
        if (delta.isEmpty()) {
            return
        }
        val lastPart = parts.lastOrNull()
        if (lastPart is AssistantMarkdownPart) {
            parts[parts.lastIndex] = lastPart.copy(text = lastPart.text + delta)
        } else {
            parts += AssistantMarkdownPart(text = delta)
        }
    }

    private fun appendThinking(parts: MutableList<AssistantPart>, delta: String) {
        if (delta.isEmpty()) {
            return
        }
        val lastPart = parts.lastOrNull()
        if (lastPart is AssistantThinkingPart) {
            parts[parts.lastIndex] = lastPart.copy(text = lastPart.text + delta)
        } else {
            parts += AssistantThinkingPart(text = delta)
        }
    }
}
