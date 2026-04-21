package com.mukapp.mote.ui.markdown

class BlockParser {

    fun parse(text: String, isStreaming: Boolean = false): List<MdBlock> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val tailLineComplete = isTailLineComplete(text)
        val linkDefs = collectLinkDefinitions(lines, isStreaming, tailLineComplete)
        return parseBlocks(lines, 0, lines.size, isStreaming, linkDefs, tailLineComplete)
    }

    /**
     * 使用外部提供的 linkDefs 解析，避免重复收集链接定义。
     */
    fun parse(text: String, isStreaming: Boolean, externalLinkDefs: Map<String, Pair<String, String>>): List<MdBlock> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val tailLineComplete = isTailLineComplete(text)
        return parseBlocks(lines, 0, lines.size, isStreaming, externalLinkDefs, tailLineComplete)
    }

    private fun collectLinkDefinitions(
        lines: List<String>,
        isStreaming: Boolean,
        tailLineComplete: Boolean
    ): Map<String, Pair<String, String>> {
        val defs = mutableMapOf<String, Pair<String, String>>()
        val lastIndex = lines.lastIndex
        for ((index, line) in lines.withIndex()) {
            if (isStreaming && !tailLineComplete && index == lastIndex) {
                continue
            }
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || !trimmed.contains("]:")) continue
            val match = LINK_DEF_REGEX.matchEntire(trimmed) ?: continue
            val id = match.groupValues[1].lowercase()
            val url = match.groupValues[2]
            val title = match.groupValues[3]
            if (!defs.containsKey(id)) {
                defs[id] = Pair(url, title)
            }
        }
        return defs
    }

    private fun parseBlocks(
        lines: List<String>,
        start: Int,
        end: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        tailLineComplete: Boolean
    ): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        var i = start
        var offset = computeOffset(lines, start)

        while (i < end) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val unstableTailLine = isUnstableTailLine(i, end, isStreaming, tailLineComplete)

            if (trimmed.isEmpty()) {
                offset += line.length + 1
                i++
                continue
            }

            if (!unstableTailLine && isLinkDefLine(trimmed)) {
                offset += line.length + 1
                i++
                continue
            }

            if (trimmed.startsWith("#")) {
                val heading = parseHeading(trimmed, offset, offset + line.length)
                if (heading != null) {
                    blocks.add(heading)
                    offset += line.length + 1
                    i++
                    continue
                }
            }

            val setextHeading = parseSetextHeading(lines, i, blocks, offset, allowTailTransformation = !unstableTailLine)
            if (setextHeading != null) {
                blocks.add(setextHeading.first)
                offset = setextHeading.second
                i = setextHeading.third
                continue
            }

            if (isBacktickFenceStart(trimmed)) {
                val result = parseCodeBlock(lines, i, offset, isStreaming)
                blocks.add(result.block)
                i = result.nextLineIndex
                offset = result.nextOffset
                continue
            }

            val hr = if (unstableTailLine) {
                null
            } else {
                parseHorizontalRule(trimmed, offset, offset + line.length)
            }
            if (hr != null) {
                blocks.add(hr)
                offset += line.length + 1
                i++
                continue
            }

            if (trimmed.startsWith("|") && !unstableTailLine) {
                val table = parseTable(lines, i, offset, isStreaming, tailLineComplete)
                if (table != null) {
                    blocks.add(table.block)
                    i = table.nextLineIndex
                    offset = table.nextOffset
                    continue
                }
            }

            if (trimmed.startsWith(">")) {
                val blockquote = parseBlockquote(lines, i, offset, isStreaming, linkDefs, tailLineComplete)
                if (blockquote != null) {
                    blocks.add(blockquote.block)
                    i = blockquote.nextLineIndex
                    offset = blockquote.nextOffset
                    continue
                }
            }

            if (isTaskListPrefix(trimmed)) {
                val taskList = parseTaskList(lines, i, offset, isStreaming, linkDefs, tailLineComplete)
                if (taskList != null) {
                    blocks.add(taskList.block)
                    i = taskList.nextLineIndex
                    offset = taskList.nextOffset
                    continue
                }
            }

            if (isUnorderedListPrefix(trimmed)) {
                val unorderedList = parseUnorderedList(lines, i, offset, isStreaming, linkDefs, tailLineComplete)
                if (unorderedList != null) {
                    blocks.add(unorderedList.block)
                    i = unorderedList.nextLineIndex
                    offset = unorderedList.nextOffset
                    continue
                }
            }

            if (isOrderedListPrefix(trimmed)) {
                val orderedList = parseOrderedList(lines, i, offset, isStreaming, linkDefs, tailLineComplete)
                if (orderedList != null) {
                    blocks.add(orderedList.block)
                    i = orderedList.nextLineIndex
                    offset = orderedList.nextOffset
                    continue
                }
            }

            val paragraph = parseParagraph(lines, i, offset, isStreaming, linkDefs, tailLineComplete)
            blocks.add(paragraph.block)
            i = paragraph.nextLineIndex
            offset = paragraph.nextOffset
        }

        return blocks
    }

    private fun computeOffset(lines: List<String>, lineIndex: Int): Int {
        var offset = 0
        for (j in 0 until lineIndex) {
            offset += lines[j].length + 1
        }
        return offset
    }

    private fun isLinkDefLine(trimmed: String): Boolean {
        if (!trimmed.startsWith("[") || !trimmed.contains("]:")) return false
        return LINK_DEF_REGEX.matchEntire(trimmed) != null
    }

    private fun isUnorderedListPrefix(trimmed: String): Boolean {
        if (trimmed.length < 2) return false
        val c = trimmed[0]
        if (c != '-' && c != '*' && c != '+') return false
        return trimmed[1] == ' '
    }

    private fun isOrderedListPrefix(trimmed: String): Boolean {
        if (!trimmed[0].isDigit()) return false
        var i = 0
        while (i < trimmed.length && trimmed[i].isDigit()) i++
        if (i >= trimmed.length) return false
        return (trimmed[i] == '.' || trimmed[i] == ')') && i + 1 < trimmed.length && trimmed[i + 1] == ' '
    }

    private fun isTaskListPrefix(trimmed: String): Boolean {
        if (!isUnorderedListPrefix(trimmed)) return false
        val afterMarker = trimmed.substring(2).trimStart()
        return afterMarker.startsWith("[") && afterMarker.length >= 3 &&
            (afterMarker[1] == ' ' || afterMarker[1] == 'x' || afterMarker[1] == 'X') &&
            afterMarker[2] == ']'
    }

    private fun parseHeading(
        trimmed: String,
        startOffset: Int,
        endOffset: Int
    ): MdBlock.Heading? {
        val match = HEADING_REGEX.matchEntire(trimmed) ?: return null
        val level = match.groupValues[1].length
        if (level !in 1..6) return null
        val text = match.groupValues[2].trim()
        return MdBlock.Heading(level, text, startOffset, endOffset)
    }

    private fun parseSetextHeading(
        lines: List<String>,
        i: Int,
        blocks: MutableList<MdBlock>,
        offset: Int,
        allowTailTransformation: Boolean
    ): Triple<MdBlock.Heading, Int, Int>? {
        if (!allowTailTransformation) return null
        val trimmed = lines[i].trimStart()
        val level = isSetextUnderline(trimmed) ?: return null
        if (blocks.isEmpty()) return null
        if (i == 0 || lines[i - 1].isBlank()) return null
        val lastBlock = blocks.last()
        if (lastBlock !is MdBlock.Paragraph) return null
        blocks.removeAt(blocks.lastIndex)
        val heading = MdBlock.Heading(level, lastBlock.text, lastBlock.startOffset, offset + lines[i].length)
        val newOffset = offset + lines[i].length + 1
        return Triple(heading, newOffset, i + 1)
    }

    private fun parseCodeBlock(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean
    ): CodeBlockResult {
        val firstLine = lines[startIndex].trimStart()
        val fenceLength = leadingBacktickCount(firstLine)
        val language = firstLine.substring(fenceLength).trim()
        val codeLines = mutableListOf<String>()
        var offset = startOffset + lines[startIndex].length + 1
        var i = startIndex + 1
        var closed = false

        while (i < lines.size) {
            val line = lines[i]
            val lineTrimmed = line.trim()
            if (isClosingBacktickFence(lineTrimmed, fenceLength)) {
                closed = true
                offset += line.length + 1
                i++
                break
            }
            codeLines.add(line)
            offset += line.length + 1
            i++
        }

        if (!closed && isStreaming) {
            val lastLine = codeLines.lastOrNull()
            if (lastLine != null) {
                val lastTrimmed = lastLine.trim()
                if (isClosingBacktickFence(lastTrimmed, fenceLength)) {
                    codeLines.removeAt(codeLines.lastIndex)
                    closed = true
                    offset -= lastLine.length + 1
                }
            }
        }

        val code = codeLines.joinToString("\n")
        val endOffset = offset - 1
        return CodeBlockResult(MdBlock.CodeBlock(language, code, closed, startOffset, endOffset), i, offset)
    }

    private fun parseHorizontalRule(trimmed: String, startOffset: Int, endOffset: Int): MdBlock.HorizontalRule? {
        if (trimmed.length < 3) return null
        val first = trimmed[0]
        if (first != '-' && first != '*' && first != '_') return null
        if (!trimmed.all { it == first }) return null
        return MdBlock.HorizontalRule(startOffset, endOffset)
    }

    private fun parseTable(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        tailLineComplete: Boolean
    ): TableResult? {
        val firstLine = lines[startIndex].trim()
        if (!firstLine.startsWith("|") || !firstLine.endsWith("|")) return null
        val headerCells = parseTableRow(firstLine)
        if (headerCells.isEmpty()) return null
        if (startIndex + 1 >= lines.size) return null
        if (isUnstableTailLine(startIndex + 1, lines.size, isStreaming, tailLineComplete)) return null
        val separatorLine = lines[startIndex + 1].trim()
        if (!isTableSeparatorRow(separatorLine, headerCells.size)) return null

        val alignments = parseTableAlignments(separatorLine, headerCells.size)
        val headers = headerCells
        val rows = mutableListOf<List<String>>()
        var offset = startOffset
        var i = startIndex

        offset += lines[i].length + 1; i++
        offset += lines[i].length + 1; i++

        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("|")) break
            val rowCells = parseTableRow(line)
            if (rowCells.isEmpty()) break
            rows.add(rowCells)
            offset += lines[i].length + 1; i++
        }

        return TableResult(MdBlock.Table(headers, rows, alignments, startOffset, offset - 1), i, offset)
    }

    private fun parseTableRow(line: String): List<String> {
        var content = line.trim()
        if (content.startsWith("|")) content = content.substring(1)
        if (content.endsWith("|")) content = content.substring(0, content.length - 1)
        return content.split("|").map { it.trim() }
    }

    private fun parseBlockquote(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        tailLineComplete: Boolean
    ): BlockquoteResult? {
        val quoteLines = mutableListOf<String>()
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                val content = if (trimmed.length > 1 && trimmed[1] == ' ') {
                    trimmed.substring(2)
                } else {
                    trimmed.substring(1)
                }
                quoteLines.add(content)
                offset += line.length + 1
                i++
            } else if (trimmed.isEmpty()) {
                break
            } else {
                break
            }
        }

        val children = parseBlocks(quoteLines, 0, quoteLines.size, isStreaming, linkDefs, tailLineComplete)
        val endOffset = offset - 1

        return BlockquoteResult(MdBlock.Blockquote(children, startOffset, endOffset), i, offset)
    }

    private fun parseTaskList(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        tailLineComplete: Boolean
    ): TaskListResult? {
        val firstLine = lines[startIndex].trimStart()
        val match = TASK_LIST_REGEX.matchEntire(firstLine) ?: return null

        val items = mutableListOf<Pair<TaskItem, List<MdBlock>>>()
        val baseIndent = leadingIndentWidth(lines[startIndex])
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            if (leadingIndentWidth(line) != baseIndent) break
            if (!isTaskListPrefix(trimmed)) break
            val itemMatch = TASK_LIST_REGEX.matchEntire(trimmed)
            if (itemMatch != null) {
                val taskItem = TaskItem(
                    checked = itemMatch.groupValues[1] == "x" || itemMatch.groupValues[1] == "X",
                    text = itemMatch.groupValues[2]
                )
                offset += line.length + 1
                i++
                val continuation = collectListItemContinuation(lines, i, offset, baseIndent)
                val childLines = mutableListOf(itemMatch.groupValues[2])
                childLines.addAll(normalizeListContinuationLines(continuation.lines))
                i = continuation.nextLineIndex
                offset = continuation.nextOffset
                val childBlocks = parseBlocks(childLines, 0, childLines.size, isStreaming, linkDefs, tailLineComplete)
                items.add(Pair(taskItem, childBlocks))
            } else {
                break
            }
        }

        val endOffset = offset - 1
        return TaskListResult(MdBlock.TaskList(items, startOffset, endOffset), i, offset)
    }

    private fun parseUnorderedList(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        tailLineComplete: Boolean
    ): UnorderedListResult? {
        val firstLine = lines[startIndex].trimStart()
        val match = UNORDERED_LIST_REGEX.matchEntire(firstLine) ?: return null

        val items = mutableListOf<List<MdBlock>>()
        val baseIndent = leadingIndentWidth(lines[startIndex])
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            if (leadingIndentWidth(line) != baseIndent) break
            if (!isUnorderedListPrefix(trimmed)) break
            val itemMatch = UNORDERED_LIST_REGEX.matchEntire(trimmed)
            if (itemMatch != null) {
                offset += line.length + 1
                i++
                val continuation = collectListItemContinuation(lines, i, offset, baseIndent)
                val childLines = mutableListOf(itemMatch.groupValues[2])
                childLines.addAll(normalizeListContinuationLines(continuation.lines))
                i = continuation.nextLineIndex
                offset = continuation.nextOffset
                val childBlocks = parseBlocks(childLines, 0, childLines.size, isStreaming, linkDefs, tailLineComplete)
                items.add(childBlocks)
            } else {
                break
            }
        }

        val endOffset = offset - 1
        return UnorderedListResult(MdBlock.UnorderedList(items, startOffset, endOffset), i, offset)
    }

    private fun parseOrderedList(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        tailLineComplete: Boolean
    ): OrderedListResult? {
        val firstLine = lines[startIndex].trimStart()
        val match = ORDERED_LIST_REGEX.matchEntire(firstLine) ?: return null

        val items = mutableListOf<List<MdBlock>>()
        val baseIndent = leadingIndentWidth(lines[startIndex])
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            if (leadingIndentWidth(line) != baseIndent) break
            if (!isOrderedListPrefix(trimmed)) break
            val itemMatch = ORDERED_LIST_REGEX.matchEntire(trimmed)
            if (itemMatch != null) {
                offset += line.length + 1
                i++
                val continuation = collectListItemContinuation(lines, i, offset, baseIndent)
                val childLines = mutableListOf(itemMatch.groupValues[2])
                childLines.addAll(normalizeListContinuationLines(continuation.lines))
                i = continuation.nextLineIndex
                offset = continuation.nextOffset
                val childBlocks = parseBlocks(childLines, 0, childLines.size, isStreaming, linkDefs, tailLineComplete)
                items.add(childBlocks)
            } else {
                break
            }
        }

        val endOffset = offset - 1
        return OrderedListResult(MdBlock.OrderedList(items, startOffset, endOffset), i, offset)
    }

    private fun parseParagraph(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        tailLineComplete: Boolean
    ): ParagraphResult {
        val textLines = mutableListOf<String>()
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            val unstableTailLine = isUnstableTailLine(i, lines.size, isStreaming, tailLineComplete)
            if (i > startIndex && (isBlockStart(trimmed) || isSetextUnderline(trimmed) != null)) {
                if (!(unstableTailLine && isTailSensitiveBlockStart(trimmed))) {
                    break
                }
            }

            textLines.add(line)
            offset += line.length + 1
            i++
        }

        val text = textLines.joinToString("\n").trim()
        val endOffset = offset - 1

        return ParagraphResult(MdBlock.Paragraph(text, startOffset, endOffset), i, offset)
    }

    private fun isBlockStart(trimmed: String): Boolean {
        if (trimmed.startsWith("#") && HEADING_PREFIX.containsMatchIn(trimmed)) return true
        if (isSetextUnderline(trimmed) != null) return true
        if (isBacktickFenceStart(trimmed)) return true
        if (trimmed.startsWith(">")) return true
        if (isHorizontalRuleLike(trimmed)) return true
        if (isUnorderedListPrefix(trimmed)) return true
        if (isOrderedListPrefix(trimmed)) return true
        if (isTaskListPrefix(trimmed)) return true
        if (trimmed.startsWith("|") && trimmed.endsWith("|")) return true
        if (trimmed.startsWith("[") && trimmed.contains("]:") && LINK_DEF_PREFIX.containsMatchIn(trimmed)) return true
        return false
    }

    private fun isHorizontalRuleLike(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val first = trimmed[0]
        if (first != '-' && first != '*' && first != '_') return false
        if (trimmed.all { it == first }) return true
        return false
    }

    private fun isTailSensitiveBlockStart(trimmed: String): Boolean {
        if (isSetextUnderline(trimmed) != null) return true
        if (isHorizontalRuleLike(trimmed)) return true
        if (trimmed.startsWith("|")) return true
        if (trimmed.startsWith("[") && trimmed.contains("]:") && LINK_DEF_PREFIX.containsMatchIn(trimmed)) return true
        return false
    }

    private fun isSetextUnderline(trimmed: String): Int? {
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.all { it == '=' } -> 1
            trimmed.all { it == '-' } -> 2
            else -> null
        }
    }

    private fun isBacktickFenceStart(trimmed: String): Boolean {
        val backtickCount = leadingBacktickCount(trimmed)
        if (backtickCount < 3) return false
        if (trimmed.length == backtickCount) return true
        return !trimmed.substring(backtickCount).contains('`')
    }

    private fun isClosingBacktickFence(trimmed: String, requiredLength: Int): Boolean {
        val backtickCount = leadingBacktickCount(trimmed)
        return backtickCount >= requiredLength && backtickCount == trimmed.length
    }

    private fun leadingBacktickCount(text: String): Int {
        var count = 0
        while (count < text.length && text[count] == '`') {
            count++
        }
        return count
    }

    private fun collectListItemContinuation(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        baseIndent: Int
    ): ListItemContinuationResult {
        val continuationLines = mutableListOf<String>()
        var i = startIndex
        var offset = startOffset

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            if (leadingIndentWidth(line) <= baseIndent) break
            continuationLines.add(line)
            offset += line.length + 1
            i++
        }

        return ListItemContinuationResult(continuationLines, i, offset)
    }

    private fun normalizeListContinuationLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val indent = lines
            .filter { it.isNotBlank() }
            .minOfOrNull { leadingIndentWidth(it) }
            ?: 0
        return lines.map { removeLeadingIndent(it, indent) }
    }

    private fun leadingIndentWidth(line: String): Int {
        var indent = 0
        for (ch in line) {
            when (ch) {
                ' ' -> indent++
                '\t' -> indent += 4
                else -> return indent
            }
        }
        return indent
    }

    private fun removeLeadingIndent(line: String, indentWidth: Int): String {
        var remaining = indentWidth
        var index = 0
        while (index < line.length && remaining > 0) {
            when (line[index]) {
                ' ' -> {
                    remaining--
                    index++
                }
                '\t' -> {
                    remaining -= 4
                    index++
                }
                else -> break
            }
        }
        return line.substring(index)
    }

    private fun isTableSeparatorRow(line: String, expectedColumnCount: Int): Boolean {
        if (expectedColumnCount <= 0) return false
        val raw = line.trim()
        if (!raw.contains('-')) return false
        val cells = parseTableRow(raw)
        if (cells.isEmpty() || cells.size != expectedColumnCount) return false
        return cells.all { cell ->
            val trimmed = cell.trim()
            trimmed.isNotEmpty() && TABLE_SEPARATOR_CELL_REGEX.matches(trimmed)
        }
    }

    /** 从分隔行解析各列的对齐方式 */
    private fun parseTableAlignments(separatorLine: String, colCount: Int): List<MdBlock.Alignment> {
        val cells = parseTableRow(separatorLine)
        return List(colCount) { j ->
            if (j < cells.size) {
                val cell = cells[j].trim()
                val left = cell.startsWith(':')
                val right = cell.endsWith(':')
                when {
                    left && right -> MdBlock.Alignment.CENTER
                    right -> MdBlock.Alignment.RIGHT
                    else -> MdBlock.Alignment.LEFT
                }
            } else {
                MdBlock.Alignment.LEFT
            }
        }
    }

    private fun isTailLineComplete(text: String): Boolean {
        return text.isEmpty() || text.endsWith("\n") || text.endsWith("\r")
    }

    private fun isUnstableTailLine(
        lineIndex: Int,
        end: Int,
        isStreaming: Boolean,
        tailLineComplete: Boolean
    ): Boolean {
        return isStreaming && !tailLineComplete && lineIndex == end - 1
    }

    private data class CodeBlockResult(val block: MdBlock.CodeBlock, val nextLineIndex: Int, val nextOffset: Int)
    private data class BlockquoteResult(val block: MdBlock.Blockquote, val nextLineIndex: Int, val nextOffset: Int)
    private data class UnorderedListResult(val block: MdBlock.UnorderedList, val nextLineIndex: Int, val nextOffset: Int)
    private data class OrderedListResult(val block: MdBlock.OrderedList, val nextLineIndex: Int, val nextOffset: Int)
    private data class TaskListResult(val block: MdBlock.TaskList, val nextLineIndex: Int, val nextOffset: Int)
    private data class ListItemContinuationResult(val lines: List<String>, val nextLineIndex: Int, val nextOffset: Int)
    private data class TableResult(val block: MdBlock.Table, val nextLineIndex: Int, val nextOffset: Int)
    private data class ParagraphResult(val block: MdBlock.Paragraph, val nextLineIndex: Int, val nextOffset: Int)

    companion object {
        private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
        private val HEADING_PREFIX = Regex("^#{1,6}\\s")
        private val UNORDERED_LIST_REGEX = Regex("^([-*+])\\s+(.+)$")
        private val ORDERED_LIST_REGEX = Regex("^(\\d+)[.)]\\s+(.+)$")
        private val TASK_LIST_REGEX = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.+)$")
        private val TABLE_SEPARATOR_CELL_REGEX = Regex("^:?-+:?$")
        private val LINK_DEF_REGEX = Regex("^\\[([^\\]]+)]:\\s+(\\S+)(?:\\s+[\"'](.+?)[\"'])?\\s*$")
        private val LINK_DEF_PREFIX = Regex("^\\[[^\\]]+]:\\s")
    }
}
