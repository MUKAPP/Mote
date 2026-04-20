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
    val thinkingContent: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val intermediateSteps: List<IntermediateStep> = emptyList()
)

data class IntermediateStep(
    val thinkingContent: String = "",
    val content: String = "",
    val toolResults: List<ToolResultInfo> = emptyList()
)

data class ToolResultInfo(
    val toolCallId: String = "",
    val toolName: String = "",
    val toolArguments: String = "",
    val result: String = ""
)

data class ApiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
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
    val conversationMessages: List<ChatMessage>
)

data class ToolCallAccumulator(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)
