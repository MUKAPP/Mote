package com.mukapp.mote.data

import android.content.Context
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.IntermediateStep
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.data.model.ToolResultInfo
import com.mukapp.mote.util.toChatRoleOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ChatHistoryStore {
    private const val DirectoryName = "chat_history"
    private const val FileName = "history.json"

    fun saveConversation(
        context: Context,
        settings: ApiSettings,
        uiMessages: List<ChatMessage>,
        conversationMessages: List<ChatMessage>
    ): File {
        val historyDir = File(context.filesDir, DirectoryName)
        if (!historyDir.exists() && !historyDir.mkdirs()) {
            throw IllegalStateException("无法创建聊天记录目录。")
        }

        val historyFile = File(historyDir, FileName)
        val tempFile = File(historyDir, "$FileName.tmp")
        val payload = JSONObject().apply {
            put("baseUrl", settings.baseUrl)
            put("model", settings.model)
            put("uiMessageCount", uiMessages.size)
            put("conversationMessageCount", conversationMessages.size)
            put("uiMessages", serializeMessages(uiMessages))
            put("conversationMessages", serializeMessages(conversationMessages))
        }
        tempFile.writeText(payload.toString(), Charsets.UTF_8)
        if (historyFile.exists()) {
            historyFile.delete()
        }
        if (!tempFile.renameTo(historyFile)) {
            tempFile.copyTo(historyFile, overwrite = true)
            tempFile.delete()
        }
        return historyFile
    }

    fun loadLatestConversation(context: Context): SavedConversationState {
        val historyDir = File(context.filesDir, DirectoryName)
        if (!historyDir.exists() || !historyDir.isDirectory) {
            return SavedConversationState(
                uiMessages = emptyList(),
                conversationMessages = emptyList()
            )
        }

        val historyFile = File(historyDir, FileName)
        if (!historyFile.exists() || !historyFile.isFile) {
            return SavedConversationState(
                uiMessages = emptyList(),
                conversationMessages = emptyList()
            )
        }

        val root = JSONObject(historyFile.readText(Charsets.UTF_8))
        val uiMessageArray = root.optJSONArray("uiMessages")
        val conversationMessageArray = root.optJSONArray("conversationMessages")
        val legacyMessages = root.optJSONArray("messages")

        return when {
            uiMessageArray != null || conversationMessageArray != null -> {
                SavedConversationState(
                    uiMessages = deserializeMessages(uiMessageArray),
                    conversationMessages = deserializeMessages(conversationMessageArray)
                )
            }

            legacyMessages != null -> {
                val legacyList = deserializeMessages(legacyMessages)
                SavedConversationState(
                    uiMessages = legacyList,
                    conversationMessages = legacyList.filter { it.role != ChatRole.Tool }
                )
            }

            else -> SavedConversationState(
                uiMessages = emptyList(),
                conversationMessages = emptyList()
            )
        }
    }

    fun clearConversation(context: Context) {
        val historyFile = File(File(context.filesDir, DirectoryName), FileName)
        if (historyFile.exists() && !historyFile.delete()) {
            throw IllegalStateException("无法删除历史记录文件。")
        }
    }

    private fun serializeMessages(messages: List<ChatMessage>): JSONArray {
        return JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject().apply {
                        put("id", message.id)
                        put("role", message.role.apiValue)
                        put("content", message.content)
                        if (message.thinkingContent.isNotBlank()) {
                            put("thinkingContent", message.thinkingContent)
                        }
                        message.toolCallId?.let { put("toolCallId", it) }
                        message.toolName?.let { put("toolName", it) }
                        message.toolArguments?.let { put("toolArguments", it) }
                        if (message.toolCalls.isNotEmpty()) {
                            put(
                                "toolCalls",
                                JSONArray().apply {
                                    message.toolCalls.forEach { toolCall ->
                                        put(
                                            JSONObject().apply {
                                                put("id", toolCall.id)
                                                put("name", toolCall.name)
                                                put("arguments", toolCall.arguments)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                        if (message.intermediateSteps.isNotEmpty()) {
                            put("intermediateSteps", serializeIntermediateSteps(message.intermediateSteps))
                        }
                    }
                )
            }
        }
    }

    private fun serializeIntermediateSteps(steps: List<IntermediateStep>): JSONArray {
        return JSONArray().apply {
            steps.forEach { step ->
                put(
                    JSONObject().apply {
                        if (step.thinkingContent.isNotBlank()) {
                            put("thinkingContent", step.thinkingContent)
                        }
                        if (step.content.isNotBlank()) {
                            put("content", step.content)
                        }
                        if (step.toolResults.isNotEmpty()) {
                            put(
                                "toolResults",
                                JSONArray().apply {
                                    step.toolResults.forEach { result ->
                                        put(
                                            JSONObject().apply {
                                                put("toolCallId", result.toolCallId)
                                                put("toolName", result.toolName)
                                                put("toolArguments", result.toolArguments)
                                                put("result", result.result)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    private fun deserializeMessages(messageArray: JSONArray?): List<ChatMessage> {
        if (messageArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until messageArray.length()) {
                val item = messageArray.optJSONObject(index) ?: continue
                val role = item.optString("role").toChatRoleOrNull() ?: continue
                add(
                    ChatMessage(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        role = role,
                        content = item.optString("content"),
                        thinkingContent = item.optString("thinkingContent"),
                        toolCallId = item.optString("toolCallId").takeIf { it.isNotBlank() },
                        toolName = item.optString("toolName").takeIf { it.isNotBlank() },
                        toolArguments = item.optString("toolArguments").takeIf { it.isNotBlank() },
                        toolCalls = deserializeToolCalls(item.optJSONArray("toolCalls")),
                        intermediateSteps = deserializeIntermediateSteps(item.optJSONArray("intermediateSteps"))
                    )
                )
            }
        }
    }

    private fun deserializeIntermediateSteps(stepsArray: JSONArray?): List<IntermediateStep> {
        if (stepsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until stepsArray.length()) {
                val item = stepsArray.optJSONObject(index) ?: continue
                add(
                    IntermediateStep(
                        thinkingContent = item.optString("thinkingContent"),
                        content = item.optString("content"),
                        toolResults = deserializeToolResultInfos(item.optJSONArray("toolResults"))
                    )
                )
            }
        }
    }

    private fun deserializeToolResultInfos(resultsArray: JSONArray?): List<ToolResultInfo> {
        if (resultsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until resultsArray.length()) {
                val item = resultsArray.optJSONObject(index) ?: continue
                add(
                    ToolResultInfo(
                        toolCallId = item.optString("toolCallId"),
                        toolName = item.optString("toolName"),
                        toolArguments = item.optString("toolArguments"),
                        result = item.optString("result")
                    )
                )
            }
        }
    }

    private fun deserializeToolCalls(toolCallsArray: JSONArray?): List<AiToolCall> {
        if (toolCallsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until toolCallsArray.length()) {
                val item = toolCallsArray.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) {
                    continue
                }
                add(
                    AiToolCall(
                        id = id,
                        name = name,
                        arguments = item.optString("arguments")
                    )
                )
            }
        }
    }
}
