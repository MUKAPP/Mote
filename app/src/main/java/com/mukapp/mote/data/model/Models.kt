package com.mukapp.mote.data.model

import java.util.UUID

enum class ChatRole(val apiValue: String) {
    System(apiValue = "system"),
    User(apiValue = "user"),
    Assistant(apiValue = "assistant"),
    Tool(apiValue = "tool")
}

enum class ChatAttachmentType(val storageValue: String) {
    Image(storageValue = "image"),
    File(storageValue = "file")
}

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val type: ChatAttachmentType,
    val displayName: String,
    val mimeType: String? = null,
    val path: String,
    val directReadable: Boolean = false,
    val textContent: String? = null,
    val base64Data: String? = null,
    val truncated: Boolean = false
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val assistantParts: List<AssistantPart> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    val excludeFromConversation: Boolean = false,
    val isContextSummary: Boolean = false,
    val contextSummarySourceIds: List<String> = emptyList()
)

data class ContextSummary(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val sourceMessageIds: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
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

/** 单个模型的配置。`id` 即发给 API 的模型名。 */
data class ModelInfo(
    val id: String,
    val displayName: String = "",
    val contextLength: Int = 0,
    val reasoningEffort: String = "high"
) {
    val label: String get() = displayName.ifBlank { id }
}

/** 一个 OpenAI 兼容提供商：独立的 baseUrl/apiKey 与模型列表。 */
data class ModelProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val type: ProviderType = ProviderType.Generic,
    val models: List<ModelInfo> = emptyList()
) {
    val label: String get() = name.ifBlank { baseUrl }
}

/** 指向某提供商下某模型的引用。 */
data class ModelRef(
    val providerId: String,
    val modelId: String
)

/** 网络层发起一次请求所需的完整解析结果。 */
data class ResolvedModel(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String,
    val contextLength: Int,
    val providerType: ProviderType = ProviderType.Generic
)

data class ApiSettings(
    val providers: List<ModelProvider> = emptyList(),
    val chatModel: ModelRef? = null,
    val titleModel: ModelRef? = null,
    val compressionModel: ModelRef? = null,
    val compressionTriggerPercent: Int = DefaultCompressionTriggerPercent,
    val searxngUrl: String = "",
    val tavilyApiKey: String = ""
) {
    companion object {
        const val DefaultCompressionTriggerPercent: Int = 80
    }
}

/** 在 providers 中定位提供商。 */
fun ApiSettings.findProvider(providerId: String?): ModelProvider? {
    if (providerId.isNullOrBlank()) return null
    return providers.firstOrNull { it.id == providerId }
}

/** 把模型引用解析为网络层可用的 [ResolvedModel]，找不到返回 null。 */
fun ApiSettings.resolve(ref: ModelRef?): ResolvedModel? {
    ref ?: return null
    val provider = findProvider(ref.providerId) ?: return null
    if (provider.baseUrl.isBlank()) return null
    val model = provider.models.firstOrNull { it.id == ref.modelId } ?: return null
    return ResolvedModel(
        baseUrl = provider.baseUrl,
        apiKey = provider.apiKey,
        model = model.id,
        reasoningEffort = model.reasoningEffort,
        contextLength = model.contextLength,
        providerType = provider.type
    )
}

fun ApiSettings.resolvedChatModel(): ResolvedModel? = resolve(chatModel)

fun ApiSettings.resolvedTitleModel(): ResolvedModel? = resolve(titleModel)

/** 压缩模型未配置或解析失败时回退到聊天模型，保留旧 `compressionModel.ifBlank{model}` 语义。 */
fun ApiSettings.resolvedCompressionModel(): ResolvedModel? = resolve(compressionModel) ?: resolvedChatModel()

data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatCompletionResult(
    val content: String,
    val thinkingContent: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: TokenUsage? = null
)

data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val reasoningOutputTokens: Int? = null
)

data class SavedConversationState(
    val uiMessages: List<ChatMessage>,
    val conversationMessages: List<ChatMessage>,
    val contextSummaries: List<ContextSummary> = emptyList(),
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
