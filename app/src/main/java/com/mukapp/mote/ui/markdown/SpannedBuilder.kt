package com.mukapp.mote.ui.markdown

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

class SpannedBuilder(private val context: Context) {

    private val inlineParser = InlineParser()

    private val codeBlockBgColor: Int by lazy {
        val surfaceVariant = resolveThemeColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            0xFFE7E0EC.toInt()
        )
        blendWithAlpha(surfaceVariant, 0x55)
    }

    private val inlineCodeBgColor: Int by lazy {
        val surfaceVariant = resolveThemeColor(
            com.google.android.material.R.attr.colorSurfaceVariant,
            0xFFE7E0EC.toInt()
        )
        blendWithAlpha(surfaceVariant, 0x44)
    }

    private val linkColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }

    private val keywordColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }

    private val stringColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorSecondary, 0xFF386A20.toInt())
    }

    private val commentColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF707579.toInt())
    }

    private val bulletGapWidth: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f,
            context.resources.displayMetrics
        ).toInt()
    }

    fun build(blocks: List<MdBlock>, isStreaming: Boolean = false, linkDefs: Map<String, Pair<String, String>> = emptyMap()): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        appendBlocks(ssb, blocks, isStreaming, linkDefs)
        return ssb
    }

    private fun appendBlocks(ssb: SpannableStringBuilder, blocks: List<MdBlock>, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, block) in blocks.withIndex()) {
            when (block) {
                is MdBlock.Heading -> appendHeading(ssb, block, linkDefs)
                is MdBlock.CodeBlock -> appendCodeBlock(ssb, block)
                is MdBlock.UnorderedList -> appendUnorderedList(ssb, block, isStreaming, linkDefs)
                is MdBlock.OrderedList -> appendOrderedList(ssb, block, isStreaming, linkDefs)
                is MdBlock.TaskList -> appendTaskList(ssb, block, isStreaming, linkDefs)
                is MdBlock.Blockquote -> appendBlockquote(ssb, block, isStreaming, linkDefs)
                is MdBlock.Table -> appendTable(ssb, block, isStreaming, linkDefs)
                is MdBlock.Paragraph -> appendParagraph(ssb, block, isStreaming, linkDefs)
                is MdBlock.HorizontalRule -> appendHorizontalRule(ssb)
            }
            if (index < blocks.lastIndex) {
                ssb.append('\n')
            }
        }
    }

    private fun appendHeading(ssb: SpannableStringBuilder, heading: MdBlock.Heading, linkDefs: Map<String, Pair<String, String>>) {
        val start = ssb.length
        val inlineElements = inlineParser.parse(heading.text, isStreaming = false, linkDefs = linkDefs)
        appendInlineElements(ssb, inlineElements)
        val end = ssb.length
        ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(RelativeSizeSpan(headingSize(heading.level)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendCodeBlock(ssb: SpannableStringBuilder, codeBlock: MdBlock.CodeBlock) {
        val start = ssb.length
        if (codeBlock.language.isNotBlank()) {
            ssb.append(codeBlock.language)
            ssb.append('\n')
        }
        ssb.append(codeBlock.code)
        val end = ssb.length
        ssb.setSpan(BackgroundColorSpan(codeBlockBgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        applySyntaxHighlight(ssb, codeBlock.language, start, end)
    }

    private fun applySyntaxHighlight(ssb: SpannableStringBuilder, language: String, start: Int, end: Int) {
        val code = ssb.substring(start, end)
        val langLower = language.lowercase()

        val keywords = when {
            langLower in setOf("kotlin", "java", "scala") -> KOTLIN_JAVA_KEYWORDS
            langLower in setOf("python", "py") -> PYTHON_KEYWORDS
            langLower in setOf("javascript", "js", "typescript", "ts") -> JS_KEYWORDS
            langLower == "c" || langLower == "cpp" || langLower == "c++" -> C_KEYWORDS
            langLower in setOf("bash", "sh", "shell", "zsh") -> SHELL_KEYWORDS
            langLower in setOf("sql") -> SQL_KEYWORDS
            else -> null
        }

        if (keywords != null) {
            for (keyword in keywords) {
                var searchFrom = 0
                while (searchFrom < code.length) {
                    val idx = code.indexOf(keyword, searchFrom)
                    if (idx < 0) break
                    val beforeOk = idx == 0 || !code[idx - 1].isLetterOrDigit()
                    val afterIdx = idx + keyword.length
                    val afterOk = afterIdx >= code.length || !code[afterIdx].isLetterOrDigit()
                    if (beforeOk && afterOk) {
                        ssb.setSpan(ForegroundColorSpan(keywordColor), start + idx, start + afterIdx, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    searchFrom = idx + keyword.length
                }
            }
        }

        val stringPattern = STRING_REGEX
        for (match in stringPattern.findAll(code)) {
            val matchStart = start + match.range.first
            val matchEnd = start + match.range.last + 1
            if (matchStart < end && matchEnd <= end) {
                ssb.setSpan(ForegroundColorSpan(stringColor), matchStart, matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val commentPattern = COMMENT_REGEX
        for (match in commentPattern.findAll(code)) {
            val matchStart = start + match.range.first
            val matchEnd = start + match.range.last + 1
            if (matchStart < end && matchEnd <= end) {
                ssb.setSpan(ForegroundColorSpan(commentColor), matchStart, matchEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun appendUnorderedList(ssb: SpannableStringBuilder, list: MdBlock.UnorderedList, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, childBlocks) in list.items.withIndex()) {
            val itemStart = ssb.length
            appendBlocks(ssb, childBlocks, isStreaming, linkDefs)
            val itemEnd = ssb.length
            ssb.setSpan(BulletSpan(bulletGapWidth), itemStart, itemEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < list.items.lastIndex) {
                ssb.append('\n')
            }
        }
    }

    private fun appendOrderedList(ssb: SpannableStringBuilder, list: MdBlock.OrderedList, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, childBlocks) in list.items.withIndex()) {
            val itemStart = ssb.length
            val number = "${index + 1}. "
            ssb.append(number)
            appendBlocks(ssb, childBlocks, isStreaming, linkDefs)
            val itemEnd = ssb.length
            val numberWidth = number.length * 12
            ssb.setSpan(LeadingMarginSpan.Standard(numberWidth, numberWidth), itemStart, itemEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < list.items.lastIndex) {
                ssb.append('\n')
            }
        }
    }

    private fun appendTaskList(ssb: SpannableStringBuilder, list: MdBlock.TaskList, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, pair) in list.items.withIndex()) {
            val (taskItem, childBlocks) = pair
            val itemStart = ssb.length
            val checkbox = if (taskItem.checked) "\u2611 " else "\u2610 "
            ssb.append(checkbox)
            appendBlocks(ssb, childBlocks, isStreaming, linkDefs)
            val itemEnd = ssb.length
            ssb.setSpan(LeadingMarginSpan.Standard(0), itemStart, itemEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < list.items.lastIndex) {
                ssb.append('\n')
            }
        }
    }

    private fun appendBlockquote(ssb: SpannableStringBuilder, blockquote: MdBlock.Blockquote, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        val start = ssb.length
        appendBlocks(ssb, blockquote.children, isStreaming, linkDefs)
        val end = ssb.length
        val quoteMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics).toInt()
        ssb.setSpan(LeadingMarginSpan.Standard(quoteMargin), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendTable(ssb: SpannableStringBuilder, table: MdBlock.Table, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        val colCount = table.headers.size
        if (colCount == 0) return
        val colWidths = calculateColumnWidths(table)

        appendTableRow(ssb, table.headers, colWidths, isStreaming, linkDefs, isHeader = true)
        ssb.append('\n')
        val sepStart = ssb.length
        val separator = buildString {
            append("|")
            for (w in colWidths) { append(" "); repeat(w) { append("-") }; append(" |") }
        }
        ssb.append(separator)
        ssb.setSpan(TypefaceSpan("monospace"), sepStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        for (row in table.rows) {
            ssb.append('\n')
            appendTableRow(ssb, row, colWidths, isStreaming, linkDefs, isHeader = false)
        }
    }

    private fun calculateColumnWidths(table: MdBlock.Table): IntArray {
        val colCount = table.headers.size
        val widths = IntArray(colCount)
        for (j in 0 until colCount) widths[j] = table.headers[j].length
        for (row in table.rows) {
            for (j in 0 until minOf(colCount, row.size)) {
                widths[j] = maxOf(widths[j], row[j].length)
            }
        }
        for (j in 0 until colCount) widths[j] = maxOf(widths[j], 3)
        return widths
    }

    private fun appendTableRow(ssb: SpannableStringBuilder, cells: List<String>, colWidths: IntArray, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>, isHeader: Boolean) {
        val rowStart = ssb.length
        val rowBuilder = StringBuilder()
        rowBuilder.append("|")
        for (j in colWidths.indices) {
            val cellText = if (j < cells.size) cells[j] else ""
            rowBuilder.append(" ").append(cellText.padEnd(colWidths[j])).append(" |")
        }
        val inlineElements = inlineParser.parse(rowBuilder.toString(), isStreaming, linkDefs)
        appendInlineElements(ssb, inlineElements)
        if (isHeader) {
            ssb.setSpan(StyleSpan(Typeface.BOLD), rowStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ssb.setSpan(TypefaceSpan("monospace"), rowStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendParagraph(ssb: SpannableStringBuilder, paragraph: MdBlock.Paragraph, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        val inlineElements = inlineParser.parse(paragraph.text, isStreaming, linkDefs)
        appendInlineElements(ssb, inlineElements)
    }

    private fun appendHorizontalRule(ssb: SpannableStringBuilder) {
        val start = ssb.length
        ssb.append("───────────────────")
        val end = ssb.length
        ssb.setSpan(LeadingMarginSpan.Standard(0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendInlineElements(ssb: SpannableStringBuilder, elements: List<InlineElement>) {
        for (element in elements) {
            when (element) {
                is InlineElement.Text -> ssb.append(element.content)
                is InlineElement.Bold -> {
                    val start = ssb.length
                    appendInlineElements(ssb, element.children)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.Italic -> {
                    val start = ssb.length
                    appendInlineElements(ssb, element.children)
                    ssb.setSpan(StyleSpan(Typeface.ITALIC), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.Strikethrough -> {
                    val start = ssb.length
                    appendInlineElements(ssb, element.children)
                    ssb.setSpan(StrikethroughSpan(), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.Superscript -> {
                    val start = ssb.length
                    appendInlineElements(ssb, element.children)
                    ssb.setSpan(SuperscriptSpan(), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(RelativeSizeSpan(0.75f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.Subscript -> {
                    val start = ssb.length
                    appendInlineElements(ssb, element.children)
                    ssb.setSpan(SubscriptSpan(), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(RelativeSizeSpan(0.75f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.InlineCode -> {
                    val start = ssb.length
                    ssb.append(element.content)
                    val end = ssb.length
                    ssb.setSpan(BackgroundColorSpan(inlineCodeBgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.Link -> {
                    val start = ssb.length
                    ssb.append(element.text)
                    val end = ssb.length
                    ssb.setSpan(URLSpan(element.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.AutoLink -> {
                    val start = ssb.length
                    ssb.append(element.url)
                    val end = ssb.length
                    ssb.setSpan(URLSpan(element.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun headingSize(level: Int): Float = when (level) {
        1 -> 1.6f; 2 -> 1.4f; 3 -> 1.25f; 4 -> 1.1f; 5 -> 1.0f; 6 -> 0.9f; else -> 1.0f
    }

    @ColorInt
    private fun resolveThemeColor(attr: Int, @ColorInt fallback: Int): Int {
        val typedValue = TypedValue()
        val resolved = context.theme.resolveAttribute(attr, typedValue, true)
        return if (resolved) {
            if (typedValue.resourceId != 0) ContextCompat.getColor(context, typedValue.resourceId) else typedValue.data
        } else fallback
    }

    @ColorInt
    private fun blendWithAlpha(@ColorInt color: Int, alpha: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        private val STRING_REGEX = Regex("""\"[^"]*\"|'[^']*'|\"\"\"[\s\S]*?\"\"\"""")
        private val COMMENT_REGEX = Regex("//.*?$|/\\*[\\s\\S]*?\\*/", RegexOption.MULTILINE)

        private val KOTLIN_JAVA_KEYWORDS = setOf(
            "fun", "val", "var", "class", "interface", "object", "if", "else", "when", "for",
            "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw",
            "import", "package", "new", "this", "super", "null", "true", "false", "override",
            "private", "protected", "public", "internal", "abstract", "open", "sealed", "data",
            "enum", "annotation", "companion", "init", "constructor", "by", "lazy", "lateinit",
            "inline", "reified", "suspend", "operator", "infix", "tailrec", "crossinline",
            "noinline", "typealias", "where", "is", "as", "in", "out", "void", "static",
            "final", "extends", "implements", "throws", "instanceof", "boolean", "int",
            "long", "float", "double", "char", "byte", "short", "String"
        )
        private val PYTHON_KEYWORDS = setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from",
            "as", "try", "except", "finally", "raise", "with", "yield", "lambda", "pass",
            "break", "continue", "and", "or", "not", "in", "is", "None", "True", "False",
            "global", "nonlocal", "assert", "del", "async", "await", "self"
        )
        private val JS_KEYWORDS = setOf(
            "function", "const", "let", "var", "class", "if", "else", "for", "while", "do",
            "return", "break", "continue", "try", "catch", "finally", "throw", "new", "this",
            "super", "null", "undefined", "true", "false", "typeof", "instanceof", "in",
            "of", "async", "await", "yield", "import", "export", "from", "default", "extends",
            "static", "get", "set", "interface", "type", "enum", "implements"
        )
        private val C_KEYWORDS = setOf(
            "int", "long", "float", "double", "char", "void", "bool", "auto", "const",
            "static", "extern", "register", "volatile", "signed", "unsigned", "short",
            "struct", "union", "enum", "typedef", "if", "else", "for", "while", "do",
            "switch", "case", "default", "break", "continue", "return", "goto", "sizeof",
            "NULL", "true", "false", "class", "namespace", "using", "template", "virtual",
            "override", "public", "private", "protected", "new", "delete", "try", "catch",
            "throw", "constexpr", "nullptr", "auto", "decltype", "static_cast", "dynamic_cast"
        )
        private val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
            "case", "esac", "function", "return", "exit", "break", "continue", "in",
            "echo", "printf", "read", "cd", "pwd", "ls", "mkdir", "rm", "cp", "mv",
            "cat", "grep", "sed", "awk", "find", "sort", "uniq", "wc", "head", "tail",
            "chmod", "chown", "export", "source", "alias", "unset", "set", "shift",
            "test", "true", "false", "local", "declare", "typeset", "readonly"
        )
        private val SQL_KEYWORDS = setOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "JOIN",
            "INNER", "LEFT", "RIGHT", "OUTER", "ON", "AND", "OR", "NOT", "NULL",
            "IS", "IN", "BETWEEN", "LIKE", "ORDER", "BY", "GROUP", "HAVING", "LIMIT",
            "OFFSET", "UNION", "ALL", "AS", "DISTINCT", "COUNT", "SUM", "AVG", "MIN",
            "MAX", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "PRIMARY", "KEY",
            "FOREIGN", "REFERENCES", "CONSTRAINT", "DEFAULT", "CHECK", "UNIQUE"
        )
    }
}
