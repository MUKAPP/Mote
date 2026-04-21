package com.mukapp.mote.ui.markdown

import android.content.Context
import android.text.Spanned

class StreamingMarkdownRenderer(context: Context) {

    private val blockParser = BlockParser()
    private val spannedBuilder = SpannedBuilder(context)

    private var lastText: String = ""

    /** 设置表格绘制的可用宽度（像素） */
    var tableAvailableWidth: Int
        get() = spannedBuilder.tableAvailableWidth
        set(value) { spannedBuilder.tableAvailableWidth = value }

    fun setMarkdown(text: String): Spanned {
        lastText = text
        val blocks = blockParser.parse(text, isStreaming = true)
        val linkDefs = collectLinkDefs(text, isStreaming = true)
        return spannedBuilder.build(blocks, isStreaming = true, linkDefs = linkDefs)
    }

    fun finalize(): Spanned {
        val blocks = blockParser.parse(lastText, isStreaming = false)
        val linkDefs = collectLinkDefs(lastText, isStreaming = false)
        return spannedBuilder.build(blocks, isStreaming = false, linkDefs = linkDefs)
    }

    fun renderStatic(text: String): Spanned {
        lastText = text
        val blocks = blockParser.parse(text, isStreaming = false)
        val linkDefs = collectLinkDefs(text, isStreaming = false)
        return spannedBuilder.build(blocks, isStreaming = false, linkDefs = linkDefs)
    }

    fun reset() {
        lastText = ""
    }

    private fun collectLinkDefs(text: String, isStreaming: Boolean): Map<String, Pair<String, String>> {
        val defs = mutableMapOf<String, Pair<String, String>>()
        val lines = text.lines()
        val skipLastLine = isStreaming && text.isNotEmpty() && !text.endsWith("\n") && !text.endsWith("\r")
        val lastIndex = lines.lastIndex
        for ((index, line) in lines.withIndex()) {
            if (skipLastLine && index == lastIndex) {
                continue
            }
            val match = LINK_DEF_REGEX.matchEntire(line.trim())
            if (match != null) {
                val id = match.groupValues[1].lowercase()
                val url = match.groupValues[2]
                val title = match.groupValues[3]
                if (!defs.containsKey(id)) {
                    defs[id] = Pair(url, title)
                }
            }
        }
        return defs
    }

    companion object {
        private val LINK_DEF_REGEX = Regex("^\\[([^\\]]+)]:\\s+(\\S+)(?:\\s+[\"'](.+?)[\"'])?\\s*$")
    }
}
