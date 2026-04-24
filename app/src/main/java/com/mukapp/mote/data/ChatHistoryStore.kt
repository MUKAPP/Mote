package com.mukapp.mote.data

import android.content.Context
import android.util.Log
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.util.toChatRoleOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object ChatHistoryStore {
    private const val DirectoryName = "chat_history"
    private const val ConversationsDirectoryName = "conversations"
    private const val IndexFileName = "index.json"
    private const val LegacyFileName = "history.json"
    private const val LegacyMigrationMarkerFileName = "legacy_migrated.json"
    private const val CorruptedDirectoryName = "corrupted"
    private const val DefaultConversationTitle = "新对话"

    private val summaryCacheLock = Any()
    @Volatile
    private var legacyMigrationChecked: Boolean = false
    @Volatile
    private var cachedConversationSummaries: List<ConversationSummary>? = null

    fun newConversationId(): String = UUID.randomUUID().toString()

    fun saveConversation(
        context: Context,
        settings: ApiSettings,
        conversationId: String,
        title: String,
        uiMessages: List<ChatMessage>,
        conversationMessages: List<ChatMessage>
    ): File {
        val conversationsDir = ensureConversationsDir(context)
        val safeConversationId = conversationId.ifBlank { newConversationId() }
        val historyFile = conversationFile(conversationsDir, safeConversationId)
        val existingRoot = readJsonObjectOrNull(historyFile)
        val now = System.currentTimeMillis()
        val createdAt = existingRoot?.optLong("createdAt", 0L)?.takeIf { it > 0L } ?: now
        val fallbackTitle = buildFallbackTitle(uiMessages)
        val existingTitle = existingRoot?.optString("title").orEmpty()
        val incomingTitle = title.trim()
        val savedTitle = when {
            incomingTitle.isBlank() -> fallbackTitle
            existingTitle.isNotBlank() && existingTitle != fallbackTitle && incomingTitle == fallbackTitle -> existingTitle
            else -> incomingTitle
        }.ifBlank { fallbackTitle }

        val payload = JSONObject().apply {
            put("id", safeConversationId)
            put("title", savedTitle)
            put("createdAt", createdAt)
            put("updatedAt", now)
            put("baseUrl", settings.baseUrl)
            put("model", settings.model)
            put("uiMessageCount", uiMessages.size)
            put("conversationMessageCount", conversationMessages.size)
            put("uiMessages", serializeMessages(uiMessages))
            put("conversationMessages", serializeMessages(conversationMessages))
        }
        writeJsonAtomically(historyFile, payload)
        if (loadCurrentConversationId(context) == safeConversationId) {
            saveCurrentConversationId(
                context = context,
                conversationId = safeConversationId,
                allowMissingConversation = false
            )
        }
        parseConversationSummary(historyFile, payload)?.let { summary ->
            upsertCachedSummary(summary)
        }
        return historyFile
    }

    fun loadCurrentConversation(context: Context): SavedConversationState {
        migrateLegacyConversationIfNeeded(context)

        val currentId = loadCurrentConversationId(context)
        if (currentId.isNotBlank()) {
            val current = loadConversation(context, currentId)
            if (current != null) {
                return current
            }
            val currentFile = conversationFile(ensureConversationsDir(context), currentId)
            if (!currentFile.exists() && isMissingCurrentConversationAllowed(context)) {
                return emptyConversationState(conversationId = currentId)
            }
            saveCurrentConversationId(context, "")
        }

        val latest = listConversations(context).firstOrNull()
        if (latest != null) {
            saveCurrentConversationId(context, latest.id)
            return loadConversation(context, latest.id) ?: emptyConversationState()
        }

        return emptyConversationState(conversationId = newConversationId())
    }

    fun loadLatestConversation(context: Context): SavedConversationState {
        return loadCurrentConversation(context)
    }

    fun loadConversation(context: Context, conversationId: String): SavedConversationState? {
        migrateLegacyConversationIfNeeded(context)

        val file = conversationFile(ensureConversationsDir(context), conversationId)
        if (!file.exists() || !file.isFile) {
            return null
        }

        return deserializeConversation(file, readJsonObjectOrNull(file) ?: return null)
    }

    fun listConversations(context: Context): List<ConversationSummary> {
        migrateLegacyConversationIfNeeded(context)

        return synchronized(summaryCacheLock) {
            cachedConversationSummaries ?: scanConversationSummaries(context).also { summaries ->
                cachedConversationSummaries = summaries
            }
        }
    }

    private fun scanConversationSummaries(context: Context): List<ConversationSummary> {
        val conversationsDir = ensureConversationsDir(context)
        val files = conversationsDir.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()
        return files.mapNotNull { file ->
            val root = readJsonObjectOrNull(file) ?: return@mapNotNull null
            parseConversationSummary(file, root)
        }.sortedByDescending { it.updatedAt }
    }

    private fun upsertCachedSummary(summary: ConversationSummary) {
        synchronized(summaryCacheLock) {
            val summaries = cachedConversationSummaries ?: return
            cachedConversationSummaries = (summaries.filterNot { it.id == summary.id } + summary)
                .sortedByDescending { it.updatedAt }
        }
    }

    private fun removeCachedSummary(conversationId: String) {
        synchronized(summaryCacheLock) {
            val summaries = cachedConversationSummaries ?: return
            cachedConversationSummaries = summaries.filterNot { it.id == conversationId }
        }
    }

    private fun resetCaches() {
        synchronized(summaryCacheLock) {
            legacyMigrationChecked = false
            cachedConversationSummaries = null
        }
    }

    fun saveCurrentConversationId(
        context: Context,
        conversationId: String,
        allowMissingConversation: Boolean = false
    ) {
        val historyDir = ensureHistoryDir(context)
        val indexFile = File(historyDir, IndexFileName)
        writeJsonAtomically(
            indexFile,
            JSONObject().apply {
                put("currentConversationId", conversationId)
                put("allowMissingConversation", allowMissingConversation)
            }
        )
    }

    fun deleteConversation(context: Context, conversationId: String): String? {
        val conversationsDir = ensureConversationsDir(context)
        val file = conversationFile(conversationsDir, conversationId)
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("无法删除对话记录文件。")
        }
        removeCachedSummary(conversationId)

        val replacementId = listConversations(context).firstOrNull { it.id != conversationId }?.id
        val currentId = loadCurrentConversationId(context)
        if (currentId == conversationId || currentId.isBlank()) {
            saveCurrentConversationId(context, replacementId.orEmpty())
        }
        return replacementId
    }

    fun updateConversationTitle(context: Context, conversationId: String, title: String): Boolean {
        val normalizedTitle = title.trim().takeIf { it.isNotBlank() } ?: return false
        val conversationsDir = ensureConversationsDir(context)
        val file = conversationFile(conversationsDir, conversationId)
        val root = readJsonObjectOrNull(file) ?: return false
        root.put("title", normalizeTitle(normalizedTitle))
        writeJsonAtomically(file, root)
        parseConversationSummary(file, root)?.let { summary ->
            upsertCachedSummary(summary)
        }
        return true
    }

    fun clearConversation(context: Context) {
        val historyDir = File(context.filesDir, DirectoryName)
        if (!historyDir.exists()) {
            resetCaches()
            return
        }
        if (!historyDir.deleteRecursively()) {
            throw IllegalStateException("无法删除历史记录目录。")
        }
        resetCaches()
    }

    private fun migrateLegacyConversationIfNeeded(context: Context) {
        if (legacyMigrationChecked) {
            return
        }

        synchronized(summaryCacheLock) {
            if (legacyMigrationChecked) {
                return
            }

            val historyDir = ensureHistoryDir(context)
            val conversationsDir = ensureConversationsDir(context)
            val migrationMarkerFile = File(historyDir, LegacyMigrationMarkerFileName)
            val hasConversationFiles = conversationsDir.listFiles { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true)
            }?.isNotEmpty() == true
            if (hasConversationFiles || migrationMarkerFile.exists()) {
                legacyMigrationChecked = true
                return
            }

            val legacyFile = File(historyDir, LegacyFileName)
            if (!legacyFile.exists() || !legacyFile.isFile) {
                legacyMigrationChecked = true
                return
            }

            val legacyRoot = readJsonObjectOrNull(legacyFile)
            if (legacyRoot == null) {
                legacyMigrationChecked = true
                return
            }

            val legacyState = deserializeLegacyConversation(legacyRoot)
            if (legacyState.uiMessages.isEmpty() && legacyState.conversationMessages.isEmpty()) {
                writeJsonAtomically(
                    migrationMarkerFile,
                    JSONObject().apply {
                        put("migratedAt", System.currentTimeMillis())
                        put("empty", true)
                    }
                )
                legacyMigrationChecked = true
                cachedConversationSummaries = null
                return
            }

            val conversationId = newConversationId()
            val legacyTitle = buildFallbackTitle(legacyState.uiMessages)
            val timestamp = legacyFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val migratedRoot = JSONObject().apply {
                put("id", conversationId)
                put("title", legacyTitle)
                put("createdAt", timestamp)
                put("updatedAt", timestamp)
                put("baseUrl", legacyRoot.optString("baseUrl"))
                put("model", legacyRoot.optString("model"))
                put("uiMessageCount", legacyState.uiMessages.size)
                put("conversationMessageCount", legacyState.conversationMessages.size)
                put("uiMessages", serializeMessages(legacyState.uiMessages))
                put("conversationMessages", serializeMessages(legacyState.conversationMessages))
            }
            writeJsonAtomically(conversationFile(conversationsDir, conversationId), migratedRoot)
            if (loadCurrentConversationId(context).isBlank()) {
                saveCurrentConversationId(context, conversationId)
            }
            writeJsonAtomically(
                migrationMarkerFile,
                JSONObject().apply {
                    put("migratedAt", System.currentTimeMillis())
                    put("conversationId", conversationId)
                }
            )
            legacyMigrationChecked = true
            cachedConversationSummaries = null
        }
    }

    private fun deserializeLegacyConversation(root: JSONObject): SavedConversationState {
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

            else -> emptyConversationState()
        }
    }

    private fun deserializeConversation(file: File, root: JSONObject): SavedConversationState {
        val uiMessages = deserializeMessages(root.optJSONArray("uiMessages"))
        val conversationMessages = deserializeMessages(root.optJSONArray("conversationMessages"))
        val conversationId = file.nameWithoutExtension
        return SavedConversationState(
            uiMessages = uiMessages,
            conversationMessages = conversationMessages,
            conversationId = conversationId,
            title = root.optString("title").ifBlank { buildFallbackTitle(uiMessages) }
        )
    }

    private fun parseConversationSummary(file: File, root: JSONObject): ConversationSummary? {
        val conversationId = file.nameWithoutExtension
        if (conversationId.isBlank()) {
            return null
        }
        val uiMessages = root.optJSONArray("uiMessages")
        val messageCount = root.optInt("uiMessageCount", uiMessages?.length() ?: 0)
        return ConversationSummary(
            id = conversationId,
            title = root.optString("title").ifBlank {
                buildFallbackTitle(deserializeMessages(uiMessages))
            },
            createdAt = root.optLong("createdAt", file.lastModified()).takeIf { it > 0L }
                ?: file.lastModified(),
            updatedAt = root.optLong("updatedAt", file.lastModified()).takeIf { it > 0L }
                ?: file.lastModified(),
            messageCount = messageCount
        )
    }

    private fun loadCurrentConversationId(context: Context): String {
        val indexFile = File(ensureHistoryDir(context), IndexFileName)
        return readJsonObjectOrNull(indexFile)?.optString("currentConversationId").orEmpty()
    }

    private fun isMissingCurrentConversationAllowed(context: Context): Boolean {
        val indexFile = File(ensureHistoryDir(context), IndexFileName)
        return readJsonObjectOrNull(indexFile)?.optBoolean("allowMissingConversation", false) == true
    }

    private fun ensureHistoryDir(context: Context): File {
        val historyDir = File(context.filesDir, DirectoryName)
        if (!historyDir.exists() && !historyDir.mkdirs()) {
            throw IllegalStateException("无法创建聊天记录目录。")
        }
        return historyDir
    }

    private fun ensureConversationsDir(context: Context): File {
        val conversationsDir = File(ensureHistoryDir(context), ConversationsDirectoryName)
        if (!conversationsDir.exists() && !conversationsDir.mkdirs()) {
            throw IllegalStateException("无法创建对话记录目录。")
        }
        return conversationsDir
    }

    private fun conversationFile(conversationsDir: File, conversationId: String): File {
        return File(conversationsDir, "${conversationId.ifBlank { newConversationId() }}.json")
    }

    private fun readJsonObjectOrNull(file: File): JSONObject? {
        if (!file.exists() || !file.isFile) {
            return null
        }
        return runCatching {
            JSONObject(file.readText(Charsets.UTF_8))
        }.onFailure { error ->
            Log.e("Mote", "读取历史记录失败：${file.name}", error)
            quarantineCorruptedJsonFile(file)
        }.getOrNull()
    }

    private fun quarantineCorruptedJsonFile(file: File) {
        if (!file.extension.equals("json", ignoreCase = true)) {
            return
        }

        val parent = file.parentFile ?: return
        if (parent.name == CorruptedDirectoryName || file.name == IndexFileName) {
            return
        }

        val corruptedDir = File(parent, CorruptedDirectoryName)
        if (!corruptedDir.exists() && !corruptedDir.mkdirs()) {
            Log.e("Mote", "无法创建损坏历史隔离目录：${corruptedDir.path}")
            return
        }

        val quarantinedFile = File(
            corruptedDir,
            "${file.nameWithoutExtension}.${System.currentTimeMillis()}.corrupt.json"
        )
        if (!file.renameTo(quarantinedFile)) {
            Log.e("Mote", "无法隔离损坏历史记录：${file.path}")
        }
    }

    private fun writeJsonAtomically(file: File, payload: JSONObject) {
        val parent = file.parentFile ?: throw IllegalStateException("记录文件路径无效。")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("无法创建记录目录。")
        }

        val tempFile = File(parent, "${file.name}.${UUID.randomUUID()}.tmp")
        runCatching {
            FileOutputStream(tempFile).use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                    output.fd.sync()
                }
            }
            moveReplacing(tempFile, file)
        }.onFailure { error ->
            tempFile.delete()
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun buildFallbackTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.role == ChatRole.User }
            ?.content
            .orEmpty()
        return normalizeTitle(firstUserMessage).ifBlank { DefaultConversationTitle }
    }

    private fun normalizeTitle(value: String): String {
        val compact = value.trim().replace(Regex("\\s+"), " ")
        return if (compact.length > 24) {
            compact.take(24).trimEnd() + "..."
        } else {
            compact
        }
    }

    private fun emptyConversationState(conversationId: String = ""): SavedConversationState {
        return SavedConversationState(
            uiMessages = emptyList(),
            conversationMessages = emptyList(),
            conversationId = conversationId,
            title = DefaultConversationTitle
        )
    }

    private fun serializeMessages(messages: List<ChatMessage>): JSONArray {
        return JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject().apply {
                        put("id", message.id)
                        put("role", message.role.apiValue)
                        put("content", message.content)
                        message.toolCallId?.let { put("toolCallId", it) }
                        message.toolName?.let { put("toolName", it) }
                        message.toolArguments?.let { put("toolArguments", it) }
                        put("excludeFromConversation", message.excludeFromConversation)
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
                        if (message.assistantParts.isNotEmpty()) {
                            put("assistantParts", serializeAssistantParts(message.assistantParts))
                        }
                    }
                )
            }
        }
    }

    private fun serializeAssistantParts(parts: List<AssistantPart>): JSONArray {
        return JSONArray().apply {
            parts.forEach { part ->
                put(
                    JSONObject().apply {
                        put("id", part.id)
                        when (part) {
                            is AssistantMarkdownPart -> {
                                put("type", "markdown")
                                put("text", part.text)
                            }

                            is AssistantThinkingPart -> {
                                put("type", "thinking")
                                put("text", part.text)
                            }

                            is AssistantToolPart -> {
                                put("type", "tool")
                                put("toolName", part.toolName)
                                put("toolArguments", part.toolArguments)
                                put("result", part.result)
                            }
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
                        toolCallId = item.optString("toolCallId").takeIf { it.isNotBlank() },
                        toolName = item.optString("toolName").takeIf { it.isNotBlank() },
                        toolArguments = item.optString("toolArguments").takeIf { it.isNotBlank() },
                        toolCalls = deserializeToolCalls(item.optJSONArray("toolCalls")),
                        assistantParts = deserializeAssistantParts(item.optJSONArray("assistantParts")),
                        excludeFromConversation = item.optBoolean("excludeFromConversation", false)
                    )
                )
            }
        }
    }

    private fun deserializeAssistantParts(partsArray: JSONArray?): List<AssistantPart> {
        if (partsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until partsArray.length()) {
                val item = partsArray.optJSONObject(index) ?: continue
                val id = item.optString("id", UUID.randomUUID().toString())
                when (item.optString("type")) {
                    "markdown" -> add(
                        AssistantMarkdownPart(
                            id = id,
                            text = item.optString("text")
                        )
                    )

                    "thinking" -> add(
                        AssistantThinkingPart(
                            id = id,
                            text = item.optString("text")
                        )
                    )

                    "tool" -> add(
                        AssistantToolPart(
                            id = id,
                            toolName = item.optString("toolName"),
                            toolArguments = item.optString("toolArguments"),
                            result = item.optString("result")
                        )
                    )
                }
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
