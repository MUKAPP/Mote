package com.mukapp.mote.network

import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatCompletionResult
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ToolCallAccumulator
import com.mukapp.mote.tools.LocalAiTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ChatApiClient {
    private val mainDispatcher = Dispatchers.Main
    private const val ERROR_SNIPPET_MAX_LENGTH = 240

    suspend fun generateConversationTitle(
        settings: ApiSettings,
        userMessage: String
    ): String {
        return withContext(Dispatchers.IO) {
            require(settings.baseUrl.isNotBlank()) { "API 地址不能为空。" }
            require(settings.titleModel.isNotBlank()) { "标题模型不能为空。" }

            val connection = (URL(resolveChatUrl(settings.baseUrl)).openConnection() as HttpURLConnection)
            val coroutineContext = currentCoroutineContext()
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion {
                runCatching { connection.disconnect() }
            }
            try {
                coroutineContext.ensureActive()
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                if (settings.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }

                val requestBody = JSONObject().apply {
                    put("model", settings.titleModel)
                    put("stream", false)
                    put("temperature", 0.2)
                    put("max_tokens", 48)
                    put(
                        "messages",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", ChatRole.System.apiValue)
                                    put(
                                        "content",
                                        """
                                        请根据用户的首条消息提炼一个简短标题。严格遵守以下规则：
                                        1. 语言：必须与用户消息的语言完全一致。
                                        2. 长度：中文最多12个字，其他语言最多6个单词。
                                        3. 格式：直接输出标题文本，严禁包含任何解释、引导语、标点符号包裹（如引号）或Markdown格式。
                                        """.trimIndent()
                                    )
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("role", ChatRole.User.apiValue)
                                    put("content", userMessage)
                                }
                            )
                        }
                    )
                }.toString()

                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                }

                coroutineContext.ensureActive()
                val statusCode = connection.responseCode
                val responseText = readResponseText(connection, statusCode)
                if (statusCode !in 200..299) {
                    val errorMessage = parseErrorMessage(responseText)
                    throw IllegalStateException(errorMessage.ifBlank { "接口请求失败，HTTP $statusCode" })
                }

                parseAssistantReply(responseText).content
            } catch (error: Throwable) {
                coroutineContext.ensureActive()
                throw error
            } finally {
                cancellationHandle?.dispose()
                runCatching { connection.disconnect() }
            }
        }
    }

    suspend fun streamChat(
        settings: ApiSettings,
        messages: List<ChatMessage>,
        onDelta: suspend (String) -> Unit,
        onThinkingDelta: suspend (String) -> Unit = {}
    ): ChatCompletionResult {
        return withContext(Dispatchers.IO) {
            val connection = (URL(resolveChatUrl(settings.baseUrl)).openConnection() as HttpURLConnection)
            val coroutineContext = currentCoroutineContext()
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion {
                runCatching { connection.disconnect() }
            }
            try {
                coroutineContext.ensureActive()
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "text/event-stream")
                if (settings.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }

                val requestBody = JSONObject().apply {
                    put("model", settings.model)
                    put("stream", true)
                    put("tools", LocalAiTools.toolDefinitions(settings))
                    if (settings.reasoningEffort.isNotBlank()) {
                        put("reasoning_effort", settings.reasoningEffort)
                    }
                    put(
                        "messages",
                        JSONArray().apply {
                            messages.forEach { message ->
                                put(buildApiMessage(message))
                            }
                        }
                    )
                }.toString()

                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                }

                coroutineContext.ensureActive()
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    val responseText = readResponseText(connection, statusCode)
                    val errorMessage = parseErrorMessage(responseText)
                    throw IllegalStateException(errorMessage.ifBlank { "接口请求失败，HTTP $statusCode" })
                }

                val contentType = connection.contentType.orEmpty()
                if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    readStreamedReply(connection, onDelta, onThinkingDelta)
                } else {
                    val responseText = readResponseText(connection, statusCode)
                    coroutineContext.ensureActive()
                    val response = parseAssistantReply(responseText)
                    withContext(mainDispatcher) {
                        if (response.content.isNotBlank()) {
                            onDelta(response.content)
                        }
                    }
                    response
                }
            } catch (error: Throwable) {
                coroutineContext.ensureActive()
                throw error
            } finally {
                cancellationHandle?.dispose()
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun resolveChatUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "API 地址不能为空。" }
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "API 地址需要以 http:// 或 https:// 开头。"
        }
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    private fun readResponseText(connection: HttpURLConnection, statusCode: Int): String {
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    }

    private fun buildApiMessage(message: ChatMessage): JSONObject {
        return JSONObject().apply {
            put("role", message.role.apiValue)
            when (message.role) {
                ChatRole.Tool -> {
                    put("content", message.content)
                    put("tool_call_id", message.toolCallId)
                }

                ChatRole.Assistant -> {
                    put("content", message.content.ifEmpty { JSONObject.NULL })
                    if (message.toolCalls.isNotEmpty()) {
                        put(
                            "tool_calls",
                            JSONArray().apply {
                                message.toolCalls.forEach { toolCall ->
                                    put(
                                        JSONObject().apply {
                                            put("id", toolCall.id)
                                            put("type", "function")
                                            put(
                                                "function",
                                                JSONObject().apply {
                                                    put("name", toolCall.name)
                                                    put("arguments", toolCall.arguments)
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    }
                }

                else -> {
                    put("content", message.content)
                }
            }
        }
    }

    private suspend fun readStreamedReply(
        connection: HttpURLConnection,
        onDelta: suspend (String) -> Unit,
        onThinkingDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        val coroutineContext = currentCoroutineContext()
        val replyBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        var streamFinished = false
        var finishReason: String? = null
        val toolCalls = linkedMapOf<Int, ToolCallAccumulator>()

        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val eventPayload = StringBuilder()
            while (true) {
                coroutineContext.ensureActive()
                val line = reader.readLine() ?: break
                if (line.isBlank()) {
                    if (eventPayload.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        streamFinished = processStreamEvent(
                            payload = eventPayload.toString(),
                            replyBuilder = replyBuilder,
                            thinkingBuilder = thinkingBuilder,
                            toolCalls = toolCalls,
                            onFinishReason = { finishReason = it },
                            onDelta = onDelta,
                            onThinkingDelta = onThinkingDelta
                        )
                        eventPayload.clear()
                        if (streamFinished) {
                            break
                        }
                    }
                    continue
                }

                if (line.startsWith("data:")) {
                    if (eventPayload.isNotEmpty()) {
                        eventPayload.append('\n')
                    }
                    eventPayload.append(line.substringAfter("data:").trimStart())
                }
            }

            if (!streamFinished && eventPayload.isNotEmpty()) {
                coroutineContext.ensureActive()
                processStreamEvent(
                    payload = eventPayload.toString(),
                    replyBuilder = replyBuilder,
                    thinkingBuilder = thinkingBuilder,
                    toolCalls = toolCalls,
                    onFinishReason = { finishReason = it },
                    onDelta = onDelta,
                    onThinkingDelta = onThinkingDelta
                )
            }
        }

        val content = replyBuilder.toString()
        val thinkingContent = thinkingBuilder.toString()
        val finalizedToolCalls = toolCalls
            .toSortedMap()
            .values
            .mapNotNull { accumulator ->
                val id = accumulator.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = accumulator.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AiToolCall(
                    id = id,
                    name = name,
                    arguments = accumulator.arguments.toString()
                )
            }

        if (content.isBlank() && thinkingContent.isBlank() && finalizedToolCalls.isEmpty()) {
            throw IllegalStateException(buildEmptyResponseMessage(finishReason))
        }

        val finishReasonNotice = buildFinishReasonNotice(finishReason, hasToolCalls = finalizedToolCalls.isNotEmpty())
        if (finishReasonNotice.isNotEmpty() && finalizedToolCalls.isEmpty()) {
            withContext(mainDispatcher) {
                onDelta(finishReasonNotice)
            }
            replyBuilder.append(finishReasonNotice)
        }

        return ChatCompletionResult(
            content = replyBuilder.toString(),
            thinkingContent = thinkingContent,
            toolCalls = finalizedToolCalls
        )
    }

    private suspend fun processStreamEvent(
        payload: String,
        replyBuilder: StringBuilder,
        thinkingBuilder: StringBuilder,
        toolCalls: MutableMap<Int, ToolCallAccumulator>,
        onFinishReason: (String) -> Unit,
        onDelta: suspend (String) -> Unit,
        onThinkingDelta: suspend (String) -> Unit
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val normalizedPayload = payload.trim()
        if (normalizedPayload.isEmpty()) {
            return false
        }
        if (normalizedPayload == "[DONE]") {
            return true
        }

        val responseJson = parseStreamPayload(normalizedPayload)
        val choices = responseJson.optJSONArray("choices") ?: return false
        val firstChoice = choices.optJSONObject(0) ?: return false
        val finishReason = firstChoice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        if (finishReason != null) {
            onFinishReason(finishReason)
        }
        val deltaObject = firstChoice.optJSONObject("delta")
        val toolCallsArray = deltaObject?.optJSONArray("tool_calls")
        if (toolCallsArray != null) {
            appendToolCallDeltas(toolCallsArray, toolCalls)
        }

        val thinkingDelta = deltaObject?.let { extractMessageContent(it.opt("reasoning_content")) }.orEmpty()
        if (thinkingDelta.isNotEmpty()) {
            thinkingBuilder.append(thinkingDelta)
            currentCoroutineContext().ensureActive()
            onThinkingDelta(thinkingDelta)
        }

        val deltaText = when {
            deltaObject != null -> extractMessageContent(deltaObject.opt("content"))
            firstChoice.has("text") -> firstChoice.optString("text")
            else -> ""
        }

        if (deltaText.isNotEmpty()) {
            replyBuilder.append(deltaText)
            currentCoroutineContext().ensureActive()
            onDelta(deltaText)
        }
        return false
    }

    private fun parseStreamPayload(payload: String): JSONObject {
        return runCatching { JSONObject(payload) }.getOrElse { error ->
            throw IllegalStateException(
                "流式响应解析失败，payload 片段：${truncateForError(payload)}",
                error
            )
        }
    }

    private fun appendToolCallDeltas(
        toolCallsArray: JSONArray,
        accumulators: MutableMap<Int, ToolCallAccumulator>
    ) {
        for (index in 0 until toolCallsArray.length()) {
            val item = toolCallsArray.optJSONObject(index) ?: continue
            val itemIndex = item.optInt("index", index)
            val accumulator = accumulators.getOrPut(itemIndex) { ToolCallAccumulator() }

            val id = item.optString("id")
            if (id.isNotBlank()) {
                accumulator.id = id
            }

            val functionObject = item.optJSONObject("function")
            val name = functionObject?.optString("name").orEmpty()
            if (name.isNotBlank()) {
                accumulator.name = name
            }

            val argumentsDelta = functionObject?.optString("arguments").orEmpty()
            if (argumentsDelta.isNotEmpty()) {
                accumulator.arguments.append(argumentsDelta)
            }
        }
    }

    private fun parseAssistantReply(responseText: String): ChatCompletionResult {
        val responseJson = JSONObject(responseText)
        val choices = responseJson.optJSONArray("choices")
            ?: throw IllegalStateException("接口返回缺少 choices 字段。")
        if (choices.length() == 0) {
            throw IllegalStateException("接口没有返回任何候选结果。")
        }

        val firstChoice = choices.getJSONObject(0)
        val messageObject = firstChoice.optJSONObject("message")
        val toolCalls = parseToolCalls(messageObject?.optJSONArray("tool_calls"))
        val content = when {
            messageObject != null -> extractMessageContent(messageObject.opt("content"))
            firstChoice.has("text") -> firstChoice.optString("text")
            else -> ""
        }

        val thinkingContent = messageObject?.let { extractMessageContent(it.opt("reasoning_content")) }.orEmpty()
        val finishReason = firstChoice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        if (content.isBlank() && thinkingContent.isBlank() && toolCalls.isEmpty()) {
            throw IllegalStateException(buildEmptyResponseMessage(finishReason))
        }

        val finishReasonNotice = buildFinishReasonNotice(finishReason, hasToolCalls = toolCalls.isNotEmpty())
        val finalContent = if (finishReasonNotice.isNotEmpty() && toolCalls.isEmpty()) {
            content + finishReasonNotice
        } else {
            content
        }

        return ChatCompletionResult(
            content = finalContent,
            thinkingContent = thinkingContent,
            toolCalls = toolCalls
        )
    }

    private fun parseToolCalls(toolCallsArray: JSONArray?): List<AiToolCall> {
        if (toolCallsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until toolCallsArray.length()) {
                val item = toolCallsArray.optJSONObject(index) ?: continue
                val functionObject = item.optJSONObject("function") ?: continue
                val id = item.optString("id")
                val name = functionObject.optString("name")
                val arguments = functionObject.optString("arguments")
                if (id.isBlank() || name.isBlank()) {
                    continue
                }
                add(
                    AiToolCall(
                        id = id,
                        name = name,
                        arguments = arguments
                    )
                )
            }
        }
    }

    private fun extractMessageContent(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    when (val item = content.opt(index)) {
                        is String -> append(item)
                        is JSONObject -> {
                            val text = item.optString("text")
                            if (text.isNotBlank()) {
                                append(text)
                            }
                        }
                    }
                }
            }

            else -> ""
        }
    }

    private fun parseErrorMessage(responseText: String): String {
        val trimmedResponse = responseText.trim()
        if (trimmedResponse.isBlank()) {
            return "接口请求失败，服务器返回了空错误响应。"
        }

        return runCatching {
            val root = JSONObject(trimmedResponse)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: "接口请求失败，错误响应缺少可读消息。"
        }.getOrElse {
            "接口请求失败，服务器返回了非 JSON 错误响应：${truncateForError(trimmedResponse)}"
        }.let { truncateForError(it.trim()) }
    }

    private fun buildEmptyResponseMessage(finishReason: String?): String {
        return when (finishReason) {
            "length" -> "接口返回内容为空，模型输出因达到长度限制被截断。"
            "content_filter" -> "接口返回内容为空，模型输出被内容安全策略过滤。"
            null, "stop", "tool_calls" -> "接口返回内容为空。"
            else -> "接口返回内容为空，结束原因：${truncateForError(finishReason)}。"
        }
    }

    private fun buildFinishReasonNotice(finishReason: String?, hasToolCalls: Boolean): String {
        if (finishReason == null || finishReason == "stop" || finishReason == "tool_calls" || hasToolCalls) {
            return ""
        }
        return when (finishReason) {
            "length" -> "\n\n> 提示：模型输出因达到长度限制被截断。"
            "content_filter" -> "\n\n> 提示：模型输出被内容安全策略过滤，部分内容可能缺失。"
            else -> "\n\n> 提示：模型以非标准原因结束：${truncateForError(finishReason)}。"
        }
    }

    private fun truncateForError(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= ERROR_SNIPPET_MAX_LENGTH) {
            compact
        } else {
            compact.take(ERROR_SNIPPET_MAX_LENGTH) + "..."
        }
    }
}
