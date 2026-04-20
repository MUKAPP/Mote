package com.mukapp.mote.ui.markdown

class InlineParser {

    private var linkDefinitions: Map<String, Pair<String, String>> = emptyMap()

    fun parse(text: String, isStreaming: Boolean = false, linkDefs: Map<String, Pair<String, String>> = emptyMap()): List<InlineElement> {
        if (text.isEmpty()) return emptyList()
        linkDefinitions = linkDefs

        val elements = mutableListOf<InlineElement>()
        var i = 0
        var textStart = 0

        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE_CHARS) {
                if (i > textStart) {
                    elements.add(InlineElement.Text(text.substring(textStart, i)))
                }
                elements.add(InlineElement.Text(text[i + 1].toString()))
                i += 2
                textStart = i
                continue
            }

            if (text[i] == '`') {
                val backtickCount = getRunCount(text, i, '`')
                val marker = "`".repeat(backtickCount)
                val closePos = findCloseMarker(text, i + backtickCount, marker)
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.InlineCode(text.substring(i + backtickCount, closePos)))
                    i = closePos + backtickCount
                    textStart = i
                    continue
                }
            }

            if (i + 2 < text.length && text[i] == '*' && text[i + 1] == '*' && text[i + 2] == '*') {
                val closePos = findCloseMarker(text, i + 3, "***")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    val inner = parse(text.substring(i + 3, closePos), isStreaming, linkDefs)
                    elements.add(InlineElement.Bold(listOf(InlineElement.Italic(inner))))
                    i = closePos + 3
                    textStart = i
                    continue
                }
            }

            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val closePos = findCloseMarker(text, i + 2, "**")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Bold(parse(text.substring(i + 2, closePos), isStreaming, linkDefs)))
                    i = closePos + 2
                    textStart = i
                    continue
                }
            }

            if (text[i] == '~' && i + 1 < text.length && text[i + 1] == '~') {
                val closePos = findCloseMarker(text, i + 2, "~~")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Strikethrough(parse(text.substring(i + 2, closePos), isStreaming, linkDefs)))
                    i = closePos + 2
                    textStart = i
                    continue
                }
            }

            if (text[i] == '^') {
                val closePos = findCloseMarker(text, i + 1, "^")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Superscript(parse(text.substring(i + 1, closePos), isStreaming, linkDefs)))
                    i = closePos + 1
                    textStart = i
                    continue
                }
            }

            if (text[i] == '~') {
                val closePos = findCloseMarker(text, i + 1, "~")
                if (closePos >= 0 && (i + 1 >= text.length || text[i + 1] != '~')) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Subscript(parse(text.substring(i + 1, closePos), isStreaming, linkDefs)))
                    i = closePos + 1
                    textStart = i
                    continue
                }
            }

            if (text[i] == '*' && (i == 0 || text[i - 1] != '*') && i + 1 < text.length && text[i + 1] != '*') {
                val closePos = findCloseMarker(text, i + 1, "*")
                if (closePos >= 0 && (closePos + 1 >= text.length || text[closePos + 1] != '*')) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Italic(parse(text.substring(i + 1, closePos), isStreaming, linkDefs)))
                    i = closePos + 1
                    textStart = i
                    continue
                }
            }

            if (text[i] == '<') {
                val autoLinkMatch = matchAutoLinkAt(text, i)
                if (autoLinkMatch != null) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.AutoLink(autoLinkMatch))
                    val closeAngle = text.indexOf('>', i + 1)
                    i = closeAngle + 1
                    textStart = i
                    continue
                }
            }

            val linkMatch = matchLinkAt(text, i)
            if (linkMatch != null) {
                if (i > textStart) {
                    elements.add(InlineElement.Text(text.substring(textStart, i)))
                }
                elements.add(InlineElement.Link(linkMatch.first, linkMatch.second))
                i = i + linkMatch.third
                textStart = i
                continue
            }

            val refLinkMatch = matchRefLinkAt(text, i)
            if (refLinkMatch != null) {
                if (i > textStart) {
                    elements.add(InlineElement.Text(text.substring(textStart, i)))
                }
                elements.add(InlineElement.Link(refLinkMatch.first, refLinkMatch.second))
                i = i + refLinkMatch.third
                textStart = i
                continue
            }

            i++
        }

        if (textStart < text.length) {
            elements.add(InlineElement.Text(text.substring(textStart)))
        }

        return elements
    }

    private fun matchAutoLinkAt(text: String, pos: Int): String? {
        if (text[pos] != '<') return null
        val closeAngle = text.indexOf('>', pos + 1)
        if (closeAngle < 0) return null
        val content = text.substring(pos + 1, closeAngle)
        if (content.startsWith("http://") || content.startsWith("https://") ||
            content.startsWith("ftp://") || content.startsWith("mailto:")
        ) {
            if (content.none { it.isWhitespace() }) return content
        }
        return null
    }

    private fun matchLinkAt(text: String, pos: Int): Triple<String, String, Int>? {
        if (text[pos] != '[') return null
        val rest = text.substring(pos)
        val match = LINK_REGEX.find(rest) ?: return null
        if (match.range.first != 0) return null
        val linkText = match.groupValues[1]
        val linkUrl = match.groupValues[2]
        return Triple(linkText, linkUrl, match.range.last + 1)
    }

    private fun matchRefLinkAt(text: String, pos: Int): Triple<String, String, Int>? {
        if (text[pos] != '[') return null
        val rest = text.substring(pos)
        val match = REF_LINK_REGEX.find(rest) ?: return null
        if (match.range.first != 0) return null
        val linkText = match.groupValues[1]
        val refId = match.groupValues[2].ifBlank { linkText }.lowercase()
        val def = linkDefinitions[refId] ?: return null
        return Triple(linkText, def.first, match.range.last + 1)
    }

    private fun getRunCount(text: String, start: Int, char: Char): Int {
        var count = 0
        while (start + count < text.length && text[start + count] == char) count++
        return count
    }

    private fun findCloseMarker(text: String, searchStart: Int, marker: String): Int {
        var i = searchStart
        while (i <= text.length - marker.length) {
            if (text.substring(i, i + marker.length) == marker) return i
            i++
        }
        return -1
    }

    companion object {
        private val LINK_REGEX = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
        private val REF_LINK_REGEX = Regex("\\[([^\\]]+)\\](?:\\[([^\\]]*)\\])")
        private val ESCAPABLE_CHARS = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~', '>', '^')
    }
}
