package com.mukapp.mote.ui

import android.app.Application
import android.util.Log
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
import com.mukapp.mote.data.model.ChatCompletionResult
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.data.model.ContextSummary
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.data.model.TokenUsage
import com.mukapp.mote.network.ChatApiClient
import com.mukapp.mote.tools.LocalAiTools
import com.mukapp.mote.tools.ShellProcessManager
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

    fun reloadSettings() {
        _savedSettings.value = ApiSettingsStore.load(appContext)
    }

    fun saveSettings(settings: ApiSettings) {
        ApiSettingsStore.save(appContext, settings)
        _savedSettings.value = settings
        if (uiMessagesInternal.isNotEmpty() || conversationMessagesInternal.isNotEmpty()) {
            persistConversationAsync()
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
        currentConversationTitle = DefaultConversationTitle
        val newConversationId = ChatHistoryStore.newConversationId()
        setCurrentConversationId(newConversationId)
        _draftMessage.value = ""
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
        val requestVersion = markStateChanged()
        viewModelScope.launch(Dispatchers.IO) {
            val historyState = runCatching {
                ChatHistoryStore.loadConversation(appContext, conversationId)
            }.onFailure { error ->
                Log.e("Mote", "加载指定对话失败", error)
            }.getOrNull() ?: return@launch

            withContext(Dispatchers.Main) {
                if (stateVersion.get() != requestVersion) {
                    return@withContext
                }
                applyConversationState(historyState)
                _draftMessage.value = ""
                persistCurrentConversationIdAsync(conversationId, requestVersion)
            }
        }
    }

    fun deleteCurrentConversation() {
        if (_isSending.value == true) {
            return
        }

        clearPendingShellConfirmation(discardToken = true)
        val conversationId = _currentConversationId.value.orEmpty()
        if (conversationId.isBlank()) {
            startNewConversation()
            return
        }

        val requestVersion = markStateChanged()
        markConversationDeleted(conversationId)
        viewModelScope.launch(Dispatchers.IO) {
            val deleteResult = runCatching {
                persistenceMutex.withLock {
                    ChatHistoryStore.deleteConversation(appContext, conversationId)
                }
            }.onFailure { error ->
                Log.e("Mote", "删除当前对话失败", error)
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
                    .onFailure { error -> Log.e("Mote", "加载替换对话失败", error) }
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
                    publishMessagesImmediately()
                }
                _conversationSummaries.value = summaries
            }
        }
    }

    fun sendMessage() {
        val content = _draftMessage.value.orEmpty().trim()
        if (content.isEmpty() || _isSending.value == true) {
            return
        }

        val settings = _savedSettings.value ?: ApiSettingsStore.load(appContext)
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) {
            uiMessagesInternal += ChatMessage(
                role = ChatRole.Assistant,
                content = "请先在设置页填写 API 地址和模型，然后再开始对话。"
            )
            publishMessagesImmediately()
            return
        }

        markStateChanged()
        val isFirstUserMessage = uiMessagesInternal.none { it.role == ChatRole.User }
        val userMessage = ChatMessage(role = ChatRole.User, content = content)
        uiMessagesInternal += userMessage
        conversationMessagesInternal += userMessage
        if (isFirstUserMessage) {
            currentConversationTitle = buildFallbackTitle(content)
        }
        _draftMessage.value = ""

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

                fun commitRawConversationSnapshot() {
                    commitRawConversation(workingRawConversation)
                }

                repeat(MaxToolRounds) { roundIndex ->
                    val streamResult = streamChatWithRetries(
                        settings = settings,
                        messages = buildRequestMessages(workingConversation),
                        assistantParts = assistantParts,
                        assistantIndex = assistantIndex,
                        assistantId = assistantId
                    )
                    val response = streamResult.response

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
                        if (isFirstUserMessage) {
                            generateConversationTitleAsync(settings, content)
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
                    val limitedToolResults = toolResults.map { result ->
                        ChatConversationContextHelper.limitToolResultForContext(result)
                    }
                    workingConversation.addAll(limitedToolResults)
                    workingRawConversation.addAll(limitedToolResults)
                    commitRawConversationSnapshot()
                    workingConversation = prepareConversationForSending(
                        settings = settings,
                        rawMessages = workingRawConversation
                    ).toMutableList()
                    hasCommittedToolContextInCurrentTurn = true
                    skipStoppedAssistantContextCommit = false

                    // 用实际结果替换 loading 占位
                    replaceLoadingToolParts(assistantParts, toolResults)

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
                        if (isFirstUserMessage) {
                            generateConversationTitleAsync(settings, content)
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
                        delay(waitSeconds * 1000L)
                    }
                }

                throw IllegalStateException("工具调用次数过多，请缩小问题范围后再试。")
            }

            result.onFailure { error ->
                if (error is CancellationException) {
                    if (stopGenerationRequested) {
                        handleStoppedGeneration(
                            assistantIndex = assistantIndex,
                            assistantId = assistantId,
                            includeAssistantInConversation = !skipStoppedAssistantContextCommit &&
                                    !hasCommittedToolContextInCurrentTurn
                        )
                    }
                    return@onFailure
                }

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
                    generateConversationTitleAsync(settings, content)
                }
            }

            cancelPendingStreamingPublish()
            streamingBuilders.clear()
            cachedAssistantContent = null
            activeSendJob = null
            stopGenerationRequested = false
            _isSending.value = false
        }
    }

    fun stopGenerating() {
        if (_isSending.value != true) {
            return
        }

        stopGenerationRequested = true
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
            pendingShellConfirmationId = null
            pendingShellConfirmationDecision = null
            _shellConfirmation.value = null
            decision.complete(true)
        } else {
            pendingShellConfirmationId = null
            pendingShellConfirmationDecision = null
            _shellConfirmation.value = null
            _userNotice.value = "Shell 命令确认已过期，请重新发起请求。"
            decision.complete(false)
        }
    }

    fun cancelPendingShellCommand() {
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
        repeat(uiMessagesInternal.size - index) {
            uiMessagesInternal.removeAt(uiMessagesInternal.lastIndex)
        }
        rebuildConversationAfterUiMutation(affectedUserMessageIds)
        publishMessagesImmediately()
        persistConversationAsync()
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
            val userContent = uiMessagesInternal[userIndex].content
            val affectedUserMessageIds = setOf(uiMessagesInternal[userIndex].id)
            _draftMessage.value = userContent
            uiMessagesInternal.removeAt(userIndex)
            rebuildConversationAfterUiMutation(affectedUserMessageIds)
            publishMessagesImmediately()
            sendMessage()
        } else {
            publishMessagesImmediately()
        }
    }

    private fun loadHistory() {
        val loadVersion = stateVersion.get()
        viewModelScope.launch(Dispatchers.IO) {
            val historyState = runCatching {
                ChatHistoryStore.loadCurrentConversation(appContext)
            }.onFailure { error ->
                Log.e("Mote", "加载历史记录失败", error)
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
                Log.e("Mote", "加载对话列表失败", error)
            }.getOrDefault(emptyList())

            withContext(Dispatchers.Main) {
                if (stateVersion.get() != loadVersion) {
                    return@withContext
                }
                applyConversationState(historyState)
                _conversationSummaries.value = summaries
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
        val resultById = toolResults.associateBy { it.toolCallId }
        for (index in parts.indices) {
            val part = parts[index]
            if (part is AssistantToolPart && part.isLoading) {
                val result = resultById[part.id]
                parts[index] = part.copy(
                    result = result?.content.orEmpty(),
                    isLoading = false
                )
            }
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
                val response = ChatApiClient.streamChat(
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
            var result = executeLocalToolCall(settings, toolCall)
            val confirmation = parseShellConfirmationRequest(result.content, toolCall.arguments)
            if (confirmation != null) {
                val approved = awaitShellConfirmation(
                    confirmation = confirmation,
                    assistantParts = assistantParts,
                    assistantIndex = assistantIndex,
                    assistantId = assistantId
                )
                if (!approved) {
                    LocalAiTools.discardPendingShellConfirmation(confirmation.confirmationId)
                    results += buildCancelledShellToolResult(toolCall, confirmation)
                    return ToolExecutionBatch(results = results, cancelled = true)
                }

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
    }

    private suspend fun prepareConversationForSending(
        settings: ApiSettings,
        rawMessages: List<ChatMessage> = conversationMessagesInternal
    ): List<ChatMessage> {
        val result = compressConversationForContext(
            settings = settings,
            rawMessages = rawMessages
        )
        if (result.compressed) {
            persistConversationAsync()
        }
        return result.requestMessages
    }

    private suspend fun compressConversationForContext(
        settings: ApiSettings,
        rawMessages: List<ChatMessage>
    ): ContextCompressionResult {
        val normalizedMessages = ChatConversationContextHelper.filterConversationMessages(rawMessages)
            .filterNot { it.isContextSummary }
        val requestMessages = ChatConversationContextHelper.applyContextSummariesForRequest(
            conversationMessages = normalizedMessages,
            contextSummaries = contextSummariesInternal
        )
        val tokenCount = resolveConversationTokenCount(requestMessages)
        val plan = buildContextCompressionPlan(settings, requestMessages, tokenCount)
        if (plan == null) {
            throwIfContextExceedsHardLimitWithoutCompression(settings, tokenCount)
            return ContextCompressionResult(requestMessages = requestMessages, compressed = false)
        }
        val summary = try {
            val summary = ChatApiClient.compressConversation(
                settings = settings,
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
            Log.e("Mote", "压缩聊天上下文失败", error)
            null
        }

        if (summary != null) {
            appendContextSummary(summary)
            Log.i(
                "Mote",
                "已压缩聊天上下文：${plan.tokenCount.sourceLabel} ${plan.tokenCount.tokens} tokens，压缩 ${plan.messagesToCompress.size} 条消息，保留 ${plan.recentMessages.size} 条消息。"
            )
            clearContextTokenUsageAnchor()
            return ContextCompressionResult(
                requestMessages = ChatConversationContextHelper.applyContextSummariesForRequest(
                    conversationMessages = normalizedMessages,
                    contextSummaries = contextSummariesInternal
                ),
                compressed = true
            )
        }

        val hardLimit = settings.modelContextLength.takeIf { it > 0 }
        if (hardLimit == null || plan.tokenCount.tokens <= hardLimit) {
            _userNotice.value = "上下文压缩失败，已临时使用原始上下文继续发送。"
            return ContextCompressionResult(requestMessages = requestMessages, compressed = false)
        }
        throw IllegalStateException("上下文压缩失败，且当前上下文长度已超过模型上下文长度，请调大压缩模型配置或删除部分历史消息后重试。")
    }

    private fun appendContextSummary(newSummary: ContextSummary) {
        contextSummariesInternal += newSummary
    }

    private fun buildRequestMessages(messages: List<ChatMessage>): List<ChatMessage> {
        return listOf(ChatMessage(role = ChatRole.System, content = buildSystemPrompt())) + messages
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
        ChatConversationContextHelper.requireWithinModelContextLength(settings, tokenCount.tokens)
    }

    private fun calculateEffectiveCompressionTrigger(settings: ApiSettings): Int? {
        val configuredTrigger = settings.compressionTriggerLength.takeIf { it > 0 } ?: return null
        val contextSoftLimit = settings.modelContextLength
            .takeIf { it > 0 }
            ?.let { (it * ModelContextCompressionRatio).toInt().coerceAtLeast(1) }
        return minPositive(configuredTrigger, contextSoftLimit ?: 0)
    }

    private fun calculateRecentContextBudget(settings: ApiSettings): Int {
        val referenceLimit = minPositive(settings.modelContextLength, settings.compressionTriggerLength)
            ?: settings.compressionTriggerLength.takeIf { it > 0 }
            ?: DefaultRecentContextBudget
        return (referenceLimit * RecentContextBudgetRatio)
            .toInt()
            .coerceIn(MinRecentContextBudget, MaxRecentContextBudget)
    }

    private fun calculateSummaryTokenBudget(settings: ApiSettings): Int {
        val referenceLimit = minPositive(settings.modelContextLength, settings.compressionTriggerLength)
            ?: settings.compressionTriggerLength.takeIf { it > 0 }
            ?: DefaultRecentContextBudget
        return (referenceLimit * SummaryBudgetRatio)
            .toInt()
            .coerceIn(MinSummaryTokenBudget, MaxSummaryTokenBudget)
    }

    private fun minPositive(first: Int, second: Int): Int? {
        return listOf(first, second).filter { it > 0 }.minOrNull()
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
                ChatConversationContextHelper.applyContextSummariesForRequest(
                    conversationMessages = conversationMessagesInternal,
                    contextSummaries = contextSummariesInternal
                ),
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

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                persistenceMutex.withLock {
                    if (shouldSkipSave(conversationIdSnapshot, saveVersion)) {
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
                Log.e("Mote", "保存聊天记录失败", error)
                clearSaveSnapshot(conversationIdSnapshot, saveVersion)
            }.onSuccess { summaries ->
                clearSaveSnapshot(conversationIdSnapshot, saveVersion)
                withContext(Dispatchers.Main) {
                    _conversationSummaries.value = summaries
                }
            }
        }
    }

    private fun generateConversationTitleAsync(settings: ApiSettings, firstUserMessage: String) {
        if (settings.titleModel.isBlank() || firstUserMessage.isBlank()) {
            refreshConversationSummariesAsync()
            return
        }

        val conversationIdSnapshot = _currentConversationId.value.orEmpty()
        if (conversationIdSnapshot.isBlank()) {
            refreshConversationSummariesAsync()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val generatedTitle = runCatching {
                ChatApiClient.generateConversationTitle(settings, firstUserMessage)
            }.onFailure { error ->
                Log.e("Mote", "生成对话标题失败", error)
            }.getOrNull()

            val normalizedTitle = generatedTitle
                ?.let { normalizeTitle(it) }
                ?.takeIf { it.isNotBlank() }
                ?: buildFallbackTitle(firstUserMessage)
            val summaries = runCatching {
                persistenceMutex.withLock {
                    val deleted = synchronized(persistenceStateLock) {
                        conversationIdSnapshot in deletedConversationIds
                    }
                    if (deleted) {
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
                Log.e("Mote", "保存对话标题失败", error)
            }.getOrDefault(emptyList())

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
                Log.e("Mote", "刷新对话列表失败", error)
            }.getOrDefault(emptyList())

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
                Log.e("Mote", "保存当前对话索引失败", error)
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
        const val DefaultConversationTitle = "新对话"
        const val MaxToolRounds = 200
        const val MaxStreamRetryAttempts = 3
        const val DefaultRecentContextBudget = 16_000
        const val MinRecentContextBudget = 1_024
        const val MaxRecentContextBudget = 32_000
        const val MinSummaryTokenBudget = 512
        const val MaxSummaryTokenBudget = 8_192
        const val RecentContextBudgetRatio = 0.35f
        const val SummaryBudgetRatio = 0.12f
        const val ModelContextCompressionRatio = 0.8f

        fun buildFallbackTitle(message: String): String {
            return normalizeTitle(message).ifBlank { DefaultConversationTitle }
        }

        fun normalizeTitle(value: String): String {
            val compact = value
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('"', '\'', '“', '”', '‘', '’', '。', '，', ',', '.', '、', ':', '：')
            return if (compact.length > 24) {
                compact.take(24).trimEnd() + "..."
            } else {
                compact
            }
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
                
                # 安全协议（最高优先级）
                - 拦截机制：当你调用高风险指令（如 `rm` 删除、`mv` 覆盖、修改敏感配置等）时，宿主应用会自动拦截并弹窗要求用户确认。
                - 执行准则：执行高风险命令之前必须先向用户说明风险，用户同意之后才能继续执行。
            """.trimIndent()
        }
    }
}
