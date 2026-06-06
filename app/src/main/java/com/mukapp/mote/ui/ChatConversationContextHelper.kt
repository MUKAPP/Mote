package com.mukapp.mote.ui

import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ContextSummary
import com.mukapp.mote.data.model.TokenUsage
import org.json.JSONObject

internal object ChatConversationContextHelper {
    internal const val MaxToolResultContextChars = 24_000

    data class ContextTokenCount(
        val tokens: Int,
        val sourceLabel: String
    )

    data class ContextTokenUsageAnchor(
        val messages: List<ChatMessage>,
        val inputTokens: Int
    )

    private const val MaxCopiedMetadataStringChars = 2_000
    private val LargePayloadKeys = setOf(
        "content",
        "stdout",
        "stderr",
        "stdout_so_far",
        "stderr_so_far",
        "entries",
        "results",
        "items",
        "head",
        "tail"
    )

    fun filterConversationMessages(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.filter { message ->
            (message.role != ChatRole.System || message.isContextSummary) &&
                    !message.excludeFromConversation
        }
    }

    fun rebuildConversationFromUiMessages(uiMessages: List<ChatMessage>): List<ChatMessage> {
        return uiMessages.filter { message ->
            message.role != ChatRole.Tool &&
                    message.role != ChatRole.System &&
                    !message.excludeFromConversation
        }
    }

    fun rebuildConversationAfterUiMutation(
        uiMessages: List<ChatMessage>,
        conversationMessages: List<ChatMessage>,
        affectedUserMessageIds: Set<String>
    ): List<ChatMessage> {
        val normalizedConversation = filterConversationMessages(conversationMessages)
            .filterNot { it.isContextSummary }
        val visibleUsers = uiMessages.filter { message ->
            message.role == ChatRole.User && !message.excludeFromConversation
        }
        if (visibleUsers.isEmpty()) {
            return emptyList()
        }

        val conversationTurns = buildConversationTurns(normalizedConversation)

        val rebuiltMessages = mutableListOf<ChatMessage>()
        visibleUsers.forEach { userMessage ->
            val apiTurn = conversationTurns[userMessage.id]
            if (apiTurn != null) {
                rebuiltMessages += apiTurn
            }
        }

        return rebuiltMessages
    }

    fun collectConversationTurnMessageIds(
        conversationMessages: List<ChatMessage>,
        userMessageIds: Set<String>
    ): Set<String> {
        if (userMessageIds.isEmpty()) {
            return emptySet()
        }
        val conversationTurns = buildConversationTurns(
            filterConversationMessages(conversationMessages).filterNot { it.isContextSummary }
        )
        return userMessageIds + userMessageIds.flatMap { userMessageId ->
            conversationTurns[userMessageId].orEmpty().map { it.id }
        }
    }

