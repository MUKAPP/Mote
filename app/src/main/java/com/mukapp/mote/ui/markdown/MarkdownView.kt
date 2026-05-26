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
import android.view.View.MeasureSpec
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
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
import io.ratex.RaTeXView
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

    /** Markdown 文本的解析结果缓存条目，记录文本与对应的 block 列表、链接定义及流式状态 */
    private data class CachedParseEntry(
        val text: String,
        val isStreaming: Boolean,
        val blocks: List<MdBlock>,
        val linkDefs: Map<String, Pair<String, String>>
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
        resolveThemeColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
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
        resolveThemeColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
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
    /** 缓存上一次 setMarkdown 调用的输入文本，用于在文本未变化时直接复用解析结果 */
    private var lastRenderedMarkdownText: String? = null
    /** 每个 markdown 片段单独缓存的 (text, isStreaming) -> ParseResult，用于 setParts 路径下避免重复解析 */
    private val partParseCache = HashMap<String, CachedParseEntry>()

    /** 外部注入的全局解析缓存，用于跨 ViewHolder 复用后台预解析结果 */
    private var globalParseCache: MarkdownParseCache? = null

    /** 设置全局解析缓存引用，由 ChatMessageAdapter 在创建 ViewHolder 时注入 */
    fun setGlobalParseCache(cache: MarkdownParseCache?) {
        globalParseCache = cache
    }

    /**
     * 为 setParts 路径中每个 markdown part 缓存上一次渲染的 block 列表和 linkDefs，
     * 以便在内容变化时对其容器 LinearLayout 执行 block 级增量更新而非整体重建。
     * key = part id
     */
    private data class PartBlocksCache(
        val blocks: List<MdBlock>,
        val linkDefs: Map<String, Pair<String, String>>,
        val isStreaming: Boolean
    )
    private val partBlocksCache = HashMap<String, PartBlocksCache>()

    init {
        orientation = VERTICAL
        val fallbackTextColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        val typedArray = context.obtainStyledAttributes(
            attrs,
            MARKDOWN_VIEW_TEXT_ATTRS,
            defStyleAttr,
            0
        )
        inlineTextAppearanceRes = typedArray.getResourceId(INDEX_TEXT_APPEARANCE, defaultTextAppearanceRes)
        baseTextColor = typedArray.getColorStateList(INDEX_TEXT_COLOR)
        bodyTextColor = baseTextColor?.defaultColor ?: fallbackTextColor
        val defaultTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            16f,
            resources.displayMetrics
        )
        baseTextSizePx = typedArray.getDimension(INDEX_TEXT_SIZE, defaultTextSize)
        typedArray.recycle()
        spannedBuilder.inlineMathTextSizePx = baseTextSizePx
    }

    fun setMarkdown(text: String, isStreaming: Boolean) {
        if (text.isBlank()) {
            clearMarkdown()
            return
        }
        isVisible = true

        // 与上一次完全一致时直接跳过解析与视图更新（典型场景：流式停止后又收到一次同状态刷新）
        if (renderMode == RenderMode.Markdown
            && lastRenderedMarkdownText == text
            && lastRenderedIsStreaming == isStreaming
        ) {
            return
        }

        val parseResult = obtainParseResultGlobal(text, isStreaming)
        val blocks = parseResult.blocks
        val linkDefs = parseResult.linkDefs

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
        lastRenderedMarkdownText = text
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

        // 收集所有当前可见的 markdown 片段文本，用于淘汰已不再使用的解析缓存
        val activeMarkdownTexts = HashSet<String>()
        visibleParts.forEach { part ->
            if (part is AssistantMarkdownPart && part.text.isNotBlank()) {
                activeMarkdownTexts.add(part.text)
            }
        }
        // 当 isStreaming 与缓存不一致或文本不再使用时清理对应条目
        val cacheKeysToDrop = ArrayList<String>()
        for ((key, entry) in partParseCache) {
            if (entry.isStreaming != isStreaming || entry.text !in activeMarkdownTexts) {
                cacheKeysToDrop.add(key)
            }
        }
        cacheKeysToDrop.forEach { partParseCache.remove(it) }

        val partStates = visibleParts.map { part ->
            createRenderedPartState(part, expandedThinkingPartIds, expandedToolPartIds)
        }

        // setParts 模式下不需要全局 linkDefs 比较；每个 markdown 片段内部的 linkDefs 跟随其文本一同进入缓存

        if (canApplyIncrementalPartUpdate(partStates)) {
            renderPartViewsIncrementally(
                parts = visibleParts,
                partStates = partStates,
                isStreaming = isStreaming,
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
                        expandedThinkingPartIds = expandedThinkingPartIds,
                        expandedToolPartIds = expandedToolPartIds
                    )
                )
            }
        }

        renderMode = RenderMode.Parts
        lastRenderedPartStates = partStates
        lastRenderedLinkDefs = emptyMap()
    }

    fun clearMarkdown() {
        resetRenderedPartState()
        removeAllViews()
        isVisible = false
    }

    private fun renderMarkdownTextInto(
        container: ViewGroup,
        text: String,
        isStreaming: Boolean
    ) {
        if (text.isBlank()) {
            return
        }
        val parseResult = obtainParseResult(text, isStreaming)
        val blocks = parseResult.blocks
        val linkDefs = parseResult.linkDefs
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
     * 将 markdown part 渲染到容器中，同时缓存 block 列表以便后续增量更新。
     */
    private fun renderMarkdownPartInto(
        container: ViewGroup,
        part: AssistantMarkdownPart,
        isStreaming: Boolean
    ) {
        if (part.text.isBlank()) return
        val parseResult = obtainParseResult(part.text, isStreaming)
        val blocks = parseResult.blocks
        val linkDefs = parseResult.linkDefs
        blocks.forEachIndexed { index, block ->
            container.addView(
                createBlockView(block, isStreaming, linkDefs, nested = false, isLastInContainer = index == blocks.lastIndex)
            )
        }
        partBlocksCache[part.id] = PartBlocksCache(blocks, linkDefs, isStreaming)
    }

    /** 优先复用 partParseCache / 全局缓存中的解析结果，避免对相同文本的重复解析 */
    private fun obtainParseResult(text: String, isStreaming: Boolean): BlockParser.ParseResult {
        val cached = partParseCache[text]
        if (cached != null && cached.text == text && cached.isStreaming == isStreaming) {
            return BlockParser.ParseResult(cached.blocks, cached.linkDefs)
        }
        // 查询全局预解析缓存
        val globalCached = globalParseCache?.get(text, isStreaming)
        if (globalCached != null) {
            partParseCache[text] = CachedParseEntry(text, isStreaming, globalCached.blocks, globalCached.linkDefs)
            return globalCached
        }
        val parsed = blockParser.parseWithLinkDefs(text, isStreaming)
        partParseCache[text] = CachedParseEntry(text, isStreaming, parsed.blocks, parsed.linkDefs)
        return parsed
    }

    /** setMarkdown 路径使用的全局缓存优先解析 */
    private fun obtainParseResultGlobal(text: String, isStreaming: Boolean): BlockParser.ParseResult {
        val globalCached = globalParseCache?.get(text, isStreaming)
        if (globalCached != null) return globalCached
        return blockParser.parseWithLinkDefs(text, isStreaming)
    }

    /**
     * Block 级别增量更新：对比上一次的 block 列表，
     * 只替换发生变化的尾部 block，避免流式渲染时全量重建视图树。
     */
    private fun renderBlocksIncrementally(
        container: ViewGroup,
        newBlocks: List<MdBlock>,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        oldBlocks: List<MdBlock> = lastRenderedBlocks
    ) {
        val oldCount = oldBlocks.size
        val newCount = newBlocks.size

        // 找到第一个不同的 block 位置；先做廉价的类型与 offset 比较，再回退到完整 equals
        val sharedCount = minOf(oldCount, newCount)
        var firstDiffIndex = sharedCount
        for (i in 0 until sharedCount) {
            if (!areBlocksEquivalent(oldBlocks[i], newBlocks[i])) {
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

    /**
     * 廉价快速判等：先比较类型与 offset，只有当 offset 不一致或属于内容易变类型时才回退到完整 equals。
     * 流式追加场景下，前缀 block 的 offset 必然稳定，绝大多数情况下可以以 O(1) 短路命中。
     */
    private fun areBlocksEquivalent(a: MdBlock, b: MdBlock): Boolean {
        if (a === b) return true
        if (a.javaClass !== b.javaClass) return false
        if (a.startOffset != b.startOffset || a.endOffset != b.endOffset) return false
        // offset 一致但内容仍可能不同：例如 CodeBlock 的 closed 状态、List/Blockquote 的子结构。
        // 直接走 data class 全字段比较确保正确性；该路径仅在 offset 命中时触发，开销可控。
        return a == b
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
        expandedThinkingPartIds: MutableSet<String>,
        expandedToolPartIds: MutableSet<String>
    ): View {
        return when (part) {
            is AssistantMarkdownPart -> LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                renderMarkdownPartInto(this, part, isStreaming)
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
                contentHash = 31 * (31 * (31 * part.toolName.hashCode() + part.toolArguments.hashCode()) + part.result.hashCode()) + part.isLoading.hashCode(),
                expanded = expandedToolPartIds.contains(part.id)
            )
        }
    }

    private fun canApplyIncrementalPartUpdate(
        nextPartStates: List<RenderedAssistantPartState>
    ): Boolean {
        if (renderMode != RenderMode.Parts || childCount != lastRenderedPartStates.size) {
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
        expandedThinkingPartIds: MutableSet<String>,
        expandedToolPartIds: MutableSet<String>
    ) {
        val sharedCount = minOf(lastRenderedPartStates.size, partStates.size)
        for (index in 0 until sharedCount) {
            if (lastRenderedPartStates[index] == partStates[index]) {
                continue
            }
            val part = parts[index]
            // 对于 markdown 片段，尝试复用已有容器做 block 级增量更新
            if (part is AssistantMarkdownPart && part.text.isNotBlank()) {
                val existingView = getChildAt(index)
                if (existingView is LinearLayout && updateMarkdownPartInPlace(existingView, part, isStreaming)) {
                    continue
                }
            }
            replacePartViewAt(
                index = index,
                view = createPartView(
                    part = parts[index],
                    isStreaming = isStreaming,
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

    /**
     * 对已有的 markdown part 容器执行 block 级增量更新，避免整体重建视图树。
     * 返回 true 表示成功原地更新，false 表示需要回退到全量重建。
     */
    private fun updateMarkdownPartInPlace(
        container: LinearLayout,
        part: AssistantMarkdownPart,
        isStreaming: Boolean
    ): Boolean {
        val parseResult = obtainParseResult(part.text, isStreaming)
        val newBlocks = parseResult.blocks
        val newLinkDefs = parseResult.linkDefs

        val cached = partBlocksCache[part.id]
        if (cached == null || cached.linkDefs != newLinkDefs || (cached.isStreaming && !isStreaming)) {
            // 没有缓存、linkDefs 变化、或从流式切换到非流式——需要全量重建
            container.removeAllViews()
            newBlocks.forEachIndexed { index, block ->
                container.addView(
                    createBlockView(block, isStreaming, newLinkDefs, nested = false, isLastInContainer = index == newBlocks.lastIndex)
                )
            }
            partBlocksCache[part.id] = PartBlocksCache(newBlocks, newLinkDefs, isStreaming)
            return true
        }

        // 执行 block 级增量更新
        renderBlocksIncrementally(container, newBlocks, isStreaming, newLinkDefs, cached.blocks)
        partBlocksCache[part.id] = PartBlocksCache(newBlocks, newLinkDefs, isStreaming)
        return true
    }

    private fun resetRenderedPartState() {
        renderMode = RenderMode.None
        lastRenderedPartStates = emptyList()
        lastRenderedLinkDefs = emptyMap()
        lastRenderedBlocks = emptyList()
        lastRenderedIsStreaming = false
        lastRenderedMarkdownText = null
        partParseCache.clear()
        partBlocksCache.clear()
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
        return MaterialCardView(context).apply thinkingCard@ {
            layoutParams = createBlockLayoutParams()
            radius = 12.dp
            strokeWidth = 0
            cardElevation = 0f
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
                        if (nextExpanded) {
                            expandedThinkingPartIds.add(part.id)
                        } else {
                            expandedThinkingPartIds.remove(part.id)
                        }
                        // 启动高度变化过渡动画
                        val animParent = this@thinkingCard.parent as? ViewGroup
                        if (animParent != null) {
                            TransitionManager.beginDelayedTransition(
                                animParent,
                                ChangeBounds().apply {
                                    duration = 200
                                    interpolator = DecelerateInterpolator()
                                }
                            )
                        }
                        contentView.isVisible = nextExpanded
                        toggleView.contentDescription = context.getString(
                            if (nextExpanded) R.string.action_collapse else R.string.action_expand
                        )
                        toggleView.setImageResource(
                            if (nextExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        )
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

        binding.imageToolIcon.setImageResource(toolIconRes(toolPart.toolName))

        val summary = IntermediateStepsHelper.parseToolSummary(toolPart.toolName, toolPart.toolArguments)
        binding.textSummary.text = if (toolPart.isLoading) {
            context.getString(R.string.tool_summary_loading, summary)
        } else {
            summary
        }

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
            // 在父容器上启动过渡动画，实现平滑展开/折叠
            val parent = binding.root.parent as? ViewGroup
            if (parent != null) {
                val transition = ChangeBounds().apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
                }
                TransitionManager.beginDelayedTransition(parent, transition)
            }
            binding.containerDetail.isVisible = nextExpanded
            binding.btnToggleDetail.setImageResource(
                if (nextExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }
        binding.layoutHeader.setOnClickListener(toggle)
        return binding.root
    }

    @DrawableRes
    private fun toolIconRes(toolName: String): Int {
        return when (toolName) {
            "read_file", "read_local_file" -> R.drawable.ic_description
            "list_path" -> R.drawable.ic_folder_open
            "fetch_url" -> R.drawable.ic_link
            "fetch_webview" -> R.drawable.ic_web_asset
            "web_search" -> R.drawable.ic_travel_explore
            "shell" -> R.drawable.ic_terminal
            "shell_status" -> R.drawable.ic_manage_search
            "shell_stop" -> R.drawable.ic_stop_circle
            "wait" -> R.drawable.ic_hourglass_empty
            else -> R.drawable.ic_build
        }
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
            is MdBlock.MathBlock -> createMathBlockView(block, nested, isLastInContainer)
            is MdBlock.Blockquote -> createBlockquoteView(block, isStreaming, linkDefs, nested, isLastInContainer)
            is MdBlock.UnorderedList -> createListView(
                items = block.items,
                numbered = false,
                isStreaming = isStreaming,
                linkDefs = linkDefs,
                nested = nested,
                isLastInContainer = isLastInContainer
            )
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

    private fun createMathBlockView(
        mathBlock: MdBlock.MathBlock,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        if (!mathBlock.closed) {
            return createMathFallbackTextView(mathBlock, nested, isLastInContainer)
        }

        val fallbackView = createBaseTextView().apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            text = mathBlockFallbackText(mathBlock)
            setTextColor(secondaryTextColor)
            setLineSpacing(0f, 1.15f)
            isVisible = false
        }

        val mathView = RaTeXView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            fontSize = (baseTextSizePx / resources.displayMetrics.density * 1.12f).coerceAtLeast(12f)
            displayMode = true
            color = bodyTextColor
            onError = {
                isVisible = false
                fallbackView.isVisible = true
            }
            latex = mathBlock.formula
        }

        val content = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(mathView)
            addView(fallbackView)
        }

        return HorizontalScrollView(context).apply {
            layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
    }

    private fun createMathFallbackTextView(
        mathBlock: MdBlock.MathBlock,
        nested: Boolean,
        isLastInContainer: Boolean
    ): TextView {
        return createBaseTextView().apply {
            layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
            text = mathBlockFallbackText(mathBlock)
            setTextColor(secondaryTextColor)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.12f)
        }
    }

    private fun mathBlockFallbackText(mathBlock: MdBlock.MathBlock): String {
        val closeDelimiter = when (mathBlock.delimiter) {
            "$$" -> "$$"
            "\\[" -> "\\]"
            else -> mathBlock.delimiter
        }
        return if (mathBlock.closed) {
            "${mathBlock.delimiter}\n${mathBlock.formula}\n$closeDelimiter"
        } else {
            "${mathBlock.delimiter}\n${mathBlock.formula}"
        }
    }

    private fun createTableView(
        table: MdBlock.Table,
        isStreaming: Boolean,
        linkDefs: Map<String, Pair<String, String>>,
        nested: Boolean,
        isLastInContainer: Boolean
    ): View {
        val tableView = MarkdownTableView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTableData(table.headers, table.rows, table.alignments, linkDefs, isStreaming)
        }

        val scrollView = object : HorizontalScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                isFillViewport = false
                val viewportWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    0
                } else {
                    (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                }
                tableView.setAvailableViewportWidth(viewportWidth)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        scrollView.layoutParams = createBlockLayoutParams(bottomMargin = blockBottomMargin(nested, isLastInContainer))
        scrollView.isHorizontalScrollBarEnabled = false
        scrollView.overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

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
        startNumber: Int = 1,
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
                    if (numbered) "${startNumber + index}." else "•",
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
            startNumber = list.startNumber,
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

    companion object {
        private const val INDEX_TEXT_APPEARANCE = 0
        private const val INDEX_TEXT_COLOR = 1
        private const val INDEX_TEXT_SIZE = 2

        private val MARKDOWN_VIEW_TEXT_ATTRS = intArrayOf(
            android.R.attr.textAppearance,
            android.R.attr.textColor,
            android.R.attr.textSize
        )
    }
}
