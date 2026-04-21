package com.mukapp.mote.ui.markdown

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.mukapp.mote.util.dp
import com.mukapp.mote.util.dpInt
import com.mukapp.mote.util.sp

class MarkdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val blockParser = BlockParser()
    private val spannedBuilder = SpannedBuilder(context)
    private val defaultTextAppearanceRes = resolveThemeResource(
        context,
        com.google.android.material.R.attr.textAppearanceBodyLarge,
        0
    )
    private val inlineTextAppearanceRes: Int
    private val baseTextColor: ColorStateList?
    private val baseTextSizePx: Float
    private val bodyTextColor: Int
    private val secondaryTextColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt())
    }
    private val quoteStripeColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorOutline, 0xFF79747E.toInt()),
            0x88
        )
    }
    private val horizontalRuleColor: Int by lazy {
        blendWithAlpha(
            resolveThemeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt()),
            0x88
        )
    }
    private val taskMarkerOutlineColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt())
    }
    private val taskMarkerCheckedColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }
    private val taskMarkerCheckedBgColor: Int by lazy {
        blendWithAlpha(taskMarkerCheckedColor, 0x18)
    }

    private var lastMarkdown: String = ""
    private var lastIsStreaming: Boolean = false

    init {
        orientation = VERTICAL
        val fallbackTextColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        val typedArray = context.obtainStyledAttributes(
            attrs,
            intArrayOf(android.R.attr.textAppearance, android.R.attr.textColor, android.R.attr.textSize),
            defStyleAttr,
            0
        )
        inlineTextAppearanceRes = typedArray.getResourceId(0, defaultTextAppearanceRes)
        baseTextColor = typedArray.getColorStateList(1)
        bodyTextColor = baseTextColor?.defaultColor ?: fallbackTextColor
        val defaultTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            16f,
            resources.displayMetrics
        )
        baseTextSizePx = typedArray.getDimension(2, defaultTextSize)
        typedArray.recycle()
    }

    fun setMarkdown(text: String, isStreaming: Boolean) {
        lastMarkdown = text
        lastIsStreaming = isStreaming
        if (text.isBlank()) {
            removeAllViews()
            isVisible = false
            return
        }
        isVisible = true

        val blocks = blockParser.parse(text, isStreaming = isStreaming)
        val linkDefs = collectLinkDefs(text, isStreaming)
        renderBlocks(blocks, isStreaming, linkDefs)
    }

    fun clearMarkdown() {
        lastMarkdown = ""
        lastIsStreaming = false
        removeAllViews()
        isVisible = false
    }

    private fun renderBlocks(
        blocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ) {
        removeAllViews()
        blocks.forEach { block ->
            addView(createBlockView(block, isStreaming, linkDefs))
        }
    }

    private fun createBlockView(
        block: MdBlock,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        return when (block) {
            is MdBlock.Table -> createTableView(block)
            is MdBlock.CodeBlock -> createCodeBlockView(block)
            is MdBlock.Blockquote -> createBlockquoteView(block, isStreaming, linkDefs)
            is MdBlock.UnorderedList -> createListView(block.items, false, isStreaming, linkDefs)
            is MdBlock.OrderedList -> createOrderedListView(block, isStreaming, linkDefs)
            is MdBlock.TaskList -> createTaskListView(block, isStreaming, linkDefs)
            is MdBlock.HorizontalRule -> createHorizontalRuleView()
            else -> createTextBlockView(block, isStreaming, linkDefs)
        }
    }

    private fun createTextBlockView(
        block: MdBlock,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): TextView {
        val textView = createBaseTextView()
        val text = spannedBuilder.buildSingleBlock(block, isStreaming = isStreaming, linkDefs = linkDefs)
        textView.text = text
        if (isStreaming) {
            textView.movementMethod = null
        } else {
            textView.movementMethod = LinkMovementMethod.getInstance()
        }
        return textView
    }

    private fun createCodeBlockView(codeBlock: MdBlock.CodeBlock): View {
        return MarkdownCodeBlockView(context).apply {
            layoutParams = createBlockLayoutParams()
            setCodeBlock(codeBlock.language, codeBlock.code)
        }
    }

    private fun createTableView(table: MdBlock.Table): View {
        val scrollView = object : HorizontalScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                isFillViewport = false
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        scrollView.layoutParams = createBlockLayoutParams()
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val tableView = MarkdownTableView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTableData(table.headers, table.rows, table.alignments)
        }
        scrollView.addView(tableView)
        return scrollView
    }

    private fun createBlockquoteView(
        blockquote: MdBlock.Blockquote,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = createBlockLayoutParams()
            gravity = Gravity.TOP
        }

        val stripe = View(context).apply {
            layoutParams = LayoutParams(3.dpInt, LayoutParams.MATCH_PARENT)
            setBackgroundColor(quoteStripeColor)
        }

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dpInt
            }
        }

        blockquote.children.forEach { child ->
            content.addView(createBlockView(child, isStreaming, linkDefs))
        }

        container.addView(stripe)
        container.addView(content)
        return container
    }

    private fun createListView(
        items: List<List<MdBlock>>,
        numbered: Boolean,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = createBlockLayoutParams()
        }
        items.forEachIndexed { index, childBlocks ->
            container.addView(createListItemView(if (numbered) "${index + 1}." else "•", childBlocks, isStreaming, linkDefs))
        }
        return container
    }

    private fun createOrderedListView(
        list: MdBlock.OrderedList,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        return createListView(list.items, numbered = true, isStreaming = isStreaming, linkDefs = linkDefs)
    }

    private fun createTaskListView(
        list: MdBlock.TaskList,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = createBlockLayoutParams()
        }
        list.items.forEach { (taskItem, childBlocks) ->
            container.addView(createTaskListItemView(taskItem.checked, childBlocks, isStreaming, linkDefs))
        }
        return container
    }

    private fun createTaskListItemView(
        checked: Boolean,
        childBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        return createListItemRow(createTaskMarkerView(checked), childBlocks, isStreaming, linkDefs)
    }

    private fun createListItemView(
        marker: String,
        childBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        return createListItemRow(createTextMarkerView(marker), childBlocks, isStreaming, linkDefs)
    }

    private fun createListItemRow(
        markerView: View,
        childBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = createBlockLayoutParams(bottomMargin = 4.dpInt)
            gravity = Gravity.TOP
        }

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        childBlocks.forEach { child ->
            content.addView(createBlockView(child, isStreaming, linkDefs))
        }

        row.addView(markerView)
        row.addView(content)
        return row
    }

    private fun createTextMarkerView(marker: String): TextView {
        return createBaseTextView().apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            text = marker
            setTextColor(secondaryTextColor)
            setPadding(0, 0, 10.dpInt, 0)
        }
    }

    private fun createTaskMarkerView(checked: Boolean): View {
        val boxSize = 18.dpInt
        val innerSize = 8.dpInt
        val strokeWidth = 1.dpInt.coerceAtLeast(1)
        return FrameLayout(context).apply {
            layoutParams = LayoutParams(boxSize, boxSize).apply {
                topMargin = 2.dpInt
                marginEnd = 10.dpInt
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4.dp
                setStroke(strokeWidth, if (checked) taskMarkerCheckedColor else taskMarkerOutlineColor)
                setColor(if (checked) taskMarkerCheckedBgColor else Color.TRANSPARENT)
            }
            if (checked) {
                addView(View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 2.dp
                        setColor(taskMarkerCheckedColor)
                    }
                })
            }
        }
    }

    private fun createHorizontalRuleView(): View {
        return View(context).apply {
            layoutParams = createBlockLayoutParams(topMargin = 6.dpInt, bottomMargin = 6.dpInt).apply {
                height = 1.dpInt
            }
            setBackgroundColor(horizontalRuleColor)
        }
    }

    private fun createBaseTextView(): TextView {
        return TextView(context).apply {
            layoutParams = createBlockLayoutParams()
            if (inlineTextAppearanceRes != 0) {
                setTextAppearance(inlineTextAppearanceRes)
            }
            if (baseTextColor != null) {
                setTextColor(baseTextColor)
            } else {
                setTextColor(bodyTextColor)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx)
            includeFontPadding = false
            linksClickable = true
            setLineSpacing(0f, 1.15f)
        }
    }

    private fun createBlockLayoutParams(
        topMargin: Int = 0,
        bottomMargin: Int = 8.dpInt
    ): LayoutParams {
        return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
    }

    private fun collectLinkDefs(text: String, isStreaming: Boolean): Map<String, Pair<String, String>> {
        val defs = mutableMapOf<String, Pair<String, String>>()
        val lines = text.lines()
        val skipLastLine = isStreaming && text.isNotEmpty() && !text.endsWith("\n") && !text.endsWith("\r")
        val lastIndex = lines.lastIndex
        for ((index, line) in lines.withIndex()) {
            if (skipLastLine && index == lastIndex) continue
            val match = LINK_DEF_REGEX.matchEntire(line.trim()) ?: continue
            val id = match.groupValues[1].lowercase()
            val url = match.groupValues[2]
            val title = match.groupValues[3]
            if (!defs.containsKey(id)) {
                defs[id] = Pair(url, title)
            }
        }
        return defs
    }

    companion object {
        private val LINK_DEF_REGEX = Regex("^\\[([^\\]]+)]:\\s+(\\S+)(?:\\s+[\"'](.+?)[\"'])?\\s*$")
    }
}
