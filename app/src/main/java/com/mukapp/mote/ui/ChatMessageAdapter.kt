package com.mukapp.mote.ui

import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.mukapp.mote.R
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.databinding.ItemChatMessageBinding
import com.mukapp.mote.databinding.ItemChatMessageUserBinding

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
            AssistantViewHolder(ItemChatMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        when (holder) {
            is AssistantViewHolder -> holder.bind(messages[position], position)
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
                changedIndices.size <= 4 -> changedIndices.forEach {
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
        fun clear() {
            binding.markdownContent.clearMarkdown()
        }

        fun bind(message: ChatMessage, position: Int) {
            val isStreamingMessage = isSending && message.id == streamingMessageId
            val hasContent = message.content.isNotBlank()
            val hasParts = message.assistantParts.isNotEmpty()
            val isLastAiMessage = message.role == ChatRole.Assistant && position == messages.lastIndex
            syncThinkingPartExpansion(message, isStreamingMessage)

            binding.textAiLabel.text = itemView.context.getString(R.string.label_ai)
            binding.textStatus.isVisible = !hasContent && !hasParts
            binding.textStatus.text = itemView.context.getString(R.string.status_generating)
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

            binding.layoutActions.isVisible = !isStreamingMessage
            binding.btnCopy.isEnabled = hasContent
            binding.btnRetry.isVisible = isLastAiMessage && !isStreamingMessage
            binding.btnCopy.setOnClickListener { onCopyMessage(message) }
            binding.btnEdit.setOnClickListener { onEditMessage(position) }
            binding.btnDelete.setOnClickListener { onDeleteMessage(position) }
            binding.btnRetry.setOnClickListener { onRetryMessage(position) }
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

    private inner class UserViewHolder(
        private val binding: ItemChatMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage, position: Int) {
            binding.textContent.text = message.content
            binding.btnCopy.isEnabled = message.content.isNotBlank()
            binding.btnCopy.setOnClickListener { onCopyMessage(message) }
            binding.btnEdit.setOnClickListener { onEditMessage(position) }
            binding.btnDelete.setOnClickListener { onDeleteMessage(position) }
        }
    }

    private companion object {
        const val ViewTypeAssistant = 0
        const val ViewTypeUser = 1
        const val STREAMING_PAYLOAD = "streaming"
    }
}
