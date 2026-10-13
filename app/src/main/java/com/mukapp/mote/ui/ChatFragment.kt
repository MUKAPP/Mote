package com.mukapp.mote.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.resolvedChatModel
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.shortName
import com.mukapp.mote.databinding.FragmentChatBinding
import com.mukapp.mote.databinding.ItemAttachmentFileBinding
import com.mukapp.mote.databinding.ItemAttachmentImageBinding
import com.mukapp.mote.ui.markdown.MarkdownParseCache
import com.mukapp.mote.util.AttachmentThumbnailLoader
import com.mukapp.mote.util.dpInt
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.view.isVisible
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by activityViewModels()
    private lateinit var adapter: ChatMessageAdapter
    private val parseCache = MarkdownParseCache()
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handlePickedAttachment(it, AttachmentPickerKind.Image) }
    }
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handlePickedAttachment(it, AttachmentPickerKind.File) }
    }

    private var latestMessages: List<ChatMessage> = emptyList()
    private var renderedConversationId: String = ""
    private var latestMessagesConversationId: String = ""
    private var latestSettings: ApiSettings = ApiSettings()
    private var latestIsSending: Boolean = false
    private var latestDraftAttachments: List<ChatAttachment> = emptyList()
    private var latestToolConfirmation: ToolConfirmationUiState? = null
    private var followOutput: Boolean = true
    private var userScrolling: Boolean = false
    private var updatingDraft: Boolean = false
    private var pendingImmediateScrollToBottom: Boolean = false
    private var observedConversationId: String? = null
    private var renderMessagesPending: Boolean = false
    private var lastRenderedMessageCount: Int = 0
    private var scrollGeneration = 0L
    private var readingReadyGeneration: Long? = null
    private data class PendingReadingRestore(
        val conversationId: String,
        val generation: Long,
        val position: ConversationReadingPosition?
    )
    private var pendingReadingRestore: PendingReadingRestore? = null
    private var readingLayoutListener: ViewTreeObserver.OnPreDrawListener? = null
    private var bottomScrollRunnable: Runnable? = null
    private var animatedBottomGeneration: Long? = null
    private val markdownPreparseRunnable = Runnable { runMarkdownPreparseForViewport() }
    private var lastMarkdownPreparseSignature: String? = null
    private var lastMarkdownPreparseUptime: Long = 0L
    private var lastScrollDy: Int = 0
    private var scrollToBottomVisible = false
    private val scrollButtonShowInterpolator = OvershootInterpolator(1f)
    private val scrollButtonHideInterpolator = DecelerateInterpolator()

    private var systemBottomInset = 0
    private var imeBottomInset = 0
    private var systemTopInset = 0
    private var toolbarOverlayHeight = 0
    private val topContentGap = 8.dpInt
    private val fallbackToolbarHeight = 56.dpInt
    private val cardMarginBottom = 16.dpInt
    private val bottomOffset = cardMarginBottom + 8.dpInt
    private val confirmationCardGap = 8.dpInt
    private var toolbarLayoutChangeListener: View.OnLayoutChangeListener? = null

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
        val binding = this.binding
        setupRecyclerView()
        setupInputArea()
        setupExampleChips()
        observeViewModel()

        binding.recyclerMessages.clipToPadding = false

        var imeAnimationRunning = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            if (_binding !== binding) return@setOnApplyWindowInsetsListener insets
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            systemBottomInset = systemBars.bottom
            imeBottomInset = ime.bottom
            systemTopInset = systemBars.top

            if (!imeAnimationRunning) {
                val currentBottom = max(systemBottomInset, imeBottomInset)

                updateContentPadding(
                    top = resolvedTopPadding(systemBars.top),
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
                if (_binding !== binding) return@OnLayoutChangeListener
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
                if (_binding !== binding) return
                imeAnimationRunning = true
                previousBottom = max(systemBottomInset, imeBottomInset)
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                super.onEnd(animation)
                if (_binding !== binding) return
                imeAnimationRunning = false
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                if (_binding !== binding) return insets
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
                if (readingReadyGeneration == scrollGeneration &&
                    isReadingTarget(renderedConversationId, scrollGeneration)
                ) binding.recyclerMessages.scrollBy(0, delta)

                return insets
            }
        }

        ViewCompat.setWindowInsetsAnimationCallback(binding.root, animationCallback)

        setupToolbarOverlayTracking()
        setupScrollToBottomButton()

        val realWindowBackground = requireActivity().window.decorView.background
        val backgroundColor = ContextCompat.getColor(requireContext(), R.color.mote_background)
        val cardColor = ContextCompat.getColor(requireContext(), R.color.mote_card)
        val backgroundOverlayColor = ColorUtils.setAlphaComponent(backgroundColor, (255 * 0.6).toInt())
        val overlayColor = ColorUtils.compositeColors(cardColor, backgroundOverlayColor)

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
        setupBlur(binding.blurScrollToBottom, 20f, overlayColor, 22f)
    }

    override fun onDestroyView() {
        saveReadingPositionIfReady()
        cancelReadingCallbacks()
        scrollGeneration++
        readingReadyGeneration = null
        pendingReadingRestore = null
        observedConversationId = null
        renderedConversationId = ""
        renderMessagesPending = false
        parseCache.clear()
        toolbarLayoutChangeListener?.let { listener ->
            activity?.findViewById<View>(R.id.blur_view_toolbar)?.removeOnLayoutChangeListener(listener)
        }
        toolbarLayoutChangeListener = null
        binding.recyclerMessages.stopScroll()
        binding.recyclerMessages.removeCallbacks(markdownPreparseRunnable)
        binding.scrollToBottomContent.animate().cancel()
        scrollToBottomVisible = false
        binding.recyclerMessages.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(
            onCopyMessage = { message -> copyMessage(message) },
            onFreeCopyMessage = { message -> openFreeCopy(message) },
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
            onRetryMessage = { index -> viewModel.retryMessage(index) },
            conversationIdProvider = { renderedConversationId }
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

                    override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                        // 默认速度偏慢，回到底部时缩短动画时长
                        return super.calculateSpeedPerPixel(displayMetrics) * ScrollSpeedFactor
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
            setMaxRecycledViews(2, 4)  // 系统提示条
        }
        // RecyclerView 大小由父布局约束，不随数据条数变化
        binding.recyclerMessages.setHasFixedSize(true)
        binding.recyclerMessages.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                newState: Int
            ) {
                if (recyclerView !== _binding?.recyclerMessages) return
                when (newState) {
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // 用户开始触摸拖拽时立即取消自动吸附，避免流式输出的延迟滚动抢滚动。
                        userScrolling = true
                        followOutput = false
                        cancelPendingReadingRestore()
                    }

                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> {
                        if (userScrolling) {
                            // 用户发起的滚动结束后，根据是否在底部决定是否吸附
                            followOutput = !recyclerView.canScrollVertically(1)
                        }
                        userScrolling = false
                        animatedBottomGeneration?.let { generation ->
                            val conversationId = renderedConversationId
                            afterMessagesLayout(conversationId, generation) { completeAnimatedBottomIfSettled() }
                        }
                        scheduleMarkdownPreparse(immediate = true)
                        updateScrollToBottomButton()
                    }
                }
            }

            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                if (recyclerView !== _binding?.recyclerMessages) return
                if (dy != 0) {
                    lastScrollDy = dy
                }
                scheduleMarkdownPreparse(immediate = false)
                if (userScrolling) {
                    // 先更新跟随状态，再刷新按钮，包含到达底部的最后一帧。
                    if (dy < 0) {
                        followOutput = false
                    } else if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                        followOutput = true
                    }
                }
                updateScrollToBottomButton()
            }
        })
    }

    private fun setupInputArea() {
        binding.editMessage.doAfterTextChanged { editable ->
            if (!updatingDraft) {
                viewModel.updateDraftMessage(editable?.toString().orEmpty())
            }
        }
        binding.btnAddAttachment.setOnClickListener {
            if (!latestIsSending) {
                showAttachmentMenu()
            }
        }
        binding.btnSend.setOnClickListener {
            if (latestIsSending) {
                viewModel.stopGenerating()
            } else {
                cancelPendingReadingRestore()
                followOutput = true
                // 发送后首帧直接吸附到底，避免输出开始时的平滑滚动动画
                pendingImmediateScrollToBottom = true
                viewModel.sendMessage()
            }
        }
        binding.btnConfirmShellConfirmation.setOnClickListener {
            viewModel.confirmPendingToolAction()
        }
        binding.btnCancelShellConfirmation.setOnClickListener {
            viewModel.cancelPendingToolAction()
        }
        renderSendButton()
    }

    private fun setupExampleChips() {
        val chipGroup = binding.emptyPlaceholder.chipGroupExamples
        chipGroup.removeAllViews()
        val examples = listOf(
            R.string.example_question_1,
            R.string.example_question_2,
            R.string.example_question_3,
            R.string.example_question_4
        )
        examples.forEach { resId ->
            val text = getString(resId)
            val chip = com.google.android.material.chip.Chip(
                requireContext(),
                null,
                com.google.android.material.R.attr.chipStyle
            ).apply {
                setChipDrawable(
                    com.google.android.material.chip.ChipDrawable.createFromAttributes(
                        context, null, 0, R.style.Widget_Mote_Chip_Attachment_User
                    )
                )
                this.text = text
                isClickable = true
                isCheckable = false
                setOnClickListener {
                    binding.editMessage.setText(text)
                    binding.editMessage.setSelection(text.length)
                    binding.editMessage.requestFocus()
                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(binding.editMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun showAttachmentMenu() {
        val items = listOf(
            MotePopupWindowItem(MenuAddImage, R.string.action_add_image),
            MotePopupWindowItem(MenuAddFile, R.string.action_add_file)
        )
        MotePopupWindowMenu.showAnchored(binding.btnAddAttachment, items) { itemId ->
            when (itemId) {
                MenuAddImage -> imagePickerLauncher.launch(arrayOf("image/*"))
                MenuAddFile -> filePickerLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    private fun handlePickedAttachment(uri: Uri, requestedKind: AttachmentPickerKind) {
        persistReadPermission(uri)
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val attachment = withContext(Dispatchers.IO) {
                resolvePickedAttachment(appContext, uri, requestedKind)
            }
            if (!isAdded) {
                return@launch
            }
            if (attachment == null) {
                val errorRes = when (requestedKind) {
                    AttachmentPickerKind.Image -> R.string.attachment_error_image_read_failed
                    AttachmentPickerKind.File -> R.string.attachment_error_file_read_failed
                }
                Toast.makeText(requireContext(), getString(errorRes), Toast.LENGTH_SHORT).show()
                return@launch
            }

            viewModel.addDraftAttachment(attachment)
            if (attachment.type == ChatAttachmentType.File && attachment.truncated) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.attachment_notice_file_truncated),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun observeViewModel() {
        viewModel.currentConversationId.observe(viewLifecycleOwner) { conversationId ->
            if (conversationId == observedConversationId) return@observe
            saveReadingPositionIfReady()
            binding.recyclerMessages.stopScroll()
            cancelReadingCallbacks()
            scrollGeneration++
            readingReadyGeneration = null
            observedConversationId = conversationId
            userScrolling = false
            pendingImmediateScrollToBottom = false
            val position = viewModel.getReadingPosition(conversationId)
            followOutput = position?.followOutput ?: true
            pendingReadingRestore = if (conversationId.isBlank()) null else
                PendingReadingRestore(conversationId, scrollGeneration, position)
        }

        viewModel.uiMessages.observe(viewLifecycleOwner) { messages ->
            latestMessages = messages
            latestMessagesConversationId = viewModel.currentConversationId.value.orEmpty()
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
            updateScrollToBottomButton()
        }

        viewModel.toolConfirmation.observe(viewLifecycleOwner) { confirmation ->
            latestToolConfirmation = confirmation
            renderToolConfirmation()
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

        viewModel.draftAttachments.observe(viewLifecycleOwner) { attachments ->
            latestDraftAttachments = attachments
            renderAttachmentPreview()
            renderSendButton()
        }

    }

    private fun renderSendButton() {
        val sending = latestIsSending
        val hasDraft = binding.editMessage.text?.isNotBlank() == true || latestDraftAttachments.isNotEmpty()
        binding.btnSend.isEnabled = sending || hasDraft
        binding.btnAddAttachment.isEnabled = !sending
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

    private fun renderAttachmentPreview() {
        val attachments = latestDraftAttachments
        binding.layoutAttachmentPreview.isVisible = attachments.isNotEmpty()
        binding.containerDraftAttachments.removeAllViews()
        if (attachments.isEmpty()) {
            return
        }

        val inflater = layoutInflater
        val container = binding.containerDraftAttachments
        attachments.forEach { attachment ->
            val itemView = when (attachment.type) {
                ChatAttachmentType.Image -> {
                    val itemBinding = ItemAttachmentImageBinding.inflate(inflater, container, false)
                    AttachmentThumbnailLoader.load(itemBinding.imageAttachmentThumb, attachment, 96.dpInt, viewModel.currentConversationId.value.orEmpty())
                    itemBinding.imageAttachmentRemove.setOnClickListener {
                        viewModel.removeDraftAttachment(attachment.id)
                    }
                    installAttachmentRemoveTouchDelegate(itemBinding.root, itemBinding.imageAttachmentRemove)
                    itemBinding.root
                }

                ChatAttachmentType.File -> {
                    val itemBinding = ItemAttachmentFileBinding.inflate(inflater, container, false)
                    itemBinding.root.minimumWidth = 48.dpInt
                    itemBinding.textAttachmentName.text = attachment.shortName()
                    itemBinding.root.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.mote_card)
                    )
                    itemBinding.imageAttachmentRemove.setOnClickListener {
                        viewModel.removeDraftAttachment(attachment.id)
                    }
                    installAttachmentRemoveTouchDelegate(itemBinding.root, itemBinding.imageAttachmentRemove)
                    itemBinding.root
                }
            }
            container.addView(itemView)
        }
    }

    /** 保持移除图标的视觉尺寸，仅扩展当前附件 root 内的点击区。 */
    private fun installAttachmentRemoveTouchDelegate(root: View, removeButton: View) {
        root.post(object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow) return
                if (root.isLayoutRequested) {
                    root.postOnAnimation(this)
                    return
                }
                val target = Rect().also(removeButton::getHitRect)
                val minimumSize = 48.dpInt
                val extraWidth = (minimumSize - target.width()).coerceAtLeast(0)
                val extraHeight = (minimumSize - target.height()).coerceAtLeast(0)
                target.inset(-(extraWidth / 2), -(extraHeight / 2))
                target.right += extraWidth % 2
                target.bottom += extraHeight % 2
                if (target.left < 0) target.offset(-target.left, 0)
                if (target.right > root.width) target.offset(root.width - target.right, 0)
                if (target.top < 0) target.offset(0, -target.top)
                if (target.bottom > root.height) target.offset(0, root.height - target.bottom)
                root.touchDelegate = TouchDelegate(target, removeButton)
            }
        })
    }

    private fun renderToolConfirmation() {
        val confirmation = latestToolConfirmation
        if (confirmation == null) {
            setToolConfirmationVisible(false)
            return
        }

        binding.textShellConfirmationTitle.setText(
            when (confirmation.type) {
                ToolConfirmationType.Shell -> R.string.shell_confirmation_title
                ToolConfirmationType.SensitivePath -> R.string.path_confirmation_title
            }
        )
        binding.textShellConfirmationRisk.text = getString(
            R.string.shell_confirmation_risk,
            confirmation.risk
        )
        binding.textShellConfirmationDescription.text = getString(
            R.string.shell_confirmation_description,
            confirmation.description.ifBlank { getString(R.string.shell_confirmation_description_empty) }
        )
        binding.textShellConfirmationCommand.text = when (confirmation.type) {
            ToolConfirmationType.Shell -> buildString {
                val workDir = confirmation.workDir?.takeIf { it.isNotBlank() } ?: "默认目录"
                append(getString(R.string.shell_confirmation_work_dir, workDir))
                append('\n')
                append(confirmation.command.orEmpty())
            }

            ToolConfirmationType.SensitivePath -> getString(
                R.string.path_confirmation_target,
                confirmation.path.orEmpty()
            )
        }
        setToolConfirmationVisible(true)
    }

    private fun setToolConfirmationVisible(visible: Boolean) {
        val binding = _binding ?: return
        if (binding.cardShellConfirmation.isVisible != visible) {
            binding.cardShellConfirmation.visibility = if (visible) View.VISIBLE else View.GONE
            updateContentPadding(
                bottom = max(systemBottomInset, imeBottomInset) + inputStackHeight() + bottomOffset,
                scrollRecyclerByBottomDelta = true
            )
        }
        updateScrollToBottomButton()
        val conversationId = observedConversationId.orEmpty()
        val generation = scrollGeneration

        binding.cardShellConfirmation.post {
            if (_binding !== binding || !isReadingTarget(conversationId, generation)) return@post
            updateContentPadding(
                bottom = max(systemBottomInset, imeBottomInset) + inputStackHeight() + bottomOffset,
                scrollRecyclerByBottomDelta = true
            )
            updateScrollToBottomButton()
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
        if (scrollRecyclerByBottomDelta && bottomDelta != 0 &&
            readingReadyGeneration == scrollGeneration && isReadingTarget(renderedConversationId, scrollGeneration)
        ) {
            recyclerView.scrollBy(0, bottomDelta)
        }
    }

    private fun resolvedTopPadding(systemTop: Int): Int {
        val toolbarHeight = if (toolbarOverlayHeight > 0) {
            toolbarOverlayHeight
        } else {
            systemTop + fallbackToolbarHeight
        }
        return toolbarHeight + topContentGap
    }

    /**
     * 跟踪活动顶栏（毛玻璃 Toolbar）的真实高度，替代写死的 56dp 假设。
     * 大字体、横屏等情况下 Toolbar 高度会变化，监听其布局变化即可让内容顶部留白始终正确。
     */
    private fun setupToolbarOverlayTracking() {
        val toolbarOverlay = activity?.findViewById<View>(R.id.blur_view_toolbar) ?: return
        toolbarOverlayHeight = toolbarOverlay.height
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.height == toolbarOverlayHeight) {
                return@OnLayoutChangeListener
            }
            toolbarOverlayHeight = view.height
            val binding = _binding ?: return@OnLayoutChangeListener
            updateContentPadding(
                top = resolvedTopPadding(systemTopInset),
                bottom = binding.recyclerMessages.paddingBottom
            )
        }
        toolbarOverlay.addOnLayoutChangeListener(listener)
        toolbarLayoutChangeListener = listener
    }

    private fun setupScrollToBottomButton() {
        binding.blurScrollToBottom.setOnClickListener {
            cancelPendingReadingRestore()
            userScrolling = false
            followOutput = true
            postScrollToBottom(animated = true)
            setScrollToBottomVisible(false)
        }
    }

    /** 用户未跟随输出（向上滚离底部）且下方仍有内容、无 Shell 确认条时显示"回到底部"按钮；输出过程中按钮边框显示加载环。 */
    private fun updateScrollToBottomButton() {
        val binding = _binding ?: return
        val shouldShow = binding.recyclerMessages.isVisible &&
            !binding.cardShellConfirmation.isVisible &&
            !followOutput &&
            binding.recyclerMessages.canScrollVertically(1)
        setScrollToBottomVisible(shouldShow)
        binding.progressScrollToBottom.isVisible = shouldShow && latestIsSending
    }

    private fun setScrollToBottomVisible(visible: Boolean) {
        val binding = _binding ?: return
        // 滚动每帧都会调用；目标不变时不能重启动画，否则结束动作会拖到滚动停止。
        if (scrollToBottomVisible == visible) return
        scrollToBottomVisible = visible
        val container = binding.scrollToBottomContainer
        val content = binding.scrollToBottomContent
        content.animate().cancel()
        binding.blurScrollToBottom.isClickable = visible
        binding.blurScrollToBottom.isFocusable = visible
        if (!ValueAnimator.areAnimatorsEnabled()) {
            content.scaleX = 1f
            content.scaleY = 1f
            container.isVisible = visible
            return
        }
        if (visible && !container.isVisible) {
            content.scaleX = ScrollButtonHiddenScale
            content.scaleY = ScrollButtonHiddenScale
            container.isVisible = true
        }
        // View 默认以中心为 pivot；反向切换只改目标，从当前缩放值继续。
        val targetScale = if (visible) 1f else ScrollButtonHiddenScale
        val animation = content.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(if (visible) ScrollButtonShowMs else ScrollButtonHideMs)
            .setInterpolator(if (visible) scrollButtonShowInterpolator else scrollButtonHideInterpolator)
        if (visible) {
            animation.withEndAction(null)
        } else {
            animation.withEndAction { container.isVisible = false }
        }
        animation.start()
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
            if (_binding !== binding) return@post
            renderMessagesPending = false
            renderMessages()
        }
    }

    private fun renderMessages() {
        val binding = _binding ?: return
        val conversationId = viewModel.currentConversationId.value.orEmpty()
        if (conversationId.isBlank() || latestMessagesConversationId != conversationId ||
            observedConversationId != conversationId
        ) return
        val generation = scrollGeneration
        val previousMessageCount = lastRenderedMessageCount
        cancelReadingCallbacks()
        readingReadyGeneration = null
        adapter.submitMessages(latestMessages, latestIsSending)
        renderedConversationId = conversationId
        lastRenderedMessageCount = adapter.itemCount

        // 即使本次 Diff 没有改变尺寸，也必须等待目标列表完成真实布局。
        binding.recyclerMessages.requestLayout()
        scheduleMarkdownPreparse(immediate = true)
        if (adapter.itemCount <= 0) {
            pendingImmediateScrollToBottom = false
            pendingReadingRestore = null
            return
        }

        val restore = pendingReadingRestore
        if (restore != null && restore.conversationId == conversationId && restore.generation == generation) {
            afterMessagesLayout(conversationId, generation) { restoreReadingPosition(restore) }
        } else if (followOutput) {
            val shouldJumpToBottom = pendingImmediateScrollToBottom ||
                (previousMessageCount == 0 && adapter.itemCount > InitialImmediateScrollThreshold)
            pendingImmediateScrollToBottom = false
            postScrollToBottom(animated = !latestIsSending && !shouldJumpToBottom)
        } else {
            afterMessagesLayout(conversationId, generation) { completeReadingPosition(conversationId, generation) }
        }
        binding.recyclerMessages.post {
            if (isReadingTarget(conversationId, generation)) updateScrollToBottomButton()
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

    private fun isReadingTarget(conversationId: String, generation: Long): Boolean =
        conversationId.isNotBlank() && _binding != null && scrollGeneration == generation &&
            renderedConversationId == conversationId && observedConversationId == conversationId &&
            viewModel.currentConversationId.value == conversationId

    private fun saveReadingPositionIfReady() {
        val binding = _binding ?: return
        val recyclerView = binding.recyclerMessages
        if (renderedConversationId.isBlank() || observedConversationId != renderedConversationId ||
            readingReadyGeneration != scrollGeneration || recyclerView.isLayoutRequested ||
            recyclerView.hasPendingAdapterUpdates() || recyclerView.isComputingLayout
        ) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        val message = adapter.getMessageAt(firstPosition) ?: return
        val firstView = layoutManager.findViewByPosition(firstPosition) ?: return
        viewModel.saveReadingPosition(
            renderedConversationId,
            ConversationReadingPosition(
                message.id,
                layoutManager.getDecoratedTop(firstView) - recyclerView.paddingTop,
                followOutput
            )
        )
    }

    private fun cancelReadingCallbacks() {
        val recyclerView = _binding?.recyclerMessages ?: return
        bottomScrollRunnable?.let { recyclerView.removeCallbacks(it) }
        bottomScrollRunnable = null
        animatedBottomGeneration = null
        readingLayoutListener?.let { recyclerView.viewTreeObserver.removeOnPreDrawListener(it) }
        readingLayoutListener = null
    }

    private fun cancelPendingReadingRestore() {
        val recyclerView = _binding?.recyclerMessages ?: return
        val wasReady = readingReadyGeneration == scrollGeneration && !recyclerView.isLayoutRequested &&
            !recyclerView.hasPendingAdapterUpdates() && !recyclerView.isComputingLayout
        cancelReadingCallbacks()
        scrollGeneration++
        readingReadyGeneration = if (wasReady) scrollGeneration else null
        pendingReadingRestore = null
        pendingImmediateScrollToBottom = false
        val conversationId = renderedConversationId
        val generation = scrollGeneration
        if (!wasReady && isReadingTarget(conversationId, generation) && adapter.itemCount > 0) {
            afterMessagesLayout(conversationId, generation) { completeReadingPosition(conversationId, generation) }
        }
    }

    /** Pre-draw 在真实 RecyclerView layout 后运行；post 本身不代表目标列表已布局。 */
    private fun afterMessagesLayout(conversationId: String, generation: Long, action: () -> Unit) {
        val binding = _binding ?: return
        val recyclerView = binding.recyclerMessages
        readingLayoutListener?.let { recyclerView.viewTreeObserver.removeOnPreDrawListener(it) }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (_binding !== binding || !isReadingTarget(conversationId, generation)) {
                    recyclerView.viewTreeObserver.removeOnPreDrawListener(this)
                    if (readingLayoutListener === this) readingLayoutListener = null
                    return true
                }
                if (!ViewCompat.isLaidOut(recyclerView) || recyclerView.isLayoutRequested ||
                    recyclerView.hasPendingAdapterUpdates() || recyclerView.isComputingLayout
                ) return true
                recyclerView.viewTreeObserver.removeOnPreDrawListener(this)
                if (readingLayoutListener === this) readingLayoutListener = null
                action()
                return !recyclerView.isLayoutRequested
            }
        }
        readingLayoutListener = listener
        recyclerView.viewTreeObserver.addOnPreDrawListener(listener)
        recyclerView.invalidate()
    }

    private fun completeReadingPosition(conversationId: String, generation: Long) {
        if (!isReadingTarget(conversationId, generation) || adapter.itemCount == 0) return
        readingReadyGeneration = generation
        pendingReadingRestore = null
        updateScrollToBottomButton()
    }

    private fun restoreReadingPosition(restore: PendingReadingRestore) {
        if (pendingReadingRestore !== restore || !isReadingTarget(restore.conversationId, restore.generation)) return
        val position = restore.position
        val messagePosition = if (position == null) -1 else
            (0 until adapter.itemCount).firstOrNull { adapter.getMessageAt(it)?.id == position.messageId } ?: -1
        if (position == null || messagePosition < 0) {
            scrollToBottom(animated = false, conversationId = restore.conversationId, generation = restore.generation)
            return
        }
        val recyclerView = binding.recyclerMessages
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        recyclerView.stopScroll()
        layoutManager.scrollToPositionWithOffset(messagePosition, position.offsetPx)
        afterMessagesLayout(restore.conversationId, restore.generation) {
            completeReadingPosition(restore.conversationId, restore.generation)
        }
    }

    private fun postScrollToBottom(animated: Boolean) {
        val binding = _binding ?: return
        val recyclerView = binding.recyclerMessages
        val conversationId = renderedConversationId
        val generation = scrollGeneration
        if (!isReadingTarget(conversationId, generation) || userScrolling ||
            recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING
        ) return
        readingReadyGeneration = null
        bottomScrollRunnable?.let { recyclerView.removeCallbacks(it) }
        val runnable = Runnable {
            bottomScrollRunnable = null
            if (_binding !== binding || !isReadingTarget(conversationId, generation)) return@Runnable
            afterMessagesLayout(conversationId, generation) {
                scrollToBottom(animated, conversationId, generation)
            }
        }
        bottomScrollRunnable = runnable
        recyclerView.post(runnable)
    }

    private fun scrollToBottom(animated: Boolean, conversationId: String, generation: Long) {
        if (!isReadingTarget(conversationId, generation)) return
        val recyclerView = binding.recyclerMessages
        val lastPosition = adapter.itemCount - 1
        if (lastPosition < 0 || userScrolling || recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) return
        // 内容未超过可视区域时不滚动，避免触发拉伸效果。
        if (!recyclerView.canScrollVertically(-1) && !recyclerView.canScrollVertically(1)) {
            completeReadingPosition(conversationId, generation)
            return
        }
        if (animated) {
            animatedBottomGeneration = generation
            recyclerView.smoothScrollToPosition(lastPosition)
            afterMessagesLayout(conversationId, generation) { completeAnimatedBottomIfSettled() }
            return
        }

        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        recyclerView.stopScroll()
        if (layoutManager.findViewByPosition(lastPosition) != null) {
            snapPositionEndToRecyclerBottom(layoutManager, lastPosition, conversationId, generation)
        } else {
            layoutManager.scrollToPosition(lastPosition)
            afterMessagesLayout(conversationId, generation) {
                if (adapter.itemCount - 1 == lastPosition && !userScrolling &&
                    recyclerView.scrollState != RecyclerView.SCROLL_STATE_DRAGGING
                ) snapPositionEndToRecyclerBottom(layoutManager, lastPosition, conversationId, generation)
            }
        }
    }

    private fun completeAnimatedBottomIfSettled() {
        val generation = animatedBottomGeneration ?: return
        val recyclerView = _binding?.recyclerMessages ?: return
        val conversationId = renderedConversationId
        if (!isReadingTarget(conversationId, generation) || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE ||
            recyclerView.canScrollVertically(1)
        ) return
        animatedBottomGeneration = null
        completeReadingPosition(conversationId, generation)
    }

    private fun snapPositionEndToRecyclerBottom(
        layoutManager: LinearLayoutManager,
        position: Int,
        conversationId: String,
        generation: Long
    ) {
        if (!isReadingTarget(conversationId, generation)) return
        val targetView = layoutManager.findViewByPosition(position) ?: return
        val recyclerView = binding.recyclerMessages
        val viewportBottom = recyclerView.height - recyclerView.paddingBottom
        val delta = layoutManager.getDecoratedBottom(targetView) - viewportBottom
        if (delta != 0) recyclerView.scrollBy(0, delta)
        afterMessagesLayout(conversationId, generation) { completeReadingPosition(conversationId, generation) }
    }

    private fun renderEmptyState() {
        val showEmptyState = latestMessages.isEmpty()
        val wasVisible = binding.emptyPlaceholder.root.visibility == View.VISIBLE
        binding.emptyPlaceholder.root.visibility = if (showEmptyState) View.VISIBLE else View.GONE
        binding.recyclerMessages.visibility = if (showEmptyState) View.INVISIBLE else View.VISIBLE
        
        val isConfigured = latestSettings.resolvedChatModel() != null
        binding.emptyPlaceholder.textEmptySubtitle.text = getString(
            if (isConfigured) {
                R.string.empty_subtitle_configured
            } else {
                R.string.empty_subtitle_unconfigured
            }
        )
        binding.emptyPlaceholder.textEmptyTipsBody.text = getString(
            if (isConfigured) {
                R.string.empty_tips_configured
            } else {
                R.string.empty_tips_unconfigured
            }
        )
        
        // 始终显示示例问题（已配置时）
        binding.emptyPlaceholder.chipGroupExamples.visibility = if (isConfigured) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        // 已配置时隐藏提示卡片
        binding.emptyPlaceholder.cardTips.visibility = if (isConfigured) {
            View.GONE
        } else {
            View.VISIBLE
        }
        
        // 添加淡入动画（仅首次显示）
        if (showEmptyState && !wasVisible) {
            binding.emptyPlaceholder.root.alpha = 0f
            binding.emptyPlaceholder.root.animate()
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun resolvePickedAttachment(
        context: Context,
        uri: Uri,
        requestedKind: AttachmentPickerKind
    ): ChatAttachment? {
        val displayName = queryDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: "未命名文件"
        val mimeType = resolveMimeType(context, uri, displayName)
        val resolvedPath = resolveDirectPath(context, uri)
        val readablePath = resolvedPath?.takeIf { path ->
            runCatching { File(path).let { it.isFile && it.canRead() } }.getOrDefault(false)
        }
        val attachmentPath = readablePath ?: resolvedPath ?: uri.toString()
        val effectiveKind = if (requestedKind == AttachmentPickerKind.Image || isImageLike(mimeType, displayName)) {
            AttachmentPickerKind.Image
        } else {
            AttachmentPickerKind.File
        }

        return when (effectiveKind) {
            AttachmentPickerKind.Image -> buildImageAttachment(
                context = context,
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                attachmentPath = attachmentPath,
                readablePath = readablePath
            )

            AttachmentPickerKind.File -> buildFileAttachment(
                context = context,
                uri = uri,
                displayName = displayName,
                mimeType = mimeType,
                attachmentPath = attachmentPath,
                readablePath = readablePath
            )
        }
    }

    private fun buildImageAttachment(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String?,
        attachmentPath: String,
        readablePath: String?
    ): ChatAttachment? {
        val encoded = encodeImageForModel(context, uri, readablePath, mimeType) ?: return null
        return ChatAttachment(
            type = ChatAttachmentType.Image,
            displayName = displayName,
            mimeType = encoded.mimeType,
            path = attachmentPath,
            base64Data = Base64.encodeToString(encoded.data, Base64.NO_WRAP)
        )
    }

    /**
     * 把图片编码成可以直接放进请求体的字节。
     *
     * 尺寸和体积都在限制内时保留原始字节（截图类 PNG 重编码成 JPEG 会损伤文字可读性）；
     * 否则两趟解码——先只读尺寸算采样率，再按采样率解码——最后统一重编码为 JPEG。
     * 关键是绝不把原图整份读进内存：旧实现对一张 20MB 的照片瞬时峰值可达 60MB 以上。
     */
    private fun encodeImageForModel(
        context: Context,
        uri: Uri,
        directPath: String?,
        mimeType: String?
    ): EncodedImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openAttachmentStream(context, uri, directPath)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return@runCatching null

        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return@runCatching null
        }
        // 解码炸弹保护：声明尺寸离谱的文件直接拒绝。
        if (sourceWidth.toLong() * sourceHeight.toLong() > MaxImagePixels) {
            return@runCatching null
        }

        if (max(sourceWidth, sourceHeight) <= MaxImageEdgePx) {
            val original = readAttachmentBytes(context, uri, directPath, MaxEncodedImageBytes)
            if (original != null && !original.truncated) {
                return@runCatching EncodedImage(
                    data = original.data,
                    mimeType = mimeType?.takeIf { it.startsWith("image/", ignoreCase = true) } ?: "image/jpeg"
                )
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight, MaxImageEdgePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = openAttachmentStream(context, uri, directPath)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return@runCatching null

        val scaled = scaleToMaxEdge(decoded, MaxImageEdgePx)
        try {
            compressToJpeg(scaled)
        } finally {
            if (scaled !== decoded) {
                scaled.recycle()
            }
            decoded.recycle()
        }
    }.getOrNull()

    private fun openAttachmentStream(context: Context, uri: Uri, directPath: String?): InputStream? {
        return runCatching {
            if (directPath != null) {
                File(directPath).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
        }.getOrNull()
    }

    /** 取满足「采样后长边仍不小于目标」的最大 2 的幂，保证后续精确缩放不会放大。 */
    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sampleSize = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= maxEdge) {
            longEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleToMaxEdge(source: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= maxEdge) {
            return source
        }
        val ratio = maxEdge.toFloat() / longEdge
        val targetWidth = max(1, (source.width * ratio).roundToInt())
        val targetHeight = max(1, (source.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun compressToJpeg(bitmap: Bitmap): EncodedImage? {
        for (quality in ImageCompressQualities) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                return null
            }
            val bytes = output.toByteArray()
            if (bytes.size <= MaxEncodedImageBytes) {
                return EncodedImage(data = bytes, mimeType = "image/jpeg")
            }
        }
        return null
    }

    private fun buildFileAttachment(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String?,
        attachmentPath: String,
        readablePath: String?
    ): ChatAttachment? {
        if (readablePath != null) {
            return ChatAttachment(
                type = ChatAttachmentType.File,
                displayName = displayName,
                mimeType = mimeType,
                path = readablePath,
                directReadable = true
            )
        }

        if (!isTextLike(mimeType, displayName)) {
            return null
        }

        val bytes = readAttachmentBytes(context, uri, directPath = null, maxBytes = MaxTextAttachmentBytes)
            ?: return null
        val text = bytes.data.toString(Charsets.UTF_8)
        return ChatAttachment(
            type = ChatAttachmentType.File,
            displayName = displayName,
            mimeType = mimeType,
            path = attachmentPath,
            textContent = text,
            truncated = bytes.truncated
        )
    }

    private fun readAttachmentBytes(
        context: Context,
        uri: Uri,
        directPath: String?,
        maxBytes: Int
    ): ReadBytesResult? {
        return runCatching {
            val stream = if (directPath != null) {
                File(directPath).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: return null
            stream.use { input -> readLimitedBytes(input, maxBytes) }
        }.getOrNull()
    }

    private fun readLimitedBytes(input: InputStream, maxBytes: Int): ReadBytesResult {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var truncated = false
        val readLimit = maxBytes + 1
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            val allowed = readLimit - output.size()
            if (allowed <= 0) {
                truncated = true
                break
            }
            output.write(buffer, 0, minOf(read, allowed))
            if (output.size() > maxBytes) {
                truncated = true
                break
            }
        }
        val data = output.toByteArray()
        return if (data.size > maxBytes) {
            ReadBytesResult(data.copyOf(maxBytes), truncated = true)
        } else {
            ReadBytesResult(data, truncated = truncated)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path?.let { File(it).name }
        }
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun resolveMimeType(context: Context, uri: Uri, displayName: String): String? {
        val resolverType = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (resolverType != null && !resolverType.equals("application/octet-stream", ignoreCase = true)) {
            return resolverType
        }

        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .ifBlank { MimeTypeMap.getFileExtensionFromUrl(uri.toString()) }
            .lowercase(Locale.ROOT)
        return extension.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: resolverType
    }

    private fun resolveDirectPath(context: Context, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return null
        }

        return runCatching {
            if (!DocumentsContract.isDocumentUri(context, uri)) {
                return@runCatching queryDataColumn(context, uri)
            }
            val documentId = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> resolveExternalStorageDocumentPath(documentId)
                "com.android.providers.downloads.documents" -> resolveDownloadsDocumentPath(context, documentId)
                "com.android.providers.media.documents" -> resolveMediaDocumentPath(context, documentId)
                else -> null
            }
        }.getOrNull()
    }

    private fun resolveExternalStorageDocumentPath(documentId: String): String? {
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) {
            return null
        }
        val root = when {
            parts[0].equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
            parts[0].equals("home", ignoreCase = true) -> File(
                Environment.getExternalStorageDirectory(),
                "Documents"
            ).absolutePath
            else -> "/storage/${parts[0]}"
        }
        return File(root, parts[1]).absolutePath
    }

    private fun resolveDownloadsDocumentPath(context: Context, documentId: String): String? {
        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:")
        }
        val id = documentId.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(
            Uri.parse("content://downloads/public_downloads"),
            id
        )
        return queryDataColumn(context, uri)
    }

    private fun resolveMediaDocumentPath(context: Context, documentId: String): String? {
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) {
            return null
        }
        val contentUri = when (parts[0]) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        return queryDataColumn(
            context = context,
            uri = contentUri,
            selection = "_id=?",
            selectionArgs = arrayOf(parts[1])
        )
    }

    private fun queryDataColumn(
        context: Context,
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null
    ): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isImageLike(mimeType: String?, displayName: String): Boolean {
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) {
            return true
        }
        return displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT) in ImageExtensions
    }

    private fun isTextLike(mimeType: String?, displayName: String): Boolean {
        val normalizedMimeType = mimeType.orEmpty().lowercase(Locale.ROOT)
        if (normalizedMimeType.startsWith("text/")) {
            return true
        }
        if (normalizedMimeType in TextMimeTypes) {
            return true
        }
        return displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT) in TextExtensions
    }

    private fun copyMessage(message: ChatMessage) {
        val copyText = if (message.role == ChatRole.User) {
            buildUserCopyText(message)
        } else {
            buildAssistantCopyText(message)
        }
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.app_name),
                copyText
            )
        )
        // Android 13+ 系统自带剪贴板确认气泡，无需再弹 Toast。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), getString(R.string.copy_done), Toast.LENGTH_SHORT).show()
        }
    }

    /** 打开自由复制页：用旧的「仅 TextView」Markdown 渲染展示内容，便于用户自由选取复制。 */
    private fun openFreeCopy(message: ChatMessage) {
        val content = if (message.role == ChatRole.User) {
            buildUserCopyText(message)
        } else {
            buildAssistantCopyText(message)
        }
        com.mukapp.mote.FreeCopyActivity.start(requireContext(), content)
    }

    private fun buildAssistantCopyText(message: ChatMessage): String {
        return message.assistantParts.asSequence()
            .mapNotNull { part ->
                when (part) {
                    is AssistantMarkdownPart -> part.text.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            .joinToString(separator = "\n\n")
            .ifBlank { message.content }
    }

    private fun buildUserCopyText(message: ChatMessage): String {
        if (message.attachments.isEmpty()) {
            return message.content
        }

        return buildString {
            if (message.content.isNotBlank()) {
                append(message.content)
                append("\n\n")
            }
            append("附件：")
            message.attachments.forEachIndexed { index, attachment ->
                append('\n')
                append(index + 1)
                append(". ")
                append(
                    when (attachment.type) {
                        ChatAttachmentType.Image -> "图片"
                        ChatAttachmentType.File -> "文件"
                    }
                )
                append("：")
                append(attachment.displayName.ifBlank { attachment.path })
                attachment.path.takeIf { it.isNotBlank() }?.let { path ->
                    append("\n   路径：")
                    append(path)
                }
                if (attachment.type == ChatAttachmentType.File && !attachment.directReadable && attachment.textContent != null) {
                    append("\n   内容：\n")
                    append(attachment.textContent)
                    if (attachment.truncated) {
                        append("\n（内容过长，已截断）")
                    }
                }
            }
        }
    }

    private fun showConfirmationDialog(titleResId: Int, messageResId: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleResId)
            .setMessage(messageResId)
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private enum class AttachmentPickerKind {
        Image,
        File
    }

    private data class ReadBytesResult(
        val data: ByteArray,
        val truncated: Boolean
    )

    private data class EncodedImage(
        val data: ByteArray,
        val mimeType: String
    )

    private companion object {
        const val MarkdownPreparseThrottleMs = 64L
        const val MarkdownPreparseBeforeItems = 8
        const val MarkdownPreparseAfterItems = 12
        const val MarkdownPreparseFallbackTailItems = 18
        const val MarkdownPreparseMaxEntries = 32
        const val InitialImmediateScrollThreshold = 20
        const val ScrollButtonShowMs = 200L
        const val ScrollButtonHideMs = 150L
        const val ScrollButtonHiddenScale = 0f
        const val ScrollSpeedFactor = 0.5f
        const val MenuAddImage = 1
        const val MenuAddFile = 2
        /** Vision 模型的有效长边上限，超过这个尺寸不再提升识别质量，只会撑大请求体。 */
        const val MaxImageEdgePx = 1568
        /** 编码后（Base64 之前）的兜底字节上限。 */
        const val MaxEncodedImageBytes = 4 * 1024 * 1024
        /** 解码炸弹保护：像素总数上限。 */
        const val MaxImagePixels = 200_000_000L
        const val MaxTextAttachmentBytes = 100_000

        /** 超出体积上限时逐级降质量重试。 */
        val ImageCompressQualities = intArrayOf(85, 70, 55)

        val ImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val TextExtensions = setOf(
            "txt",
            "md",
            "markdown",
            "json",
            "xml",
            "html",
            "htm",
            "css",
            "js",
            "ts",
            "kt",
            "java",
            "c",
            "cpp",
            "h",
            "hpp",
            "py",
            "rs",
            "go",
            "sh",
            "bash",
            "zsh",
            "csv",
            "tsv",
            "yaml",
            "yml",
            "toml",
            "ini",
            "log",
            "sql"
        )
        val TextMimeTypes = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/ecmascript",
            "application/xhtml+xml",
            "application/csv",
            "application/sql",
            "application/x-sh",
            "application/x-shellscript"
        )
    }
}
