package com.mukapp.mote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mukapp.mote.R
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.ChatHistoryStore
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatCompletionResult
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.data.model.ContextSummary
import com.mukapp.mote.data.model.ModelRef
import com.mukapp.mote.data.model.ResolvedModel
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.data.model.TokenUsage
import com.mukapp.mote.data.model.resolvedChatModel
import com.mukapp.mote.data.model.resolvedCompressionModel
import com.mukapp.mote.data.model.resolvedTitleModel
import com.mukapp.mote.network.ChatApiClient
import com.mukapp.mote.tools.LocalAiTools
import com.mukapp.mote.tools.ShellProcessManager
import com.mukapp.mote.util.MoteLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ShellConfirmationUiState(
    val confirmationId: String,
    val description: String,
    val command: String,
    val workDir: String?,
    val background: Boolean,
    val risk: String
)

private data class ShellConfirmationRequest(
    val confirmationId: String,
    val description: String,
    val command: String,
    val workDir: String?,
    val background: Boolean,
    val risk: String
)

private data class ToolExecutionBatch(
    val results: List<ChatMessage>,
    val cancelled: Boolean
)

private data class StreamChatAttemptResult(
    val response: ChatCompletionResult,
    val accumulatedReply: String,
    val accumulatedThinking: String
)

private data class ContextCompressionPlan(
    val messagesToCompress: List<ChatMessage>,
    val recentMessages: List<ChatMessage>,
    val tokenCount: ChatConversationContextHelper.ContextTokenCount,
    val maxSummaryTokens: Int,
    val sourceMessageIds: List<String>
)

