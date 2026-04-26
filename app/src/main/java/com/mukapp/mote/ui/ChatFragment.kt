package com.mukapp.mote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mukapp.mote.R
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.databinding.FragmentChatBinding
import com.mukapp.mote.util.dp
import com.mukapp.mote.util.dpInt
import com.mukapp.mote.util.px
import kotlin.math.max
import androidx.core.graphics.drawable.toDrawable

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by activityViewModels()
    private lateinit var adapter: ChatMessageAdapter

    private var latestMessages: List<ChatMessage> = emptyList()
    private var latestSettings: ApiSettings = ApiSettings()
    private var latestIsSending: Boolean = false
    private var latestShellConfirmation: ShellConfirmationUiState? = null
    private var followOutput: Boolean = true
    private var userScrolling: Boolean = false
    private var updatingDraft: Boolean = false
    private var pendingImmediateScrollToBottom: Boolean = false
    private var observedConversationId: String? = null
    private var lastRenderedMessageCount: Int = 0
    private var lastStreamingMessageSignature: String? = null
    private var lastStreamingScrollUptime: Long = 0L
    private val smoothScrollToBottomRunnable = Runnable { scrollToBottom(animated = true) }
    private val immediateScrollToBottomRunnable = Runnable { scrollToBottom(animated = false) }
    private val throttledSmoothScrollToBottomRunnable = Runnable {
        lastStreamingScrollUptime = SystemClock.uptimeMillis()
        scrollToBottom(animated = true)
    }

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

        fun updateContentPadding(top: Int = binding.recyclerMessages.paddingTop, bottom: Int) {
            binding.recyclerMessages.updatePadding(top = top, bottom = bottom)
            binding.emptyPlaceholder.root.updatePadding(top = top, bottom = bottom)
        }

        fun inputStackHeight(): Int {
            val confirmationHeight = if (binding.cardShellConfirmation.visibility == View.VISIBLE) {
                binding.cardShellConfirmation.height + confirmationCardGap
            } else {
                0
            }
            return binding.cardInput.height + confirmationHeight
        }

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

        val inputStackLayoutChangeListener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((bottom - top) != (oldBottom - oldTop)) {
                updateContentPadding(
                    bottom = max(systemBottomInset, imeBottomInset) + inputStackHeight() + bottomOffset
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
        binding.cardInput.setupWith(binding.blurTarget)
            .setFrameClearDrawable(realWindowBackground)
            .setBlurRadius(20f)
            .setOverlayColor(overlayColor)

        // 1. 动态创建一个透明的圆角 Drawable
        val radius = 36f * resources.displayMetrics.density
        val roundedBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
        }

        // 2. 应用背景、自定义轮廓裁剪与阴影
        binding.cardInput.apply {
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

    override fun onDestroyView() {
        binding.recyclerMessages.removeCallbacks(smoothScrollToBottomRunnable)
        binding.recyclerMessages.removeCallbacks(immediateScrollToBottomRunnable)
        binding.recyclerMessages.removeCallbacks(throttledSmoothScrollToBottomRunnable)
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
        binding.recyclerMessages.itemAnimator = null
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
                    }
                }
            }

            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
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
            renderMessages()
            renderEmptyState()
        }

        viewModel.savedSettings.observe(viewLifecycleOwner) { settings ->
            latestSettings = settings
            renderEmptyState()
        }

        viewModel.isSending.observe(viewLifecycleOwner) { sending ->
            latestIsSending = sending
            renderMessages()
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
            binding.cardShellConfirmation.visibility = View.GONE
            return
        }

        binding.textShellConfirmationRisk.text = getString(
            R.string.shell_confirmation_risk,
            confirmation.risk
        )
        binding.textShellConfirmationCommand.text = buildString {
            val workDir = confirmation.workDir?.takeIf { it.isNotBlank() } ?: "默认目录"
            append(getString(R.string.shell_confirmation_work_dir, workDir))
            append('\n')
            append(confirmation.command)
        }
        binding.cardShellConfirmation.visibility = View.VISIBLE
    }

    private fun renderMessages() {
        val previousMessageCount = lastRenderedMessageCount
        val previousStreamingSignature = lastStreamingMessageSignature
        adapter.submitMessages(latestMessages, latestIsSending)
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

        if (followOutput) {
            val animated = !pendingImmediateScrollToBottom
            pendingImmediateScrollToBottom = false
            postScrollToBottom(animated, throttle = animated && isStreamingContentUpdate)
        }
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
    }
}
