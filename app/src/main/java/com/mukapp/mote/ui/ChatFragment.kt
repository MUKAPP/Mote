package com.mukapp.mote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.databinding.FragmentChatBinding
import com.mukapp.mote.ui.markdown.MarkdownParseCache
import com.mukapp.mote.util.dpInt
import kotlin.math.max
import androidx.core.view.isVisible
import eightbitlab.com.blurview.BlurView

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by activityViewModels()
    private lateinit var adapter: ChatMessageAdapter
    private val parseCache = MarkdownParseCache()

    private var latestMessages: List<ChatMessage> = emptyList()
    private var latestSettings: ApiSettings = ApiSettings()
    private var latestIsSending: Boolean = false
    private var latestShellConfirmation: ShellConfirmationUiState? = null
    private var followOutput: Boolean = true
    private var userScrolling: Boolean = false
    private var updatingDraft: Boolean = false
    private var pendingImmediateScrollToBottom: Boolean = false
    private var observedConversationId: String? = null
    private var renderMessagesPending: Boolean = false
    private var lastRenderedMessageCount: Int = 0
    private var lastStreamingMessageSignature: String? = null
    private var lastStreamingScrollUptime: Long = 0L
    private var scrollAfterLayoutPending: Boolean = false
    private val smoothScrollToBottomRunnable = Runnable { scrollToBottomIfScrollable(animated = true) }
    private val immediateScrollToBottomRunnable = Runnable { scrollToBottom(animated = false) }
    private val throttledSmoothScrollToBottomRunnable = Runnable {
        lastStreamingScrollUptime = SystemClock.uptimeMillis()
        scrollToBottomIfScrollable(animated = true)
    }
    private val markdownPreparseRunnable = Runnable { runMarkdownPreparseForViewport() }
    private var lastMarkdownPreparseSignature: String? = null
    private var lastMarkdownPreparseUptime: Long = 0L
    private var lastScrollDy: Int = 0

    private var systemBottomInset = 0
    private var imeBottomInset = 0
    private val topOffset = (56 + 16).dpInt
    private val cardMarginBottom = 16.dpInt
    private val bottomOffset = cardMarginBottom + 8.dpInt
    private val confirmationCardGap = 8.dpInt

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupInputArea()
        observeViewModel()

        binding.recyclerMessages.clipToPadding = false

        var imeAnimationRunning = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            systemBottomInset = systemBars.bottom
            imeBottomInset = ime.bottom

            if (!imeAnimationRunning) {
                val currentBottom = max(systemBottomInset, imeBottomInset)

                updateContentPadding(
                    top = systemBars.top + topOffset,
                    bottom = currentBottom + inputStackHeight() + bottomOffset
                )

                binding.cardInput.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = currentBottom + cardMarginBottom
                }
            }
            insets
        }

        val inputStackLayoutChangeListener =
            View.OnLayoutChangeListener { changedView, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if ((bottom - top) != (oldBottom - oldTop)) {
                    updateContentPadding(
                        bottom = max(
                            systemBottomInset,
                            imeBottomInset
                        ) + inputStackHeight() + bottomOffset,
                        scrollRecyclerByBottomDelta = changedView == binding.cardShellConfirmation
                    )
                }
            }
        binding.cardInput.addOnLayoutChangeListener(inputStackLayoutChangeListener)
        binding.cardShellConfirmation.addOnLayoutChangeListener(inputStackLayoutChangeListener)

        val animationCallback = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
            private var previousBottom = 0

            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                super.onPrepare(animation)
                imeAnimationRunning = true
                previousBottom = max(systemBottomInset, imeBottomInset)
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                super.onEnd(animation)
                imeAnimationRunning = false
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val currentBottom = max(systemBottom, imeBottom)

                // 核心1：计算输入法这一帧移动了多少像素
                val delta = currentBottom - previousBottom
                previousBottom = currentBottom

                binding.cardInput.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = currentBottom + cardMarginBottom
                }

                updateContentPadding(
                    bottom = currentBottom + inputStackHeight() + bottomOffset
                )

                // 核心2：让列表内容严格跟随输入法的位移量进行像素级滚动
                binding.recyclerMessages.scrollBy(0, delta)

                return insets
            }
        }

        ViewCompat.setWindowInsetsAnimationCallback(binding.root, animationCallback)

        val realWindowBackground = requireActivity().window.decorView.background
        val baseColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val overlayColor = ColorUtils.setAlphaComponent(baseColor, (255 * 0.6).toInt())

        fun setupBlur(view: BlurView, blurRadius: Float, overlayColor: Int, borderRadius: Float) {
            view.setupWith(binding.blurTarget)
                .setFrameClearDrawable(realWindowBackground)
                .setBlurRadius(blurRadius)
                .setOverlayColor(overlayColor)

            // 1. 动态创建一个透明的圆角 Drawable
            val radius = borderRadius * resources.displayMetrics.density
            val roundedBackground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.TRANSPARENT)
            }

            // 2. 应用背景、自定义轮廓裁剪与阴影
            view.apply {
                background = roundedBackground

                // 自定义 OutlineProvider
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        // 根据 View 的宽高和圆角设置轮廓形状
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                        // 无视背景的透明度，强制设定轮廓的 Alpha 为 1.0f (不透明)，以投射阴影
                        outline.alpha = 1.0f
                    }
                }

                clipToOutline = true

                // 3. 设置阴影的高度 (数值越大，阴影越明显)
                elevation = 8f
            }
        }

        setupBlur(binding.cardInput, 20f, overlayColor, 36f)
        setupBlur(binding.cardShellConfirmation, 20f, overlayColor, 16f)
    }

    override fun onDestroyView() {
        parseCache.clear()
        binding.recyclerMessages.removeCallbacks(smoothScrollToBottomRunnable)
        binding.recyclerMessages.removeCallbacks(immediateScrollToBottomRunnable)
        binding.recyclerMessages.removeCallbacks(throttledSmoothScrollToBottomRunnable)
        binding.recyclerMessages.removeCallbacks(markdownPreparseRunnable)
        scrollAfterLayoutPending = false
        binding.recyclerMessages.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(
            onCopyMessage = { message -> copyMessage(message) },
            onEditMessage = { index ->
                showConfirmationDialog(
                    R.string.dialog_edit_title,
                    R.string.dialog_edit_message
                ) { viewModel.editMessage(index) }
            },
            onDeleteMessage = { index ->
                showConfirmationDialog(
                    R.string.dialog_delete_title,
                    R.string.dialog_delete_message
                ) { viewModel.deleteMessage(index) }
            },
            onRetryMessage = { index ->
                showConfirmationDialog(
                    R.string.dialog_retry_title,
                    R.string.dialog_retry_message
                ) { viewModel.retryMessage(index) }
            }
        )
        adapter.parseCache = parseCache

        binding.recyclerMessages.layoutManager = object : LinearLayoutManager(requireContext()) {
            override fun smoothScrollToPosition(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                state: androidx.recyclerview.widget.RecyclerView.State,
                position: Int
            ) {
                val smoothScroller = object :
                    androidx.recyclerview.widget.LinearSmoothScroller(recyclerView.context) {
                    override fun getVerticalSnapPreference(): Int {
                        return SNAP_TO_END
                    }
                }
                smoothScroller.targetPosition = position
                startSmoothScroll(smoothScroller)
            }
        }
        binding.recyclerMessages.adapter = adapter
        binding.recyclerMessages.itemAnimator = MessageItemAnimator()

        // 增大离屏缓存：在最近 6 条消息间来回滚动时，ViewHolder 完全不经过 onBindViewHolder
        binding.recyclerMessages.setItemViewCacheSize(6)
        // 增大回收池上限，减少 ViewHolder 重建频率
        binding.recyclerMessages.recycledViewPool.apply {
            setMaxRecycledViews(0, 10) // Assistant 类型
            setMaxRecycledViews(1, 6)  // User 类型
        }
        // RecyclerView 大小由父布局约束，不随数据条数变化
        binding.recyclerMessages.setHasFixedSize(true)
        binding.recyclerMessages.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                newState: Int
            ) {
                when (newState) {
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // 仅用户手指触摸拖拽时标记
                        userScrolling = true
                    }

                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> {
                        if (userScrolling) {
                            // 用户发起的滚动结束后，根据是否在底部决定是否吸附
                            followOutput = !recyclerView.canScrollVertically(1)
                        }
                        userScrolling = false
                        scheduleMarkdownPreparse(immediate = true)
                    }
                }
            }

            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                if (dy != 0) {
                    lastScrollDy = dy
                }
                scheduleMarkdownPreparse(immediate = false)
                if (!userScrolling) {
                    return
                }
                // 用户主动上滑时，取消吸附
                if (dy < 0) {
                    followOutput = false
                }
                // 用户主动下滑且已到底部，恢复吸附
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    followOutput = true
                }
            }
        })
    }

    private fun setupInputArea() {
        binding.editMessage.doAfterTextChanged { editable ->
            if (!updatingDraft) {
                viewModel.updateDraftMessage(editable?.toString().orEmpty())
            }
        }
        binding.btnSend.setOnClickListener {
            if (latestIsSending) {
                viewModel.stopGenerating()
            } else {
                followOutput = true
                viewModel.sendMessage()
            }
        }
        binding.btnConfirmShellConfirmation.setOnClickListener {
            viewModel.confirmPendingShellCommand()
        }
        binding.btnCancelShellConfirmation.setOnClickListener {
            viewModel.cancelPendingShellCommand()
        }
        renderSendButton()
    }

    private fun observeViewModel() {
        viewModel.uiMessages.observe(viewLifecycleOwner) { messages ->
            latestMessages = messages
            scheduleRenderMessages()
            renderEmptyState()
        }

        viewModel.savedSettings.observe(viewLifecycleOwner) { settings ->
            latestSettings = settings
            renderEmptyState()
        }

        viewModel.isSending.observe(viewLifecycleOwner) { sending ->
            latestIsSending = sending
            scheduleRenderMessages()
            renderSendButton()
        }

        viewModel.shellConfirmation.observe(viewLifecycleOwner) { confirmation ->
            latestShellConfirmation = confirmation
            renderShellConfirmation()
        }

        viewModel.draftMessage.observe(viewLifecycleOwner) { draft ->
            val current = binding.editMessage.text?.toString().orEmpty()
            if (current != draft) {
                updatingDraft = true
                binding.editMessage.setText(draft)
                binding.editMessage.setSelection(draft.length)
                updatingDraft = false
            }
            renderSendButton()
        }

        viewModel.currentConversationId.observe(viewLifecycleOwner) { conversationId ->
            val previousConversationId = observedConversationId
            observedConversationId = conversationId
            if (conversationId.isBlank() || conversationId == previousConversationId) {
                return@observe
            }

            followOutput = true
            if (previousConversationId != null || latestMessages.isNotEmpty()) {
                pendingImmediateScrollToBottom = true
                postScrollToBottom(animated = false)
            }
        }
    }

    private fun renderSendButton() {
        val sending = latestIsSending
        binding.btnSend.isEnabled = sending || binding.editMessage.text?.isNotBlank() == true
        binding.btnSend.contentDescription = getString(
            if (sending) {
                R.string.action_stop
            } else {
                R.string.action_send
            }
        )
        binding.btnSend.setIconResource(
            if (sending) {
                R.drawable.ic_stop
            } else {
                R.drawable.ic_send
            }
        )
    }

    private fun renderShellConfirmation() {
        val confirmation = latestShellConfirmation
        if (confirmation == null) {
            setShellConfirmationVisible(false)
            return
        }

        binding.textShellConfirmationRisk.text = getString(
            R.string.shell_confirmation_risk,
            confirmation.risk
        )
        binding.textShellConfirmationDescription.text = getString(
            R.string.shell_confirmation_description,
            confirmation.description.ifBlank { getString(R.string.shell_confirmation_description_empty) }
        )
        binding.textShellConfirmationCommand.text = buildString {
            val workDir = confirmation.workDir?.takeIf { it.isNotBlank() } ?: "默认目录"
            append(getString(R.string.shell_confirmation_work_dir, workDir))
            append('\n')
            append(confirmation.command)
        }
        setShellConfirmationVisible(true)
    }

    private fun setShellConfirmationVisible(visible: Boolean) {
        val binding = _binding ?: return
        if (binding.cardShellConfirmation.isVisible != visible) {
            binding.cardShellConfirmation.visibility = if (visible) View.VISIBLE else View.GONE
            updateContentPadding(
                bottom = max(systemBottomInset, imeBottomInset) + inputStackHeight() + bottomOffset,
                scrollRecyclerByBottomDelta = true
            )
        }

        binding.cardShellConfirmation.post {
            updateContentPadding(
                bottom = max(systemBottomInset, imeBottomInset) + inputStackHeight() + bottomOffset,
                scrollRecyclerByBottomDelta = true
            )
        }
    }

    private fun inputStackHeight(): Int {
        val binding = _binding ?: return 0
        val confirmationHeight = if (binding.cardShellConfirmation.isVisible) {
            binding.cardShellConfirmation.height + confirmationCardGap
        } else {
            0
        }
        return binding.cardInput.height + confirmationHeight
    }

    private fun updateContentPadding(
        top: Int? = null,
        bottom: Int,
        scrollRecyclerByBottomDelta: Boolean = false
    ) {
        val binding = _binding ?: return
        val recyclerView = binding.recyclerMessages
        val previousBottom = recyclerView.paddingBottom
        val resolvedTop = top ?: recyclerView.paddingTop
        recyclerView.updatePadding(top = resolvedTop, bottom = bottom)
        binding.emptyPlaceholder.root.updatePadding(top = resolvedTop, bottom = bottom)

        val bottomDelta = bottom - previousBottom
        if (scrollRecyclerByBottomDelta && bottomDelta != 0) {
            recyclerView.scrollBy(0, bottomDelta)
        }
    }

    /**
     * 合并同帧内的多次 renderMessages 调用（如 uiMessages 和 isSending 同时更新），
     * 通过 post 延迟到当前消息循环结束后再执行一次。
     */
    private fun scheduleRenderMessages() {
        if (renderMessagesPending) return
        renderMessagesPending = true
        val binding = _binding ?: run {
            renderMessagesPending = false
            return
        }
        binding.recyclerMessages.post {
            renderMessagesPending = false
            renderMessages()
        }
    }

    private fun renderMessages() {
        val previousMessageCount = lastRenderedMessageCount
        val previousStreamingSignature = lastStreamingMessageSignature
        adapter.submitMessages(latestMessages, latestIsSending)

        // 只预解析当前可见窗口及邻近消息，避免长历史加载时一次性扫描和解析完整对话。
        scheduleMarkdownPreparse(immediate = true)

        val currentStreamingSignature = currentStreamingMessageSignature()
        val isStreamingContentUpdate = latestIsSending &&
                latestMessages.size == previousMessageCount &&
                currentStreamingSignature != null &&
                currentStreamingSignature == previousStreamingSignature
        lastRenderedMessageCount = latestMessages.size
        lastStreamingMessageSignature = currentStreamingSignature

        if (adapter.itemCount <= 0) {
            pendingImmediateScrollToBottom = false
            return
        }

        val shouldJumpToBottom = pendingImmediateScrollToBottom ||
                (previousMessageCount == 0 && adapter.itemCount > InitialImmediateScrollThreshold)
        if (followOutput) {
            val animated = !shouldJumpToBottom
            pendingImmediateScrollToBottom = false
            postScrollToBottom(animated, throttle = animated && isStreamingContentUpdate)
        }
    }

    /**
     * 延迟预解析当前可见窗口及邻近消息的 Markdown。
     *
     * 长对话不再一次性解析完整历史，避免加载阶段和滚动阶段抢占 CPU；滚动时通过小窗口预取，
     * 让即将进入屏幕的消息尽量命中全局解析缓存。
     */
    private fun scheduleMarkdownPreparse(immediate: Boolean) {
        val binding = _binding ?: return
        binding.recyclerMessages.removeCallbacks(markdownPreparseRunnable)
        if (adapter.itemCount <= 0) {
            lastMarkdownPreparseSignature = null
            return
        }
        if (immediate) {
            runMarkdownPreparseForViewport()
        } else {
            val now = SystemClock.uptimeMillis()
            val delay = (MarkdownPreparseThrottleMs - (now - lastMarkdownPreparseUptime))
                .coerceAtLeast(0L)
            binding.recyclerMessages.postDelayed(markdownPreparseRunnable, delay)
        }
    }

    private fun runMarkdownPreparseForViewport() {
        val binding = _binding ?: return
        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            lastMarkdownPreparseSignature = null
            return
        }

        lastMarkdownPreparseUptime = SystemClock.uptimeMillis()

        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager
        val canUseCurrentLayout = !binding.recyclerMessages.isLayoutRequested
        val firstVisible = if (canUseCurrentLayout) {
            layoutManager
                ?.findFirstVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
        } else {
            null
        }
        val lastVisible = if (canUseCurrentLayout) {
            layoutManager
                ?.findLastVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
        } else {
            null
        }

        val positions = if (firstVisible != null && lastVisible != null) {
            val visibleStart = minOf(firstVisible, lastVisible).coerceIn(0, itemCount - 1)
            val visibleEnd = maxOf(firstVisible, lastVisible).coerceIn(0, itemCount - 1)
            val windowStart = (visibleStart - MarkdownPreparseBeforeItems).coerceAtLeast(0)
            val windowEnd = (visibleEnd + MarkdownPreparseAfterItems).coerceAtMost(itemCount - 1)
            buildList {
                addAll(visibleStart..visibleEnd)
                if (lastScrollDy < 0) {
                    if (windowStart < visibleStart) {
                        for (position in (visibleStart - 1) downTo windowStart) {
                            add(position)
                        }
                    }
                    if (visibleEnd < windowEnd) addAll((visibleEnd + 1)..windowEnd)
                } else {
                    if (visibleEnd < windowEnd) addAll((visibleEnd + 1)..windowEnd)
                    if (windowStart < visibleStart) {
                        for (position in (visibleStart - 1) downTo windowStart) {
                            add(position)
                        }
                    }
                }
            }
        } else {
            val fallbackStart = (itemCount - MarkdownPreparseFallbackTailItems).coerceAtLeast(0)
            (fallbackStart until itemCount).toList()
        }

        val entries = collectMarkdownPreparseEntries(positions)
        if (entries.isEmpty()) {
            return
        }

        val signature = buildMarkdownPreparseSignature(positions, entries)
        if (signature == lastMarkdownPreparseSignature) {
            return
        }
        lastMarkdownPreparseSignature = signature
        parseCache.preparseAll(viewLifecycleOwner.lifecycleScope, entries)
    }

    private fun collectMarkdownPreparseEntries(positions: List<Int>): List<Pair<String, Boolean>> {
        val streamingMsgId = if (latestIsSending) {
            latestMessages.asReversed().firstOrNull { it.role == ChatRole.Assistant }?.id
        } else {
            null
        }
        val entries = ArrayList<Pair<String, Boolean>>(positions.size)
        for (position in positions) {
            val message = adapter.getMessageAt(position) ?: continue
            if (message.role != ChatRole.Assistant) continue
            val msgIsStreaming = message.id == streamingMsgId
            val parts = message.assistantParts
            if (parts.isEmpty() && message.content.isNotBlank()) {
                entries += message.content to msgIsStreaming
            } else {
                for ((index, part) in parts.withIndex()) {
                    if (part !is AssistantMarkdownPart || part.text.isBlank()) continue
                    // 流式消息的最后一个 part 变化频繁，跳过预解析。
                    if (msgIsStreaming && index == parts.lastIndex) continue
                    entries += part.text to msgIsStreaming
                }
            }
            if (entries.size >= MarkdownPreparseMaxEntries) {
                break
            }
        }
        return entries
    }

    private fun buildMarkdownPreparseSignature(
        positions: List<Int>,
        entries: List<Pair<String, Boolean>>
    ): String {
        var hash = 17
        for ((text, isStreaming) in entries) {
            hash = 31 * hash + System.identityHashCode(text)
            hash = 31 * hash + text.length
            hash = 31 * hash + isStreaming.hashCode()
        }
        val firstPosition = positions.firstOrNull() ?: -1
        val lastPosition = positions.lastOrNull() ?: -1
        return "$firstPosition:$lastPosition:${entries.size}:$hash"
    }

    private fun postScrollToBottom(animated: Boolean, throttle: Boolean = false) {
        val binding = _binding ?: return
        binding.recyclerMessages.removeCallbacks(smoothScrollToBottomRunnable)
        binding.recyclerMessages.removeCallbacks(immediateScrollToBottomRunnable)
        if (!throttle) {
            binding.recyclerMessages.removeCallbacks(throttledSmoothScrollToBottomRunnable)
        }
        if (throttle) {
            val now = SystemClock.uptimeMillis()
            val delay = (StreamingScrollThrottleMs - (now - lastStreamingScrollUptime))
                .coerceAtLeast(0L)
            binding.recyclerMessages.removeCallbacks(throttledSmoothScrollToBottomRunnable)
            binding.recyclerMessages.postDelayed(throttledSmoothScrollToBottomRunnable, delay)
            return
        }
        binding.recyclerMessages.post(
            if (animated) {
                smoothScrollToBottomRunnable
            } else {
                immediateScrollToBottomRunnable
            }
        )
    }

    private fun scrollToBottom(animated: Boolean) {
        val binding = _binding ?: return
        val lastPosition = adapter.itemCount - 1
        if (lastPosition < 0) {
            return
        }
        val recyclerView = binding.recyclerMessages
        if (animated) {
            recyclerView.smoothScrollToPosition(lastPosition)
            return
        }

        recyclerView.stopScroll()
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager == null) {
            recyclerView.scrollToPosition(lastPosition)
            return
        }

        layoutManager.scrollToPosition(lastPosition)
        recyclerView.post {
            if (_binding == null || adapter.itemCount - 1 != lastPosition) {
                return@post
            }
            snapPositionEndToRecyclerBottom(layoutManager, lastPosition)
        }
    }

    private fun scrollToBottomIfScrollable(animated: Boolean) {
        val binding = _binding ?: return
        val recyclerView = binding.recyclerMessages
        if (recyclerView.isLayoutRequested) {
            if (scrollAfterLayoutPending) {
                return
            }
            scrollAfterLayoutPending = true
            recyclerView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    view.removeOnLayoutChangeListener(this)
                    scrollAfterLayoutPending = false
                    scrollToBottomIfScrollable(animated)
                }
            })
            return
        }

        // 内容未超过可视区域时跳过自动滚动，避免 RecyclerView 触发拉伸效果。
        if (!recyclerView.canScrollVertically(-1) && !recyclerView.canScrollVertically(1)) {
            return
        }
        scrollToBottom(animated)
    }

    private fun currentStreamingMessageSignature(): String? {
        val message = latestMessages.lastOrNull {
            it.role == ChatRole.Assistant && (it.assistantParts.isNotEmpty() || it.content.isNotBlank())
        }
            ?: return null
        return message.id
    }

    private fun snapPositionEndToRecyclerBottom(
        layoutManager: LinearLayoutManager,
        position: Int
    ) {
        val binding = _binding ?: return
        val targetView = layoutManager.findViewByPosition(position) ?: return
        val recyclerView = binding.recyclerMessages
        val viewportBottom = recyclerView.height - recyclerView.paddingBottom
        val targetBottom = layoutManager.getDecoratedBottom(targetView)
        val delta = targetBottom - viewportBottom
        if (delta != 0) {
            recyclerView.scrollBy(0, delta)
        }
    }

    private fun renderEmptyState() {
        val showEmptyState = latestMessages.isEmpty()
        binding.emptyPlaceholder.root.visibility = if (showEmptyState) View.VISIBLE else View.GONE
        binding.recyclerMessages.visibility = if (showEmptyState) View.INVISIBLE else View.VISIBLE
        binding.emptyPlaceholder.textEmptySubtitle.text = getString(
            if (latestSettings.baseUrl.isNotBlank() && latestSettings.model.isNotBlank()) {
                R.string.empty_subtitle_configured
            } else {
                R.string.empty_subtitle_unconfigured
            }
        )
        binding.emptyPlaceholder.textEmptyTipsBody.text = getString(
            if (latestSettings.baseUrl.isNotBlank() && latestSettings.model.isNotBlank()) {
                R.string.empty_tips_configured
            } else {
                R.string.empty_tips_unconfigured
            }
        )
    }

    private fun copyMessage(message: ChatMessage) {
        val copyText = message.assistantParts.asSequence()
            .mapNotNull { part ->
                when (part) {
                    is AssistantMarkdownPart -> part.text.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            .joinToString(separator = "\n\n")
            .ifBlank { message.content }
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.app_name),
                copyText
            )
        )
        Toast.makeText(requireContext(), getString(R.string.action_copy), Toast.LENGTH_SHORT).show()
    }

    private fun showConfirmationDialog(titleResId: Int, messageResId: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleResId)
            .setMessage(messageResId)
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private companion object {
        const val StreamingScrollThrottleMs = 80L
        const val MarkdownPreparseThrottleMs = 64L
        const val MarkdownPreparseBeforeItems = 8
        const val MarkdownPreparseAfterItems = 12
        const val MarkdownPreparseFallbackTailItems = 18
        const val MarkdownPreparseMaxEntries = 32
        const val InitialImmediateScrollThreshold = 20
    }
}
