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

class SpannedBuilder(private val context: Context) {

    private val inlineParser = InlineParser()
    private val codeSpanRenderer = MarkdownCodeSpanRenderer(context)

    /** 表格可用绘制宽度（像素），由外部设置 */
    var tableAvailableWidth: Int = 0

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

    private fun appendBlocks(ssb: SpannableStringBuilder, blocks: List<MdBlock>, isStreaming: Boolean, linkDefs: Map<String, Pair<String, String>>) {
        for ((index, block) in blocks.withIndex()) {
            appendSingleBlock(ssb, block, isStreaming, linkDefs)
            if (index < blocks.lastIndex) {
                ssb.append('\n')
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
    }

    private fun appendCodeBlock(ssb: SpannableStringBuilder, codeBlock: MdBlock.CodeBlock) {
        val start = ssb.length
        if (codeBlock.language.isNotBlank()) {
            ssb.append(codeBlock.language)
            ssb.append('\n')
        }
        ssb.append(codeSpanRenderer.buildCodeContent(codeBlock.code, codeBlock.language))
        val end = ssb.length
        ssb.setSpan(BackgroundColorSpan(codeBlockBgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun buildCodeContent(code: String, language: String): SpannableStringBuilder {
        return codeSpanRenderer.buildCodeContent(code, language)
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
            val checkbox = if (taskItem.checked) "[x] " else "[ ] "
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

    /** 表头背景色 */
    private val tableHeaderBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x88
        )
    }

    /** 表格数据行交替背景色 */
    private val tableRowAltBgColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFE7E0EC.toInt()),
            0x22
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
        return resolveThemeColor(context, attr, fallback)
    }

    @ColorInt
    private fun blendWithAlpha(@ColorInt color: Int, alpha: Int): Int {
        return com.mukapp.mote.ui.markdown.blendWithAlpha(color, alpha)
    }

    companion object
}
