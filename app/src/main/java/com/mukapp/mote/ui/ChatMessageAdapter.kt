package com.mukapp.mote.ui

import androidx.core.view.updateLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
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
            // 长按卡片弹出菜单（复制/编辑/删除/重试）
            binding.cardMessage.setOnLongClickListener {
                showAssistantPopupMenu(it)
                true
            }
        }

        private fun showAssistantPopupMenu(view: android.view.View) {
            val message = currentMessageOrNull() ?: return
            val position = currentPositionOrNull() ?: return
            val isLastAiMessage = message.role == ChatRole.Assistant && position == messages.lastIndex

            val popup = android.widget.PopupMenu(view.context, view)
            if (hasCopyableContent(message)) {
                popup.menu.add(0, MENU_COPY, 0, R.string.action_copy)
            }
            popup.menu.add(0, MENU_EDIT, 1, R.string.action_edit)
            popup.menu.add(0, MENU_DELETE, 2, R.string.action_delete)
            if (isLastAiMessage && !isSending) {
                popup.menu.add(0, MENU_RETRY, 3, R.string.action_retry)
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COPY -> {
                        onCopyMessage(message)
                        true
                    }
                    MENU_EDIT -> {
                        onEditMessage(position)
                        true
                    }
                    MENU_DELETE -> {
                        onDeleteMessage(position)
                        true
                    }
                    MENU_RETRY -> {
                        onRetryMessage(position)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
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
            // 长按卡片弹出菜单（复制/展开/编辑/删除）
            binding.cardMessage.setOnLongClickListener {
                showUserPopupMenu(it)
                true
            }
        }

        private fun showUserPopupMenu(view: android.view.View) {
            val message = currentMessageOrNull() ?: return
            val position = currentPositionOrNull() ?: return

            val popup = android.widget.PopupMenu(view.context, view)
            popup.menu.add(0, MENU_COPY, 0, R.string.action_copy)
            if (isCollapsible(message)) {
                val expanded = expandedUserMessageIds.contains(message.id)
                popup.menu.add(
                    0,
                    MENU_TOGGLE_EXPAND,
                    1,
                    if (expanded) R.string.action_collapse else R.string.action_expand
                )
            }
            popup.menu.add(0, MENU_EDIT, 2, R.string.action_edit)
            popup.menu.add(0, MENU_DELETE, 3, R.string.action_delete)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COPY -> {
                        onCopyMessage(message)
                        true
                    }
                    MENU_TOGGLE_EXPAND -> {
                        toggleUserExpansion(message)
                        true
                    }
                    MENU_EDIT -> {
                        onEditMessage(position)
                        true
                    }
                    MENU_DELETE -> {
                        onDeleteMessage(position)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
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
