package com.mukapp.mote.ui.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.util.LruCache
import android.util.TypedValue
import androidx.annotation.ColorInt
import com.mukapp.mote.util.dp
import com.mukapp.mote.util.dpInt
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlin.math.ceil

class SpannedBuilder(private val context: Context) {

    private val inlineParser = InlineParser()
    private val codeColors = resolveMarkdownCodeColors(context)
    private val codeSpanRenderer = MarkdownCodeSpanRenderer(context, codeColors)

    /** 共享的代码高亮渲染器，供 MarkdownCodeBlockView 复用，避免重复创建 Prism4j 实例 */
    val sharedCodeSpanRenderer: MarkdownCodeSpanRenderer get() = codeSpanRenderer

    /** 表格可用绘制宽度（像素），由外部设置 */
    var tableAvailableWidth: Int = 0

    /**
     * 是否把表格渲染为纯文本（等宽对齐网格）而非 Canvas 绘制的 [TableSpan]。
     * Canvas 绘制的表格不是真实文本、无法被选择复制；自由复制页开启此项以支持选取。
     */
    var tablesAsPlainText: Boolean = false

    /**
     * 是否启用「贴近 [MarkdownView] 外观」的整篇富样式（标题配色、引用块竖条、代码块圆角底色、
     * 分割线、列表标记配色等）。仅自由复制页的整篇渲染路径开启；聊天页 [MarkdownView] 不受影响。
     */
    var standalone: Boolean = false

