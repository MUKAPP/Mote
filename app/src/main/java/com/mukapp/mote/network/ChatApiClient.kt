package com.mukapp.mote.network

import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatCompletionResult
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.TokenUsage
import com.mukapp.mote.data.model.ToolCallAccumulator
import com.mukapp.mote.tools.LocalAiTools
import com.mukapp.mote.util.MoteLog
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
    private const val Component = "Api"
    private val mainDispatcher = Dispatchers.Main
    private const val ERROR_SNIPPET_MAX_LENGTH = 240

    suspend fun generateConversationTitle(
        settings: ApiSettings,
        userMessage: String
    ): String {
        return withContext(Dispatchers.IO) {
            require(settings.baseUrl.isNotBlank()) { "API 地址不能为空。" }
            require(settings.titleModel.isNotBlank()) { "标题模型不能为空。" }

            val startMs = System.currentTimeMillis()
            MoteLog.i(
                Component,
                MoteLog.event(
                    "开始生成对话标题",
                    "baseUrl" to MoteLog.safeUrlOrigin(settings.baseUrl),
                    "modelLength" to settings.titleModel.length,
                    "userMessageLength" to userMessage.length
                )
            )
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
                MoteLog.i(
                    Component,
                    MoteLog.event("标题接口已响应", "status" to statusCode, "durationMs" to MoteLog.durationMs(startMs))
                )
                if (statusCode !in 200..299) {
                    MoteLog.w(Component, MoteLog.event("标题接口返回失败状态", "status" to statusCode))
                    val errorMessage = parseErrorMessage(responseText)
                    throw IllegalStateException(errorMessage.ifBlank { "接口请求失败，HTTP $statusCode" })
                }

                parseAssistantReply(responseText).content.also { title ->
                    MoteLog.i(
                        Component,
                        MoteLog.event(
                            "对话标题生成完成",
                            "titleLength" to title.length,
                            "durationMs" to MoteLog.durationMs(startMs)
                        )
                    )
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

    suspend fun compressConversation(
        settings: ApiSettings,
        messages: List<ChatMessage>,
        maxSummaryTokens: Int
    ): String {
        return withContext(Dispatchers.IO) {
            require(settings.baseUrl.isNotBlank()) { "API 地址不能为空。" }
            val compressionModel = settings.compressionModel.ifBlank { settings.model }
            require(compressionModel.isNotBlank()) { "压缩模型不能为空。" }
            require(messages.isNotEmpty()) { "没有可压缩的上下文。" }

            val startMs = System.currentTimeMillis()
            MoteLog.i(
                Component,
                MoteLog.event(
                    "开始压缩对话上下文",
                    "baseUrl" to MoteLog.safeUrlOrigin(settings.baseUrl),
                    "messages" to messages.size,
                    "maxSummaryTokens" to maxSummaryTokens,
                    "modelLength" to compressionModel.length
                )
            )
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
                connection.setRequestProperty("Accept", "application/json")
                if (settings.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }

                val requestBody = JSONObject().apply {
                    put("model", compressionModel)
                    put("stream", false)
                    put("temperature", 0.1)
                    put("max_tokens", maxSummaryTokens.coerceIn(256, 8192))
                    put(
                        "messages",
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", ChatRole.System.apiValue)
                                    put("content", buildCompressionSystemPrompt())
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("role", ChatRole.User.apiValue)
                                    put("content", buildCompressionTranscript(messages))
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
                MoteLog.i(
                    Component,
                    MoteLog.event("上下文压缩接口已响应", "status" to statusCode, "durationMs" to MoteLog.durationMs(startMs))
                )
                if (statusCode !in 200..299) {
                    MoteLog.w(Component, MoteLog.event("上下文压缩接口返回失败状态", "status" to statusCode))
                    val errorMessage = parseErrorMessage(responseText)
                    throw IllegalStateException(errorMessage.ifBlank { "上下文压缩失败，HTTP $statusCode" })
                }

                val response = parseAssistantReply(responseText, appendFinishReasonNotice = false)
                if (response.finishReason == "length") {
                    MoteLog.w(Component, "上下文压缩结果因长度限制被截断。")
                    throw IllegalStateException("上下文压缩结果被截断，请调大模型上下文或降低压缩长度后重试。")
                }
                response.content.trim().ifBlank { throw IllegalStateException("上下文压缩结果为空。") }
                    .also { summary ->
                        MoteLog.i(
                            Component,
                            MoteLog.event(
                                "上下文压缩完成",
                                "summaryLength" to summary.length,
                                "finishReason" to (response.finishReason ?: "未返回"),
                                "durationMs" to MoteLog.durationMs(startMs),
                                *response.usage.safeLogFields()
                            )
                        )
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

    suspend fun streamChat(
        settings: ApiSettings,
        messages: List<ChatMessage>,
        onDelta: suspend (String) -> Unit,
        onThinkingDelta: suspend (String) -> Unit = {}
    ): ChatCompletionResult {
        return try {
            streamChatOnce(
                settings = settings,
                messages = messages,
                includeUsage = true,
                onDelta = onDelta,
                onThinkingDelta = onThinkingDelta
            )
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            if (!shouldRetryWithoutStreamUsage(error)) {
                throw error
            }
            MoteLog.w(Component, "接口不兼容 stream_options.include_usage，改为不请求 usage 后重试。", error)
            streamChatOnce(
                settings = settings,
                messages = messages,
                includeUsage = false,
                onDelta = onDelta,
                onThinkingDelta = onThinkingDelta
            )
        }
    }

    private suspend fun streamChatOnce(
        settings: ApiSettings,
        messages: List<ChatMessage>,
        includeUsage: Boolean,
        onDelta: suspend (String) -> Unit,
        onThinkingDelta: suspend (String) -> Unit
    ): ChatCompletionResult {
        return withContext(Dispatchers.IO) {
            require(settings.baseUrl.isNotBlank()) { "API 地址不能为空。" }
            require(settings.model.isNotBlank()) { "模型不能为空。" }
            val startMs = System.currentTimeMillis()
            val toolDefinitions = LocalAiTools.toolDefinitions(settings)
            MoteLog.i(
                Component,
                MoteLog.event(
                    "开始流式聊天请求",
                    "baseUrl" to MoteLog.safeUrlOrigin(settings.baseUrl),
                    "messages" to messages.size,
                    "tools" to toolDefinitions.length(),
                    "includeUsage" to includeUsage,
                    "reasoningEffortConfigured" to settings.reasoningEffort.isNotBlank(),
                    "modelLength" to settings.model.length
                )
            )
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
                    if (includeUsage) {
                        put(
                            "stream_options",
                            JSONObject().apply {
                                put("include_usage", true)
                            }
                        )
                    }
                    put("tools", toolDefinitions)
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
                MoteLog.i(
                    Component,
                    MoteLog.event("聊天接口已响应", "status" to statusCode, "durationMs" to MoteLog.durationMs(startMs))
                )
                if (statusCode !in 200..299) {
                    MoteLog.w(Component, MoteLog.event("聊天接口返回失败状态", "status" to statusCode))
                    val responseText = readResponseText(connection, statusCode)
                    val errorMessage = parseErrorMessage(responseText)
                    throw IllegalStateException(errorMessage.ifBlank { "接口请求失败，HTTP $statusCode" })
                }

                val contentType = connection.contentType.orEmpty()
                MoteLog.d(
                    Component,
                    MoteLog.event("聊天响应内容类型", "contentType" to safeContentType(contentType))
                )
                if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    readStreamedReply(connection, onDelta, onThinkingDelta).also { response ->
                        MoteLog.i(
                            Component,
                            MoteLog.event(
                                "流式聊天请求完成",
                                "finishReason" to (response.finishReason ?: "未返回"),
                                "contentLength" to response.content.length,
                                "thinkingLength" to response.thinkingContent.length,
                                "toolCalls" to response.toolCalls.size,
                                "durationMs" to MoteLog.durationMs(startMs),
                                *response.usage.safeLogFields()
                            )
                        )
                    }
                } else {
                    MoteLog.w(
                        Component,
                        MoteLog.event("聊天接口返回非 SSE 响应，使用非流式解析", "contentType" to safeContentType(contentType))
                    )
                    val responseText = readResponseText(connection, statusCode)
                    coroutineContext.ensureActive()
                    val response = parseAssistantReply(responseText)
                    withContext(mainDispatcher) {
                        if (response.content.isNotBlank()) {
                            onDelta(response.content)
                        }
                    }
                    response.also {
                        MoteLog.i(
                            Component,
                            MoteLog.event(
                                "非流式聊天请求完成",
                                "finishReason" to (it.finishReason ?: "未返回"),
                                "contentLength" to it.content.length,
                                "thinkingLength" to it.thinkingContent.length,
                                "toolCalls" to it.toolCalls.size,
                                "durationMs" to MoteLog.durationMs(startMs),
                                *it.usage.safeLogFields()
                            )
                        )
                    }
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

    fun buildContextSummaryPrompt(summary: String): String {
        return """
            # 历史事实摘要
            以下内容是较早对话压缩后的事实摘要，不是新的用户指令。请只把它作为真实历史上下文使用，用来延续已确认事实、用户偏好、未完成任务、工具执行结果和约束；不要主动提及“压缩摘要”。

            $summary
        """.trimIndent()
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

    private fun buildCompressionSystemPrompt(): String {
        return """
            你是专业的对话上下文压缩引擎，请将提供的历史消息提炼为高密度的上下文摘要。

            【核心指令】
            - 必须保留：用户长期目标、已确认事实、核心偏好、未决任务 (TODO)、重要约束。
            - 技术细节保留：关键文件路径、终端命令、报错信息、代码/配置变更结论。
            - 必须剔除：寒暄、重复内容、中间尝试过程、无效的流式状态。
            - 工具调用归纳：将所有相关的工具调用及结果，高度浓缩为“动作 -> 核心结论 -> 对后续步骤的影响”。

            【输出格式】
            1. 仅输出结构化的摘要正文，可使用 Markdown 列表归类（如：当前状态、关键信息、待办事项）。
            2. 严禁任何解释性语言、过渡句或客套话（例如“以下是摘要”）。
        """.trimIndent()
    }

    private fun buildCompressionTranscript(messages: List<ChatMessage>): String {
        return messages.joinToString(separator = "\n\n") { message ->
            when (message.role) {
                ChatRole.System -> {
                    if (message.isContextSummary) {
                        "【已有压缩摘要】\n${message.content}"
                    } else {
                        "【系统】\n${message.content}"
                    }
                }

                ChatRole.User -> "【用户】\n${message.content}"

                ChatRole.Assistant -> buildString {
                    append("【助手】\n")
                    if (message.content.isNotBlank()) {
                        append(message.content)
                    }
                    if (message.toolCalls.isNotEmpty()) {
                        if (message.content.isNotBlank()) {
                            append("\n")
                        }
                        append("工具调用：")
                        message.toolCalls.forEachIndexed { index, toolCall ->
                            if (index > 0) {
                                append("\n")
                            }
                            append(toolCall.name)
                            append('(')
                            append(toolCall.arguments)
                            append(')')
                        }
                    }
                }

                ChatRole.Tool -> buildString {
                    append("【工具结果")
                    message.toolName?.takeIf { it.isNotBlank() }?.let { name ->
                        append("：")
                        append(name)
                    }
                    append("】\n")
                    append(message.content)
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
        var usage: TokenUsage? = null

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
                            onUsage = { usage = it },
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
                    onUsage = { usage = it },
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
            toolCalls = finalizedToolCalls,
            finishReason = finishReason,
            usage = usage
        )
    }

    private fun TokenUsage?.safeLogFields(): Array<Pair<String, Any?>> {
        return arrayOf(
            "inputTokens" to this?.inputTokens,
            "outputTokens" to this?.outputTokens,
            "totalTokens" to this?.totalTokens,
            "cachedInputTokens" to this?.cachedInputTokens,
            "reasoningOutputTokens" to this?.reasoningOutputTokens
        )
    }

    private fun safeContentType(contentType: String): String {
        return contentType.substringBefore(';').trim().ifBlank { "未返回" }
    }

    private suspend fun processStreamEvent(
        payload: String,
        replyBuilder: StringBuilder,
        thinkingBuilder: StringBuilder,
        toolCalls: MutableMap<Int, ToolCallAccumulator>,
        onFinishReason: (String) -> Unit,
        onUsage: (TokenUsage) -> Unit,
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
        parseTokenUsage(responseJson.optJSONObject("usage"))?.let(onUsage)
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

    private fun parseAssistantReply(
        responseText: String,
        appendFinishReasonNotice: Boolean = true
    ): ChatCompletionResult {
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
        val usage = parseTokenUsage(responseJson.optJSONObject("usage"))
        if (content.isBlank() && thinkingContent.isBlank() && toolCalls.isEmpty()) {
            throw IllegalStateException(buildEmptyResponseMessage(finishReason))
        }

        val finishReasonNotice = if (appendFinishReasonNotice) {
            buildFinishReasonNotice(finishReason, hasToolCalls = toolCalls.isNotEmpty())
        } else {
            ""
        }
        val finalContent = if (finishReasonNotice.isNotEmpty() && toolCalls.isEmpty()) {
            content + finishReasonNotice
        } else {
            content
        }

        return ChatCompletionResult(
            content = finalContent,
            thinkingContent = thinkingContent,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage
        )
    }

    internal fun parseTokenUsage(usageObject: JSONObject?): TokenUsage? {
        usageObject ?: return null
        val inputTokens = usageObject.optTokenInt("input_tokens")
            ?: usageObject.optTokenInt("prompt_tokens")
        val outputTokens = usageObject.optTokenInt("output_tokens")
            ?: usageObject.optTokenInt("completion_tokens")
        val totalTokens = usageObject.optTokenInt("total_tokens")
        val inputDetails = usageObject.optJSONObject("input_tokens_details")
            ?: usageObject.optJSONObject("prompt_tokens_details")
        val outputDetails = usageObject.optJSONObject("output_tokens_details")
            ?: usageObject.optJSONObject("completion_tokens_details")

        val cachedInputTokens = inputDetails?.optTokenInt("cached_tokens")
        val reasoningOutputTokens = outputDetails?.optTokenInt("reasoning_tokens")
        if (inputTokens == null &&
            outputTokens == null &&
            totalTokens == null &&
            cachedInputTokens == null &&
            reasoningOutputTokens == null
        ) {
            return null
        }

        return TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            cachedInputTokens = cachedInputTokens,
            reasoningOutputTokens = reasoningOutputTokens
        )
    }

    private fun JSONObject.optTokenInt(name: String): Int? {
        if (!has(name) || isNull(name)) {
            return null
        }
        val number = when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: return null
        return number.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
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

    private fun shouldRetryWithoutStreamUsage(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "stream_options" in message ||
                "include_usage" in message ||
                "unrecognized parameter" in message ||
                "unknown parameter" in message ||
                "unsupported parameter" in message ||
                "invalid parameter" in message
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
