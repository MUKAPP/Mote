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
    private val thinkingCardBgColor: Int by lazy {
        blendWithAlpha(secondaryTextColor, 0x10)
    }
    private val thinkingCardStrokeColor: Int by lazy {
        blendWithAlpha(secondaryTextColor, 0x24)
    }
    private var renderMode: RenderMode = RenderMode.None
    private var lastRenderedPartStates: List<RenderedAssistantPartState> = emptyList()
    private var lastRenderedLinkDefs: Map<String, Pair<String, String>> = emptyMap()

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

        resetRenderedPartState()
        removeAllViews()
        renderMarkdownTextInto(this, text, isStreaming, collectLinkDefs(text, isStreaming))
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
        val blocks = blockParser.parse(text, isStreaming = isStreaming)
        blocks.forEach { block ->
            container.addView(createBlockView(block, isStreaming, linkDefs))
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
        linkDefs: Map<String, Pair<String, String>>
    ): View {
        return when (block) {
            is MdBlock.Table -> createTableView(block, isStreaming, linkDefs)
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

    private fun createTableView(
        table: MdBlock.Table,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>
    ): View {
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
            setTableData(table.headers, table.rows, table.alignments, linkDefs, isStreaming)
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
