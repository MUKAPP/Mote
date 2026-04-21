package com.mukapp.mote.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.ChatHistoryStore
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.IntermediateStep
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.data.model.ToolResultInfo
import com.mukapp.mote.network.ChatApiClient
import com.mukapp.mote.tools.LocalAiTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private val _isSending = MutableLiveData(false)
    val isSending: LiveData<Boolean> = _isSending

    private var pendingStreamingPublishJob: Job? = null

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
        persistConversationAsync()
    }

    fun clearConversation() {
        if (_isSending.value == true) {
            return
        }

        uiMessagesInternal.clear()
        conversationMessagesInternal.clear()
        publishMessagesImmediately()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ChatHistoryStore.clearConversation(appContext)
            }.onFailure { error ->
                Log.e("Mote", "清空聊天记录失败", error)
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

        val userMessage = ChatMessage(role = ChatRole.User, content = content)
        uiMessagesInternal += userMessage
        conversationMessagesInternal += userMessage
        _draftMessage.value = ""

        val assistantId = UUID.randomUUID().toString()
        uiMessagesInternal += ChatMessage(id = assistantId, role = ChatRole.Assistant, content = "")
        val assistantIndex = uiMessagesInternal.lastIndex
        _isSending.value = true
        publishMessagesImmediately()

        viewModelScope.launch {
            val result = runCatching {
                val workingConversation = conversationMessagesInternal.toMutableList().apply {
                    add(0, ChatMessage(role = ChatRole.System, content = SystemPrompt))
                }
                val accumulatedIntermediateSteps = mutableListOf<IntermediateStep>()
                var currentThinkingStep = IntermediateStep()
                val allRoundsContent = StringBuilder()

                repeat(50) { roundIndex ->
                    val accumulatedReply = StringBuilder()
                    val response = ChatApiClient.streamChat(
                        settings = settings,
                        messages = workingConversation,
                        onDelta = { delta ->
                            accumulatedReply.append(delta)
                            val displayContent = if (allRoundsContent.isNotBlank()) {
                                "${allRoundsContent}\n\n${accumulatedReply}"
                            } else {
                                accumulatedReply.toString()
                            }
                            updateStreamingAssistantMessage(
                                assistantIndex = assistantIndex,
                                assistantId = assistantId,
                                content = displayContent,
                                intermediateSteps = if (currentThinkingStep.thinkingContent.isNotBlank()) {
                                    accumulatedIntermediateSteps + currentThinkingStep
                                } else {
                                    accumulatedIntermediateSteps
                                }
                            )
                        },
                        onThinkingDelta = { thinkingDelta ->
                            currentThinkingStep = currentThinkingStep.copy(
                                thinkingContent = currentThinkingStep.thinkingContent + thinkingDelta
                            )
                            val displayContent = if (allRoundsContent.isNotBlank()) {
                                "${allRoundsContent}\n\n${accumulatedReply}"
                            } else {
                                accumulatedReply.toString()
                            }
                            updateStreamingAssistantMessage(
                                assistantIndex = assistantIndex,
                                assistantId = assistantId,
                                content = displayContent,
                                intermediateSteps = accumulatedIntermediateSteps + currentThinkingStep
                            )
                        }
                    )

                    if (response.toolCalls.isEmpty()) {
                        val lastRoundReply = response.content.takeIf { it.isNotBlank() }
                            ?: accumulatedReply.toString().takeIf { it.isNotBlank() }
                            ?: ""
                        val combinedReply = if (allRoundsContent.isNotBlank() && lastRoundReply.isNotBlank()) {
                            "${allRoundsContent}\n\n${lastRoundReply}"
                        } else if (allRoundsContent.isNotBlank()) {
                            allRoundsContent.toString()
                        } else {
                            lastRoundReply
                        }
                        val finalReply = combinedReply.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("接口返回内容为空。")
                        val finalThinking = response.thinkingContent.takeIf { it.isNotBlank() }
                            ?: currentThinkingStep.thinkingContent.takeIf { it.isNotBlank() }

                        val finalSteps = accumulatedIntermediateSteps.toMutableList()
                        if (finalThinking != null) {
                            finalSteps += IntermediateStep(thinkingContent = finalThinking)
                        }

                        uiMessagesInternal[assistantIndex] = ChatMessage(
                            id = assistantId,
                            role = ChatRole.Assistant,
                            content = finalReply,
                            intermediateSteps = finalSteps
                        )
                        workingConversation += ChatMessage(
                            role = ChatRole.Assistant,
                            content = finalReply
                        )

                        conversationMessagesInternal.clear()
                        conversationMessagesInternal.addAll(workingConversation)
                        publishMessagesImmediately()
                        persistConversationAsync()
                        return@runCatching finalReply
                    }

                    workingConversation += ChatMessage(
                        role = ChatRole.Assistant,
                        content = response.content,
                        toolCalls = response.toolCalls
                    )

                    val toolResults = withContext(Dispatchers.IO) {
                        response.toolCalls.map { toolCall ->
                            LocalAiTools.executeToolCall(appContext, toolCall)
                        }
                    }
                    workingConversation.addAll(toolResults)

                    val stepThinking = response.thinkingContent.takeIf { it.isNotBlank() }
                        ?: currentThinkingStep.thinkingContent.takeIf { it.isNotBlank() }
                        ?: ""
                    currentThinkingStep = IntermediateStep()
                    val stepContent = response.content.takeIf { it.isNotBlank() } ?: ""
                    if (stepContent.isNotBlank()) {
                        if (allRoundsContent.isNotBlank()) {
                            allRoundsContent.append("\n\n")
                        }
                        allRoundsContent.append(stepContent)
                    }
                    val stepToolResults = toolResults.map { result ->
                        ToolResultInfo(
                            toolCallId = result.toolCallId ?: "",
                            toolName = result.toolName ?: "",
                            toolArguments = result.toolArguments ?: "",
                            result = result.content
                        )
                    }
                    accumulatedIntermediateSteps += IntermediateStep(
                        thinkingContent = stepThinking,
                        content = stepContent,
                        toolResults = stepToolResults
                    )

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
                            ""
                        },
                        intermediateSteps = accumulatedIntermediateSteps.toList()
                    )
                    publishMessagesImmediately()

                    if (waitSeconds != null) {
                        delay(waitSeconds * 1000L)
                    }
                }

                throw IllegalStateException("工具调用次数过多，请缩小问题范围后再试。")
            }

            result.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: "请检查 API 设置、网络连接，或确认接口是否兼容 OpenAI Chat Completions。"
                val currentMessage = uiMessagesInternal.getOrNull(assistantIndex)
                val currentContent = currentMessage?.content.orEmpty()
                val currentSteps = currentMessage?.intermediateSteps ?: emptyList()
                uiMessagesInternal[assistantIndex] = ChatMessage(
                    id = assistantId,
                    role = ChatRole.Assistant,
                    content = if (currentContent.isBlank()) {
                        "请求失败：$message"
                    } else {
                        "$currentContent\n\n[处理失败：$message]"
                    },
                    intermediateSteps = currentSteps
                )
                publishMessagesImmediately()
            }

            cancelPendingStreamingPublish()
            _isSending.value = false
        }
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
                ChatHistoryStore.loadLatestConversation(appContext)
            }.onFailure { error ->
                Log.e("Mote", "加载历史记录失败", error)
            }.getOrDefault(
                SavedConversationState(
                    uiMessages = emptyList(),
                    conversationMessages = emptyList()
                )
            )

            withContext(Dispatchers.Main) {
                uiMessagesInternal.clear()
                uiMessagesInternal.addAll(historyState.uiMessages)
                conversationMessagesInternal.clear()
                conversationMessagesInternal.addAll(historyState.conversationMessages)
                publishMessagesImmediately()
            }
        }
    }

    private suspend fun updateStreamingAssistantMessage(
        assistantIndex: Int,
        assistantId: String,
        content: String,
        intermediateSteps: List<IntermediateStep>
    ) = withContext(Dispatchers.Main) {
        if (assistantIndex !in uiMessagesInternal.indices) {
            return@withContext
        }

        uiMessagesInternal[assistantIndex] = ChatMessage(
            id = assistantId,
            role = ChatRole.Assistant,
            content = content,
            intermediateSteps = intermediateSteps
        )
        scheduleStreamingPublish()
    }

    private fun rebuildConversationFromUiMessages() {
        conversationMessagesInternal.clear()
        conversationMessagesInternal.addAll(uiMessagesInternal.filter { it.role != ChatRole.Tool })
    }

    private fun persistConversationAsync() {
        val settingsSnapshot = _savedSettings.value ?: return
        val uiSnapshot = uiMessagesInternal.toList()
        val conversationSnapshot = conversationMessagesInternal.toList()

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ChatHistoryStore.saveConversation(
                    context = appContext,
                    settings = settingsSnapshot,
                    uiMessages = uiSnapshot,
                    conversationMessages = conversationSnapshot
                )
            }.onFailure { error ->
                Log.e("Mote", "保存聊天记录失败", error)
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
        val SystemPrompt = """
            # 角色定义
            你是一个运行在 Android 设备上的 AI Agent，具备读取本地文件和执行 Shell 命令的权限。

            # 安全与权限控制（最高优先级）
            执行任何高风险 Shell 命令（如 `rm` 删除文件或目录、移动敏感数据等破坏性操作）前，严禁直接执行。你必须先向用户展示将要执行的完整命令并说明潜在风险，明确征求用户同意，仅在获得明确授权后方可执行。

            # 核心工作流：耗时 Shell 命令执行规范
            当判断需要执行耗时较长的 Shell 命令时，必须严格遵守以下执行链路：
            1. 使用 `background` 模式启动该 Shell 命令。
            2. 立即调用 `wait` 工具，根据预估耗时等待适当时间。
            3. 调用 `shell_status` 工具查询任务是否完成。
            4. 若未完成，则重复步骤 2 和步骤 3；若已完成，则读取并分析结果。

            # 约束与注意事项
            - 文件路径以安卓标准的 `/sdcard/` 或应用私有目录为主。
            - 确保命令适用于当前安卓非 Root 环境。
        """.trimIndent()
    }
}
