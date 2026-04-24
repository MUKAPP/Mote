package com.mukapp.mote.data.model

import java.util.UUID

enum class ChatRole(val apiValue: String) {
    System(apiValue = "system"),
    User(apiValue = "user"),
    Assistant(apiValue = "assistant"),
    Tool(apiValue = "tool")
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val assistantParts: List<AssistantPart> = emptyList(),
    val excludeFromConversation: Boolean = false
)

sealed interface AssistantPart {
    val id: String
}

data class AssistantMarkdownPart(
    override val id: String = UUID.randomUUID().toString(),
    val text: String = ""
) : AssistantPart

data class AssistantThinkingPart(
    override val id: String = UUID.randomUUID().toString(),
    val text: String = ""
) : AssistantPart

data class AssistantToolPart(
    override val id: String = UUID.randomUUID().toString(),
    val toolName: String = "",
    val toolArguments: String = "",
    val result: String = "",
    val isLoading: Boolean = false
) : AssistantPart

data class ApiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val titleModel: String = "",
    val reasoningEffort: String = "high"
)

data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatCompletionResult(
    val content: String,
    val thinkingContent: String = "",
    val toolCalls: List<AiToolCall> = emptyList()
)

data class SavedConversationState(
    val uiMessages: List<ChatMessage>,
    val conversationMessages: List<ChatMessage>,
    val conversationId: String = "",
    val title: String = ""
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

data class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)
