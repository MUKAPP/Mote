package com.mukapp.mote.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.R
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.databinding.ItemChatMessageBinding
import com.mukapp.mote.databinding.ItemChatMessageUserBinding
import com.mukapp.mote.ui.markdown.MarkdownParseCache
import com.mukapp.mote.util.dpInt

class ChatMessageAdapter(
    private val onCopyMessage: (ChatMessage) -> Unit,
    private val onEditMessage: (Int) -> Unit,
    private val onDeleteMessage: (Int) -> Unit,
    private val onRetryMessage: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()
    private val expandedThinkingPartIds = mutableSetOf<String>()
    private val expandedToolPartIds = mutableSetOf<String>()
    private val expandedUserMessageIds = mutableSetOf<String>()
    private val activeThinkingPartIdsByMessageId = mutableMapOf<String, String>()

    private var isSending: Boolean = false
    private var streamingMessageId: String? = null

    /** 全局 Markdown 解析缓存，由 ChatFragment 设置 */
    var parseCache: MarkdownParseCache? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return stableItemId(messages[position].id)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].role == ChatRole.User) {
            ViewTypeUser
        } else {
            ViewTypeAssistant
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == ViewTypeUser) {
            UserViewHolder(ItemChatMessageUserBinding.inflate(inflater, parent, false))
        } else {
            val binding = ItemChatMessageBinding.inflate(inflater, parent, false)
            // 注入全局解析缓存，让 MarkdownView 在绑定时优先使用预解析结果
            binding.markdownContent.setGlobalParseCache(parseCache)
            AssistantViewHolder(binding)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun getMessageAt(position: Int): ChatMessage? = messages.getOrNull(position)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        when (holder) {
            is AssistantViewHolder -> {
                if (STREAMING_PAYLOAD in payloads) {
                    // 流式更新走精简路径：只更新内容和状态
                    holder.bindStreaming(messages[position])
                } else {
                    holder.bind(messages[position], position)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(messages[position], position)
            is AssistantViewHolder -> holder.bind(messages[position], position)
        }
    }

    fun submitMessages(newMessages: List<ChatMessage>, sending: Boolean) {
        val filteredMessages = visibleMessagesFrom(newMessages)
        val nextStreamingMessageId = if (sending) {
            filteredMessages.lastOrNull { it.role == ChatRole.Assistant }?.id
        } else {
            null
        }

        if (canApplyStreamingTailUpdate(filteredMessages, sending, nextStreamingMessageId)) {
            isSending = sending
            streamingMessageId = nextStreamingMessageId
            val lastIndex = messages.lastIndex
            messages[lastIndex] = filteredMessages[lastIndex]
            notifyItemChanged(lastIndex, STREAMING_PAYLOAD)
            return
        }

        val oldMessages = messages.toList()
        val oldStreamingMessageId = streamingMessageId
        trimExpansionStateIfNeeded(filteredMessages)

        isSending = sending
        streamingMessageId = nextStreamingMessageId

        if (oldMessages.isEmpty() && filteredMessages.isEmpty()) {
            return
        }

        val sameSized = oldMessages.size == filteredMessages.size
        val sameIdentityOrder = sameSized && oldMessages.indices.all { index ->
            oldMessages[index].id == filteredMessages[index].id &&
                oldMessages[index].role == filteredMessages[index].role
        }

        if (sameIdentityOrder) {
            val changedIndices = filteredMessages.indices.filter { index ->
                oldMessages[index] != filteredMessages[index] ||
                    (oldMessages[index].id == oldStreamingMessageId) !=
                    (filteredMessages[index].id == streamingMessageId)
            }
            messages.clear()
            messages.addAll(filteredMessages)
            when {
                changedIndices.isEmpty() -> Unit
                changedIndices.size <= 8 -> changedIndices.forEach {
                    val isStreamingUpdate = filteredMessages[it].id == streamingMessageId
                    notifyItemChanged(it, if (isStreamingUpdate) STREAMING_PAYLOAD else null)
                }
                else -> notifyDataSetChanged()
            }
            return
        }

        val appendedToTail = oldMessages.size < filteredMessages.size &&
            oldMessages.indices.all { index ->
                oldMessages[index].id == filteredMessages[index].id &&
                    oldMessages[index].role == filteredMessages[index].role
            }

        if (appendedToTail) {
            messages.clear()
            messages.addAll(filteredMessages)
            val insertedCount = filteredMessages.size - oldMessages.size
            if (oldMessages.isEmpty() && insertedCount > BulkInsertNotifyThreshold) {
                notifyDataSetChanged()
            } else {
                notifyItemRangeInserted(oldMessages.size, insertedCount)
            }
            return
        }

        val removedFromTail = oldMessages.size > filteredMessages.size &&
            filteredMessages.indices.all { index ->
                oldMessages[index].id == filteredMessages[index].id &&
                    oldMessages[index].role == filteredMessages[index].role
            }

        if (removedFromTail) {
            messages.clear()
            messages.addAll(filteredMessages)
            notifyItemRangeRemoved(filteredMessages.size, oldMessages.size - filteredMessages.size)
            return
        }

        messages.clear()
        messages.addAll(filteredMessages)
        notifyDataSetChanged()
    }

    private fun visibleMessagesFrom(newMessages: List<ChatMessage>): List<ChatMessage> {
        return if (newMessages.none { it.role == ChatRole.Tool }) {
            newMessages
        } else {
            newMessages.filter { it.role != ChatRole.Tool }
        }
    }

    private fun trimExpansionStateIfNeeded(filteredMessages: List<ChatMessage>) {
        if (activeThinkingPartIdsByMessageId.isEmpty() &&
            expandedThinkingPartIds.isEmpty() &&
            expandedToolPartIds.isEmpty() &&
            expandedUserMessageIds.isEmpty()
        ) {
            return
        }

        val visibleMessageIds = filteredMessages.mapTo(mutableSetOf()) { it.id }
        val visibleThinkingPartIds = filteredMessages.asSequence()
            .flatMap { message -> message.assistantParts.asSequence() }
            .mapNotNull { part -> (part as? AssistantThinkingPart)?.id }
            .toMutableSet()
        val visibleToolPartIds = filteredMessages.asSequence()
            .flatMap { message -> message.assistantParts.asSequence() }
            .mapNotNull { part -> (part as? AssistantToolPart)?.id }
            .toMutableSet()
        activeThinkingPartIdsByMessageId.keys.retainAll(visibleMessageIds)
        expandedThinkingPartIds.retainAll(visibleThinkingPartIds)
        expandedToolPartIds.retainAll(visibleToolPartIds)
        expandedUserMessageIds.retainAll(visibleMessageIds)
    }

    private fun canApplyStreamingTailUpdate(
        filteredMessages: List<ChatMessage>,
        sending: Boolean,
        nextStreamingMessageId: String?
    ): Boolean {
        if (!sending || nextStreamingMessageId == null || streamingMessageId != nextStreamingMessageId) {
            return false
        }
        if (messages.isEmpty() || messages.size != filteredMessages.size) {
            return false
        }
        val lastIndex = messages.lastIndex
        val oldLast = messages[lastIndex]
        val newLast = filteredMessages[lastIndex]
        return oldLast.id == nextStreamingMessageId &&
            newLast.id == nextStreamingMessageId &&
            oldLast.role == newLast.role
    }

    private inner class AssistantViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            // 长按卡片任意位置（含 Markdown 文本）在手指处弹出菜单（复制/编辑/删除/重试）
            binding.cardMessage.setOnContentLongPressListener { x, y ->
                showAssistantPopupMenu(x, y)
            }
        }

        private fun showAssistantPopupMenu(touchX: Int, touchY: Int) {
            val message = currentMessageOrNull() ?: return
            val position = currentPositionOrNull() ?: return
            val isLastAiMessage = message.role == ChatRole.Assistant && position == messages.lastIndex

            val popupItems = buildList {
                if (hasCopyableContent(message)) {
                    add(MessagePopupItem(MENU_COPY, R.string.action_copy, R.drawable.ic_content_copy))
                }
                add(MessagePopupItem(MENU_EDIT, R.string.action_edit, R.drawable.ic_edit))
                add(MessagePopupItem(MENU_DELETE, R.string.action_delete, R.drawable.ic_delete))
                if (isLastAiMessage && !isSending) {
                    add(MessagePopupItem(MENU_RETRY, R.string.action_retry, R.drawable.ic_refresh))
                }
            }
            showMessagePopupAt(binding.cardMessage, touchX, touchY, popupItems) { itemId ->
                when (itemId) {
                    MENU_COPY -> onCopyMessage(message)
                    MENU_EDIT -> onEditMessage(position)
                    MENU_DELETE -> onDeleteMessage(position)
                    MENU_RETRY -> onRetryMessage(position)
                }
            }
        }

        fun clear() {
            binding.markdownContent.clearMarkdown()
            binding.typingIndicator.setAnimating(false)
        }

        /** 仅停止动画，保留 MarkdownView 渲染结果以便复用 */
        fun stopAnimations() {
            binding.typingIndicator.setAnimating(false)
        }

        fun bind(message: ChatMessage, position: Int) {
            val isStreamingMessage = isSending && message.id == streamingMessageId
            val hasContent = message.content.isNotBlank()
            val hasParts = message.assistantParts.isNotEmpty()
            val showGeneratingStatus = isStreamingMessage && !hasContent && !hasParts
            syncThinkingPartExpansion(message, isStreamingMessage)

            binding.markdownContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = 0
            }

            binding.textAiLabel.text = itemView.context.getString(R.string.label_ai)
            binding.textStatus.isVisible = showGeneratingStatus
            binding.textStatus.text = itemView.context.getString(R.string.status_generating)
            binding.typingIndicator.isVisible = isStreamingMessage
            binding.typingIndicator.setAnimating(isStreamingMessage)
            binding.markdownContent.isVisible = hasParts || hasContent
            if (hasParts) {
                binding.markdownContent.setParts(
                    parts = message.assistantParts,
                    isStreaming = isStreamingMessage,
                    expandedThinkingPartIds = expandedThinkingPartIds,
                    expandedToolPartIds = expandedToolPartIds
                )
            } else if (hasContent) {
                binding.markdownContent.setMarkdown(message.content, isStreamingMessage)
            } else {
                binding.markdownContent.clearMarkdown()
            }
        }

        /** 流式更新精简路径：只更新 MarkdownView 内容和实时状态 */
        fun bindStreaming(message: ChatMessage) {
            val hasContent = message.content.isNotBlank()
            val hasParts = message.assistantParts.isNotEmpty()
            syncThinkingPartExpansion(message, true)

            binding.markdownContent.isVisible = hasParts || hasContent
            if (hasParts) {
                binding.markdownContent.setParts(
                    parts = message.assistantParts,
                    isStreaming = true,
                    expandedThinkingPartIds = expandedThinkingPartIds,
                    expandedToolPartIds = expandedToolPartIds
                )
            } else if (hasContent) {
                binding.markdownContent.setMarkdown(message.content, true)
            }

            val showGeneratingStatus = !hasContent && !hasParts
            binding.textStatus.isVisible = showGeneratingStatus
            binding.typingIndicator.isVisible = true
            binding.typingIndicator.setAnimating(true)
        }

        private fun currentPositionOrNull(): Int? {
            val position = bindingAdapterPosition
            return position.takeIf { it != RecyclerView.NO_POSITION && it in messages.indices }
        }

        private fun currentMessageOrNull(): ChatMessage? {
            val position = currentPositionOrNull() ?: return null
            return messages.getOrNull(position)
        }

        private fun syncThinkingPartExpansion(message: ChatMessage, isStreamingMessage: Boolean) {
            val previousActiveThinkingId = activeThinkingPartIdsByMessageId[message.id]
            val currentActiveThinkingId = findActiveThinkingPartId(message.assistantParts, isStreamingMessage)
            if (previousActiveThinkingId != null && previousActiveThinkingId != currentActiveThinkingId) {
                expandedThinkingPartIds.remove(previousActiveThinkingId)
            }
            if (currentActiveThinkingId != null) {
                expandedThinkingPartIds.add(currentActiveThinkingId)
                activeThinkingPartIdsByMessageId[message.id] = currentActiveThinkingId
            } else {
                activeThinkingPartIdsByMessageId.remove(message.id)
            }
        }

        private fun findActiveThinkingPartId(parts: List<AssistantPart>, isStreamingMessage: Boolean): String? {
            if (!isStreamingMessage) {
                return null
            }
            return (parts.lastOrNull() as? AssistantThinkingPart)
                ?.takeIf { it.text.isNotBlank() }
                ?.id
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AssistantViewHolder) {
            // 只停止动画，不清除 MarkdownView 渲染结果；
            // 保留视图树让 ViewHolder 复用时可以走增量更新路径，避免从零重建
            holder.stopAnimations()
        }
        super.onViewRecycled(holder)
    }

    private inner class UserViewHolder(
        private val binding: ItemChatMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            // 长按卡片任意位置在手指处弹出菜单（复制/展开/编辑/删除）
            binding.cardMessage.setOnContentLongPressListener { x, y ->
                showUserPopupMenu(x, y)
            }
        }

        private fun showUserPopupMenu(touchX: Int, touchY: Int) {
            val message = currentMessageOrNull() ?: return
            val position = currentPositionOrNull() ?: return
            // 该用户消息对应最后一条 AI 回复时，提供重试（重试入口仍作用于最后一条 AI 消息）
            val lastIndex = messages.lastIndex
            val canRetryLastTurn = !isSending &&
                position == lastIndex - 1 &&
                messages.getOrNull(lastIndex)?.role == ChatRole.Assistant

            val popupItems = buildList {
                add(MessagePopupItem(MENU_COPY, R.string.action_copy, R.drawable.ic_content_copy))
                if (isCollapsible(message)) {
                    val expanded = expandedUserMessageIds.contains(message.id)
                    add(MessagePopupItem(
                        MENU_TOGGLE_EXPAND,
                        if (expanded) R.string.action_collapse else R.string.action_expand,
                        if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                    ))
                }
                add(MessagePopupItem(MENU_EDIT, R.string.action_edit, R.drawable.ic_edit))
                add(MessagePopupItem(MENU_DELETE, R.string.action_delete, R.drawable.ic_delete))
                if (canRetryLastTurn) {
                    add(MessagePopupItem(MENU_RETRY, R.string.action_retry, R.drawable.ic_refresh))
                }
            }
            showMessagePopupAt(binding.cardMessage, touchX, touchY, popupItems) { itemId ->
                when (itemId) {
                    MENU_COPY -> onCopyMessage(message)
                    MENU_TOGGLE_EXPAND -> toggleUserExpansion(message)
                    MENU_EDIT -> onEditMessage(position)
                    MENU_DELETE -> onDeleteMessage(position)
                    MENU_RETRY -> onRetryMessage(lastIndex)
                }
            }
        }

        private fun toggleUserExpansion(message: ChatMessage) {
            val expanded = expandedUserMessageIds.contains(message.id)
            if (expanded) {
                expandedUserMessageIds.remove(message.id)
                binding.textContent.maxLines = COLLAPSED_MAX_LINES
            } else {
                expandedUserMessageIds.add(message.id)
                binding.textContent.maxLines = Int.MAX_VALUE
            }
        }

        fun bind(message: ChatMessage, position: Int) {
            binding.textContent.text = message.content
            binding.textContent.isVisible = message.content.isNotBlank()

            // 附件标签
            if (message.attachments.isNotEmpty()) {
                binding.chipGroupAttachments.isVisible = true
                binding.chipGroupAttachments.removeAllViews()
                message.attachments.forEach { attachment ->
                    val chip = com.google.android.material.chip.Chip(
                        itemView.context,
                        null,
                        com.google.android.material.R.attr.chipStyle
                    ).apply {
                        setChipDrawable(
                            com.google.android.material.chip.ChipDrawable.createFromAttributes(
                                context, null, 0, R.style.Widget_Mote_Chip_Attachment_User
                            )
                        )
                        text = buildChipLabel(attachment)
                        isClickable = false
                        isCheckable = false
                        chipIcon = androidx.core.content.ContextCompat.getDrawable(
                            context,
                            when (attachment.type) {
                                ChatAttachmentType.Image -> R.drawable.ic_image
                                ChatAttachmentType.File -> R.drawable.ic_description
                            }
                        )
                        isChipIconVisible = true
                    }
                    binding.chipGroupAttachments.addView(chip)
                }
            } else {
                binding.chipGroupAttachments.isVisible = false
            }

            // 折叠态由长按菜单切换；收起时配合 ellipsize 显示省略号
            val isExpanded = expandedUserMessageIds.contains(message.id)
            binding.textContent.maxLines = if (isCollapsible(message) && !isExpanded) {
                COLLAPSED_MAX_LINES
            } else {
                Int.MAX_VALUE
            }
        }

        private fun isCollapsible(message: ChatMessage): Boolean {
            if (message.content.isBlank()) {
                return false
            }
            val lineCount = message.content.count { it == '\n' } + 1
            return lineCount > COLLAPSED_MAX_LINES
        }

        private fun currentPositionOrNull(): Int? {
            val position = bindingAdapterPosition
            return position.takeIf { it != RecyclerView.NO_POSITION && it in messages.indices }
        }

        private fun currentMessageOrNull(): ChatMessage? {
            val position = currentPositionOrNull() ?: return null
            return messages.getOrNull(position)
        }
    }

    private fun hasCopyableMarkdownParts(message: ChatMessage): Boolean {
        return message.assistantParts.any { part ->
            part is AssistantMarkdownPart && part.text.isNotBlank()
        }
    }

    private fun hasCopyableContent(message: ChatMessage): Boolean {
        return message.content.isNotBlank() || hasCopyableMarkdownParts(message)
    }

    private fun buildChipLabel(attachment: ChatAttachment): String {
        val name = attachment.displayName.ifBlank { attachment.path.substringAfterLast('/') }
        val suffix = when {
            attachment.type == ChatAttachmentType.Image -> ""
            attachment.directReadable -> ""
            attachment.textContent != null && attachment.truncated -> " (已截断)"
            else -> ""
        }
        return name + suffix
    }

    /** 在手指按下位置弹出消息菜单，图标间距由图标槽控制，不改变图标绘制尺寸。 */
    private fun showMessagePopupAt(
        card: View,
        touchX: Int,
        touchY: Int,
        items: List<MessagePopupItem>,
        onItemClick: (Int) -> Unit
    ) {
        if (items.isEmpty()) return

        lateinit var popup: PopupWindow
        val content = createMessagePopupContent(card.context, items) { itemId ->
            popup.dismiss()
            onItemClick(itemId)
        }
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = PopupMenuElevationDp.dpInt.toFloat()
            animationStyle = R.style.Animation_Mote_MessagePopupMenu
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(card.rootView.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(card.rootView.height, View.MeasureSpec.AT_MOST)
        )
        val cardLocation = IntArray(2)
        card.getLocationOnScreen(cardLocation)
        val safeTouchX = touchX.coerceIn(0, (card.width - 1).coerceAtLeast(0))
        val safeTouchY = touchY.coerceIn(0, (card.height - 1).coerceAtLeast(0))
        val anchorX = cardLocation[0] + safeTouchX
        val anchorY = cardLocation[1] + safeTouchY
        val x = if (safeTouchX >= card.width / 2) {
            anchorX - content.measuredWidth
        } else {
            anchorX
        }
        popup.showAtLocation(card, Gravity.NO_GRAVITY, x, anchorY)
    }

    private fun createMessagePopupContent(
        context: Context,
        items: List<MessagePopupItem>,
        onItemClick: (Int) -> Unit
    ): View {
        val iconColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val textColor = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface)
        val ripple = resolveThemeDrawable(context, android.R.attr.selectableItemBackground)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.popup_message_menu_background)
            elevation = PopupMenuElevationDp.dpInt.toFloat()
            setPadding(0, PopupMenuVerticalPaddingDp.dpInt, 0, PopupMenuVerticalPaddingDp.dpInt)
            minimumWidth = PopupMenuMinWidthDp.dpInt
            items.forEach { item ->
                addView(createMessagePopupRow(context, item, iconColor, textColor, ripple, onItemClick))
            }
        }
    }

    private fun createMessagePopupRow(
        context: Context,
        item: MessagePopupItem,
        iconColor: Int,
        textColor: Int,
        ripple: android.graphics.drawable.Drawable?,
        onItemClick: (Int) -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple?.constantState?.newDrawable()?.mutate()
            isClickable = true
            isFocusable = true
            minimumHeight = PopupMenuItemHeightDp.dpInt
            setOnClickListener { onItemClick(item.id) }

            addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(PopupIconSlotWidthDp.dpInt, PopupMenuItemHeightDp.dpInt)
                addView(ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        PopupIconSizeDp.dpInt,
                        PopupIconSizeDp.dpInt,
                        Gravity.START or Gravity.CENTER_VERTICAL
                    ).apply {
                        marginStart = PopupIconStartPaddingDp.dpInt
                    }
                    imageTintList = ColorStateList.valueOf(iconColor)
                    setImageResource(item.iconRes)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            })

            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = PopupTextEndPaddingDp.dpInt
                }
                setText(item.titleRes)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, PopupTextSizeSp)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun resolveThemeDrawable(context: Context, attr: Int): android.graphics.drawable.Drawable? {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getDrawable(context, typedValue.resourceId)
        } else {
            null
        }
    }

    private data class MessagePopupItem(
        val id: Int,
        @param:StringRes val titleRes: Int,
        @param:DrawableRes val iconRes: Int
    )

    private companion object {
        const val ViewTypeAssistant = 0
        const val ViewTypeUser = 1
        const val STREAMING_PAYLOAD = "streaming"
        const val COLLAPSED_MAX_LINES = 10
        const val BulkInsertNotifyThreshold = 40

        // PopupMenu 菜单项 ID
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
        const val MENU_RETRY = 3
        const val MENU_COPY = 4
        const val MENU_TOGGLE_EXPAND = 5
        const val PopupMenuMinWidthDp = 156
        const val PopupMenuElevationDp = 3
        const val PopupMenuVerticalPaddingDp = 12
        const val PopupMenuItemHeightDp = 48
        const val PopupIconStartPaddingDp = 16
        const val PopupIconEndPaddingDp = 12
        const val PopupIconSizeDp = 24
        const val PopupIconSlotWidthDp = PopupIconStartPaddingDp + PopupIconSizeDp + PopupIconEndPaddingDp
        const val PopupTextEndPaddingDp = 20
        const val PopupTextSizeSp = 16f

        /** 优先把 UUID 形式的消息 ID 映射为稳定 long，非 UUID 时回退 FNV 风格折叠，避免 hashCode 截断碰撞。 */
        fun stableItemId(id: String): Long {
            return runCatching {
                val uuid = java.util.UUID.fromString(id)
                uuid.mostSignificantBits xor uuid.leastSignificantBits
            }.getOrElse {
                id.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
            }
        }
    }
}
