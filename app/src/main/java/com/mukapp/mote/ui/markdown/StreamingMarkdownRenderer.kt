package com.mukapp.mote.ui.markdown

import android.content.Context
import android.text.Spanned

class StreamingMarkdownRenderer(context: Context) {

    private val blockParser = BlockParser()
    private val spannedBuilder = SpannedBuilder(context)

    private var lastText: String = ""

    fun setMarkdown(text: String): Spanned {
        lastText = text
        val blocks = blockParser.parse(text, isStreaming = true)
        val linkDefs = collectLinkDefs(text)
        return spannedBuilder.build(blocks, isStreaming = true, linkDefs = linkDefs)
    }

    fun finalize(): Spanned {
        val blocks = blockParser.parse(lastText, isStreaming = false)
        val linkDefs = collectLinkDefs(lastText)
        return spannedBuilder.build(blocks, isStreaming = false, linkDefs = linkDefs)
    }

    fun renderStatic(text: String): Spanned {
        lastText = text
        val blocks = blockParser.parse(text, isStreaming = false)
        val linkDefs = collectLinkDefs(text)
        return spannedBuilder.build(blocks, isStreaming = false, linkDefs = linkDefs)
    }

    fun reset() {
        lastText = ""
    }

    private fun collectLinkDefs(text: String): Map<String, Pair<String, String>> {
        val defs = mutableMapOf<String, Pair<String, String>>()
        for (line in text.lines()) {
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