    /** 行内公式渲染字号（像素），由 MarkdownView 按当前 TextView 字号同步 */
    var inlineMathTextSizePx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        16f,
        context.resources.displayMetrics
    )

    private data class InlineMathRenderKey(
        val formula: String,
        val fontSizePx: Int,
        val color: Int
    )

    private data class InlineMathRenderResult(
        val bitmap: Bitmap,
        val heightPx: Float,
        val depthPx: Float
    )

    private val inlineMathCache = object : LruCache<InlineMathRenderKey, InlineMathRenderResult>(MaxInlineMathCacheBytes) {
        override fun sizeOf(key: InlineMathRenderKey, value: InlineMathRenderResult): Int {
            return value.bitmap.byteCount.coerceAtLeast(1)
        }
    }

    private val codeBlockBgColor: Int by lazy {
        codeColors.blockBackgroundColor
    }

    private val inlineCodeBgColor: Int by lazy {
        codeColors.inlineCodeBackgroundColor
    }

    private val inlineCodeTextColor: Int by lazy {
        codeColors.inlineCodeTextColor
    }

    private val linkColor: Int by lazy {
        resolveThemeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }

    private val bodyTextColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    }

    private val secondaryTextColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt())
    }

    private val outlineVariantColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt())
    }

    private val primaryColor: Int by lazy {
        resolveThemeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }

    private val quoteBackgroundColor: Int by lazy { blendWithAlpha(primaryColor, 0x10) }
    private val quoteStripeColor: Int by lazy { blendWithAlpha(primaryColor, 0x7A) }
    private val horizontalRuleColor: Int by lazy { blendWithAlpha(outlineVariantColor, 0x88) }

    private val quoteCornerRadiusPx: Float by lazy { 12f.dp }
    private val quoteStripeWidthPx: Int by lazy { 6.dpInt }
    private val quoteContentGapPx: Int by lazy { 12.dpInt }
    private val codeBlockCornerRadiusPx: Float by lazy { 10f.dp }
    private val codeBlockPaddingPx: Int by lazy { 12.dpInt }
    private val horizontalRuleThicknessPx: Int by lazy { 1.dpInt.coerceAtLeast(1) }

    private val inlineMathTextColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    }

    private val bulletGapWidth: Int by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f,
            context.resources.displayMetrics
        ).toInt()
    }

    fun build(blocks: List<MdBlock>, isStreaming: Boolean = false, linkDefs: Map<String, Pair<String, String>> = emptyMap()): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        // standalone（自由复制页）用空行分隔顶层块，贴近 MarkdownView 块间留白
        appendBlocks(ssb, blocks, isStreaming, linkDefs, separator = if (standalone) "\n\n" else "\n")
        return ssb
    }

    fun buildSingleBlock(block: MdBlock, isStreaming: Boolean = false, linkDefs: Map<String, Pair<String, String>> = emptyMap()): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        appendSingleBlock(ssb, block, isStreaming, linkDefs)
        return ssb
    }

    fun buildInlineText(text: String, isStreaming: Boolean = false, linkDefs: Map<String, Pair<String, String>> = emptyMap()): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val inlineElements = inlineParser.parse(text, isStreaming, linkDefs)
        appendInlineElements(ssb, inlineElements)
        return ssb
    }

    private fun appendBlocks(ssb: SpannableStringBuilder, blocks: List<MdBlock>, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>, separator: String = "\n") {
        for ((index, block) in blocks.withIndex()) {
            appendSingleBlock(ssb, block, isStreaming, linkDefs)
            if (index < blocks.lastIndex) {
                ssb.append(separator)
            }
        }
    }

    private fun appendSingleBlock(ssb: SpannableStringBuilder, block: MdBlock, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        when (block) {
            is MdBlock.Heading -> appendHeading(ssb, block, linkDefs)
            is MdBlock.CodeBlock -> appendCodeBlock(ssb, block)
            is MdBlock.UnorderedList -> appendUnorderedList(ssb, block, isStreaming, linkDefs)
            is MdBlock.OrderedList -> appendOrderedList(ssb, block, isStreaming, linkDefs)
            is MdBlock.TaskList -> appendTaskList(ssb, block, isStreaming, linkDefs)
            is MdBlock.Blockquote -> appendBlockquote(ssb, block, isStreaming, linkDefs)
            is MdBlock.Table -> appendTable(ssb, block, isStreaming, linkDefs)
            is MdBlock.MathBlock -> appendMathBlock(ssb, block)
            is MdBlock.Paragraph -> appendParagraph(ssb, block, isStreaming, linkDefs)
            is MdBlock.HorizontalRule -> appendHorizontalRule(ssb)
        }
    }

    private fun appendHeading(ssb: SpannableStringBuilder, heading: MdBlock.Heading, linkDefs: Map<String, Pair<String, String>>) {
        val start = ssb.length
        val inlineElements = inlineParser.parse(heading.text, isStreaming = false, linkDefs = linkDefs)
        appendInlineElements(ssb, inlineElements)
        val end = ssb.length
        ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(RelativeSizeSpan(headingSize(heading.level)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // standalone 下按层级着色，贴近 MarkdownView 标题观感
        if (standalone) {
            val color = when {
                heading.level <= 2 -> bodyTextColor
                heading.level <= 4 -> blendWithAlpha(bodyTextColor, 0xF0)
                else -> secondaryTextColor
            }
            ssb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun appendCodeBlock(ssb: SpannableStringBuilder, codeBlock: MdBlock.CodeBlock) {
        val start = ssb.length
        val langEnd: Int
        if (codeBlock.language.isNotBlank()) {
            ssb.append(codeBlock.language)
            langEnd = ssb.length
            ssb.append('\n')
        } else {
            langEnd = start
        }
        val codeStart = ssb.length
        ssb.append(codeSpanRenderer.buildCodeContent(codeBlock.code, codeBlock.language))
        val end = ssb.length

        if (standalone) {
            ssb.setSpan(
                CodeBlockBackgroundSpan(start, end, codeBlockBgColor, codeBlockPaddingPx, codeBlockCornerRadiusPx),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (langEnd > start) {
                ssb.setSpan(ForegroundColorSpan(codeColors.headerTextColor), start, langEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(0.82f), start, langEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ssb.setSpan(TypefaceSpan("monospace"), codeStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            ssb.setSpan(BackgroundColorSpan(codeBlockBgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun buildCodeContent(code: String, language: String): SpannableStringBuilder {
        return codeSpanRenderer.buildCodeContent(code, language)
    }

    private fun appendUnorderedList(ssb: SpannableStringBuilder, list: MdBlock.UnorderedList, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, childBlocks) in list.items.withIndex()) {
            val itemStart = ssb.length
            appendBlocks(ssb, childBlocks, isStreaming, linkDefs)
            val itemEnd = ssb.length
            val bullet = if (standalone) {
                BulletSpan(bulletGapWidth, secondaryTextColor)
            } else {
                BulletSpan(bulletGapWidth)
            }
            ssb.setSpan(bullet, itemStart, itemEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < list.items.lastIndex) {
                ssb.append('\n')
            }
        }
    }

    private fun appendOrderedList(ssb: SpannableStringBuilder, list: MdBlock.OrderedList, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, childBlocks) in list.items.withIndex()) {
            val itemStart = ssb.length
            val number = "${list.startNumber + index}. "
            ssb.append(number)
            if (standalone) {
                ssb.setSpan(ForegroundColorSpan(secondaryTextColor), itemStart, itemStart + number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
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
            val checkbox = if (standalone) {
                if (taskItem.checked) "☑ " else "☐ "
            } else {
                if (taskItem.checked) "[x] " else "[ ] "
            }
            ssb.append(checkbox)
            if (standalone) {
                ssb.setSpan(
                    ForegroundColorSpan(if (taskItem.checked) primaryColor else secondaryTextColor),
                    itemStart, itemStart + checkbox.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
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
        if (standalone) {
            ssb.setSpan(
                QuoteBlockSpan(
                    spanStart = start,
                    spanEnd = end,
                    backgroundColor = quoteBackgroundColor,
                    stripeColor = quoteStripeColor,
                    stripeWidth = quoteStripeWidthPx,
                    gap = quoteContentGapPx,
                    cornerRadius = quoteCornerRadiusPx
                ),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            val quoteMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics).toInt()
            ssb.setSpan(LeadingMarginSpan.Standard(quoteMargin), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 表头背景色 */
    private val tableHeaderBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x28
        )
    }

    /** 表格数据行交替背景色 */
    private val tableRowAltBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x14
        )
    }

    /** 表格网格线颜色 */
    private val tableGridLineColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt()),
            0x66
        )
    }

    /** 表头文字颜色 */
    private val tableHeaderTextColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    }

    /** 表格正文文字颜色 */
    private val tableCellTextColor: Int by lazy {
        resolveThemeColor(com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    }

    @Suppress("UNUSED_PARAMETER")
    private fun appendTable(ssb: SpannableStringBuilder, table: MdBlock.Table, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        val colCount = table.headers.size
        if (colCount == 0) return

        if (tablesAsPlainText) {
            appendTableAsPlainText(ssb, table, colCount)
            return
        }

        val width = if (tableAvailableWidth > 0) tableAvailableWidth else 800
        val start = ssb.length
        // 占位符：用单个特殊字符，TableSpan 会完全替换它的绘制
        ssb.append("\u200B") // zero-width space 作为占位
        val end = ssb.length

        val span = TableSpan(
            headers = table.headers,
            rows = table.rows,
            alignments = table.alignments,
            headerBgColor = tableHeaderBgColor,
            altRowBgColor = tableRowAltBgColor,
            gridLineColor = tableGridLineColor,
            headerTextColor = tableHeaderTextColor,
            cellTextColor = tableCellTextColor,
            availableWidth = width
        )
        ssb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 把表格渲染为等宽对齐的纯文本网格，保证内容可被选取复制。 */
    private fun appendTableAsPlainText(ssb: SpannableStringBuilder, table: MdBlock.Table, colCount: Int) {
        // 每列宽度取表头与各单元格的最大显示宽度（CJK 记为 2，其它记为 1）
        val widths = IntArray(colCount) { displayWidth(table.headers.getOrElse(it) { "" }) }
        for (row in table.rows) {
            for (j in 0 until colCount) {
                val w = displayWidth(row.getOrElse(j) { "" })
                if (w > widths[j]) widths[j] = w
            }
        }

        fun renderRow(cells: List<String>): String = buildString {
            append("| ")
            for (j in 0 until colCount) {
                val cell = cells.getOrElse(j) { "" }
                val align = table.alignments.getOrElse(j) { MdBlock.Alignment.LEFT }
                append(padCell(cell, widths[j], align))
                append(" | ")
            }
        }.trimEnd()

        val separator = buildString {
            append("|")
            for (j in 0 until colCount) {
                append(" ")
                append("-".repeat(widths[j].coerceAtLeast(1)))
                append(" |")
            }
        }

        val start = ssb.length
        ssb.append(renderRow(table.headers))
        ssb.append('\n')
        ssb.append(separator)
        for (row in table.rows) {
            ssb.append('\n')
            ssb.append(renderRow(row))
        }
        val end = ssb.length
        // 等宽字体保证列对齐
        ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun displayWidth(text: String): Int {
        var width = 0
        for (ch in text) {
            width += if (isWideChar(ch)) 2 else 1
        }
        return width
    }

    private fun isWideChar(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x1100..0x115F) ||      // Hangul Jamo
            (code in 0x2E80..0xA4CF) ||          // CJK 部首、假名、CJK 统一表意文字等
            (code in 0xAC00..0xD7A3) ||          // Hangul 音节
            (code in 0xF900..0xFAFF) ||          // CJK 兼容表意文字
            (code in 0xFE30..0xFE4F) ||          // CJK 兼容形式
            (code in 0xFF00..0xFF60) ||          // 全角 ASCII
            (code in 0xFFE0..0xFFE6)             // 全角符号
    }

    private fun padCell(text: String, targetWidth: Int, align: MdBlock.Alignment): String {
        val pad = (targetWidth - displayWidth(text)).coerceAtLeast(0)
        return when (align) {
            MdBlock.Alignment.RIGHT -> " ".repeat(pad) + text
            MdBlock.Alignment.CENTER -> {
                val left = pad / 2
                " ".repeat(left) + text + " ".repeat(pad - left)
            }
            MdBlock.Alignment.LEFT -> text + " ".repeat(pad)
        }
    }


    private fun appendParagraph(ssb: SpannableStringBuilder, paragraph: MdBlock.Paragraph, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        val inlineElements = inlineParser.parse(paragraph.text, isStreaming, linkDefs)
        appendInlineElements(ssb, inlineElements)
    }

    private fun appendMathBlock(ssb: SpannableStringBuilder, mathBlock: MdBlock.MathBlock) {
        val closeDelimiter = when (mathBlock.delimiter) {
            "$$" -> "$$"
            "\\[" -> "\\]"
            else -> mathBlock.delimiter
        }
        if (mathBlock.closed) {
            ssb.append(mathBlock.delimiter)
            ssb.append('\n')
            ssb.append(mathBlock.formula)
            ssb.append('\n')
            ssb.append(closeDelimiter)
        } else {
            ssb.append(mathBlock.delimiter)
            ssb.append('\n')
            ssb.append(mathBlock.formula)
        }
    }

    private fun appendHorizontalRule(ssb: SpannableStringBuilder) {
        val start = ssb.length
        if (standalone) {
            // 占位空白行 + 整行细线 Span，模仿 MarkdownView 的分割线
            ssb.append(" ")
            val end = ssb.length
            ssb.setSpan(
                HorizontalRuleLineSpan(horizontalRuleColor, horizontalRuleThicknessPx),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            ssb.append("───────────────────")
            val end = ssb.length
            ssb.setSpan(LeadingMarginSpan.Standard(0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
                    ssb.setSpan(ForegroundColorSpan(inlineCodeTextColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is InlineElement.Math -> appendInlineMath(ssb, element)
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

    private fun appendInlineMath(ssb: SpannableStringBuilder, math: InlineElement.Math) {
        val rendered = renderInlineMath(math.formula)
        if (rendered == null) {
            ssb.append(inlineMathFallbackText(math))
            return
        }
        val start = ssb.length
        ssb.append('\uFFFC')
        val end = ssb.length
        ssb.setSpan(
            MarkdownMathSpan(rendered.bitmap, rendered.heightPx, rendered.depthPx),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun renderInlineMath(formula: String): InlineMathRenderResult? {
        val fontSizePx = inlineMathTextSizePx.coerceAtLeast(1f)
        val key = InlineMathRenderKey(
            formula = formula,
            fontSizePx = fontSizePx.toInt(),
            color = inlineMathTextColor
        )
        inlineMathCache.get(key)?.let { return it }
        return runCatching {
            RaTeXFontLoader.ensureLoaded(context.applicationContext)
            val displayList = RaTeXEngine.parseBlocking(
                latex = formula,
                displayMode = false,
                color = inlineMathTextColor
            )
            val renderer = RaTeXRenderer(displayList, fontSizePx) { fontId ->
                RaTeXFontLoader.getTypeface(fontId)
            }
            val width = ceil(renderer.widthPx.toDouble()).toInt().coerceAtLeast(1)
            val height = ceil(renderer.totalHeightPx.toDouble()).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            renderer.draw(Canvas(bitmap))
            InlineMathRenderResult(bitmap, renderer.heightPx, renderer.depthPx)
        }.getOrNull()?.also { result ->
            inlineMathCache.put(key, result)
        }
    }

    private fun inlineMathFallbackText(math: InlineElement.Math): String {
        return when (math.delimiter) {
            "$" -> "$${math.formula}$"
            "\\(" -> "\\(${math.formula}\\)"
            else -> math.formula
        }
    }

    private fun headingSize(level: Int): Float = when (level) {
        1 -> 1.48f; 2 -> 1.32f; 3 -> 1.18f; 4 -> 1.08f; 5 -> 1.0f; 6 -> 0.96f; else -> 1.0f
    }

    @ColorInt
    private fun resolveThemeColor(attr: Int, @ColorInt fallback: Int): Int {
        return resolveThemeColor(context, attr, fallback)
    }

    @ColorInt
    private fun blendWithAlpha(@ColorInt color: Int, alpha: Int): Int {
        return com.mukapp.mote.ui.markdown.blendWithAlpha(color, alpha)
    }

    companion object {
        private const val MaxInlineMathCacheBytes = 4 * 1024 * 1024
    }
}
