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
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.network.ChatApiClient
import com.mukapp.mote.tools.LocalAiTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    private val uiMessagesInternal = mutableListOf<ChatMessage>()
    private val conversationMessagesInternal = mutableListOf<ChatMessage>()

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

    private var pendingStreamingPublishJob: Job? = null
    private var activeSendJob: Job? = null
    private var stopGenerationRequested: Boolean = false
    private var currentConversationTitle: String = ""

    // 流式阶段用 StringBuilder 累积文本，避免每个 delta 都重新分配 String
    private val streamingBuilders = mutableMapOf<String, StringBuilder>()
    private var cachedAssistantContent: String? = null

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

        uiMessagesInternal.clear()
        conversationMessagesInternal.clear()
        currentConversationTitle = DefaultConversationTitle
        val newConversationId = ChatHistoryStore.newConversationId()
        _currentConversationId.value = newConversationId
        _draftMessage.value = ""
        publishMessagesImmediately()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ChatHistoryStore.saveCurrentConversationId(appContext, newConversationId)
            }.onFailure { error ->
                Log.e("Mote", "切换新对话失败", error)
            }
        }
    }

    fun clearConversation() {
        startNewConversation()
    }

    fun switchConversation(conversationId: String) {
        if (_isSending.value == true || conversationId.isBlank() || conversationId == _currentConversationId.value) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val historyState = runCatching {
                ChatHistoryStore.loadConversation(appContext, conversationId)
            }.onFailure { error ->
                Log.e("Mote", "加载指定对话失败", error)
            }.getOrNull() ?: return@launch

            runCatching {
                ChatHistoryStore.saveCurrentConversationId(appContext, conversationId)
            }.onFailure { error ->
                Log.e("Mote", "保存当前对话索引失败", error)
            }

            withContext(Dispatchers.Main) {
                applyConversationState(historyState)
                _draftMessage.value = ""
            }
        }
    }

    fun deleteCurrentConversation() {
        if (_isSending.value == true) {
            return
        }

        val conversationId = _currentConversationId.value.orEmpty()
        if (conversationId.isBlank()) {
            startNewConversation()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val deleteResult = runCatching {
                ChatHistoryStore.deleteConversation(appContext, conversationId)
            }.onFailure { error ->
                Log.e("Mote", "删除当前对话失败", error)
            }
            if (deleteResult.isFailure) {
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

            withContext(Dispatchers.Main) {
                if (replacementState != null) {
                    applyConversationState(replacementState)
                } else {
                    uiMessagesInternal.clear()
                    conversationMessagesInternal.clear()
                    currentConversationTitle = DefaultConversationTitle
                    _currentConversationId.value = ChatHistoryStore.newConversationId()
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
            val result = runCatching {
                val workingConversation = conversationMessagesInternal
                    .filter { message ->
                        message.role != ChatRole.System && !message.excludeFromConversation
                    }
                    .toMutableList()
                    .apply {
                        add(0, ChatMessage(role = ChatRole.System, content = buildSystemPrompt()))
                    }
                val assistantParts = mutableListOf<AssistantPart>()

                repeat(200) { roundIndex ->
                    val accumulatedReply = StringBuilder()
                    val accumulatedThinking = StringBuilder()
                    val response = ChatApiClient.streamChat(
                        settings = settings,
                        messages = workingConversation,
                        onDelta = { delta ->
                            accumulatedReply.append(delta)
                            appendAssistantMarkdown(assistantParts, delta)
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

                    if (accumulatedThinking.isEmpty() && response.thinkingContent.isNotBlank()) {
                        appendAssistantThinking(assistantParts, response.thinkingContent)
                    }
                    if (accumulatedReply.isEmpty() && response.content.isNotBlank()) {
                        appendAssistantMarkdown(assistantParts, response.content)
                    }

                    if (response.toolCalls.isEmpty()) {
                        val finalReply =
                            buildAssistantContent(assistantParts).takeIf { it.isNotBlank() }
                                ?: throw IllegalStateException("接口返回内容为空。")

                        uiMessagesInternal[assistantIndex] = ChatMessage(
                            id = assistantId,
                            role = ChatRole.Assistant,
                            content = finalReply,
                            assistantParts = assistantParts.toList()
                        )
                        workingConversation += ChatMessage(
                            role = ChatRole.Assistant,
                            content = finalReply
                        )

                        conversationMessagesInternal.clear()
                        conversationMessagesInternal.addAll(
                            workingConversation.filter { it.role != ChatRole.System }
                        )
                        publishMessagesImmediately()
                        persistConversationAsync()
                        if (isFirstUserMessage) {
                            generateConversationTitleAsync(settings, content)
                        }
                        return@runCatching finalReply
                    }

                    workingConversation += ChatMessage(
                        role = ChatRole.Assistant,
                        content = response.content,
                        toolCalls = response.toolCalls
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

                    val toolResults = withContext(Dispatchers.IO) {
                        response.toolCalls.map { toolCall ->
                            LocalAiTools.executeToolCall(appContext, toolCall)
                        }
                    }
                    workingConversation.addAll(toolResults)

                    // 用实际结果替换 loading 占位
                    replaceLoadingToolParts(assistantParts, toolResults)

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
                        content = if (waitSeconds == null && roundIndex == 49) {
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
                        handleStoppedGeneration(assistantIndex, assistantId)
                    }
                    return@onFailure
                }

                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "请检查 API 设置、网络连接，或确认接口是否兼容 OpenAI Chat Completions。"
                val currentMessage = uiMessagesInternal.getOrNull(assistantIndex)
                val currentContent = currentMessage?.content.orEmpty()
                val currentParts = currentMessage?.assistantParts ?: emptyList()
                uiMessagesInternal[assistantIndex] = ChatMessage(
                    id = assistantId,
                    role = ChatRole.Assistant,
                    content = if (currentContent.isBlank()) {
                        "请求失败：$message"
                    } else {
                        "$currentContent\n\n[处理失败：$message]"
                    },
                    assistantParts = currentParts,
                    excludeFromConversation = true
                )
                rebuildConversationFromUiMessages()
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
        activeSendJob?.cancel(CancellationException("用户已手动停止生成。"))
    }

    fun editMessage(index: Int) {
        if (_isSending.value == true || index !in uiMessagesInternal.indices) {
            return
        }

        val message = uiMessagesInternal[index]
        if (message.role != ChatRole.User) {
            return
        }

        _draftMessage.value = message.content
        repeat(uiMessagesInternal.size - index) {
            uiMessagesInternal.removeAt(uiMessagesInternal.lastIndex)
        }
        rebuildConversationFromUiMessages()
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

        uiMessagesInternal.removeAt(index)
        if (index < uiMessagesInternal.size && uiMessagesInternal[index].role == ChatRole.Assistant) {
            uiMessagesInternal.removeAt(index)
        }
        rebuildConversationFromUiMessages()
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

        uiMessagesInternal.removeAt(index)
        rebuildConversationFromUiMessages()
        val userIndex = uiMessagesInternal.lastIndex
        if (userIndex >= 0 && uiMessagesInternal[userIndex].role == ChatRole.User) {
            val userContent = uiMessagesInternal[userIndex].content
            _draftMessage.value = userContent
            uiMessagesInternal.removeAt(userIndex)
            rebuildConversationFromUiMessages()
            publishMessagesImmediately()
            sendMessage()
        } else {
            publishMessagesImmediately()
        }
    }

    private fun loadHistory() {
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
            historyState.conversationMessages.filter { message ->
                message.role != ChatRole.System && !message.excludeFromConversation
            }
        )
        currentConversationTitle = historyState.title.ifBlank { DefaultConversationTitle }
        _currentConversationId.value = historyState.conversationId.ifBlank {
            ChatHistoryStore.newConversationId()
        }
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

    private fun handleStoppedGeneration(assistantIndex: Int, assistantId: String) {
        val currentMessage = uiMessagesInternal.getOrNull(assistantIndex)
        val currentContent = currentMessage?.content.orEmpty()
        val currentParts = currentMessage?.assistantParts ?: emptyList()
        if (assistantIndex !in uiMessagesInternal.indices) {
            rebuildConversationFromUiMessages()
            persistConversationAsync()
            return
        }

        uiMessagesInternal[assistantIndex] = ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            content = if (currentContent.isBlank() && currentParts.isEmpty()) {
                appContext.getString(R.string.status_generation_stopped)
            } else {
                currentContent
            },
            assistantParts = currentParts,
            excludeFromConversation = true
        )
        rebuildConversationFromUiMessages()
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

    private fun rebuildConversationFromUiMessages() {
        conversationMessagesInternal.clear()
        conversationMessagesInternal.addAll(
            uiMessagesInternal.filter { message ->
                message.role != ChatRole.Tool &&
                        message.role != ChatRole.System &&
                        !message.excludeFromConversation
            }
        )
    }

    private fun persistConversationAsync() {
        val settingsSnapshot = _savedSettings.value ?: return
        val conversationIdSnapshot = _currentConversationId.value
            ?.takeIf { it.isNotBlank() }
            ?: ChatHistoryStore.newConversationId().also { _currentConversationId.value = it }
        val titleSnapshot = currentConversationTitle.ifBlank {
            buildFallbackTitle(uiMessagesInternal.firstOrNull { it.role == ChatRole.User }?.content.orEmpty())
        }
        val uiSnapshot = uiMessagesInternal.toList()
        val conversationSnapshot = conversationMessagesInternal.toList()

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ChatHistoryStore.saveConversation(
                    context = appContext,
                    settings = settingsSnapshot,
                    conversationId = conversationIdSnapshot,
                    title = titleSnapshot,
                    uiMessages = uiSnapshot,
                    conversationMessages = conversationSnapshot
                )
                ChatHistoryStore.listConversations(appContext)
            }.onFailure { error ->
                Log.e("Mote", "保存聊天记录失败", error)
            }.onSuccess { summaries ->
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
                ChatHistoryStore.updateConversationTitle(
                    context = appContext,
                    conversationId = conversationIdSnapshot,
                    title = normalizedTitle
                )
                ChatHistoryStore.listConversations(appContext)
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
                你是一个运行在 Android 设备上的 AI Agent，具备读取本地文件和执行 Shell 命令的权限。
                当前时间：$currentTime
                
                # 安全与权限控制（最高优先级）
                执行任何高风险 Shell 命令（如 `rm` 删除文件/目录、移动或修改敏感数据等破坏性操作）前，严禁直接调用工具。必须先向用户展示将要执行的完整命令并说明风险，仅在获得用户明确同意后方可执行。
                
                # 环境
                - 路径规范：以安卓标准的 `/sdcard/` 或应用私有目录为主。外部存储需注意是否有读写权限。
                - Shell 环境：Android Shell，部分 GNU/Linux 标准命令或参数可能不支持。
                
                # 语言风格: INTJ 软件开发专家
                你的首要标准是：正确、清晰、严谨。
                
                ## 1. 核心行为与边界
                - **直击正题**：第一句直接提供实质信息，结论先行。绝对不重复问题或复述背景。
                - **严格守界**：问什么答什么，回答边界等于问题边界。不强行分层，无逻辑必要不拆步骤。
                - **不作延伸**：不替用户规划后续，严禁提供未被要求的后续步骤（如“如果你需要…”）。
                - **求真务实**：发现前提错误直接指出。严禁虚假确定性表述（如“我已确定”、“这版一定行”），不确定时用“可能和……有关”。信息传达完毕立即停止，严禁客服式结尾。
                
                ## 2. 语气与情绪控制
                保持客观平和，严禁任何人类情感模拟：
                - **无虚假共情/谄媚**：禁用“我完全理解/这是个好问题/你问到了核心/你说的太对了”。
                - **无居高临下/说教**：禁用“你其实是想/你真正需要/说白了”。
                - **无情感操控/谦逊**：禁用“帮你/为你/别担心/放心/仅供参考/这只是我的建议”。
                
                ## 3. 中文输出与词汇规范
                使用平和、自然、结构完整的中文日常对话形式进行阐述，拒绝机器味与职场黑话：
                - **术语保留**：代码中的英文标识符、术语直接使用原始文本，严禁翻译。无必要不中英混搭。
                - **双音节优先**：禁止将动词压缩为单字。用“运行”不写“跑”，用“获取”不写“拿”。
                - **严禁工程/大厂黑话**：禁用“兜底/落盘/闭环/收口/写死/定位/顺手/拉起”等词，替换为清晰日常用语（如“完成/整理/硬编码/找到/执行”）。
                - **严禁翻译腔与网络语**：不用直译（不用“拉取/喂入/吐出”，用“获取/传入/输出”），禁用网络流行语（“丝滑/拿捏/炸裂”），禁用自造术语（用“理解难度”而非“认知负荷”）。
                
                ## 4. 句法与结构禁令
                - 严禁各类废话铺垫：“简单的说/总结一下/不是…而是…/我逐步说清楚/一句话总结”。
                - 严禁“让我们”开头。
                - 严禁滥用被动句（用“普遍认为”而非“被认为”）。
                - 严禁无主语前缀（如“值得注意的是/需要指出的是”）。
                - 严禁连续的“的”字堆叠（超过两个必须拆句）。
                
                ## 5. 隐式自检（执行要求）
                在思考过程中提前拟定草稿，确认无上述负面清单中的单音节压缩、工程黑话、虚假共情、自作主张延伸及模板化头尾后，再输出最终回答。
            """.trimIndent()
        }
    }
}