private data class ContextCompressionResult(
    val requestMessages: List<ChatMessage>,
    val compressed: Boolean
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val logComponent = "Chat"
    private val appContext = application.applicationContext

    private val uiMessagesInternal = mutableListOf<ChatMessage>()
    private val conversationMessagesInternal = mutableListOf<ChatMessage>()
    private val contextSummariesInternal = mutableListOf<ContextSummary>()

    private val _savedSettings = MutableLiveData(ApiSettingsStore.load(appContext))
    val savedSettings: LiveData<ApiSettings> = _savedSettings

    private val _uiMessages = MutableLiveData<List<ChatMessage>>(emptyList())
    val uiMessages: LiveData<List<ChatMessage>> = _uiMessages

    private val _draftMessage = MutableLiveData("")
    val draftMessage: LiveData<String> = _draftMessage

    private val _draftAttachments = MutableLiveData<List<ChatAttachment>>(emptyList())
    val draftAttachments: LiveData<List<ChatAttachment>> = _draftAttachments

    private val _conversationSummaries = MutableLiveData<List<ConversationSummary>>(emptyList())
    val conversationSummaries: LiveData<List<ConversationSummary>> = _conversationSummaries

    private val _currentConversationId = MutableLiveData("")
    val currentConversationId: LiveData<String> = _currentConversationId

    private val _isSending = MutableLiveData(false)
    val isSending: LiveData<Boolean> = _isSending

    private val _shellConfirmation = MutableLiveData<ShellConfirmationUiState?>()
    val shellConfirmation: LiveData<ShellConfirmationUiState?> = _shellConfirmation

    private val _userNotice = MutableLiveData<String?>()
    val userNotice: LiveData<String?> = _userNotice

    /** 本次会话临时覆盖的思考强度档位 key；为 null 时用模型的持久化档位。切换模型/对话即重置。 */
    private val _temporaryReasoningEffort = MutableLiveData<String?>(null)
    val temporaryReasoningEffort: LiveData<String?> = _temporaryReasoningEffort

    private var pendingStreamingPublishJob: Job? = null
    private var activeSendJob: Job? = null
    private var stopGenerationRequested: Boolean = false

    @Volatile
    private var activeForegroundShellProcessId: String? = null
    private var pendingShellConfirmationDecision: CompletableDeferred<Boolean>? = null
    private var pendingShellConfirmationId: String? = null
    private var currentConversationTitle: String = ""
    private val stateVersion = AtomicLong(0L)

    // IO 操作先获取 persistenceMutex；内存标记只在很短的同步块内读取或修改。
    private val persistenceMutex = Mutex()
    private val persistenceStateLock = Any()
    private val latestSaveVersions = mutableMapOf<String, Long>()
    private val deletedConversationIds = mutableSetOf<String>()
    private val generatedConversationTitles = mutableMapOf<String, String>()
    private var nextSaveVersion: Long = 0L

    // 流式阶段用 StringBuilder 累积文本，避免每个 delta 都重新分配 String
    private val streamingBuilders = mutableMapOf<String, StringBuilder>()
    private var cachedAssistantContent: String? = null
    private var contextTokenUsageAnchor: ChatConversationContextHelper.ContextTokenUsageAnchor? = null

    init {
        loadHistory()
    }

    fun updateDraftMessage(value: String) {
        _draftMessage.value = value
    }

    fun addDraftAttachment(attachment: ChatAttachment) {
        if (_isSending.value == true) {
            return
        }
        _draftAttachments.value = _draftAttachments.value.orEmpty() + attachment
    }

    fun removeDraftAttachment(attachmentId: String) {
        if (_isSending.value == true) {
            return
        }
        _draftAttachments.value = _draftAttachments.value.orEmpty().filter { it.id != attachmentId }
    }

    fun reloadSettings() {
        val previousModel = _savedSettings.value?.resolvedChatModel()
        val loadedSettings = ApiSettingsStore.load(appContext)
        val loadedModel = loadedSettings.resolvedChatModel()
        _savedSettings.value = loadedSettings
        if (previousModel != loadedModel) {
            clearTemporaryReasoningEffort()
        }
        MoteLog.d(logComponent, "已重新加载设置。")
    }

    fun saveSettings(settings: ApiSettings) {
        ApiSettingsStore.save(appContext, settings)
        _savedSettings.value = settings
        MoteLog.i(
            logComponent,
            MoteLog.event(
                "用户保存设置",
                "providers" to settings.providers.size,
                "models" to settings.providers.sumOf { it.models.size },
                "chatModelConfigured" to (settings.resolvedChatModel() != null),
                "titleModelConfigured" to (settings.titleModel != null),
                "compressionTriggerPercent" to settings.compressionTriggerPercent,
                "searchEnabled" to settings.searxngUrl.isNotBlank()
            )
        )
        if (uiMessagesInternal.isNotEmpty() || conversationMessagesInternal.isNotEmpty()) {
            persistConversationAsync()
        }
    }

    /** 首页切换当前对话模型：更新 chatModel 引用并持久化设置。 */
    fun selectChatModel(ref: ModelRef) {
        val current = _savedSettings.value ?: ApiSettingsStore.load(appContext)
        if (current.chatModel == ref) {
            return
        }
        val updated = current.copy(chatModel = ref)
        ApiSettingsStore.save(appContext, updated)
        _savedSettings.value = updated
        clearTemporaryReasoningEffort()
        MoteLog.i(
            logComponent,
            MoteLog.event("切换对话模型", "providerId" to MoteLog.shortId(ref.providerId), "model" to ref.modelId)
        )
    }

    /** 底部选择框临时修改思考强度（不写入持久化设置）。 */
    fun setTemporaryReasoningEffort(effortKey: String?) {
        if (_temporaryReasoningEffort.value == effortKey) {
            return
        }
        _temporaryReasoningEffort.value = effortKey
        MoteLog.i(logComponent, MoteLog.event("临时调整思考强度", "effort" to (effortKey ?: "默认")))
    }

    private fun clearTemporaryReasoningEffort() {
        if (_temporaryReasoningEffort.value != null) {
            _temporaryReasoningEffort.value = null
        }
    }

    fun startNewConversation() {
        if (_isSending.value == true) {
            return
        }

        clearPendingShellConfirmation(discardToken = true)
        val requestVersion = markStateChanged()
        uiMessagesInternal.clear()
        conversationMessagesInternal.clear()
        contextSummariesInternal.clear()
        clearContextTokenUsageAnchor()
        clearTemporaryReasoningEffort()
        currentConversationTitle = DefaultConversationTitle
        val newConversationId = ChatHistoryStore.newConversationId()
        setCurrentConversationId(newConversationId)
        MoteLog.i(
            logComponent,
            MoteLog.event("已新建对话", "conversationId" to MoteLog.shortId(newConversationId))
        )
        _draftMessage.value = ""
        _draftAttachments.value = emptyList()
        publishMessagesImmediately()
        persistCurrentConversationIdAsync(
            conversationId = newConversationId,
            expectedStateVersion = requestVersion,
            allowMissingConversation = true
        )
    }

    fun clearConversation() {
        startNewConversation()
    }

    fun switchConversation(conversationId: String) {
        if (_isSending.value == true || conversationId.isBlank() || conversationId == _currentConversationId.value) {
            return
        }

        clearPendingShellConfirmation(discardToken = true)
        clearTemporaryReasoningEffort()
        val requestVersion = markStateChanged()
        MoteLog.i(
            logComponent,
            MoteLog.event("开始切换对话", "conversationId" to MoteLog.shortId(conversationId))
        )
        viewModelScope.launch(Dispatchers.IO) {
            val historyState = runCatching {
                ChatHistoryStore.loadConversation(appContext, conversationId)
            }.onFailure { error ->
                MoteLog.e(logComponent, "加载指定对话失败", error)
            }.getOrNull() ?: return@launch

            withContext(Dispatchers.Main) {
                if (stateVersion.get() != requestVersion) {
                    return@withContext
                }
                applyConversationState(historyState)
                _draftMessage.value = ""
                _draftAttachments.value = emptyList()
                persistCurrentConversationIdAsync(conversationId, requestVersion)
                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "已切换对话",
                        "conversationId" to MoteLog.shortId(conversationId),
                        "uiMessages" to historyState.uiMessages.size,
                        "conversationMessages" to historyState.conversationMessages.size,
                        "contextSummaries" to historyState.contextSummaries.size
                    )
                )
            }
        }
    }

    fun deleteCurrentConversation() {
        if (_isSending.value == true) {
            return
        }

        clearPendingShellConfirmation(discardToken = true)
        clearTemporaryReasoningEffort()
        val conversationId = _currentConversationId.value.orEmpty()
        if (conversationId.isBlank()) {
            startNewConversation()
            return
        }

        val requestVersion = markStateChanged()
        markConversationDeleted(conversationId)
        MoteLog.i(
            logComponent,
            MoteLog.event("开始删除当前对话", "conversationId" to MoteLog.shortId(conversationId))
        )
        viewModelScope.launch(Dispatchers.IO) {
            val deleteResult = runCatching {
                persistenceMutex.withLock {
                    ChatHistoryStore.deleteConversation(appContext, conversationId)
                }
            }.onFailure { error ->
                MoteLog.e(logComponent, "删除当前对话失败", error)
            }
            if (deleteResult.isFailure) {
                unmarkConversationDeleted(conversationId)
                withContext(Dispatchers.Main) {
                    _userNotice.value =
                        appContext.getString(R.string.error_delete_conversation_failed)
                }
                return@launch
            }
            val replacementId = deleteResult.getOrNull()

            val replacementState = replacementId?.let { id ->
                runCatching { ChatHistoryStore.loadConversation(appContext, id) }
                    .onFailure { error -> MoteLog.e(logComponent, "加载替换对话失败", error) }
                    .getOrNull()
            }
            val summaries = runCatching {
                ChatHistoryStore.listConversations(appContext)
            }.getOrDefault(emptyList())
            clearDeletedConversation(conversationId)

            withContext(Dispatchers.Main) {
                if (stateVersion.get() != requestVersion) {
                    _conversationSummaries.value = summaries
                    return@withContext
                }
                if (replacementState != null) {
                    applyConversationState(replacementState)
                    _draftMessage.value = ""
                    _draftAttachments.value = emptyList()
                } else {
                    uiMessagesInternal.clear()
                    conversationMessagesInternal.clear()
                    contextSummariesInternal.clear()
                    clearContextTokenUsageAnchor()
                    currentConversationTitle = DefaultConversationTitle
                    val newConversationId = ChatHistoryStore.newConversationId()
                    setCurrentConversationId(newConversationId)
                    persistCurrentConversationIdAsync(
                        conversationId = newConversationId,
                        expectedStateVersion = requestVersion,
                        allowMissingConversation = true
                    )
                    _draftMessage.value = ""
                    _draftAttachments.value = emptyList()
                    publishMessagesImmediately()
                }
                _conversationSummaries.value = summaries
                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "已删除当前对话",
                        "conversationId" to MoteLog.shortId(conversationId),
                        "replacementId" to MoteLog.shortId(replacementId)
                    )
                )
            }
        }
    }

    /** 删除指定的非当前对话 */
    fun deleteConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        if (conversationId == _currentConversationId.value) {
            deleteCurrentConversation()
            return
        }

        markConversationDeleted(conversationId)
        MoteLog.i(
            logComponent,
            MoteLog.event("开始删除指定对话", "conversationId" to MoteLog.shortId(conversationId))
        )
        viewModelScope.launch(Dispatchers.IO) {
            val deleteResult = runCatching {
                persistenceMutex.withLock {
                    ChatHistoryStore.deleteConversation(appContext, conversationId)
                }
            }.onFailure { error ->
                MoteLog.e(logComponent, "删除指定对话失败", error)
            }
            if (deleteResult.isFailure) {
                unmarkConversationDeleted(conversationId)
                withContext(Dispatchers.Main) {
                    _userNotice.value =
                        appContext.getString(R.string.error_delete_conversation_failed)
                }
                return@launch
            }
            val summaries = runCatching {
                ChatHistoryStore.listConversations(appContext)
            }.getOrDefault(emptyList())
            clearDeletedConversation(conversationId)

            withContext(Dispatchers.Main) {
                _conversationSummaries.value = summaries
                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "已删除指定对话",
                        "conversationId" to MoteLog.shortId(conversationId)
                    )
                )
            }
        }
    }

    fun sendMessage() {
        val content = _draftMessage.value.orEmpty().trim()
        val attachments = _draftAttachments.value.orEmpty()
        if ((content.isEmpty() && attachments.isEmpty()) || _isSending.value == true) {
            return
        }

        val settings = _savedSettings.value ?: ApiSettingsStore.load(appContext)
        val chatModel = settings.resolvedChatModel()?.let { base ->
            _temporaryReasoningEffort.value?.let { base.copy(reasoningEffort = it) } ?: base
        }
        if (chatModel == null) {
            MoteLog.w(logComponent, "发送被拒绝：未配置可用的对话模型。")
            uiMessagesInternal += ChatMessage(
                role = ChatRole.Assistant,
                content = "请先在设置页添加提供商并选择对话模型，然后再开始对话。"
            )
            publishMessagesImmediately()
            return
        }

        markStateChanged()
        val isFirstUserMessage = uiMessagesInternal.none { it.role == ChatRole.User }
        val titleSeed = content.ifBlank { buildAttachmentTitleSeed(attachments) }
        val userMessage = ChatMessage(
            role = ChatRole.User,
            content = content,
            attachments = attachments
        )
        uiMessagesInternal += userMessage
        conversationMessagesInternal += userMessage
        MoteLog.i(
            logComponent,
            MoteLog.event(
                "开始发送用户消息",
                "conversationId" to MoteLog.shortId(_currentConversationId.value),
                "firstUserMessage" to isFirstUserMessage,
                "userMessageLength" to content.length,
                "attachments" to attachments.size,
                "uiMessages" to uiMessagesInternal.size,
                "conversationMessages" to conversationMessagesInternal.size,
                "contextSummaries" to contextSummariesInternal.size,
                "baseUrl" to MoteLog.safeUrlOrigin(chatModel.baseUrl),
                "modelLength" to chatModel.model.length
            )
        )
        if (isFirstUserMessage) {
            currentConversationTitle = buildFallbackTitle(titleSeed)
        }
        _draftMessage.value = ""
        _draftAttachments.value = emptyList()

        val assistantId = UUID.randomUUID().toString()
        uiMessagesInternal += ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            content = "",
            excludeFromConversation = true
        )
        val assistantIndex = uiMessagesInternal.lastIndex
        stopGenerationRequested = false
        _isSending.value = true
        publishMessagesImmediately()

        activeSendJob = viewModelScope.launch {
            var skipStoppedAssistantContextCommit = false
            var hasCommittedToolContextInCurrentTurn = false
            val result = runCatching {
                var workingConversation = prepareConversationForSending(settings)
                    .toMutableList()
                var workingRawConversation = ChatConversationContextHelper
                    .filterConversationMessages(conversationMessagesInternal)
                    .filterNot { it.isContextSummary }
                    .toMutableList()
                val assistantParts = mutableListOf<AssistantPart>()
                var executedToolRounds = 0

                fun commitRawConversationSnapshot() {
                    commitRawConversation(workingRawConversation)
                }

                repeat(MaxToolRounds) { roundIndex ->
                    val streamResult = streamChatWithRetries(
                        chatModel = chatModel,
                        settings = settings,
                        messages = buildRequestMessages(workingConversation),
                        assistantParts = assistantParts,
                        assistantIndex = assistantIndex,
                        assistantId = assistantId
                    )
                    val response = streamResult.response
                    MoteLog.i(
                        logComponent,
                        MoteLog.event(
                            "收到模型响应",
                            "conversationId" to MoteLog.shortId(_currentConversationId.value),
                            "round" to (roundIndex + 1),
                            "finishReason" to (response.finishReason ?: "未返回"),
                            "contentLength" to response.content.length,
                            "thinkingLength" to response.thinkingContent.length,
                            "toolCalls" to response.toolCalls.size,
                            *response.usage.usageLogFields()
                        )
                    )

                    if (streamResult.accumulatedThinking.isEmpty() && response.thinkingContent.isNotBlank()) {
                        appendAssistantThinking(assistantParts, response.thinkingContent)
                    }
                    if (streamResult.accumulatedReply.isEmpty() && response.content.isNotBlank()) {
                        appendAssistantMarkdown(assistantParts, response.content)
                    }

                    if (response.toolCalls.isEmpty()) {
                        val finalReply = buildAssistantContent(assistantParts)
                        val finalConversationContent = response.content.takeIf { it.isNotBlank() }
                            ?: finalReply
                        finalConversationContent.takeIf { it.isNotBlank() }
                                ?: throw IllegalStateException("接口返回内容为空。")
                        updateContextTokenUsageAnchor(
                            requestedMessages = workingConversation,
                            usage = response.usage
                        )

                        uiMessagesInternal[assistantIndex] = ChatMessage(
                            id = assistantId,
                            role = ChatRole.Assistant,
                            content = finalReply,
                            assistantParts = assistantParts.toList()
                        )
                        workingRawConversation += ChatMessage(
                            role = ChatRole.Assistant,
                            content = finalConversationContent
                        )

                        commitRawConversationSnapshot()
                        publishMessagesImmediately()
                        persistConversationAsync()
                        MoteLog.i(
                            logComponent,
                            MoteLog.event(
                                "本轮回复完成",
                                "conversationId" to MoteLog.shortId(_currentConversationId.value),
                                "toolRounds" to executedToolRounds,
                                "assistantContentLength" to finalReply.length,
                                "assistantParts" to assistantParts.size
                            )
                        )
                        if (isFirstUserMessage) {
                            generateConversationTitleAsync(settings, titleSeed)
                        }
                        return@runCatching finalReply
                    }

                    updateContextTokenUsageAnchor(
                        requestedMessages = workingConversation,
                        usage = response.usage
                    )
                    val toolCallMessage = ChatMessage(
                        role = ChatRole.Assistant,
                        content = response.content,
                        toolCalls = response.toolCalls
                    )
                    workingConversation += toolCallMessage
                    workingRawConversation += toolCallMessage
                    executedToolRounds += 1
                    MoteLog.i(
                        logComponent,
                        MoteLog.event(
                            "开始执行工具批次",
                            "conversationId" to MoteLog.shortId(_currentConversationId.value),
                            "round" to (roundIndex + 1),
                            "toolCalls" to response.toolCalls.size,
                            "tools" to response.toolCalls.joinToString(separator = "+") { it.name }
                        )
                    )

                    // 先插入 loading 占位工具块，立即推送 UI
                    val loadingToolParts = response.toolCalls.map { toolCall ->
                        AssistantToolPart(
                            id = toolCall.id,
                            toolName = toolCall.name,
                            toolArguments = toolCall.arguments,
                            result = "",
                            isLoading = true
                        )
                    }
                    assistantParts.addAll(loadingToolParts)
                    cachedAssistantContent = null
                    updateStreamingAssistantMessage(
                        assistantIndex = assistantIndex,
                        assistantId = assistantId,
                        content = buildAssistantContent(assistantParts),
                        assistantParts = assistantParts
                    )

                    skipStoppedAssistantContextCommit = true
                    val toolBatch = executeToolCallsWithConfirmation(
                        settings = settings,
                        toolCalls = response.toolCalls,
                        assistantParts = assistantParts,
                        assistantIndex = assistantIndex,
                        assistantId = assistantId
                    )
                    val toolResults = toolBatch.results
                    replaceLoadingToolParts(assistantParts, toolResults)
                    MoteLog.i(
                        logComponent,
                        MoteLog.event(
                            "工具批次执行完成",
                            "conversationId" to MoteLog.shortId(_currentConversationId.value),
                            "round" to (roundIndex + 1),
                            "results" to toolResults.size,
                            "cancelled" to toolBatch.cancelled
                        )
                    )
                    updateStreamingAssistantMessage(
                        assistantIndex = assistantIndex,
                        assistantId = assistantId,
                        content = buildAssistantContent(assistantParts),
                        assistantParts = assistantParts
                    )

                    val limitedToolResults = ChatConversationContextHelper.limitToolResultsForContext(toolResults)
                    workingConversation.addAll(limitedToolResults)
                    workingRawConversation.addAll(toolResults)
                    commitRawConversationSnapshot()
                    workingConversation = prepareConversationForSending(
                        settings = settings,
                        rawMessages = workingRawConversation
                    ).toMutableList()
                    hasCommittedToolContextInCurrentTurn = true
                    skipStoppedAssistantContextCommit = false

                    if (toolBatch.cancelled) {
                        appendAssistantMarkdown(assistantParts, "已取消执行高风险 shell 命令。")
                        val finalContent = buildAssistantContent(assistantParts)
                        uiMessagesInternal[assistantIndex] = ChatMessage(
                            id = assistantId,
                            role = ChatRole.Assistant,
                            content = finalContent,
                            assistantParts = assistantParts.toList()
                        )
                        workingRawConversation += ChatMessage(
                            role = ChatRole.Assistant,
                            content = finalContent
                        )
                        commitRawConversationSnapshot()
                        publishMessagesImmediately()
                        persistConversationAsync()
                        MoteLog.i(
                            logComponent,
                            MoteLog.event(
                                "工具批次取消后结束回复",
                                "conversationId" to MoteLog.shortId(_currentConversationId.value),
                                "assistantContentLength" to finalContent.length
                            )
                        )
                        if (isFirstUserMessage) {
                            generateConversationTitleAsync(settings, titleSeed)
                        }
                        return@runCatching finalContent
                    }

                    val waitSeconds = response.toolCalls.maxOfOrNull { toolCall ->
                        if (toolCall.name == LocalAiTools.WaitToolName) {
                            runCatching {
                                JSONObject(toolCall.arguments).optInt("seconds", 0)
                            }.getOrDefault(0)
                        } else {
                            0
                        }
                    }?.takeIf { it > 0 }

                    uiMessagesInternal[assistantIndex] = ChatMessage(
                        id = assistantId,
                        role = ChatRole.Assistant,
                        content = if (waitSeconds == null && roundIndex == MaxToolRounds - 1) {
                            "工具调用次数过多，已经停止。"
                        } else {
                            buildAssistantContent(assistantParts)
                        },
                        assistantParts = assistantParts.toList(),
                        excludeFromConversation = true
                    )
                    publishMessagesImmediately()

                    if (waitSeconds != null) {
                        MoteLog.i(
                            logComponent,
                            MoteLog.event("工具请求等待后继续", "seconds" to waitSeconds)
                        )
                        delay(waitSeconds * 1000L)
                    }
                }

                MoteLog.w(
                    logComponent,
                    MoteLog.event("工具调用达到轮次上限", "maxRounds" to MaxToolRounds)
                )
                throw IllegalStateException("工具调用次数过多，请缩小问题范围后再试。")
            }

            result.onFailure { error ->
                if (error is CancellationException) {
                    if (stopGenerationRequested) {
                        MoteLog.i(
                            logComponent,
                            MoteLog.event("生成已被用户停止", "conversationId" to MoteLog.shortId(_currentConversationId.value))
                        )
                        handleStoppedGeneration(
                            assistantIndex = assistantIndex,
                            assistantId = assistantId,
                            includeAssistantInConversation = !skipStoppedAssistantContextCommit &&
                                    !hasCommittedToolContextInCurrentTurn
                        )
                    }
                    return@onFailure
                }

                MoteLog.e(
                    logComponent,
                    MoteLog.event(
                        "发送消息失败",
                        "conversationId" to MoteLog.shortId(_currentConversationId.value),
                        "error" to error
                    ),
                    error
                )
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "请检查 API 设置、网络连接，或确认接口是否兼容 OpenAI Chat Completions。"
                val currentMessage = uiMessagesInternal.getOrNull(assistantIndex)
                val currentContent = currentMessage?.content.orEmpty()
                val currentParts = currentMessage?.assistantParts ?: emptyList()
                val failureNotice = if (currentContent.isBlank()) {
                    "请求失败：$message"
                } else {
                    "[处理失败：$message]"
                }
                val failureContent = if (currentContent.isBlank()) {
                    failureNotice
                } else {
                    "$currentContent\n\n$failureNotice"
                }
                uiMessagesInternal[assistantIndex] = ChatMessage(
                    id = assistantId,
                    role = ChatRole.Assistant,
                    content = failureContent,
                    assistantParts = appendFailureNoticePart(currentParts, failureNotice),
                    excludeFromConversation = true
                )
                publishMessagesImmediately()
                persistConversationAsync()
                if (isFirstUserMessage) {
                    generateConversationTitleAsync(settings, titleSeed)
                }
            }

            cancelPendingStreamingPublish()
            streamingBuilders.clear()
            cachedAssistantContent = null
            activeSendJob = null
            stopGenerationRequested = false
            _isSending.value = false
            MoteLog.d(logComponent, "发送任务状态已清理。")
        }
    }

    fun stopGenerating() {
        if (_isSending.value != true) {
            return
        }

        stopGenerationRequested = true
        MoteLog.i(
            logComponent,
            MoteLog.event(
                "用户请求停止生成",
                "conversationId" to MoteLog.shortId(_currentConversationId.value),
                "hasForegroundShell" to (activeForegroundShellProcessId != null)
            )
        )
        clearPendingShellConfirmation(discardToken = true, cancelDecision = true)
        activeForegroundShellProcessId?.let { id ->
            activeForegroundShellProcessId = null
            viewModelScope.launch(Dispatchers.IO) {
                ShellProcessManager.stop(id)
            }
        }
        activeSendJob?.cancel(CancellationException("用户已手动停止生成。"))
    }

    fun confirmPendingShellCommand() {
        val confirmationId = pendingShellConfirmationId ?: return
        val decision = pendingShellConfirmationDecision ?: return
        if (LocalAiTools.activatePendingShellConfirmation(confirmationId)) {
            MoteLog.i(
                logComponent,
                MoteLog.event("用户确认 Shell 命令", "confirmationId" to MoteLog.shortId(confirmationId))
            )
            pendingShellConfirmationId = null
            pendingShellConfirmationDecision = null
            _shellConfirmation.value = null
            decision.complete(true)
        } else {
            MoteLog.w(
                logComponent,
                MoteLog.event("用户确认 Shell 命令失败：令牌失效", "confirmationId" to MoteLog.shortId(confirmationId))
            )
            pendingShellConfirmationId = null
            pendingShellConfirmationDecision = null
            _shellConfirmation.value = null
            _userNotice.value = "Shell 命令确认已过期，请重新发起请求。"
            decision.complete(false)
        }
    }

    fun cancelPendingShellCommand() {
        pendingShellConfirmationId?.let { confirmationId ->
            MoteLog.i(
                logComponent,
                MoteLog.event("用户取消 Shell 命令", "confirmationId" to MoteLog.shortId(confirmationId))
            )
        }
        clearPendingShellConfirmation(discardToken = true, cancelDecision = false)
    }

    fun clearUserNotice() {
        _userNotice.value = null
    }

    fun editMessage(index: Int) {
        if (_isSending.value == true || index !in uiMessagesInternal.indices) {
            return
        }

        val message = uiMessagesInternal[index]
        if (message.role != ChatRole.User) {
            return
        }

        markStateChanged()
        val affectedUserMessageIds = uiMessagesInternal
            .drop(index)
            .filter { it.role == ChatRole.User }
            .map { it.id }
            .toSet()
        _draftMessage.value = message.content
        _draftAttachments.value = message.attachments
        repeat(uiMessagesInternal.size - index) {
            uiMessagesInternal.removeAt(uiMessagesInternal.lastIndex)
        }
        rebuildConversationAfterUiMutation(affectedUserMessageIds)
        publishMessagesImmediately()
        persistConversationAsync()
        MoteLog.i(
            logComponent,
            MoteLog.event(
                "用户编辑消息",
                "index" to index,
                "removedMessages" to affectedUserMessageIds.size,
                "uiMessages" to uiMessagesInternal.size
            )
        )
    }

    fun deleteMessage(index: Int) {
        if (_isSending.value == true || index !in uiMessagesInternal.indices) {
            return
        }

        val message = uiMessagesInternal[index]
        if (message.role != ChatRole.User) {
            return
        }

        markStateChanged()
        val affectedUserMessageIds = setOf(message.id)
        uiMessagesInternal.removeAt(index)
        if (index < uiMessagesInternal.size && uiMessagesInternal[index].role == ChatRole.Assistant) {
            uiMessagesInternal.removeAt(index)
        }
        rebuildConversationAfterUiMutation(affectedUserMessageIds)
        publishMessagesImmediately()
        persistConversationAsync()
        MoteLog.i(
            logComponent,
            MoteLog.event("用户删除消息", "index" to index, "uiMessages" to uiMessagesInternal.size)
        )
    }

    fun retryMessage(index: Int) {
        if (_isSending.value == true || index !in uiMessagesInternal.indices) {
            return
        }

        val message = uiMessagesInternal[index]
        if (message.role != ChatRole.Assistant || index != uiMessagesInternal.lastIndex) {
            return
        }

        markStateChanged()
        uiMessagesInternal.removeAt(index)
        rebuildConversationAfterUiMutation(emptySet())
        val userIndex = uiMessagesInternal.lastIndex
        if (userIndex >= 0 && uiMessagesInternal[userIndex].role == ChatRole.User) {
            val userMessage = uiMessagesInternal[userIndex]
            val userContent = userMessage.content
            val userAttachments = userMessage.attachments
            val affectedUserMessageIds = setOf(userMessage.id)
            _draftMessage.value = userContent
            _draftAttachments.value = userAttachments
            uiMessagesInternal.removeAt(userIndex)
            rebuildConversationAfterUiMutation(affectedUserMessageIds)
            publishMessagesImmediately()
            MoteLog.i(logComponent, MoteLog.event("用户重试消息", "index" to index))
            sendMessage()
        } else {
            publishMessagesImmediately()
        }
    }

    private fun loadHistory() {
        val loadVersion = stateVersion.get()
        MoteLog.i(logComponent, "开始加载历史记录。")
        viewModelScope.launch(Dispatchers.IO) {
            val historyState = runCatching {
                ChatHistoryStore.loadCurrentConversation(appContext)
            }.onFailure { error ->
                MoteLog.e(logComponent, "加载历史记录失败", error)
            }.getOrDefault(
                SavedConversationState(
                    uiMessages = emptyList(),
                    conversationMessages = emptyList(),
                    conversationId = ChatHistoryStore.newConversationId(),
                    title = DefaultConversationTitle
                )
            )
            val summaries = runCatching {
                ChatHistoryStore.listConversations(appContext)
            }.onFailure { error ->
                MoteLog.e(logComponent, "加载对话列表失败", error)
            }.getOrDefault(emptyList())

            withContext(Dispatchers.Main) {
                if (stateVersion.get() != loadVersion) {
                    return@withContext
                }
                applyConversationState(historyState)
                _conversationSummaries.value = summaries
                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "历史记录加载完成",
                        "conversationId" to MoteLog.shortId(historyState.conversationId),
                        "uiMessages" to historyState.uiMessages.size,
                        "conversationMessages" to historyState.conversationMessages.size,
                        "contextSummaries" to historyState.contextSummaries.size,
                        "summaries" to summaries.size
                    )
                )
            }
        }
    }

    private fun applyConversationState(historyState: SavedConversationState) {
        uiMessagesInternal.clear()
        uiMessagesInternal.addAll(historyState.uiMessages)
        conversationMessagesInternal.clear()
        conversationMessagesInternal.addAll(
            ChatConversationContextHelper.filterConversationMessages(historyState.conversationMessages)
                .filterNot { it.isContextSummary }
        )
        contextSummariesInternal.clear()
        contextSummariesInternal.addAll(historyState.contextSummaries)
        clearContextTokenUsageAnchor()
        currentConversationTitle = historyState.title.ifBlank { DefaultConversationTitle }
        setCurrentConversationId(
            historyState.conversationId.ifBlank {
                ChatHistoryStore.newConversationId()
            }
        )
        publishMessagesImmediately()
        MoteLog.d(
            logComponent,
            MoteLog.event(
                "已应用对话状态",
                "conversationId" to MoteLog.shortId(_currentConversationId.value),
                "uiMessages" to uiMessagesInternal.size,
                "conversationMessages" to conversationMessagesInternal.size,
                "contextSummaries" to contextSummariesInternal.size
            )
        )
    }

    private suspend fun updateStreamingAssistantMessage(
        assistantIndex: Int,
        assistantId: String,
        content: String,
        assistantParts: List<AssistantPart>
    ) = withContext(Dispatchers.Main) {
        if (assistantIndex !in uiMessagesInternal.indices) {
            return@withContext
        }

        uiMessagesInternal[assistantIndex] = ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            content = content,
            assistantParts = assistantParts.toList(),
            excludeFromConversation = true
        )
        scheduleStreamingPublish()
    }

    private fun handleStoppedGeneration(
        assistantIndex: Int,
        assistantId: String,
        includeAssistantInConversation: Boolean
    ) {
        val currentMessage = uiMessagesInternal.getOrNull(assistantIndex)
        val currentContent = currentMessage?.content.orEmpty()
        val currentParts = currentMessage?.assistantParts ?: emptyList()
        if (assistantIndex !in uiMessagesInternal.indices) {
            persistConversationAsync()
            return
        }

        val assistantContent = currentContent.ifBlank {
            buildAssistantContent(currentParts)
        }
        val shouldKeepInConversation = includeAssistantInConversation && assistantContent.isNotBlank()
        MoteLog.i(
            logComponent,
            MoteLog.event(
                "处理已停止的生成",
                "assistantContentLength" to assistantContent.length,
                "assistantParts" to currentParts.size,
                "keepInConversation" to shouldKeepInConversation
            )
        )

        uiMessagesInternal[assistantIndex] = ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            content = when {
                assistantContent.isNotBlank() -> assistantContent
                currentParts.isEmpty() -> appContext.getString(R.string.status_generation_stopped)
                else -> ""
            },
            assistantParts = currentParts,
            excludeFromConversation = !shouldKeepInConversation
        )
        if (shouldKeepInConversation) {
            conversationMessagesInternal += ChatMessage(
                role = ChatRole.Assistant,
                content = assistantContent
            )
            clearContextTokenUsageAnchor()
        }
        publishMessagesImmediately()
        persistConversationAsync()
    }

    private fun appendAssistantThinking(parts: MutableList<AssistantPart>, delta: String) {
        if (delta.isEmpty()) {
            return
        }
        cachedAssistantContent = null
        val lastPart = parts.lastOrNull()
        if (lastPart is AssistantThinkingPart) {
            val builder = streamingBuilders.getOrPut(lastPart.id) { StringBuilder(lastPart.text) }
            builder.append(delta)
            parts[parts.lastIndex] = lastPart.copy(text = builder.toString())
        } else {
            val newPart = AssistantThinkingPart(text = delta)
            streamingBuilders[newPart.id] = StringBuilder(delta)
            parts += newPart
        }
    }

    private fun appendAssistantMarkdown(parts: MutableList<AssistantPart>, delta: String) {
        if (delta.isEmpty()) {
            return
        }
        cachedAssistantContent = null
        val lastPart = parts.lastOrNull()
        if (lastPart is AssistantMarkdownPart) {
            val builder = streamingBuilders.getOrPut(lastPart.id) { StringBuilder(lastPart.text) }
            builder.append(delta)
            parts[parts.lastIndex] = lastPart.copy(text = builder.toString())
        } else {
            val newPart = AssistantMarkdownPart(text = delta)
            streamingBuilders[newPart.id] = StringBuilder(delta)
            parts += newPart
        }
    }

    private fun appendAssistantMarkdownAfterRetryNotice(
        parts: MutableList<AssistantPart>,
        retryNoticeIndex: Int?,
        delta: String
    ) {
        if (delta.isEmpty()) {
            return
        }

        if (retryNoticeIndex == parts.lastIndex && parts.lastOrNull() is AssistantMarkdownPart) {
            cachedAssistantContent = null
            val newPart = AssistantMarkdownPart(text = delta)
            streamingBuilders[newPart.id] = StringBuilder(delta)
            parts += newPart
            return
        }

        appendAssistantMarkdown(parts, delta)
    }

    private fun appendFailureNoticePart(
        parts: List<AssistantPart>,
        failureNotice: String
    ): List<AssistantPart> {
        if (parts.isEmpty()) {
            return emptyList()
        }
        return parts + AssistantMarkdownPart(text = failureNotice)
    }

    private fun appendAssistantToolResults(
        parts: MutableList<AssistantPart>,
        toolResults: List<ChatMessage>
    ) {
        toolResults.forEach { result ->
            parts += AssistantToolPart(
                id = result.toolCallId ?: UUID.randomUUID().toString(),
                toolName = result.toolName.orEmpty(),
                toolArguments = result.toolArguments.orEmpty(),
                result = result.content
            )
        }
    }

    private fun replaceLoadingToolParts(
        parts: MutableList<AssistantPart>,
        toolResults: List<ChatMessage>
    ) {
        cachedAssistantContent = null
        val replacedParts = ChatConversationContextHelper.replaceLoadingToolParts(
            parts = parts,
            toolResults = toolResults
        )
        for (index in replacedParts.indices) {
            parts[index] = replacedParts[index]
        }
    }

    private fun buildAssistantContent(parts: List<AssistantPart>): String {
        cachedAssistantContent?.let { return it }
        val result = parts.asSequence()
            .mapNotNull { part ->
                when (part) {
                    is AssistantMarkdownPart -> part.text.takeIf { it.isNotBlank() }
                    else -> null
                }
            }
            .joinToString(separator = "\n\n")
        cachedAssistantContent = result
        return result
    }

    private suspend fun streamChatWithRetries(
        chatModel: ResolvedModel,
        settings: ApiSettings,
        messages: List<ChatMessage>,
        assistantParts: MutableList<AssistantPart>,
        assistantIndex: Int,
        assistantId: String
    ): StreamChatAttemptResult {
        var lastError: Throwable? = null
        var retryNoticeIndex: Int? = null
        repeat(MaxStreamRetryAttempts + 1) { attemptIndex ->
            val accumulatedReply = StringBuilder()
            val accumulatedThinking = StringBuilder()
            val partCountBeforeAttempt = assistantParts.size

            try {
                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "开始流式请求尝试",
                        "attempt" to (attemptIndex + 1),
                        "maxAttempts" to (MaxStreamRetryAttempts + 1),
                        "messages" to messages.size
                    )
                )
                val response = ChatApiClient.streamChat(
                    model = chatModel,
                    settings = settings,
                    messages = messages,
                    onDelta = { delta ->
                        accumulatedReply.append(delta)
                        appendAssistantMarkdownAfterRetryNotice(
                            parts = assistantParts,
                            retryNoticeIndex = retryNoticeIndex,
                            delta = delta
                        )
                        updateStreamingAssistantMessage(
                            assistantIndex = assistantIndex,
                            assistantId = assistantId,
                            content = buildAssistantContent(assistantParts),
                            assistantParts = assistantParts
                        )
                    },
                    onThinkingDelta = { thinkingDelta ->
                        accumulatedThinking.append(thinkingDelta)
                        appendAssistantThinking(assistantParts, thinkingDelta)
                        updateStreamingAssistantMessage(
                            assistantIndex = assistantIndex,
                            assistantId = assistantId,
                            content = buildAssistantContent(assistantParts),
                            assistantParts = assistantParts
                        )
                    }
                )
                if (removeRetryNoticePart(assistantParts, retryNoticeIndex)) {
                    updateStreamingAssistantMessage(
                        assistantIndex = assistantIndex,
                        assistantId = assistantId,
                        content = buildAssistantContent(assistantParts),
                        assistantParts = assistantParts
                    )
                }
                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "流式请求尝试成功",
                        "attempt" to (attemptIndex + 1),
                        "contentLength" to accumulatedReply.length,
                        "thinkingLength" to accumulatedThinking.length,
                        "toolCalls" to response.toolCalls.size
                    )
                )
                return StreamChatAttemptResult(
                    response = response,
                    accumulatedReply = accumulatedReply.toString(),
                    accumulatedThinking = accumulatedThinking.toString()
                )
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }

                lastError = error
                removeAssistantPartsFrom(assistantParts, partCountBeforeAttempt)
                val nextAttempt = attemptIndex + 1
                if (nextAttempt > MaxStreamRetryAttempts) {
                    MoteLog.e(
                        logComponent,
                        MoteLog.event("流式请求重试已耗尽", "attempts" to (attemptIndex + 1), "error" to error),
                        error
                    )
                    if (removeRetryNoticePart(assistantParts, retryNoticeIndex)) {
                        updateStreamingAssistantMessage(
                            assistantIndex = assistantIndex,
                            assistantId = assistantId,
                            content = buildAssistantContent(assistantParts),
                            assistantParts = assistantParts
                        )
                    }
                    throw error
                }

                MoteLog.w(
                    logComponent,
                    MoteLog.event("流式请求失败，准备重试", "nextAttempt" to (nextAttempt + 1), "error" to error)
                )
                retryNoticeIndex = replaceRetryNoticePart(
                    parts = assistantParts,
                    retryNoticeIndex = retryNoticeIndex,
                    attempt = nextAttempt,
                    error = error
                )
                updateStreamingAssistantMessage(
                    assistantIndex = assistantIndex,
                    assistantId = assistantId,
                    content = buildAssistantContent(assistantParts),
                    assistantParts = assistantParts
                )
            }
        }

        throw lastError ?: IllegalStateException("请求失败，重试次数已用尽。")
    }

    private fun removeAssistantPartsFrom(parts: MutableList<AssistantPart>, startIndex: Int) {
        if (startIndex !in 0..parts.size) {
            return
        }
        while (parts.size > startIndex) {
            val removedPart = parts.removeAt(parts.lastIndex)
            streamingBuilders.remove(removedPart.id)
        }
        cachedAssistantContent = null
    }

    private fun removeRetryNoticePart(
        parts: MutableList<AssistantPart>,
        retryNoticeIndex: Int?
    ): Boolean {
        val index = retryNoticeIndex
            ?.takeIf { it in parts.indices && parts[it] is AssistantMarkdownPart }
            ?: return false
        val removedPart = parts.removeAt(index)
        streamingBuilders.remove(removedPart.id)
        cachedAssistantContent = null
        return true
    }

    private fun replaceRetryNoticePart(
        parts: MutableList<AssistantPart>,
        retryNoticeIndex: Int?,
        attempt: Int,
        error: Throwable
    ): Int {
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: "请求失败。"
        val part = AssistantMarkdownPart(
            text = "请求异常，正在重试第 $attempt/$MaxStreamRetryAttempts 次：$message"
        )
        val existingIndex = retryNoticeIndex
            ?.takeIf { it in parts.indices && parts[it] is AssistantMarkdownPart }
        if (existingIndex != null) {
            streamingBuilders.remove(parts[existingIndex].id)
            parts[existingIndex] = part
            cachedAssistantContent = null
            return existingIndex
        }

        parts += part
        cachedAssistantContent = null
        return parts.lastIndex
    }

    private suspend fun executeToolCallsWithConfirmation(
        settings: ApiSettings,
        toolCalls: List<AiToolCall>,
        assistantParts: MutableList<AssistantPart>,
        assistantIndex: Int,
        assistantId: String
    ): ToolExecutionBatch {
        val results = mutableListOf<ChatMessage>()
        for (toolCall in toolCalls) {
            MoteLog.i(
                logComponent,
                MoteLog.event(
                    "开始本地工具调用",
                    "tool" to toolCall.name,
                    "toolCallId" to MoteLog.shortId(toolCall.id)
                )
            )
            var result = executeLocalToolCall(settings, toolCall)
            val confirmation = parseShellConfirmationRequest(result.content, toolCall.arguments)
            if (confirmation != null) {
                MoteLog.w(
                    logComponent,
                    MoteLog.event(
                        "工具请求 Shell 高风险确认",
                        "confirmationId" to MoteLog.shortId(confirmation.confirmationId),
                        "background" to confirmation.background,
                        "risk" to confirmation.risk
                    )
                )
                val approved = awaitShellConfirmation(
                    confirmation = confirmation,
                    assistantParts = assistantParts,
                    assistantIndex = assistantIndex,
                    assistantId = assistantId
                )
                if (!approved) {
                    LocalAiTools.discardPendingShellConfirmation(confirmation.confirmationId)
                    MoteLog.i(
                        logComponent,
                        MoteLog.event(
                            "Shell 高风险确认未通过",
                            "confirmationId" to MoteLog.shortId(confirmation.confirmationId)
                        )
                    )
                    results += buildCancelledShellToolResult(toolCall, confirmation)
                    return ToolExecutionBatch(results = results, cancelled = true)
                }

                MoteLog.i(
                    logComponent,
                    MoteLog.event(
                        "Shell 高风险确认已通过，继续执行工具",
                        "confirmationId" to MoteLog.shortId(confirmation.confirmationId)
                    )
                )
                result = executeLocalToolCall(
                    settings,
                    toolCall.copy(
                        arguments = addShellConfirmationId(
                            toolCall.arguments,
                            confirmation.confirmationId
                        )
                    )
                )
            }
            results += result
            MoteLog.i(
                logComponent,
                MoteLog.event(
                    "本地工具调用完成",
                    "tool" to toolCall.name,
                    "toolCallId" to MoteLog.shortId(toolCall.id),
                    "resultLength" to result.content.length
                )
            )
        }
        return ToolExecutionBatch(results = results, cancelled = false)
    }

    private suspend fun executeLocalToolCall(settings: ApiSettings, toolCall: AiToolCall): ChatMessage {
        return try {
            withContext(Dispatchers.IO) {
                LocalAiTools.executeToolCall(
                    context = appContext,
                    toolCall = toolCall,
                    settings = settings,
                    onShellProcessStarted = { id, background ->
                        if (!background) {
                            activeForegroundShellProcessId = id
                        }
                    }
                )
            }
        } finally {
            activeForegroundShellProcessId = null
        }
    }

    private suspend fun awaitShellConfirmation(
        confirmation: ShellConfirmationRequest,
        assistantParts: List<AssistantPart>,
        assistantIndex: Int,
        assistantId: String
    ): Boolean {
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            clearPendingShellConfirmation(discardToken = true, cancelDecision = false)
            pendingShellConfirmationId = confirmation.confirmationId
            pendingShellConfirmationDecision = decision
            _shellConfirmation.value = ShellConfirmationUiState(
                confirmationId = confirmation.confirmationId,
                description = confirmation.description,
                command = confirmation.command,
                workDir = confirmation.workDir,
                background = confirmation.background,
                risk = confirmation.risk
            )
            updateStreamingAssistantMessage(
                assistantIndex = assistantIndex,
                assistantId = assistantId,
                content = buildAssistantContent(assistantParts),
                assistantParts = assistantParts
            )
            MoteLog.i(
                logComponent,
                MoteLog.event(
                    "已展示 Shell 确认 UI",
                    "confirmationId" to MoteLog.shortId(confirmation.confirmationId),
                    "background" to confirmation.background,
                    "risk" to confirmation.risk
                )
            )
        }

        return try {
            decision.await()
        } finally {
            withContext(Dispatchers.Main) {
                if (pendingShellConfirmationDecision == decision) {
                    clearPendingShellConfirmation(discardToken = true, cancelDecision = true)
                }
            }
        }
    }

    private fun parseShellConfirmationRequest(
        toolResult: String,
        toolArguments: String
    ): ShellConfirmationRequest? {
        if (!LocalAiTools.isShellConfirmationRequest(toolResult)) {
            return null
        }
        val payload = runCatching { JSONObject(toolResult) }.getOrNull() ?: return null
        val rawWorkDir = payload.opt("work_dir")
        val description = runCatching { JSONObject(toolArguments) }
            .getOrNull()
            ?.optString("description")
            ?.trim()
            .orEmpty()
        return ShellConfirmationRequest(
            confirmationId = payload.optString("confirmation_id"),
            description = description,
            command = payload.optString("command"),
            workDir = rawWorkDir?.takeIf { it != JSONObject.NULL }?.toString()
                ?.takeIf { it.isNotBlank() },
            background = payload.optBoolean("background", false),
            risk = payload.optString("risk").ifBlank { "可能修改设备数据" }
        ).takeIf { it.confirmationId.isNotBlank() && it.command.isNotBlank() }
    }

    private fun addShellConfirmationId(arguments: String, confirmationId: String): String {
        val payload = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        payload.put("confirmation_id", confirmationId)
        return payload.toString()
    }

    private fun resolveConversationTokenCount(messages: List<ChatMessage>): ChatConversationContextHelper.ContextTokenCount {
        return ChatConversationContextHelper.resolveConversationTokenCount(
            messages = messages,
            usageAnchor = contextTokenUsageAnchor
        )
    }

    private fun updateContextTokenUsageAnchor(
        requestedMessages: List<ChatMessage>,
        usage: TokenUsage?
    ) {
        val anchor = ChatConversationContextHelper.buildTokenUsageAnchor(
            messages = requestedMessages,
            usage = usage
        ) ?: return
        contextTokenUsageAnchor = anchor
    }

    private fun clearContextTokenUsageAnchor() {
        contextTokenUsageAnchor = null
    }

    private fun buildCancelledShellToolResult(
        toolCall: AiToolCall,
        confirmation: ShellConfirmationRequest
    ): ChatMessage {
        val output = JSONObject().apply {
            put("ok", false)
            put("cancelled", true)
            put("confirmation_id", confirmation.confirmationId)
            put("command", confirmation.command)
            put("risk", confirmation.risk)
            put("message", "用户已取消执行该高风险 shell 命令。")
        }.toString(2)
        return ChatMessage(
            role = ChatRole.Tool,
            content = output,
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            toolArguments = toolCall.arguments
        )
    }

    private fun clearPendingShellConfirmation(
        discardToken: Boolean,
        cancelDecision: Boolean = false
    ) {
        val confirmationId = pendingShellConfirmationId
        if (discardToken && confirmationId != null) {
            LocalAiTools.discardPendingShellConfirmation(confirmationId)
        }
        if (confirmationId != null) {
            MoteLog.d(
                logComponent,
                MoteLog.event(
                    "清理待确认 Shell 命令",
                    "confirmationId" to MoteLog.shortId(confirmationId),
                    "discardToken" to discardToken,
                    "cancelDecision" to cancelDecision
                )
            )
        }
        pendingShellConfirmationId = null
        _shellConfirmation.value = null
        val decision = pendingShellConfirmationDecision
        pendingShellConfirmationDecision = null
        if (cancelDecision) {
            decision?.cancel(CancellationException("Shell 命令确认已取消。"))
        } else {
            decision?.complete(false)
        }
    }

    private fun rebuildConversationFromUiMessages() {
        conversationMessagesInternal.clear()
        conversationMessagesInternal.addAll(ChatConversationContextHelper.rebuildConversationFromUiMessages(uiMessagesInternal))
        contextSummariesInternal.clear()
        clearContextTokenUsageAnchor()
    }

    private fun rebuildConversationAfterUiMutation(affectedMessageIds: Set<String>) {
        val currentConversation = conversationMessagesInternal.toList()
        val affectedConversationIds = ChatConversationContextHelper.collectConversationTurnMessageIds(
            conversationMessages = currentConversation,
            userMessageIds = affectedMessageIds
        )
        val allAffectedIds = affectedMessageIds + affectedConversationIds
        conversationMessagesInternal.clear()
        conversationMessagesInternal.addAll(
            ChatConversationContextHelper.rebuildConversationAfterUiMutation(
                uiMessages = uiMessagesInternal,
                conversationMessages = currentConversation,
                affectedUserMessageIds = affectedMessageIds
            )
        )
        val filteredSummaries = ChatConversationContextHelper.filterContextSummariesAfterMessagesRemoved(
            summaries = contextSummariesInternal,
            removedMessageIds = allAffectedIds
        )
        contextSummariesInternal.clear()
        contextSummariesInternal.addAll(filteredSummaries)
        clearContextTokenUsageAnchor()
        MoteLog.d(
            logComponent,
            MoteLog.event(
                "UI 消息变更后已重建上下文",
                "affectedMessages" to affectedMessageIds.size,
                "conversationMessages" to conversationMessagesInternal.size,
                "contextSummaries" to contextSummariesInternal.size
            )
        )
    }

    private suspend fun prepareConversationForSending(
        settings: ApiSettings,
        rawMessages: List<ChatMessage> = conversationMessagesInternal
    ): List<ChatMessage> {
        val result = compressConversationForContext(
            settings = settings,
            rawMessages = rawMessages
        )
        return result.requestMessages
    }

    private suspend fun compressConversationForContext(
        settings: ApiSettings,
        rawMessages: List<ChatMessage>
    ): ContextCompressionResult {
        val normalizedMessages = ChatConversationContextHelper.filterConversationMessages(rawMessages)
            .filterNot { it.isContextSummary }
        val requestMessages = buildLimitedRequestContextMessages(normalizedMessages)
        val tokenCount = resolveConversationTokenCount(requestMessages)
        val plan = buildContextCompressionPlan(settings, requestMessages, tokenCount)
        if (plan == null) {
            MoteLog.d(
                logComponent,
                MoteLog.event(
                    "上下文无需压缩",
                    "messages" to requestMessages.size,
                    "tokens" to tokenCount.tokens,
                    "source" to tokenCount.sourceLabel
                )
            )
            throwIfContextExceedsHardLimitWithoutCompression(settings, tokenCount)
            return ContextCompressionResult(requestMessages = requestMessages, compressed = false)
        }
        MoteLog.i(
            logComponent,
            MoteLog.event(
                "开始压缩聊天上下文",
                "messagesToCompress" to plan.messagesToCompress.size,
                "recentMessages" to plan.recentMessages.size,
                "tokens" to plan.tokenCount.tokens,
                "source" to plan.tokenCount.sourceLabel,
                "maxSummaryTokens" to plan.maxSummaryTokens
            )
        )
        val summary = try {
            val compressionModel = settings.resolvedCompressionModel()
                ?: throw IllegalStateException("未配置可用的压缩模型。")
            val summary = ChatApiClient.compressConversation(
                model = compressionModel,
                messages = plan.messagesToCompress,
                maxSummaryTokens = plan.maxSummaryTokens
            )
            ContextSummary(
                content = summary,
                sourceMessageIds = plan.sourceMessageIds.distinct()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            MoteLog.e(logComponent, "压缩聊天上下文失败", error)
            null
        }

        if (summary != null) {
            appendContextSummary(summary)
            MoteLog.i(
                logComponent,
                MoteLog.event(
                    "已压缩聊天上下文",
                    "source" to plan.tokenCount.sourceLabel,
                    "tokens" to plan.tokenCount.tokens,
                    "compressedMessages" to plan.messagesToCompress.size,
                    "recentMessages" to plan.recentMessages.size,
                    "contextSummaries" to contextSummariesInternal.size
                )
            )
            clearContextTokenUsageAnchor()
            return ContextCompressionResult(
                requestMessages = buildLimitedRequestContextMessages(normalizedMessages),
                compressed = true
            )
        }

        val hardLimit = settings.resolvedChatModel()?.contextLength?.takeIf { it > 0 }
        if (hardLimit == null || plan.tokenCount.tokens <= hardLimit) {
            MoteLog.w(
                logComponent,
                MoteLog.event(
                    "上下文压缩失败，继续使用原始上下文",
                    "tokens" to plan.tokenCount.tokens,
                    "hardLimit" to hardLimit
                )
            )
            _userNotice.value = "上下文压缩失败，已临时使用原始上下文继续发送。"
            return ContextCompressionResult(requestMessages = requestMessages, compressed = false)
        }
        throw IllegalStateException("上下文压缩失败，且当前上下文长度已超过模型上下文长度，请调大压缩模型配置或删除部分历史消息后重试。")
    }

    private fun appendContextSummary(newSummary: ContextSummary) {
        contextSummariesInternal += newSummary
    }

    private fun buildLimitedRequestContextMessages(rawMessages: List<ChatMessage>): List<ChatMessage> {
        return ChatConversationContextHelper.limitToolResultsForContext(
            ChatConversationContextHelper.applyContextSummariesForRequest(
                conversationMessages = rawMessages,
                contextSummaries = contextSummariesInternal
            )
        )
    }

    private fun buildRequestMessages(messages: List<ChatMessage>): List<ChatMessage> {
        return listOf(ChatMessage(role = ChatRole.System, content = buildSystemPrompt())) + messages
    }

    private fun TokenUsage?.usageLogFields(): Array<Pair<String, Any?>> {
        return arrayOf(
            "inputTokens" to this?.inputTokens,
            "outputTokens" to this?.outputTokens,
            "totalTokens" to this?.totalTokens,
            "cachedInputTokens" to this?.cachedInputTokens,
            "reasoningOutputTokens" to this?.reasoningOutputTokens
        )
    }

    private fun buildContextCompressionPlan(
        settings: ApiSettings,
        messages: List<ChatMessage>,
        tokenCount: ChatConversationContextHelper.ContextTokenCount = resolveConversationTokenCount(messages)
    ): ContextCompressionPlan? {
        val triggerLength = calculateEffectiveCompressionTrigger(settings) ?: return null
        if (messages.size < 2) {
            return null
        }
        if (tokenCount.tokens < triggerLength) {
            return null
        }

        val userIndices = messages.indices.filter { index -> messages[index].role == ChatRole.User }
        if (userIndices.size < 2) {
            return null
        }

        val recentBudget = calculateRecentContextBudget(settings)
        var splitIndex = userIndices.last()
        for (candidate in userIndices.asReversed()) {
            val tailTokens = ChatConversationContextHelper.estimateConversationTokens(
                messages.subList(candidate, messages.size)
            )
            if (tailTokens <= recentBudget) {
                splitIndex = candidate
            } else {
                break
            }
        }
        if (splitIndex <= 0) {
            splitIndex = userIndices.getOrNull(1) ?: return null
        }

        val messagesToCompress = messages.take(splitIndex)
        val recentMessages = messages.drop(splitIndex)
        if (messagesToCompress.isEmpty() || recentMessages.none { it.role == ChatRole.User }) {
            return null
        }

        return ContextCompressionPlan(
            messagesToCompress = messagesToCompress,
            recentMessages = recentMessages,
            tokenCount = tokenCount,
            maxSummaryTokens = calculateSummaryTokenBudget(settings),
            sourceMessageIds = messagesToCompress.map { message -> message.id }
        )
    }

    private fun throwIfContextExceedsHardLimitWithoutCompression(
        settings: ApiSettings,
        tokenCount: ChatConversationContextHelper.ContextTokenCount
    ) {
        ChatConversationContextHelper.requireWithinModelContextLength(
            modelContextLength = settings.resolvedChatModel()?.contextLength ?: 0,
            contextTokens = tokenCount.tokens
        )
    }

    /** 触发阈值 = 聊天模型上下文长度 × compressionTriggerPercent%；上下文长度未知则不自动压缩。 */
    private fun calculateEffectiveCompressionTrigger(settings: ApiSettings): Int? {
        val contextLength = settings.resolvedChatModel()?.contextLength?.takeIf { it > 0 } ?: return null
        val percent = settings.compressionTriggerPercent.takeIf { it > 0 } ?: return null
        return (contextLength.toLong() * percent / 100L).toInt().coerceAtLeast(1)
    }

    private fun calculateRecentContextBudget(settings: ApiSettings): Int {
        val referenceLimit = settings.resolvedChatModel()?.contextLength?.takeIf { it > 0 }
            ?: DefaultRecentContextBudget
        return (referenceLimit * RecentContextBudgetRatio)
            .toInt()
            .coerceIn(MinRecentContextBudget, MaxRecentContextBudget)
    }

    private fun calculateSummaryTokenBudget(settings: ApiSettings): Int {
        val referenceLimit = settings.resolvedChatModel()?.contextLength?.takeIf { it > 0 }
            ?: DefaultRecentContextBudget
        return (referenceLimit * SummaryBudgetRatio)
            .toInt()
            .coerceIn(MinSummaryTokenBudget, MaxSummaryTokenBudget)
    }

    private fun commitRawConversation(workingConversation: List<ChatMessage>) {
        conversationMessagesInternal.clear()
        conversationMessagesInternal.addAll(
            ChatConversationContextHelper.filterConversationMessages(workingConversation)
                .filterNot { it.isContextSummary }
        )
        val anchoredMessages = contextTokenUsageAnchor?.messages
        if (anchoredMessages != null &&
            !ChatConversationContextHelper.hasMessagePrefix(
                buildLimitedRequestContextMessages(conversationMessagesInternal),
                anchoredMessages
            )
        ) {
            clearContextTokenUsageAnchor()
        }
    }

    private fun persistConversationAsync() {
        val settingsSnapshot = _savedSettings.value ?: return
        val conversationIdSnapshot = _currentConversationId.value
            ?.takeIf { it.isNotBlank() }
            ?: ChatHistoryStore.newConversationId().also { setCurrentConversationId(it) }
        val titleSnapshot = currentConversationTitle.ifBlank {
            buildFallbackTitle(uiMessagesInternal.firstOrNull { it.role == ChatRole.User }?.content.orEmpty())
        }
        val uiSnapshot = uiMessagesInternal.toList()
        val conversationSnapshot = conversationMessagesInternal.toList()
        val contextSummarySnapshot = contextSummariesInternal.toList()
        val saveVersion = registerSaveSnapshot(conversationIdSnapshot)
        MoteLog.d(
            logComponent,
            MoteLog.event(
                "已注册保存快照",
                "conversationId" to MoteLog.shortId(conversationIdSnapshot),
                "saveVersion" to saveVersion,
                "uiMessages" to uiSnapshot.size,
                "conversationMessages" to conversationSnapshot.size,
                "contextSummaries" to contextSummarySnapshot.size
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                persistenceMutex.withLock {
                    if (shouldSkipSave(conversationIdSnapshot, saveVersion)) {
                        MoteLog.d(
                            logComponent,
                            MoteLog.event(
                                "跳过过期保存快照",
                                "conversationId" to MoteLog.shortId(conversationIdSnapshot),
                                "saveVersion" to saveVersion
                            )
                        )
                        return@runCatching ChatHistoryStore.listConversations(appContext)
                    }
                    val effectiveTitle = synchronized(persistenceStateLock) {
                        generatedConversationTitles[conversationIdSnapshot] ?: titleSnapshot
                    }
                    ChatHistoryStore.saveConversation(
                        context = appContext,
                        settings = settingsSnapshot,
                        conversationId = conversationIdSnapshot,
                        title = effectiveTitle,
                        uiMessages = uiSnapshot,
                        conversationMessages = conversationSnapshot,
                        contextSummaries = contextSummarySnapshot
                    )
                    ChatHistoryStore.listConversations(appContext)
                }
            }.onFailure { error ->
                MoteLog.e(logComponent, "保存聊天记录失败", error)
                clearSaveSnapshot(conversationIdSnapshot, saveVersion)
            }.onSuccess { summaries ->
                clearSaveSnapshot(conversationIdSnapshot, saveVersion)
                MoteLog.d(
                    logComponent,
                    MoteLog.event(
                        "聊天记录保存任务完成",
                        "conversationId" to MoteLog.shortId(conversationIdSnapshot),
                        "summaries" to summaries.size
                    )
                )
                withContext(Dispatchers.Main) {
                    _conversationSummaries.value = summaries
                }
            }
        }
    }

    private fun generateConversationTitleAsync(settings: ApiSettings, firstUserMessage: String) {
        val titleModel = settings.resolvedTitleModel()
        if (titleModel == null || firstUserMessage.isBlank()) {
            MoteLog.d(
                logComponent,
                MoteLog.event(
                    "跳过模型标题生成",
                    "titleModelConfigured" to (titleModel != null),
                    "firstUserMessageBlank" to firstUserMessage.isBlank()
                )
            )
            refreshConversationSummariesAsync()
            return
        }

        val conversationIdSnapshot = _currentConversationId.value.orEmpty()
        if (conversationIdSnapshot.isBlank()) {
            MoteLog.w(logComponent, "跳过模型标题生成：当前对话 ID 为空。")
            refreshConversationSummariesAsync()
            return
        }

        MoteLog.i(
            logComponent,
            MoteLog.event(
                "开始异步生成对话标题",
                "conversationId" to MoteLog.shortId(conversationIdSnapshot),
                "firstUserMessageLength" to firstUserMessage.length
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            val generatedTitle = runCatching {
                ChatApiClient.generateConversationTitle(titleModel, firstUserMessage)
            }.onFailure { error ->
                MoteLog.e(logComponent, "生成对话标题失败", error)
            }.getOrNull()

            val modelTitle = generatedTitle
                ?.let { normalizeTitle(it) }
                ?.takeIf { it.isNotBlank() }
            val usedFallbackTitle = modelTitle == null
            val normalizedTitle = modelTitle ?: buildFallbackTitle(firstUserMessage)
            val summaries = runCatching {
                persistenceMutex.withLock {
                    val deleted = synchronized(persistenceStateLock) {
                        conversationIdSnapshot in deletedConversationIds
                    }
                    if (deleted) {
                        MoteLog.d(
                            logComponent,
                            MoteLog.event(
                                "跳过保存标题：对话已删除",
                                "conversationId" to MoteLog.shortId(conversationIdSnapshot)
                            )
                        )
                        return@withLock ChatHistoryStore.listConversations(appContext)
                    }
                    synchronized(persistenceStateLock) {
                        generatedConversationTitles[conversationIdSnapshot] = normalizedTitle
                    }
                    ChatHistoryStore.updateConversationTitle(
                        context = appContext,
                        conversationId = conversationIdSnapshot,
                        title = normalizedTitle
                    )
                    clearGeneratedConversationTitle(conversationIdSnapshot, normalizedTitle)
                    ChatHistoryStore.listConversations(appContext)
                }
            }.onFailure { error ->
                MoteLog.e(logComponent, "保存对话标题失败", error)
            }.getOrDefault(emptyList())

            MoteLog.i(
                logComponent,
                MoteLog.event(
                    "对话标题生成流程完成",
                    "conversationId" to MoteLog.shortId(conversationIdSnapshot),
                    "modelTitleAvailable" to (modelTitle != null),
                    "usedFallbackTitle" to usedFallbackTitle,
                    "titleLength" to normalizedTitle.length
                )
            )

            withContext(Dispatchers.Main) {
                if (_currentConversationId.value == conversationIdSnapshot) {
                    currentConversationTitle = normalizedTitle
                }
                _conversationSummaries.value = summaries
            }
        }
    }

    private fun refreshConversationSummariesAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            val summaries = runCatching {
                ChatHistoryStore.listConversations(appContext)
            }.onFailure { error ->
                MoteLog.e(logComponent, "刷新对话列表失败", error)
            }.getOrDefault(emptyList())

            MoteLog.d(logComponent, MoteLog.event("对话列表刷新完成", "summaries" to summaries.size))

            withContext(Dispatchers.Main) {
                _conversationSummaries.value = summaries
            }
        }
    }

    private fun markStateChanged(): Long {
        return stateVersion.incrementAndGet()
    }

    private fun setCurrentConversationId(conversationId: String) {
        _currentConversationId.value = conversationId
        synchronized(persistenceStateLock) {
            deletedConversationIds.remove(conversationId)
        }
    }

    private fun markConversationDeleted(conversationId: String) {
        synchronized(persistenceStateLock) {
            deletedConversationIds += conversationId
            latestSaveVersions.remove(conversationId)
            generatedConversationTitles.remove(conversationId)
        }
    }

    private fun unmarkConversationDeleted(conversationId: String) {
        synchronized(persistenceStateLock) {
            deletedConversationIds.remove(conversationId)
        }
    }

    private fun clearDeletedConversation(conversationId: String) {
        synchronized(persistenceStateLock) {
            deletedConversationIds.remove(conversationId)
            latestSaveVersions.remove(conversationId)
            generatedConversationTitles.remove(conversationId)
        }
    }

    private fun registerSaveSnapshot(conversationId: String): Long {
        synchronized(persistenceStateLock) {
            nextSaveVersion += 1
            latestSaveVersions[conversationId] = nextSaveVersion
            return nextSaveVersion
        }
    }

    private fun shouldSkipSave(conversationId: String, saveVersion: Long): Boolean {
        synchronized(persistenceStateLock) {
            return conversationId in deletedConversationIds ||
                    latestSaveVersions[conversationId] != saveVersion
        }
    }

    private fun clearSaveSnapshot(conversationId: String, saveVersion: Long) {
        synchronized(persistenceStateLock) {
            if (latestSaveVersions[conversationId] == saveVersion) {
                latestSaveVersions.remove(conversationId)
            }
        }
    }

    private fun clearGeneratedConversationTitle(conversationId: String, title: String) {
        synchronized(persistenceStateLock) {
            if (generatedConversationTitles[conversationId] == title) {
                generatedConversationTitles.remove(conversationId)
            }
        }
    }

    private fun persistCurrentConversationIdAsync(
        conversationId: String,
        expectedStateVersion: Long,
        allowMissingConversation: Boolean = false
    ) {
        if (conversationId.isBlank()) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                persistenceMutex.withLock {
                    if (stateVersion.get() != expectedStateVersion) {
                        return@withLock
                    }
                    ChatHistoryStore.saveCurrentConversationId(
                        context = appContext,
                        conversationId = conversationId,
                        allowMissingConversation = allowMissingConversation
                    )
                }
            }.onFailure { error ->
                MoteLog.e(logComponent, "保存当前对话索引失败", error)
            }.onSuccess {
                MoteLog.d(
                    logComponent,
                    MoteLog.event(
                        "当前对话索引保存任务完成",
                        "conversationId" to MoteLog.shortId(conversationId),
                        "allowMissingConversation" to allowMissingConversation
                    )
                )
            }
        }
    }

    private fun publishMessages() {
        _uiMessages.value = uiMessagesInternal.toList()
    }

    private fun publishMessagesImmediately() {
        cancelPendingStreamingPublish()
        publishMessages()
    }

    private fun scheduleStreamingPublish() {
        if (pendingStreamingPublishJob?.isActive == true) {
            return
        }

        pendingStreamingPublishJob = viewModelScope.launch {
            delay(50)
            publishMessages()
            pendingStreamingPublishJob = null
        }
    }

    private fun cancelPendingStreamingPublish() {
        pendingStreamingPublishJob?.cancel()
        pendingStreamingPublishJob = null
    }

    private companion object {
        const val DefaultConversationTitle = ConversationTitleFormatter.DefaultTitle
        const val MaxToolRounds = 200
        const val MaxStreamRetryAttempts = 3
        const val DefaultRecentContextBudget = 16_000
        const val MinRecentContextBudget = 1_024
        const val MaxRecentContextBudget = 32_000
        const val MinSummaryTokenBudget = 512
        const val MaxSummaryTokenBudget = 8_192
        const val RecentContextBudgetRatio = 0.35f
        const val SummaryBudgetRatio = 0.12f

        fun buildFallbackTitle(message: String): String {
            return ConversationTitleFormatter.buildFallbackTitle(message)
        }

        fun buildAttachmentTitleSeed(attachments: List<ChatAttachment>): String {
            return attachments.joinToString(separator = " ") { attachment ->
                val label = when (attachment.type) {
                    ChatAttachmentType.Image -> "图片"
                    ChatAttachmentType.File -> "文件"
                }
                "$label：${attachment.displayName.ifBlank { attachment.path }}"
            }
        }

        fun normalizeTitle(value: String): String {
            return ConversationTitleFormatter.normalize(value)
        }

        fun buildSystemPrompt(): String {
            val currentTime = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            return """
                # 角色定义
                你是一个 Android 系统的本地 AI Agent，负责协助用户完成设备级任务。你可以调用工具来读取本地文件和执行 Shell 命令。
                当前系统时间：$currentTime
                
                # 环境规范
                - 基础环境：Android busybox（执行器已自动注入相关环境变量）。
                - 路径约定：优先操作 `/sdcard/` 或应用私有目录。访问其他目录时需注意 Android 沙盒机制与读写权限限制。
                - 附件约定：用户消息可能附带图片或文件；图片会以 base64 图片内容随消息提供，文件在可直接读取时只提供路径，否则会附带通过 ContentResolver 读取到的文本内容。
                
                # 安全协议（最高优先级）
                - 拦截机制：当你调用高风险指令（如 `rm` 删除、`mv` 覆盖、修改敏感配置等）时，宿主应用会自动拦截并弹窗要求用户确认。
                - 执行准则：执行高风险命令之前必须先向用户说明风险，用户同意之后才能继续执行。
            """.trimIndent()
        }
    }
}
