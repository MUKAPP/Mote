package com.mukapp.mote.ui.markdown

class BlockParser {

    fun parse(text: String, isStreaming: Boolean = false): List<MdBlock> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val linkDefs = collectLinkDefinitions(lines)
        return parseBlocks(lines, 0, lines.size, isStreaming, linkDefs)
    }

    private fun collectLinkDefinitions(lines: List<String>): Map<String, Pair<String, String>> {
        val defs = mutableMapOf<String, Pair<String, String>>()
        for (line in lines) {
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

    private fun parseBlocks(
        lines: List<String>,
        start: Int,
        end: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        var i = start
        var offset = computeOffset(lines, start)

        while (i < end) {
            val line = lines[i]
            val trimmed = line.trimStart()

            if (trimmed.isEmpty()) {
                offset += line.length + 1
                i++
                continue
            }

            if (LINK_DEF_REGEX.matchEntire(trimmed) != null) {
                offset += line.length + 1
                i++
                continue
            }

            val heading = parseHeading(trimmed, offset, offset + line.length)
            if (heading != null) {
                blocks.add(heading)
                offset += line.length + 1
                i++
                continue
            }

            val setextHeading = parseSetextHeading(lines, i, blocks, offset)
            if (setextHeading != null) {
                offset = setextHeading.second
                i = setextHeading.third
                continue
            }

            if (trimmed.startsWith("```")) {
                val result = parseCodeBlock(lines, i, offset, isStreaming)
                blocks.add(result.block)
                i = result.nextLineIndex
                offset = result.nextOffset
                continue
            }

            val hr = parseHorizontalRule(trimmed, offset, offset + line.length)
            if (hr != null) {
                blocks.add(hr)
                offset += line.length + 1
                i++
                continue
            }

            val table = parseTable(lines, i, offset)
            if (table != null) {
                blocks.add(table.block)
                i = table.nextLineIndex
                offset = table.nextOffset
                continue
            }

            val blockquote = parseBlockquote(lines, i, offset, isStreaming, linkDefs)
            if (blockquote != null) {
                blocks.add(blockquote.block)
                i = blockquote.nextLineIndex
                offset = blockquote.nextOffset
                continue
            }

            val taskList = parseTaskList(lines, i, offset, isStreaming, linkDefs)
            if (taskList != null) {
                blocks.add(taskList.block)
                i = taskList.nextLineIndex
                offset = taskList.nextOffset
                continue
            }

            val unorderedList = parseUnorderedList(lines, i, offset, isStreaming, linkDefs)
            if (unorderedList != null) {
                blocks.add(unorderedList.block)
                i = unorderedList.nextLineIndex
                offset = unorderedList.nextOffset
                continue
            }

            val orderedList = parseOrderedList(lines, i, offset, isStreaming, linkDefs)
            if (orderedList != null) {
                blocks.add(orderedList.block)
                i = orderedList.nextLineIndex
                offset = orderedList.nextOffset
                continue
            }

            val paragraph = parseParagraph(lines, i, offset, isStreaming, linkDefs)
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

    private fun parseHeading(
        trimmed: String,
        startOffset: Int,
        endOffset: Int
    ): MdBlock.Heading? {
        if (!trimmed.startsWith("#")) return null
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
        offset: Int
    ): Triple<MdBlock.Heading, Int, Int>? {
        val trimmed = lines[i].trimStart()
        val level = when {
            SETEXT_H1_REGEX.matches(trimmed) -> 1
            SETEXT_H2_REGEX.matches(trimmed) -> 2
            else -> return null
        }
        if (blocks.isEmpty()) return null
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
        val language = firstLine.removePrefix("```").trim()
        val codeLines = mutableListOf<String>()
        var offset = startOffset + lines[startIndex].length + 1
        var i = startIndex + 1
        var closed = false

        while (i < lines.size) {
            val line = lines[i]
            val lineTrimmed = line.trim()
            if (lineTrimmed.startsWith("```") && lineTrimmed.count { it == '`' } == lineTrimmed.length) {
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
                if (lastTrimmed.startsWith("```") && lastTrimmed.count { it == '`' } == lastTrimmed.length) {
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
        if (HR_REGEX.matches(trimmed)) {
            return MdBlock.HorizontalRule(startOffset, endOffset)
        }
        return null
    }

    private fun parseTable(lines: List<String>, startIndex: Int, startOffset: Int): TableResult? {
        val firstLine = lines[startIndex].trim()
        if (!firstLine.startsWith("|") || !firstLine.endsWith("|")) return null
        val headerCells = parseTableRow(firstLine)
        if (headerCells.isEmpty()) return null
        if (startIndex + 1 >= lines.size) return null
        val separatorLine = lines[startIndex + 1].trim()
        if (!TABLE_SEPARATOR_REGEX.matches(separatorLine)) return null

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

        return TableResult(MdBlock.Table(headers, rows, startOffset, offset - 1), i, offset)
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
        linkDefs: Map<String, Pair<String, String>>
    ): BlockquoteResult? {
        val firstLine = lines[startIndex].trimStart()
        if (!firstLine.startsWith(">")) return null

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

        val innerText = quoteLines.joinToString("\n")
        val children = parseBlocks(innerText.lines(), 0, innerText.lines().size, isStreaming, linkDefs)
        val endOffset = offset - 1

        return BlockquoteResult(MdBlock.Blockquote(children, startOffset, endOffset), i, offset)
    }

    private fun parseTaskList(
        lines: List<String>,
        startIndex: Int,
        startOffset: Int,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): TaskListResult? {
        val firstLine = lines[startIndex].trimStart()
        val match = TASK_LIST_REGEX.matchEntire(firstLine) ?: return null

        val items = mutableListOf<Pair<TaskItem, List<MdBlock>>>()
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            val itemMatch = TASK_LIST_REGEX.matchEntire(trimmed)
            if (itemMatch != null) {
                val taskItem = TaskItem(
                    checked = itemMatch.groupValues[1] == "x" || itemMatch.groupValues[1] == "X",
                    text = itemMatch.groupValues[2]
                )
                val childLines = mutableListOf(itemMatch.groupValues[2])
                offset += line.length + 1
                i++

                while (i < lines.size) {
                    val contLine = lines[i]
                    val contTrimmed = contLine.trimStart()
                    if (contTrimmed.isEmpty()) break
                    if (TASK_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (UNORDERED_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (ORDERED_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (contLine.startsWith("  ") || contLine.startsWith("\t")) {
                        childLines.add(contTrimmed)
                    } else {
                        break
                    }
                    offset += contLine.length + 1
                    i++
                }

                val childText = childLines.joinToString("\n")
                val childBlocks = parseBlocks(childText.lines(), 0, childText.lines().size, isStreaming, linkDefs)
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
        linkDefs: Map<String, Pair<String, String>>
    ): UnorderedListResult? {
        val firstLine = lines[startIndex].trimStart()
        val match = UNORDERED_LIST_REGEX.matchEntire(firstLine) ?: return null

        val items = mutableListOf<List<MdBlock>>()
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            val itemMatch = UNORDERED_LIST_REGEX.matchEntire(trimmed)
            if (itemMatch != null) {
                val childLines = mutableListOf(itemMatch.groupValues[2])
                offset += line.length + 1
                i++

                while (i < lines.size) {
                    val contLine = lines[i]
                    val contTrimmed = contLine.trimStart()
                    if (contTrimmed.isEmpty()) break
                    if (UNORDERED_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (ORDERED_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (TASK_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (contLine.startsWith("  ") || contLine.startsWith("\t")) {
                        childLines.add(contTrimmed)
                    } else {
                        break
                    }
                    offset += contLine.length + 1
                    i++
                }

                val childText = childLines.joinToString("\n")
                val childBlocks = parseBlocks(childText.lines(), 0, childText.lines().size, isStreaming, linkDefs)
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
        linkDefs: Map<String, Pair<String, String>>
    ): OrderedListResult? {
        val firstLine = lines[startIndex].trimStart()
        val match = ORDERED_LIST_REGEX.matchEntire(firstLine) ?: return null

        val items = mutableListOf<List<MdBlock>>()
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            val itemMatch = ORDERED_LIST_REGEX.matchEntire(trimmed)
            if (itemMatch != null) {
                val childLines = mutableListOf(itemMatch.groupValues[2])
                offset += line.length + 1
                i++

                while (i < lines.size) {
                    val contLine = lines[i]
                    val contTrimmed = contLine.trimStart()
                    if (contTrimmed.isEmpty()) break
                    if (ORDERED_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (UNORDERED_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (TASK_LIST_REGEX.matchEntire(contTrimmed) != null) break
                    if (contLine.startsWith("  ") || contLine.startsWith("\t")) {
                        childLines.add(contTrimmed)
                    } else {
                        break
                    }
                    offset += contLine.length + 1
                    i++
                }

                val childText = childLines.joinToString("\n")
                val childBlocks = parseBlocks(childText.lines(), 0, childText.lines().size, isStreaming, linkDefs)
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
        linkDefs: Map<String, Pair<String, String>>
    ): ParagraphResult {
        val textLines = mutableListOf<String>()
        var offset = startOffset
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) break
            if (trimmed.startsWith("#") && HEADING_REGEX.matches(trimmed)) break
            if (trimmed.startsWith("```")) break
            if (trimmed.startsWith(">")) break
            if (HR_REGEX.matches(trimmed)) break
            if (SETEXT_H1_REGEX.matches(trimmed) || SETEXT_H2_REGEX.matches(trimmed)) break
            if (UNORDERED_LIST_REGEX.matches(trimmed)) break
            if (ORDERED_LIST_REGEX.matches(trimmed)) break
            if (TASK_LIST_REGEX.matches(trimmed)) break
            if (trimmed.trimStart().startsWith("|") && trimmed.trim().endsWith("|")) break
            if (LINK_DEF_REGEX.matchEntire(trimmed) != null) break

            textLines.add(line)
            offset += line.length + 1
            i++
        }

        val text = textLines.joinToString("\n").trim()
        val endOffset = offset - 1

        return ParagraphResult(MdBlock.Paragraph(text, startOffset, endOffset), i, offset)
    }

    private data class CodeBlockResult(val block: MdBlock.CodeBlock, val nextLineIndex: Int, val nextOffset: Int)
    private data class BlockquoteResult(val block: MdBlock.Blockquote, val nextLineIndex: Int, val nextOffset: Int)
    private data class UnorderedListResult(val block: MdBlock.UnorderedList, val nextLineIndex: Int, val nextOffset: Int)
    private data class OrderedListResult(val block: MdBlock.OrderedList, val nextLineIndex: Int, val nextOffset: Int)
    private data class TaskListResult(val block: MdBlock.TaskList, val nextLineIndex: Int, val nextOffset: Int)
    private data class TableResult(val block: MdBlock.Table, val nextLineIndex: Int, val nextOffset: Int)
    private data class ParagraphResult(val block: MdBlock.Paragraph, val nextLineIndex: Int, val nextOffset: Int)

    companion object {
        private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
        private val HR_REGEX = Regex("^[-*_]{3,}\\s*$")
        private val SETEXT_H1_REGEX = Regex("^=+\\s*$")
        private val SETEXT_H2_REGEX = Regex("^-+\\s*$")
        private val UNORDERED_LIST_REGEX = Regex("^([-*+])\\s+(.+)$")
        private val ORDERED_LIST_REGEX = Regex("^(\\d+)[.)]\\s+(.+)$")
        private val TASK_LIST_REGEX = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.+)$")
        private val TABLE_SEPARATOR_REGEX = Regex("^\\|?([\\s:]*-+[\\s:]*\\|)+[\\s:]*-+[\\s:]*\\|?$")
        private val LINK_DEF_REGEX = Regex("^\\[([^\\]]+)]:\\s+(\\S+)(?:\\s+[\"'](.+?)[\"'])?\\s*$")
    }
}
