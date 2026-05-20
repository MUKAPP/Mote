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
    private val activeThinkingPartIdsByMessageId = mutableMapOf<String, String>()

    private var isSending: Boolean = false
    private var streamingMessageId: String? = null

    /** 全局 Markdown 解析缓存，由 ChatFragment 设置 */
    var parseCache: MarkdownParseCache? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return messages[position].id.hashCode().toLong()
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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        when (holder) {
            is AssistantViewHolder -> {
                if (STREAMING_PAYLOAD in payloads) {
                    // 流式更新走精简路径：只更新内容和状态，跳过按钮和 layoutActions 等
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
        val oldMessages = messages.toList()
        val oldStreamingMessageId = streamingMessageId
        val filteredMessages = newMessages.filter { it.role != ChatRole.Tool }
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

        isSending = sending
        streamingMessageId = if (sending) {
            filteredMessages.lastOrNull { it.role == ChatRole.Assistant }?.id
        } else {
            null
        }

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
            notifyItemRangeInserted(oldMessages.size, filteredMessages.size - oldMessages.size)
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

    private inner class AssistantViewHolder(
        private val binding: ItemChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            // OnClickListener 在创建时设置一次，通过 bindingAdapterPosition 动态获取位置
            binding.btnCopy.setOnClickListener {
                currentMessageOrNull()?.let(onCopyMessage)
            }
            binding.btnEdit.setOnClickListener {
                currentPositionOrNull()?.let(onEditMessage)
            }
            binding.btnDelete.setOnClickListener {
                currentPositionOrNull()?.let(onDeleteMessage)
            }
            binding.btnRetry.setOnClickListener {
                currentPositionOrNull()?.let(onRetryMessage)
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
            val hasCopyableContent = hasContent || hasCopyableMarkdownParts(message)
            val isLastAiMessage = message.role == ChatRole.Assistant && position == messages.lastIndex
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

            binding.layoutActions.isVisible = !isStreamingMessage && (hasContent || hasParts)
            binding.btnCopy.isEnabled = hasCopyableContent
            binding.btnRetry.isVisible = isLastAiMessage && !isStreamingMessage
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
            binding.btnCopy.setOnClickListener {
                currentMessageOrNull()?.let(onCopyMessage)
            }
            binding.btnEdit.setOnClickListener {
                currentPositionOrNull()?.let(onEditMessage)
            }
            binding.btnDelete.setOnClickListener {
                currentPositionOrNull()?.let(onDeleteMessage)
            }
        }

        fun bind(message: ChatMessage, position: Int) {
            binding.textContent.text = message.content
            binding.btnCopy.isEnabled = message.content.isNotBlank()
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

    private companion object {
        const val ViewTypeAssistant = 0
        const val ViewTypeUser = 1
        const val STREAMING_PAYLOAD = "streaming"
    }
}