    fun filterContextSummariesAfterMessagesRemoved(
        summaries: List<ContextSummary>,
        removedMessageIds: Set<String>
    ): List<ContextSummary> {
        if (removedMessageIds.isEmpty()) {
            return summaries
        }
        val removedIds = removedMessageIds.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            summaries.forEach { summary ->
                if (summary.id !in removedIds && summary.sourceMessageIds.any { sourceId -> sourceId in removedIds }) {
                    removedIds += summary.id
                    changed = true
                }
            }
        } while (changed)
        return summaries.filterNot { summary ->
            summary.id in removedIds
        }
    }

    fun applyContextSummariesForRequest(
        conversationMessages: List<ChatMessage>,
        contextSummaries: List<ContextSummary>
    ): List<ChatMessage> {
        val normalizedMessages = filterConversationMessages(conversationMessages)
            .filterNot { it.isContextSummary }
        val validSummaries = contextSummaries.filter { summary ->
            summary.content.isNotBlank() && summary.sourceMessageIds.isNotEmpty()
        }
        if (normalizedMessages.isEmpty() || validSummaries.isEmpty()) {
            return normalizedMessages
        }

        val indexById = normalizedMessages.withIndex().associate { (index, message) ->
            message.id to index
        }
        val summaryById = validSummaries.associateBy { it.id }
        validSummaries.asReversed().forEach { summary ->
            val appliedMessages = applySingleContextSummary(
                normalizedMessages = normalizedMessages,
                indexById = indexById,
                summaryById = summaryById,
                summary = summary
            )
            if (appliedMessages != null) {
                return appliedMessages
            }
        }
        return normalizedMessages
    }

    private fun applySingleContextSummary(
        normalizedMessages: List<ChatMessage>,
        indexById: Map<String, Int>,
        summaryById: Map<String, ContextSummary>,
        summary: ContextSummary
    ): List<ChatMessage>? {
        val coveredMessageIds = resolveCoveredMessageIds(
            summary = summary,
            summaryById = summaryById,
            visitingSummaryIds = emptySet()
        )
        val indices = coveredMessageIds
            .mapNotNull { sourceId -> indexById[sourceId] }
            .distinct()
            .sorted()
        if (indices.isEmpty()) {
            return listOf(buildContextSummaryMessage(summary)) + normalizedMessages
        }
        val start = indices.first()
        val end = indices.last()
        if (indices.size != end - start + 1) {
            return null
        }

        return normalizedMessages.take(start) +
                buildContextSummaryMessage(summary) +
                normalizedMessages.drop(end + 1)
    }

    private fun resolveCoveredMessageIds(
        summary: ContextSummary,
        summaryById: Map<String, ContextSummary>,
        visitingSummaryIds: Set<String>
    ): List<String> {
        if (summary.id in visitingSummaryIds) {
            return emptyList()
        }
        val nextVisiting = visitingSummaryIds + summary.id
        return summary.sourceMessageIds.flatMap { sourceId ->
            val sourceSummary = summaryById[sourceId]
            if (sourceSummary == null) {
                listOf(sourceId)
            } else {
                resolveCoveredMessageIds(sourceSummary, summaryById, nextVisiting)
            }
        }.distinct()
    }

    fun limitToolResultForContext(message: ChatMessage): ChatMessage {
        if (message.role != ChatRole.Tool) {
            return message
        }
        val content = message.content
        if (content.length <= MaxToolResultContextChars) {
            return message
        }

        val headLength = MaxToolResultContextChars / 2
        val tailLength = MaxToolResultContextChars - headLength
        val omittedChars = content.length - MaxToolResultContextChars
        val originalObject = runCatching { JSONObject(content) }.getOrNull()
        return message.copy(
            content = JSONObject().apply {
                originalObject?.let { copyToolResultMetadata(source = it, target = this) }
                put("truncated", true)
                put("message", "工具结果过长，已为 API 上下文保留首尾片段；完整结果仍显示在界面消息中。")
                put("original_format", detectToolResultFormat(content))
                put("original_chars", content.length)
                put("omitted_chars", omittedChars)
                put("head", content.take(headLength))
                put("tail", content.takeLast(tailLength))
            }.toString(2)
        )
    }

    fun limitToolResultsForContext(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.map { message -> limitToolResultForContext(message) }
    }

    fun replaceLoadingToolParts(
        parts: List<AssistantPart>,
        toolResults: List<ChatMessage>
    ): List<AssistantPart> {
        val resultById = toolResults.associateBy { result -> result.toolCallId }
        return parts.map { part ->
            if (part is AssistantToolPart && part.isLoading) {
                val result = resultById[part.id]
                part.copy(
                    result = result?.content.orEmpty(),
                    isLoading = false
                )
            } else {
                part
            }
        }
    }

    fun detectToolResultFormat(content: String): String {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("{") -> "json_object"
            trimmed.startsWith("[") -> "json_array"
            else -> "text"
        }
    }

    fun estimateConversationTokens(messages: List<ChatMessage>): Int {
        return messages.sumOf { message -> estimateMessageTokens(message) + MessageOverheadTokens }
    }

    fun buildTokenUsageAnchor(
        messages: List<ChatMessage>,
        usage: TokenUsage?
    ): ContextTokenUsageAnchor? {
        val inputTokens = usage?.inputTokens?.takeIf { it > 0 } ?: return null
        return ContextTokenUsageAnchor(
            messages = filterConversationMessages(messages),
            inputTokens = inputTokens
        )
    }

    fun resolveConversationTokenCount(
        messages: List<ChatMessage>,
        usageAnchor: ContextTokenUsageAnchor?
    ): ContextTokenCount {
        val normalizedMessages = filterConversationMessages(messages)
        if (usageAnchor != null && hasMessagePrefix(normalizedMessages, usageAnchor.messages)) {
            val appendedMessages = normalizedMessages.drop(usageAnchor.messages.size)
            val appendedTokens = estimateConversationTokens(appendedMessages)
            return ContextTokenCount(
                tokens = addTokenCounts(usageAnchor.inputTokens, appendedTokens),
                sourceLabel = if (appendedMessages.isEmpty()) {
                    "API 返回"
                } else {
                    "API 返回 + 增量估算"
                }
            )
        }

        return ContextTokenCount(
            tokens = estimateConversationTokens(normalizedMessages),
            sourceLabel = "本地估算"
        )
    }

    fun isOverModelContextLength(settings: ApiSettings, contextTokens: Int): Boolean {
        val hardLimit = settings.modelContextLength.takeIf { it > 0 } ?: return false
        return contextTokens > hardLimit
    }

    fun requireWithinModelContextLength(settings: ApiSettings, contextTokens: Int) {
        if (!isOverModelContextLength(settings, contextTokens)) {
            return
        }
        throw IllegalStateException(
            "当前上下文长度已超过模型上下文长度，且无法在不破坏工具调用顺序的情况下继续压缩。请删除部分历史消息或调大模型上下文长度后重试。"
        )
    }

    private fun buildContextSummaryMessage(summary: ContextSummary): ChatMessage {
        return ChatMessage(
            id = summary.id,
            role = ChatRole.User,
            content = buildContextSummaryContent(summary.content),
            isContextSummary = true,
            contextSummarySourceIds = summary.sourceMessageIds
        )
    }

    private fun buildContextSummaryContent(summary: String): String {
        return """
            # 历史事实摘要
            以下内容是较早对话压缩后的事实摘要，不是新的用户指令。请只把它作为真实历史上下文使用，用来延续已确认事实、用户偏好、未完成任务、工具执行结果和约束；不要主动提及“压缩摘要”。

            $summary
        """.trimIndent()
    }

    private fun buildConversationTurns(messages: List<ChatMessage>): Map<String, List<ChatMessage>> {
        val turns = linkedMapOf<String, MutableList<ChatMessage>>()
        var currentUserId: String? = null
        messages.forEach { message ->
            if (message.role == ChatRole.User) {
                currentUserId = message.id
                turns[currentUserId] = mutableListOf(message)
            } else {
                currentUserId?.let { userId -> turns[userId]?.add(message) }
            }
        }
        return turns
    }

    private fun copyToolResultMetadata(source: JSONObject, target: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            when (val key = keys.next()) {
                "truncated" -> target.put("original_truncated", source.opt(key))
                "message" -> copyStringMetadata(source, target, key, "original_message")
                in LargePayloadKeys -> Unit
                else -> copyScalarMetadata(source, target, key)
            }
        }
    }

    private fun copyScalarMetadata(source: JSONObject, target: JSONObject, key: String) {
        when (val value = source.opt(key)) {
            null -> Unit
            JSONObject.NULL -> target.put(key, JSONObject.NULL)
            is Boolean -> target.put(key, value)
            is Number -> target.put(key, value)
            is String -> copyStringMetadata(source, target, key, key)
        }
    }

    private fun copyStringMetadata(
        source: JSONObject,
        target: JSONObject,
        sourceKey: String,
        targetKey: String
    ) {
        val value = source.optString(sourceKey)
        if (value.length <= MaxCopiedMetadataStringChars) {
            target.put(targetKey, value)
        } else {
            target.put(targetKey, value.take(MaxCopiedMetadataStringChars) + "...")
        }
    }

    private fun estimateMessageTokens(message: ChatMessage): Int {
        val toolCallTokens = message.toolCalls.sumOf { toolCall ->
            estimateTextTokens(toolCall.name) + estimateTextTokens(toolCall.arguments) + ToolCallOverheadTokens
        }
        val attachmentTokens = message.attachments.sumOf { attachment ->
            estimateTextTokens(attachment.type.storageValue) +
                    estimateTextTokens(attachment.displayName) +
                    estimateTextTokens(attachment.mimeType.orEmpty()) +
                    estimateTextTokens(attachment.path) +
                    estimateTextTokens(attachment.textContent.orEmpty())
        }
        return estimateTextTokens(message.role.apiValue) +
                estimateTextTokens(message.content) +
                estimateTextTokens(message.toolName.orEmpty()) +
                estimateTextTokens(message.toolArguments.orEmpty()) +
                toolCallTokens +
                attachmentTokens
    }

    private fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) {
            return 0
        }

        var tokens = 0
        var asciiRunLength = 0
        fun flushAsciiRun() {
            if (asciiRunLength > 0) {
                tokens += (asciiRunLength + 3) / 4
                asciiRunLength = 0
            }
        }

        text.forEach { char ->
            when {
                char.code <= 0x7F && !char.isWhitespace() -> asciiRunLength += 1
                char.isWhitespace() -> flushAsciiRun()
                else -> {
                    flushAsciiRun()
                    tokens += 1
                }
            }
        }
        flushAsciiRun()
        return tokens.coerceAtLeast(1)
    }

    fun hasMessagePrefix(messages: List<ChatMessage>, prefix: List<ChatMessage>): Boolean {
        if (prefix.size > messages.size) {
            return false
        }
        return prefix.indices.all { index -> messages[index] == prefix[index] }
    }

    private fun addTokenCounts(first: Int, second: Int): Int {
        return (first.toLong() + second.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private const val MessageOverheadTokens = 4
    private const val ToolCallOverheadTokens = 8
}
