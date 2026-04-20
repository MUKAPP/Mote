package com.mukapp.mote.ui

import android.widget.TextView
import android.util.TypedValue
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.collection.LruCache
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.IntermediateStep
import com.mukapp.mote.data.model.ToolResultInfo
import com.mukapp.mote.databinding.IntermediateStepsBlockBinding
import com.mukapp.mote.databinding.ItemChatMessageBinding
import com.mukapp.mote.databinding.ItemChatMessageUserBinding
import com.mukapp.mote.databinding.ItemToolResultBinding
import com.mukapp.mote.ui.markdown.StreamingMarkdownRenderer
import com.mukapp.mote.R
import org.json.JSONObject

class ChatMessageAdapter(
    private val onCopyMessage: (ChatMessage) -> Unit,
    private val onEditMessage: (Int) -> Unit,
    private val onDeleteMessage: (Int) -> Unit,
    private val onRetryMessage: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()
    private val expandedStepMessageIds = mutableSetOf<String>()
    private val expandedToolCallIds = mutableSetOf<String>()

    private var isSending: Boolean = false
    private var streamingMessageId: String? = null
    private val rendererCache = LruCache<String, StreamingMarkdownRenderer>(16)
    private var maxStepsHeightPx: Int = 0

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
        if (maxStepsHeightPx == 0) {
            maxStepsHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                320f,
                parent.resources.displayMetrics
            ).toInt()
        }

        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == ViewTypeUser) {
            UserViewHolder(ItemChatMessageUserBinding.inflate(inflater, parent, false))
        } else {
            AssistantViewHolder(ItemChatMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = messages.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is AssistantViewHolder) {
            holder.clear()
        }
    }

    private fun getOrCreateRenderer(messageId: String, context: android.content.Context): StreamingMarkdownRenderer {
        var renderer = rendererCache[messageId]
        if (renderer == null) {
            renderer = StreamingMarkdownRenderer(context.applicationContext)
            rendererCache.put(messageId, renderer)
        }
        return renderer
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        when (holder) {
            is AssistantViewHolder -> holder.bindStreamingUpdate(messages[position], position)
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
        private var lastStepSignature: Int = 0
        private var lastStepCount: Int = 0
        private var streamingThinkingTextView: TextView? = null

        fun clear() {
            streamingThinkingTextView = null
        }

        fun bind(message: ChatMessage, position: Int) {
            streamingThinkingTextView = null
            val displaySteps = IntermediateStepsHelper.displayStepsFor(message)
            val isStreamingMessage = isSending && message.id == streamingMessageId
            val hasSteps = displaySteps.isNotEmpty()
            val hasContent = message.content.isNotBlank()
            val isLastAiMessage = message.role == ChatRole.Assistant && position == messages.lastIndex

            binding.textAiLabel.text = itemView.context.getString(R.string.label_ai)
            binding.textStatus.isVisible = !hasContent && !hasSteps
            binding.textStatus.text = itemView.context.getString(R.string.status_generating)
            binding.markdownContent.isVisible = hasContent
            if (hasContent) {
                val renderer = getOrCreateRenderer(message.id, itemView.context)
                if (isStreamingMessage) {
                    binding.markdownContent.text = renderer.setMarkdown(message.content)
                    binding.markdownContent.movementMethod = null
                } else {
                    binding.markdownContent.text = renderer.renderStatic(message.content)
                    binding.markdownContent.movementMethod = LinkMovementMethod.getInstance()
                }
            } else {
                binding.markdownContent.text = ""
                binding.markdownContent.movementMethod = null
            }

            binding.layoutActions.isVisible = !isStreamingMessage
            binding.btnCopy.isEnabled = hasContent
            binding.btnRetry.isVisible = isLastAiMessage && !isStreamingMessage
            binding.btnCopy.setOnClickListener { onCopyMessage(message) }
            binding.btnEdit.setOnClickListener { onEditMessage(position) }
            binding.btnDelete.setOnClickListener { onDeleteMessage(position) }
            binding.btnRetry.setOnClickListener { onRetryMessage(position) }

            binding.stepsBlock.root.isVisible = hasSteps
            if (hasSteps) {
                bindIntermediateSteps(binding.stepsBlock, message, displaySteps, isStreamingMessage)
            } else {
                binding.stepsBlock.scrollSteps.scrollTo(0, 0)
            }

            lastStepSignature = computeStepSignature(displaySteps)
            lastStepCount = displaySteps.size
        }

        fun bindStreamingUpdate(message: ChatMessage, position: Int) {
            val displaySteps = IntermediateStepsHelper.displayStepsFor(message)
            val isStreamingMessage = isSending && message.id == streamingMessageId
            val hasSteps = displaySteps.isNotEmpty()
            val hasContent = message.content.isNotBlank()
            val currentStepSignature = computeStepSignature(displaySteps)
            val currentStepCount = displaySteps.size

            binding.textStatus.isVisible = !hasContent && !hasSteps
            binding.markdownContent.isVisible = hasContent
            if (hasContent) {
                binding.markdownContent.movementMethod = null
                val renderer = getOrCreateRenderer(message.id, itemView.context)
                binding.markdownContent.text = renderer.setMarkdown(message.content)
            } else {
                binding.markdownContent.text = ""
            }

            if (hasContent && binding.stepsBlock.scrollSteps.isVisible
                && !expandedStepMessageIds.contains(message.id)
            ) {
                updateStepsScrollHeight(binding.stepsBlock, expanded = false, scrollToBottom = false)
                binding.stepsBlock.btnToggleSteps.setImageResource(R.drawable.ic_expand_more)
            }

            if (currentStepSignature == lastStepSignature) {
                return
            }

            if (currentStepCount == lastStepCount && currentStepCount > 0) {
                val lastStep = displaySteps.lastOrNull()
                if (lastStep != null && lastStep.thinkingContent.isNotBlank() && streamingThinkingTextView != null) {
                    streamingThinkingTextView?.text = lastStep.thinkingContent
                    lastStepSignature = currentStepSignature
                    updateStepsScrollHeight(binding.stepsBlock, expanded = true, scrollToBottom = true)
                    return
                }
            }

            lastStepSignature = currentStepSignature
            lastStepCount = currentStepCount
            streamingThinkingTextView = null
            binding.stepsBlock.root.isVisible = hasSteps
            if (hasSteps) {
                bindIntermediateSteps(
                    binding.stepsBlock,
                    message,
                    displaySteps,
                    isStreamingMessage
                ) { textView -> streamingThinkingTextView = textView }
            }
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

    private fun bindIntermediateSteps(
        binding: IntermediateStepsBlockBinding,
        message: ChatMessage,
        steps: List<IntermediateStep>,
        isStreaming: Boolean,
        onLastThinkingViewCreated: ((TextView) -> Unit)? = null
    ) {
        val expanded = if (isStreaming) {
            message.content.isBlank()
        } else {
            expandedStepMessageIds.contains(message.id)
        }

        binding.textStepsTitle.text = binding.root.context.getString(
            if (isStreaming) R.string.label_processing else R.string.label_thinking
        )
        binding.btnToggleSteps.setImageResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
        val toggle = View.OnClickListener {
            val nextExpanded = !binding.scrollSteps.isVisible
            binding.btnToggleSteps.setImageResource(
                if (nextExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            if (nextExpanded) {
                expandedStepMessageIds.add(message.id)
            } else {
                expandedStepMessageIds.remove(message.id)
            }
            updateStepsScrollHeight(
                binding = binding,
                expanded = nextExpanded,
                scrollToBottom = nextExpanded && isStreaming
            )
        }
        binding.layoutHeader.setOnClickListener(toggle)
        binding.containerSteps.removeAllViews()

        val inflater = LayoutInflater.from(binding.root.context)
        steps.forEachIndexed { stepIndex, step ->
            if (step.thinkingContent.isNotBlank()) {
                val view = createStepText(inflater, binding.containerSteps, step.thinkingContent, true)
                binding.containerSteps.addView(view)
                if (isStreaming && stepIndex == steps.lastIndex) {
                    onLastThinkingViewCreated?.invoke(view as TextView)
                }
            }
            if (step.content.isNotBlank()) {
                binding.containerSteps.addView(createStepText(inflater, binding.containerSteps, step.content, false))
            }
            step.toolResults.forEach { toolResult ->
                binding.containerSteps.addView(createToolResultView(inflater, binding.containerSteps, toolResult))
            }
        }
        updateStepsScrollHeight(
            binding = binding,
            expanded = expanded,
            scrollToBottom = expanded && isStreaming
        )
    }

    private fun updateStepsScrollHeight(
        binding: IntermediateStepsBlockBinding,
        expanded: Boolean,
        scrollToBottom: Boolean
    ) {
        binding.scrollSteps.isVisible = expanded
        val layoutParams = binding.scrollSteps.layoutParams
        if (!expanded) {
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.scrollSteps.layoutParams = layoutParams
            binding.scrollSteps.scrollTo(0, 0)
            return
        }

        binding.scrollSteps.post {
            val availableWidth = when {
                binding.scrollSteps.width > 0 -> binding.scrollSteps.width
                binding.root.width > 0 -> binding.root.width - binding.root.paddingLeft - binding.root.paddingRight
                else -> binding.containerSteps.width
            }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                availableWidth.coerceAtLeast(1),
                View.MeasureSpec.EXACTLY
            )
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            binding.containerSteps.measure(widthSpec, heightSpec)

            val desiredHeight = binding.containerSteps.measuredHeight
                .coerceAtLeast(1)
                .coerceAtMost(maxStepsHeightPx)
            val currentLayoutParams = binding.scrollSteps.layoutParams
            if (currentLayoutParams.height != desiredHeight) {
                currentLayoutParams.height = desiredHeight
                binding.scrollSteps.layoutParams = currentLayoutParams
            }

            if (scrollToBottom) {
                binding.scrollSteps.post {
                    binding.scrollSteps.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun createStepText(
        inflater: LayoutInflater,
        parent: ViewGroup,
        text: String,
        useMonospace: Boolean
    ): View {
        val view = inflater.inflate(R.layout.item_intermediate_text, parent, false)
        val binding = com.mukapp.mote.databinding.ItemIntermediateTextBinding.bind(view)
        binding.textStep.text = text
        binding.textStep.typeface = if (useMonospace) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT
        return binding.root
    }

    private fun createToolResultView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        toolResult: ToolResultInfo
    ): View {
        val binding = ItemToolResultBinding.inflate(inflater, parent, false)
        val toolStateKey = toolResult.toolCallId.ifBlank {
            toolResult.toolName + toolResult.toolArguments.hashCode().toString()
        }
        val expanded = expandedToolCallIds.contains(toolStateKey)

        binding.textSummary.text = IntermediateStepsHelper.parseToolSummary(toolResult.toolName, toolResult.toolArguments)
        binding.textArguments.text = if (toolResult.toolArguments.isBlank()) {
            ""
        } else {
            runCatching { JSONObject(toolResult.toolArguments).toString(2) }.getOrDefault(toolResult.toolArguments)
        }
        binding.textResult.text = toolResult.result
        binding.groupArguments.isVisible = binding.textArguments.text.isNotBlank()
        binding.containerDetail.isVisible = expanded
        binding.btnToggleDetail.setImageResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )

        val toggle = View.OnClickListener {
            val nextExpanded = !binding.containerDetail.isVisible
            binding.containerDetail.isVisible = nextExpanded
            binding.btnToggleDetail.setImageResource(
                if (nextExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            if (nextExpanded) {
                expandedToolCallIds.add(toolStateKey)
            } else {
                expandedToolCallIds.remove(toolStateKey)
            }
        }
        binding.layoutHeader.setOnClickListener(toggle)
        return binding.root
    }

    private companion object {
        const val ViewTypeAssistant = 0
        const val ViewTypeUser = 1
        const val STREAMING_PAYLOAD = "streaming"

        fun computeStepSignature(steps: List<IntermediateStep>): Int {
            var result = steps.size
            steps.forEach { step ->
                result = 31 * result + step.thinkingContent.length
                result = 31 * result + step.content.length
                result = 31 * result + step.toolResults.size
            }
            return result
        }
    }
}
