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
            val inlineMath = matchInlineMathAt(text, i, isStreaming)
            if (inlineMath != null) {
                appendText(elements, text, textStart, i)
                if (inlineMath.closed) {
                    elements.add(InlineElement.Math(inlineMath.formula, inlineMath.delimiter, display = false))
                    i += inlineMath.consumed
                    textStart = i
                    continue
                } else {
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

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
                if (isStreaming && canOpenDelimited(text, i, backtickCount)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

            if (i + 2 < text.length && text[i] == '*' && text[i + 1] == '*' && text[i + 2] == '*') {
                val closePos = findCloseMarker(text, i + 3, "***")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    val inner = parse(text.substring(i + 3, closePos), isStreaming = false, linkDefs = linkDefs)
                    elements.add(InlineElement.Bold(listOf(InlineElement.Italic(inner))))
                    i = closePos + 3
                    textStart = i
                    continue
                }
                if (isStreaming && canOpenDelimited(text, i, 3)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val closePos = findCloseMarker(text, i + 2, "**")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Bold(parse(text.substring(i + 2, closePos), isStreaming = false, linkDefs = linkDefs)))
                    i = closePos + 2
                    textStart = i
                    continue
                }
                if (isStreaming && canOpenDelimited(text, i, 2)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

            if (text[i] == '~' && i + 1 < text.length && text[i + 1] == '~') {
                val closePos = findCloseMarker(text, i + 2, "~~")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Strikethrough(parse(text.substring(i + 2, closePos), isStreaming = false, linkDefs = linkDefs)))
                    i = closePos + 2
                    textStart = i
                    continue
                }
                if (isStreaming && canOpenDelimited(text, i, 2)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

            if (text[i] == '^') {
                val closePos = findCloseMarker(text, i + 1, "^")
                if (closePos >= 0) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Superscript(parse(text.substring(i + 1, closePos), isStreaming = false, linkDefs = linkDefs)))
                    i = closePos + 1
                    textStart = i
                    continue
                }
                if (isStreaming && canOpenDelimited(text, i, 1)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

            if (text[i] == '~') {
                val closePos = findCloseMarker(text, i + 1, "~")
                if (closePos >= 0 && (i + 1 >= text.length || text[i + 1] != '~')) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Subscript(parse(text.substring(i + 1, closePos), isStreaming = false, linkDefs = linkDefs)))
                    i = closePos + 1
                    textStart = i
                    continue
                }
                if (isStreaming && canOpenDelimited(text, i, 1)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
                }
            }

            if (text[i] == '*' && (i == 0 || text[i - 1] != '*') && i + 1 < text.length && text[i + 1] != '*') {
                val closePos = findCloseMarker(text, i + 1, "*")
                if (closePos >= 0 && (closePos + 1 >= text.length || text[closePos + 1] != '*')) {
                    if (i > textStart) {
                        elements.add(InlineElement.Text(text.substring(textStart, i)))
                    }
                    elements.add(InlineElement.Italic(parse(text.substring(i + 1, closePos), isStreaming = false, linkDefs = linkDefs)))
                    i = closePos + 1
                    textStart = i
                    continue
                }
                if (isStreaming && canOpenDelimited(text, i, 1)) {
                    appendText(elements, text, textStart, i)
                    elements.add(InlineElement.Text(text.substring(i)))
                    return elements
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
            if (isStreaming && text[i] == '[' && looksLikeUnfinishedLink(text, i)) {
                appendText(elements, text, textStart, i)
                elements.add(InlineElement.Text(text.substring(i)))
                return elements
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

    private fun matchInlineMathAt(text: String, pos: Int, isStreaming: Boolean): InlineMathResult? {
        if (pos >= text.length) return null
        if (text.regionMatches(pos, "\\(", 0, 2) && !isEscaped(text, pos)) {
            val closePos = findCloseDelimiter(text, pos + 2, "\\)")
            if (closePos >= 0) {
                val formula = text.substring(pos + 2, closePos)
                if (formula.isNotBlank()) {
                    return InlineMathResult(
                        formula = formula,
                        delimiter = "\\(",
                        consumed = closePos + 2 - pos,
                        closed = true
                    )
                }
            }
            return if (isStreaming && looksLikeUnfinishedBackslashMath(text, pos, "\\(")) {
                InlineMathResult("", "\\(", text.length - pos, closed = false)
            } else {
                null
            }
        }

        if (text[pos] == '$' && canOpenDollarMath(text, pos)) {
            val closePos = findDollarMathClose(text, pos + 1)
            if (closePos >= 0) {
                val formula = text.substring(pos + 1, closePos)
                if (formula.isNotBlank()) {
                    return InlineMathResult(
                        formula = formula,
                        delimiter = "$",
                        consumed = closePos + 1 - pos,
                        closed = true
                    )
                }
            }
            return if (isStreaming) {
                InlineMathResult("", "$", text.length - pos, closed = false)
            } else {
                null
            }
        }

        return null
    }

    private fun looksLikeUnfinishedBackslashMath(text: String, pos: Int, delimiter: String): Boolean {
        if (!text.regionMatches(pos, delimiter, 0, delimiter.length)) return false
        val contentStart = pos + delimiter.length
        return contentStart < text.length && text.substring(contentStart).isNotBlank()
    }

    private fun findCloseDelimiter(text: String, searchStart: Int, delimiter: String): Int {
        var index = searchStart
        while (index <= text.length - delimiter.length) {
            if (text.regionMatches(index, delimiter, 0, delimiter.length) && !isEscaped(text, index)) {
                return index
            }
            index++
        }
        return -1
    }

    private fun canOpenDollarMath(text: String, pos: Int): Boolean {
        if (text[pos] != '$' || isEscaped(text, pos)) return false
        if (pos + 1 >= text.length || text[pos + 1] == '$') return false
        if (text[pos + 1].isWhitespace()) return false
        return true
    }

    private fun findDollarMathClose(text: String, searchStart: Int): Int {
        var index = searchStart
        while (index < text.length) {
            if (text[index] == '$' && !isEscaped(text, index)) {
                val previous = text.getOrNull(index - 1)
                val next = text.getOrNull(index + 1)
                if (previous != null && !previous.isWhitespace() && next != '$' && next?.isDigit() != true) {
                    return index
                }
            }
            if (text[index] == '\n' && text.getOrNull(index + 1) == '\n') {
                return -1
            }
            index++
        }
        return -1
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
        val closeBracket = findUnescapedChar(text, ']', pos + 1)
        if (closeBracket < 0 || closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') {
            return null
        }
        val closeParen = findInlineLinkUrlEnd(text, closeBracket + 2) ?: return null
        val linkText = text.substring(pos + 1, closeBracket)
        val linkUrl = unescapeBasic(text.substring(closeBracket + 2, closeParen).trim())
        if (linkText.isEmpty() || linkUrl.isEmpty() || linkUrl.any { it.isWhitespace() }) {
            return null
        }
        return Triple(linkText, linkUrl, closeParen - pos + 1)
    }

    private fun findInlineLinkUrlEnd(text: String, start: Int): Int? {
        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\\' -> {
                    index += 2
                    continue
                }
                '(' -> depth++
                ')' -> {
                    if (depth == 0) return index
                    depth--
                }
            }
            index++
        }
        return null
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
            if (text.regionMatches(i, marker, 0, marker.length) && !isEscaped(text, i) && isExactMarkerRun(text, i, marker)) {
                return i
            }
            i++
        }
        return -1
    }

    private fun appendText(
        elements: MutableList<InlineElement>,
        text: String,
        start: Int,
        endExclusive: Int
    ) {
        if (endExclusive > start) {
            elements.add(InlineElement.Text(text.substring(start, endExclusive)))
        }
    }

    private fun canOpenDelimited(text: String, pos: Int, markerLength: Int): Boolean {
        val nextIndex = pos + markerLength
        if (nextIndex >= text.length) return false
        val nextChar = text[nextIndex]
        if (nextChar.isWhitespace()) return false
        val prevChar = text.getOrNull(pos - 1)
        return prevChar == null || prevChar.isWhitespace() || isPunctuation(prevChar)
    }

    private fun looksLikeUnfinishedLink(text: String, pos: Int): Boolean {
        val closingBracket = findUnescapedChar(text, ']', pos + 1)
        if (closingBracket < 0) return false
        val nextIndex = closingBracket + 1
        if (nextIndex >= text.length) return false
        return when (text[nextIndex]) {
            '(' -> findUnescapedChar(text, ')', nextIndex + 1) < 0
            '[' -> findUnescapedChar(text, ']', nextIndex + 1) < 0
            else -> false
        }
    }

    private fun findUnescapedChar(text: String, target: Char, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] == target && !isEscaped(text, index)) {
                return index
            }
            index++
        }
        return -1
    }

    private fun unescapeBasic(text: String): String {
        if ('\\' !in text) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE_CHARS) {
                builder.append(text[index + 1])
                index += 2
            } else {
                builder.append(char)
                index++
            }
        }
        return builder.toString()
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            backslashCount++
            cursor--
        }
        return backslashCount % 2 == 1
    }

    private fun isExactMarkerRun(text: String, index: Int, marker: String): Boolean {
        val markerChar = marker[0]
        if (markerChar != '*' && markerChar != '~' && markerChar != '`') {
            return true
        }
        if (index > 0 && text[index - 1] == markerChar) return false
        val after = index + marker.length
        if (after < text.length && text[after] == markerChar) return false
        return true
    }

    private fun isPunctuation(char: Char): Boolean {
        return !char.isLetterOrDigit() && !char.isWhitespace()
    }

    private data class InlineMathResult(
        val formula: String,
        val delimiter: String,
        val consumed: Int,
        val closed: Boolean
    )

    companion object {
        private val REF_LINK_REGEX = Regex("\\[([^\\]]+)\\](?:\\[([^\\]]*)\\])")
        private val ESCAPABLE_CHARS = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~', '>', '^')
    }
}
