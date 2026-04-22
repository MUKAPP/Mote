package com.mukapp.mote.ui.markdown

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.mukapp.mote.R
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.databinding.ItemToolResultBinding
import com.mukapp.mote.ui.IntermediateStepsHelper
import com.mukapp.mote.util.dp
import com.mukapp.mote.util.dpInt
import org.json.JSONObject

class MarkdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private enum class RenderMode {
        None,
        Markdown,
        Parts
    }

    private enum class AssistantPartKind {
        Markdown,
        Thinking,
        Tool
    }

    private data class RenderedAssistantPartState(
        val id: String,
        val kind: AssistantPartKind,
        val contentHash: Int,
        val expanded: Boolean
    )

    private val blockParser = BlockParser()
    private val spannedBuilder = SpannedBuilder(context)
    private val codeSpanRenderer: MarkdownCodeSpanRenderer = spannedBuilder.sharedCodeSpanRenderer
    private val defaultTextAppearanceRes = resolveThemeResource(
        context,
        com.google.android.material.R.attr.textAppearanceBodyLarge,
        0
    )
    private val inlineTextAppearanceRes: Int
    private val baseTextColor: ColorStateList?
    private val baseTextSizePx: Float
    private val bodyTextColor: Int
    private val primaryColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }
    private val secondaryTextColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt())
    }
    private val outlineVariantColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0.toInt())
    }
    private val quoteStripeColor: Int by lazy {
        blendWithAlpha(primaryColor, 0x7A)
    }
    private val quoteBackgroundColor: Int by lazy {
        blendWithAlpha(primaryColor, 0x10)
    }
    private val quoteStrokeColor: Int by lazy {
        blendWithAlpha(outlineVariantColor, 0xB8)
    }
    private val horizontalRuleColor: Int by lazy {
        blendWithAlpha(outlineVariantColor, 0x88)
    }
    private val taskMarkerOutlineColor: Int by lazy {
        outlineVariantColor
    }
    private val taskMarkerCheckedColor: Int by lazy {
        resolveThemeColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF6750A4.toInt())
    }
    private val taskMarkerCheckedBgColor: Int by lazy {
        blendWithAlpha(taskMarkerCheckedColor, 0x18)
    }
    private val thinkingCardBgColor: Int by lazy {
        blendWithAlpha(secondaryTextColor, 0x10)
    }
    private val thinkingCardStrokeColor: Int by lazy {
        blendWithAlpha(secondaryTextColor, 0x24)
    }
    private val quoteCornerRadius = 12.dp
    private val quoteStripeWidth = 6.dpInt
    private val quoteContentPaddingVertical = 8.dpInt
    private val quoteContentPaddingStart = 12.dpInt
    private val quoteContentPaddingEnd = 12.dpInt
    private var renderMode: RenderMode = RenderMode.None
    private var lastRenderedPartStates: List<RenderedAssistantPartState> = emptyList()
    private var lastRenderedLinkDefs: Map<String, Pair<String, String>> = emptyMap()
    /** 缓存上一次渲染 Markdown 文本时的 block 列表，用于流式增量更新 */
    private var lastRenderedBlocks: List<MdBlock> = emptyList()
    /** 缓存上一次渲染时的 isStreaming 状态，用于检测流式→非流式转换时强制全量重建 */
    private var lastRenderedIsStreaming: Boolean = false

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
        if (text.isBlank()) {
            clearMarkdown()
            return
        }
        isVisible = true

        val linkDefs = collectLinkDefs(text, isStreaming)
        val blocks = blockParser.parse(text, isStreaming, linkDefs)

        val canIncremental = renderMode == RenderMode.Markdown
            && lastRenderedLinkDefs == linkDefs
            && !(lastRenderedIsStreaming && !isStreaming)  // 流式→非流式需要全量重建以启用链接点击

        if (canIncremental) {
            renderBlocksIncrementally(this, blocks, isStreaming, linkDefs)
        } else {
            resetRenderedPartState()
            removeAllViews()
            blocks.forEachIndexed { index, block ->
                addView(
                    createBlockView(block, isStreaming, linkDefs, nested = false, isLastInContainer = index == blocks.lastIndex)
                )
            }
        }

        lastRenderedBlocks = blocks
        lastRenderedLinkDefs = linkDefs
        lastRenderedIsStreaming = isStreaming
        renderMode = RenderMode.Markdown
    }

    fun setParts(
        parts: List<AssistantPart>,
        isStreaming: Boolean,
        expandedThinkingPartIds: MutableSet<String>,
        expandedToolPartIds: MutableSet<String>
    ) {
        val visibleParts = parts.filter(::hasVisibleContent)
        if (visibleParts.isEmpty()) {
            clearMarkdown()
            return
        }
        isVisible = true

        val markdownText = visibleParts.asSequence()
            .mapNotNull { part ->
                when (part) {
                    is AssistantMarkdownPart -> part.text.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            .joinToString(separator = "\n\n")
        val linkDefs = collectLinkDefs(markdownText, isStreaming)
        val partStates = visibleParts.map { part ->
            createRenderedPartState(part, expandedThinkingPartIds, expandedToolPartIds)
        }

        if (canApplyIncrementalPartUpdate(partStates, linkDefs)) {
            renderPartViewsIncrementally(
                parts = visibleParts,
                partStates = partStates,
                isStreaming = isStreaming,
                linkDefs = linkDefs,
                expandedThinkingPartIds = expandedThinkingPartIds,
                expandedToolPartIds = expandedToolPartIds
            )
        } else {
            resetRenderedPartState()
            removeAllViews()
            visibleParts.forEach { part ->
                addView(
                    createPartView(
                        part = part,
                        isStreaming = isStreaming,
                        linkDefs = linkDefs,
                        expandedThinkingPartIds = expandedThinkingPartIds,
                        expandedToolPartIds = expandedToolPartIds
                    )
                )
            }
        }

        renderMode = RenderMode.Parts
        lastRenderedPartStates = partStates
        lastRenderedLinkDefs = linkDefs
    }

    fun clearMarkdown() {
        resetRenderedPartState()
        removeAllViews()
        isVisible = false
    }

    private fun renderMarkdownTextInto(
        container: ViewGroup,
        text: String,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ) {
        if (text.isBlank()) {
            return
        }
        val blocks = blockParser.parse(text, isStreaming, linkDefs)
        blocks.forEachIndexed { index, block ->
            container.addView(
                createBlockView(
                    block,
                    isStreaming,
                    linkDefs,
                    nested = false,
                    isLastInContainer = index == blocks.lastIndex
                )
            )
        }
    }

    /**
     * Block 级别增量更新：对比上一次的 block 列表，
     * 只替换发生变化的尾部 block，避免流式渲染时全量重建视图树。
     */
    private fun renderBlocksIncrementally(
        container: ViewGroup,
        newBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ) {
        val oldBlocks = lastRenderedBlocks
        val oldCount = oldBlocks.size
        val newCount = newBlocks.size

        // 找到第一个不同的 block 位置
        val sharedCount = minOf(oldCount, newCount)
        var firstDiffIndex = sharedCount
        for (i in 0 until sharedCount) {
            if (oldBlocks[i] != newBlocks[i]) {
                firstDiffIndex = i
                break
            }
        }

        // 如果完全相同，无需更新
        if (firstDiffIndex == newCount && oldCount == newCount) {
            return
        }

        // 移除从 firstDiffIndex 开始的旧视图
        while (container.childCount > firstDiffIndex) {
            container.removeViewAt(container.childCount - 1)
        }

        // 添加从 firstDiffIndex 开始的新 block 视图
        for (i in firstDiffIndex until newCount) {
            container.addView(
                createBlockView(
                    newBlocks[i],
                    isStreaming,
                    linkDefs,
                    nested = false,
                    isLastInContainer = i == newBlocks.lastIndex
                )
            )
        }
    }

    private fun hasVisibleContent(part: AssistantPart): Boolean {
        return when (part) {
            is AssistantMarkdownPart -> part.text.isNotBlank()
            is AssistantThinkingPart -> part.text.isNotBlank()
            is AssistantToolPart -> {
                part.toolName.isNotBlank() || part.toolArguments.isNotBlank() || part.result.isNotBlank()
            }
        }
    }

    private fun createPartView(
        part: AssistantPart,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        expandedThinkingPartIds: MutableSet<String>,
        expandedToolPartIds: MutableSet<String>
    ): View {
        return when (part) {
            is AssistantMarkdownPart -> LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                renderMarkdownTextInto(this, part.text, isStreaming, linkDefs)
            }

            is AssistantThinkingPart -> createThinkingPartView(part, expandedThinkingPartIds)
            is AssistantToolPart -> createToolPartView(part, expandedToolPartIds)
        }
    }

    private fun createRenderedPartState(
        part: AssistantPart,
        expandedThinkingPartIds: Set<String>,
        expandedToolPartIds: Set<String>
    ): RenderedAssistantPartState {
        return when (part) {
            is AssistantMarkdownPart -> RenderedAssistantPartState(
                id = part.id,
                kind = AssistantPartKind.Markdown,
                contentHash = part.text.hashCode(),
                expanded = false
            )

            is AssistantThinkingPart -> RenderedAssistantPartState(
                id = part.id,
                kind = AssistantPartKind.Thinking,
                contentHash = part.text.hashCode(),
                expanded = expandedThinkingPartIds.contains(part.id)
            )

            is AssistantToolPart -> RenderedAssistantPartState(
                id = part.id,
                kind = AssistantPartKind.Tool,
                contentHash = 31 * (31 * part.toolName.hashCode() + part.toolArguments.hashCode()) + part.result.hashCode(),
                expanded = expandedToolPartIds.contains(part.id)
            )
        }
    }

    private fun canApplyIncrementalPartUpdate(
        nextPartStates: List<RenderedAssistantPartState>,
        nextLinkDefs: Map<String, Pair<String, String>>
    ): Boolean {
        if (renderMode != RenderMode.Parts || childCount != lastRenderedPartStates.size) {
            return false
        }
        if (lastRenderedLinkDefs != nextLinkDefs) {
            return false
        }
        val sharedCount = minOf(lastRenderedPartStates.size, nextPartStates.size)
        for (index in 0 until sharedCount) {
            val previous = lastRenderedPartStates[index]
            val next = nextPartStates[index]
            if (previous.id != next.id || previous.kind != next.kind) {
                return false
            }
        }
        return true
    }

    private fun renderPartViewsIncrementally(
        parts: List<AssistantPart>,
        partStates: List<RenderedAssistantPartState>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        expandedThinkingPartIds: MutableSet<String>,
        expandedToolPartIds: MutableSet<String>
    ) {
        val sharedCount = minOf(lastRenderedPartStates.size, partStates.size)
        for (index in 0 until sharedCount) {
            if (lastRenderedPartStates[index] == partStates[index]) {
                continue
            }
            replacePartViewAt(
                index = index,
                view = createPartView(
                    part = parts[index],
                    isStreaming = isStreaming,
                    linkDefs = linkDefs,
                    expandedThinkingPartIds = expandedThinkingPartIds,
                    expandedToolPartIds = expandedToolPartIds
                )
            )
        }

        while (childCount > partStates.size) {
            removeViewAt(childCount - 1)
        }

        for (index in sharedCount until partStates.size) {
            addView(
                createPartView(
                    part = parts[index],
                    isStreaming = isStreaming,
                    linkDefs = linkDefs,
                    expandedThinkingPartIds = expandedThinkingPartIds,
                    expandedToolPartIds = expandedToolPartIds
                )
            )
        }
    }

    private fun replacePartViewAt(index: Int, view: View) {
        removeViewAt(index)
        addView(view, index)
    }

    private fun resetRenderedPartState() {
        renderMode = RenderMode.None
        lastRenderedPartStates = emptyList()
        lastRenderedLinkDefs = emptyMap()
        lastRenderedBlocks = emptyList()
        lastRenderedIsStreaming = false
    }

    private fun createThinkingPartView(
        part: AssistantThinkingPart,
        expandedThinkingPartIds: MutableSet<String>
    ): View {
        if (part.text.isBlank()) {
            return View(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
            }
        }
        val expanded = expandedThinkingPartIds.contains(part.id)
        return MaterialCardView(context).apply {
            layoutParams = createBlockLayoutParams()
            radius = 12.dp
            strokeWidth = 1.dpInt.coerceAtLeast(1)
            strokeColor = thinkingCardStrokeColor
            setCardBackgroundColor(thinkingCardBgColor)
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(LinearLayout(context).apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val selectableAttrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                        foreground = selectableAttrs.getDrawable(0)
                        selectableAttrs.recycle()
                        isClickable = true
                        isFocusable = true
                        setPadding(12.dpInt, 12.dpInt, 12.dpInt, 12.dpInt)

                        addView(TextView(context).apply {
                            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                            setText(R.string.label_thinking)
                            setTextColor(secondaryTextColor)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setTypeface(typeface, Typeface.BOLD)
                        })
                        addView(ImageView(context).apply {
                            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                            contentDescription = context.getString(
                                if (expanded) R.string.action_collapse else R.string.action_expand
                            )
                            setImageResource(
                                if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                            )
                        })
                    })
                    addView(TextView(context).apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            leftMargin = 12.dpInt
                            rightMargin = 12.dpInt
                            bottomMargin = 12.dpInt
                        }
                        text = part.text
                        setTextColor(secondaryTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = Typeface.DEFAULT
                        setLineSpacing(0f, 1.15f)
                        includeFontPadding = false
                        setTextIsSelectable(true)
                        isVisible = expanded
                    })

                    val headerView = getChildAt(0)
                    val contentView = getChildAt(1)
                    val toggleView = (headerView as LinearLayout).getChildAt(1) as ImageView
                    val toggle = View.OnClickListener {
                        val nextExpanded = !contentView.isVisible
                        contentView.isVisible = nextExpanded
                        toggleView.contentDescription = context.getString(
                            if (nextExpanded) R.string.action_collapse else R.string.action_expand
                        )
                        toggleView.setImageResource(
                            if (nextExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        )
                        if (nextExpanded) {
                            expandedThinkingPartIds.add(part.id)
                        } else {
                            expandedThinkingPartIds.remove(part.id)
                        }
                    }
                    headerView.setOnClickListener(toggle)
                }
            )
        }
    }

    private fun createToolPartView(
        toolPart: AssistantToolPart,
        expandedToolPartIds: MutableSet<String>
    ): View {
        val binding = ItemToolResultBinding.inflate(LayoutInflater.from(context), this, false)
        binding.root.layoutParams = createBlockLayoutParams()
        val expanded = expandedToolPartIds.contains(toolPart.id)

        binding.textSummary.text = IntermediateStepsHelper.parseToolSummary(toolPart.toolName, toolPart.toolArguments)

        var detailPopulated = false
        fun populateDetail() {
            if (detailPopulated) {
                return
            }
            detailPopulated = true
            binding.textArguments.text = if (toolPart.toolArguments.isBlank()) {
                ""
            } else {
                runCatching { JSONObject(toolPart.toolArguments).toString(2) }.getOrDefault(toolPart.toolArguments)
            }
            binding.textResult.text = toolPart.result
            binding.groupArguments.isVisible = binding.textArguments.text.isNotBlank()
        }

        if (expanded) {
            populateDetail()
        }
        binding.containerDetail.isVisible = expanded
        binding.btnToggleDetail.setImageResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )

        val toggle = View.OnClickListener {
            val nextExpanded = !binding.containerDetail.isVisible
            if (nextExpanded) {
                populateDetail()
                expandedToolPartIds.add(toolPart.id)
            } else {
                expandedToolPartIds.remove(toolPart.id)
            }
            binding.containerDetail.isVisible = nextExpanded
            binding.btnToggleDetail.setImageResource(
                if (nextExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }
        binding.layoutHeader.setOnClickListener(toggle)
        return binding.root
    }

    private fun createBlockView(
        block: MdBlock,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        return when (block) {
            is MdBlock.Table -> createTableView(block, isStreaming, linkDefs, nested, isLastInContainer)
            is MdBlock.CodeBlock -> createCodeBlockView(block, nested, isLastInContainer)
            is MdBlock.Blockquote -> createBlockquoteView(block, isStreaming, linkDefs, nested, isLastInContainer)
            is MdBlock.UnorderedList -> createListView(block.items, false, isStreaming, linkDefs, nested, isLastInContainer)
            is MdBlock.OrderedList -> createOrderedListView(block, isStreaming, linkDefs, nested, isLastInContainer)
            is MdBlock.TaskList -> createTaskListView(block, isStreaming, linkDefs, nested, isLastInContainer)
            is MdBlock.HorizontalRule -> createHorizontalRuleView(nested, isLastInContainer)
            else -> createTextBlockView(block, isStreaming, linkDefs, nested, isLastInContainer)
        }
    }

    private fun createTextBlockView(
        block: MdBlock,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): TextView {
        val textView = createBaseTextView()
        val text = spannedBuilder.buildSingleBlock(block, isStreaming = isStreaming, linkDefs = linkDefs)
        textView.text = text
        applyTextBlockStyle(textView, block, nested, isLastInContainer)
        if (isStreaming) {
            textView.movementMethod = null
        } else {
            textView.movementMethod = LinkMovementMethod.getInstance()
        }
        return textView
    }

    private fun createCodeBlockView(
        codeBlock: MdBlock.CodeBlock,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        return MarkdownCodeBlockView(context).apply {
            layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
            setSharedCodeSpanRenderer(codeSpanRenderer)
            setCodeBlock(codeBlock.language, codeBlock.code)
        }
    }

    private fun createTableView(
        table: MdBlock.Table,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        val scrollView = object : HorizontalScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                isFillViewport = false
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        scrollView.layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val tableView = MarkdownTableView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTableData(table.headers, table.rows, table.alignments, linkDefs, isStreaming)
        }
        scrollView.addView(tableView)
        return scrollView
    }

    private fun createBlockquoteView(
        blockquote: MdBlock.Blockquote,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        val container = FrameLayout(context).apply {
            layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
            minimumHeight = 24.dpInt
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = quoteCornerRadius
                setColor(quoteBackgroundColor)
            }
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = quoteCornerRadius
                setColor(Color.TRANSPARENT)
                setStroke(1.dpInt.coerceAtLeast(1), quoteStrokeColor)
            }
            clipToOutline = true
        }

        val stripe = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(quoteStripeWidth, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(quoteStripeColor)
        }

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                quoteStripeWidth + quoteContentPaddingStart,
                quoteContentPaddingVertical,
                quoteContentPaddingEnd,
                quoteContentPaddingVertical
            )
        }

        blockquote.children.forEachIndexed { index, child ->
            content.addView(
                createBlockView(
                    child,
                    isStreaming,
                    linkDefs,
                    nested = true,
                    isLastInContainer = index == blockquote.children.lastIndex
                )
            )
        }

        container.addView(stripe)
        container.addView(content)
        return container
    }

    private fun createListView(
        items: List<List<MdBlock>>,
        numbered: Boolean,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
        }
        items.forEachIndexed { index, childBlocks ->
            container.addView(
                createListItemView(
                    if (numbered) "${index + 1}." else "•",
                    childBlocks,
                    isStreaming,
                    linkDefs,
                    nested,
                    isLastItem = index == items.lastIndex
                )
            )
        }
        return container
    }

    private fun createOrderedListView(
        list: MdBlock.OrderedList,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        return createListView(
            list.items,
            numbered = true,
            isStreaming = isStreaming,
            linkDefs = linkDefs,
            nested = nested,
            isLastInContainer = isLastInContainer
        )
    }

    private fun createTaskListView(
        list: MdBlock.TaskList,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
        }
        list.items.forEachIndexed { index, (taskItem, childBlocks) ->
            container.addView(
                createTaskListItemView(
                    taskItem.checked,
                    childBlocks,
                    isStreaming,
                    linkDefs,
                    nested,
                    isLastItem = index == list.items.lastIndex
                )
            )
        }
        return container
    }

    private fun createTaskListItemView(
        checked: Boolean,
        childBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastItem: Boolean
    ): View {
        return createListItemRow(createTaskMarkerView(checked), childBlocks, isStreaming, linkDefs, nested, isLastItem)
    }

    private fun createListItemView(
        marker: String,
        childBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastItem: Boolean
    ): View {
        return createListItemRow(createTextMarkerView(marker), childBlocks, isStreaming, linkDefs, nested, isLastItem)
    }

    private fun createListItemRow(
        markerView: View,
        childBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastItem: Boolean
    ): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = createBlockLayoutParams(bottomMargin = listItemBottomMargin(nested, isLastItem))
            gravity = Gravity.TOP
        }

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        childBlocks.forEachIndexed { index, child ->
            content.addView(
                createBlockView(
                    child,
                    isStreaming,
                    linkDefs,
                    nested = true,
                    isLastInContainer = index == childBlocks.lastIndex
                )
            )
        }

        row.addView(markerView)
        row.addView(content)
        return row
    }

    private fun createTextMarkerView(marker: String): TextView {
        return createBaseTextView().apply {
            layoutParams = LayoutParams(24.dpInt, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8.dpInt
            }
            text = marker
            setTextColor(secondaryTextColor)
            gravity = Gravity.END
            textAlignment = TEXT_ALIGNMENT_VIEW_END
        }
    }

    private fun createTaskMarkerView(checked: Boolean): View {
        val markerWidth = 24.dpInt
        val boxSize = 18.dpInt
        val innerSize = 8.dpInt
        val strokeWidth = 1.dpInt.coerceAtLeast(1)
        return FrameLayout(context).apply {
            layoutParams = LayoutParams(markerWidth, boxSize).apply {
                topMargin = 2.dpInt
                marginEnd = 8.dpInt
            }
            addView(FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(boxSize, boxSize, Gravity.TOP or Gravity.END)
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
            })
        }
    }

    private fun createHorizontalRuleView(nested: Boolean, isLastInContainer: Boolean): View {
        return View(context).apply {
            layoutParams = createBlockLayoutParams(
                topMargin = if (nested) 8.dpInt else 10.dpInt,
                bottomMargin = if (nested && isLastInContainer) 0 else if (nested) 8.dpInt else 10.dpInt
            ).apply {
                height = 1.dpInt
            }
            setBackgroundColor(horizontalRuleColor)
        }
    }

    private fun applyTextBlockStyle(
        textView: TextView,
        block: MdBlock,
        nested: Boolean,
        isLastInContainer: Boolean
    ) {
        when (block) {
            is MdBlock.Heading -> {
                textView.layoutParams = createBlockLayoutParams(
                    topMargin = if (nested) 4.dpInt else if (block.level <= 2) 10.dpInt else 6.dpInt,
                    bottomMargin = if (nested && isLastInContainer) 0 else if (nested) 6.dpInt else if (block.level <= 2) 10.dpInt else 8.dpInt
                )
                textView.setTextColor(
                    when {
                        block.level <= 2 -> bodyTextColor
                        block.level <= 4 -> blendWithAlpha(bodyTextColor, 0xF0)
                        else -> secondaryTextColor
                    }
                )
                textView.setLineSpacing(0f, if (block.level <= 2) 1.08f else 1.12f)
                textView.letterSpacing = when (block.level) {
                    1 -> -0.01f
                    2 -> -0.005f
                    else -> 0f
                }
            }

            is MdBlock.Paragraph -> {
                textView.layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
                textView.setLineSpacing(0f, 1.22f)
            }

            else -> {
                textView.layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
            }
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

    private fun defaultBlockBottomMargin(nested: Boolean): Int {
        return if (nested) 6.dpInt else 10.dpInt
    }

    private fun blockBottomMargin(nested: Boolean, isLastInContainer: Boolean): Int {
        return if (nested && isLastInContainer) 0 else defaultBlockBottomMargin(nested)
    }

    private fun listItemBottomMargin(nested: Boolean, isLastItem: Boolean): Int {
        if (isLastItem) {
            return 0
        }
        return if (nested) 2.dpInt else 4.dpInt
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
