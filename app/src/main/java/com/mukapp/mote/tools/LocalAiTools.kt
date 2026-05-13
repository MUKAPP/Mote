package com.mukapp.mote.tools

import android.content.Context
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.util.optIntOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object LocalAiTools {
    private const val ReadFileToolName = "read_file"
    private const val ListPathToolName = "list_path"
    private const val WebSearchToolName = "web_search"
    private const val ShellToolName = "shell"
    private const val ShellStatusToolName = "shell_status"
    private const val ShellStopToolName = "shell_stop"
    const val WaitToolName = "wait"

    private const val MaxReadLines = 400
    private const val MaxListEntries = 200
    private const val DefaultSearchResultLimit = 5
    private const val MaxSearchResultLimit = 10
    private const val MaxSearchQueryChars = 300
    private const val MaxSearchSnippetChars = 600
    private const val MaxSearchResponseChars = 1_000_000
    private const val ShellShortTimeoutMs = 30_000L
    private const val MaxShellOutputChars = 8000
    private const val ShellConfirmationTtlMs = 10 * 60 * 1000L

    private data class PendingShellConfirmation(
        val id: String,
        val command: String,
        val workDir: String?,
        val background: Boolean,
        val risk: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        @Volatile var active: Boolean = false
    )

    private val pendingShellConfirmations = ConcurrentHashMap<String, PendingShellConfirmation>()

    private val cachedBaseToolDefinitions: JSONArray by lazy {
        JSONArray()
            .put(buildReadFileDefinition())
            .put(buildListPathDefinition())
            .put(buildShellDefinition())
            .put(buildShellStatusDefinition())
            .put(buildShellStopDefinition())
            .put(buildWaitDefinition())
    }

    private val cachedWebSearchToolDefinition: JSONObject by lazy { buildWebSearchDefinition() }

    fun toolDefinitions(settings: ApiSettings = ApiSettings()): JSONArray {
        val definitions = JSONArray(cachedBaseToolDefinitions.toString())
        if (settings.searxngUrl.isNotBlank()) {
            definitions.put(JSONObject(cachedWebSearchToolDefinition.toString()))
        }
        return definitions
    }

    fun executeToolCall(
        context: Context,
        toolCall: AiToolCall,
        settings: ApiSettings = ApiSettings(),
        onShellProcessStarted: ((String, Boolean) -> Unit)? = null
    ): ChatMessage {
        val output = runCatching {
            when (toolCall.name) {
                ReadFileToolName, "read_local_file" -> readFile(toolCall.arguments)
                ListPathToolName -> listPath(toolCall.arguments)
                WebSearchToolName -> webSearch(settings, toolCall.arguments)
                ShellToolName -> runShell(context, toolCall.arguments, onShellProcessStarted)
                ShellStatusToolName -> checkShellStatus(toolCall.arguments)
                ShellStopToolName -> stopShell(toolCall.arguments)
                WaitToolName -> scheduleWait(toolCall.arguments)
                else -> JSONObject().apply {
                    put("ok", false)
                    put("error", "不支持的工具：${toolCall.name}")
                }.toString(2)
            }
        }.getOrElse { error ->
            JSONObject().apply {
                put("ok", false)
                put("error", error.message ?: "工具执行失败")
            }.toString(2)
        }

        return ChatMessage(
            role = ChatRole.Tool,
            content = output,
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            toolArguments = toolCall.arguments
        )
    }

    fun activatePendingShellConfirmation(confirmationId: String): Boolean {
        val now = System.currentTimeMillis()
        val confirmation = pendingShellConfirmations[confirmationId] ?: return false
        if (now - confirmation.createdAtMs > ShellConfirmationTtlMs) {
            pendingShellConfirmations.remove(confirmationId)
            return false
        }
        confirmation.active = true
        return true
    }

    fun discardPendingShellConfirmation(confirmationId: String) {
        pendingShellConfirmations.remove(confirmationId)
    }

    fun isShellConfirmationRequest(message: ChatMessage): Boolean {
        return message.toolName == ShellToolName && isShellConfirmationRequest(message.content)
    }

    fun isShellConfirmationRequest(content: String): Boolean {
        val payload = runCatching { JSONObject(content) }.getOrNull() ?: return false
        return !payload.optBoolean("ok", true) && payload.optBoolean("needs_confirmation", false)
    }

    internal fun readFile(arguments: String): String {
        val payload = JSONObject(arguments)
        val rawPath = payload.optString("path").trim()
        require(rawPath.isNotEmpty()) { "path 不能为空。" }

        val rawFirstLines = payload.optIntOrNull("first_lines")
        val rawStartLine = payload.optIntOrNull("start_line")
        val rawEndLine = payload.optIntOrNull("end_line")
        val hasExplicitRange = rawStartLine != null || rawEndLine != null
        val firstLines = rawFirstLines?.takeIf { it > 0 || !hasExplicitRange }

        val targetFile = File(rawPath).canonicalFile
        require(targetFile.exists() && targetFile.isFile) { "文件不存在。" }
        require(targetFile.canRead()) {
            "文件不可读，当前应用可能没有权限访问该路径。对于外部存储路径，请先在设置页授予文件管理应用权限。"
        }

        // 未提供任何行范围参数时，默认读取前 200 行
        val defaultFirstLines = 200

        val (actualStartLine, actualEndLine) = if (hasExplicitRange) {
            // 模型有时会同时补 first_lines，占位值不应覆盖明确的行号范围。
            val startLine = maxOf(rawStartLine ?: 1, 1)
            val endLine = maxOf(rawEndLine ?: startLine, startLine)
            val requestedLines = endLine - startLine + 1
            require(requestedLines <= MaxReadLines) { "单次读取行数不能超过 $MaxReadLines。" }
            startLine to endLine
        } else if (firstLines != null) {
            require(firstLines > 0) { "first_lines 必须大于 0。" }
            require(firstLines <= MaxReadLines) { "first_lines 不能超过 $MaxReadLines。" }
            1 to firstLines
        } else {
            // 都没提供，默认读取前 defaultFirstLines 行
            1 to defaultFirstLines
        }

        val fileSize = targetFile.length()

        val selectedLines = mutableListOf<String>()
        var lastSeenLine = 0
        var hasMore = false
        targetFile.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            for ((index, line) in sequence.withIndex()) {
                val lineNumber = index + 1
                lastSeenLine = lineNumber
                if (lineNumber in actualStartLine..actualEndLine) {
                    selectedLines += "$lineNumber: $line"
                }
                if (lineNumber > actualEndLine) {
                    hasMore = true
                    break
                }
            }
        }

        val totalLinesKnown = !hasMore

        val returnedEnd = if (selectedLines.isEmpty()) {
            actualStartLine - 1
        } else {
            actualStartLine + selectedLines.size - 1
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", targetFile.path.replace('\\', '/'))
            put("size", fileSize)
            put("total_lines", if (totalLinesKnown) lastSeenLine else JSONObject.NULL)
            put("total_lines_known", totalLinesKnown)
            put("has_more", hasMore)
            put("start", if (selectedLines.isEmpty()) JSONObject.NULL else actualStartLine)
            put("end", if (selectedLines.isEmpty()) JSONObject.NULL else returnedEnd)
            put("lines", selectedLines.size)
            put("content", selectedLines.joinToString(separator = "\n"))
        }.toString(2)
    }

    private fun listPath(arguments: String): String {
        val payload = JSONObject(arguments)
        val rawPath = payload.optString("path").trim()
        require(rawPath.isNotEmpty()) { "path 不能为空。" }

        val limit = payload.optIntOrNull("limit") ?: 100
        require(limit > 0) { "limit 必须大于 0。" }
        require(limit <= MaxListEntries) { "limit 不能超过 $MaxListEntries。" }

        val target = File(rawPath).canonicalFile
        require(target.exists()) { "路径不存在。" }
        require(target.canRead()) {
            "路径不可读，当前应用可能没有权限访问该路径。对于外部存储路径，请先在设置页授予文件管理应用权限。"
        }

        return if (target.isDirectory) {
            val children = target.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                .orEmpty()

            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

            JSONObject().apply {
                put("ok", true)
                put("path", target.path.replace('\\', '/'))
                put("type", "directory")
                put("returned", minOf(children.size, limit))
                put("total", children.size)
                put(
                    "entries",
                    JSONArray().apply {
                        children.take(limit).forEach { child ->
                            put(
                                JSONObject().apply {
                                    put("name", child.name)
                                    put("type", if (child.isDirectory) "dir" else "file")
                                    if (child.isFile) {
                                        put("size", child.length())
                                    }
                                    put("modified", dateFmt.format(Date(child.lastModified())))
                                }
                            )
                        }
                    }
                )
            }.toString(2)
        } else {
            JSONObject().apply {
                put("ok", true)
                put("path", target.path.replace('\\', '/'))
                put("type", "file")
                put("name", target.name)
                put("size", target.length())
                put("parent", target.parentFile?.path?.replace('\\', '/'))
            }.toString(2)
        }
    }

    internal fun webSearch(settings: ApiSettings, arguments: String): String {
        val searxngBaseUrl = settings.searxngUrl.trim()
        require(searxngBaseUrl.isNotEmpty()) { "SearXNG 地址未配置，无法执行搜索。" }

        val payload = JSONObject(arguments)
        val query = payload.optString("query").trim()
        require(query.isNotEmpty()) { "query 不能为空。" }
        require(query.length <= MaxSearchQueryChars) { "query 不能超过 $MaxSearchQueryChars 个字符。" }

        val limit = payload.optIntOrNull("limit") ?: DefaultSearchResultLimit
        require(limit > 0) { "limit 必须大于 0。" }
        require(limit <= MaxSearchResultLimit) { "limit 不能超过 $MaxSearchResultLimit。" }

        val page = payload.optIntOrNull("page") ?: 1
        require(page > 0) { "page 必须大于 0。" }
        require(page <= 20) { "page 不能超过 20。" }

        val searchUrl = buildSearxngSearchUrl(
            baseUrl = searxngBaseUrl,
            query = query,
            page = page,
            language = payload.optString("language").trim().takeIf { it.isNotEmpty() },
            categories = payload.optString("categories").trim().takeIf { it.isNotEmpty() },
            timeRange = payload.optString("time_range").trim().takeIf { it.isNotEmpty() },
            safesearch = payload.optIntOrNull("safesearch")
        )
        val connection = (URL(searchUrl).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mote/1.0")

            val statusCode = connection.responseCode
            val responseText = readHttpResponseText(connection, statusCode, MaxSearchResponseChars)
            if (statusCode !in 200..299) {
                return JSONObject().apply {
                    put("ok", false)
                    put("status", statusCode)
                    put("error", "SearXNG 请求失败，HTTP $statusCode。")
                    put("body", truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }

            val root = runCatching { JSONObject(responseText) }.getOrElse { error ->
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "SearXNG 返回的内容不是有效 JSON：${error.message ?: "解析失败"}")
                    put("body", truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }
            formatSearchResults(query = query, page = page, limit = limit, root = root)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun buildSearxngSearchUrl(
        baseUrl: String,
        query: String,
        page: Int,
        language: String?,
        categories: String?,
        timeRange: String?,
        safesearch: Int?
    ): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        require(normalizedBaseUrl.startsWith("http://") || normalizedBaseUrl.startsWith("https://")) {
            "SearXNG 地址需要以 http:// 或 https:// 开头。"
        }

        val endpoint = if (normalizedBaseUrl.endsWith("/search")) {
            normalizedBaseUrl
        } else {
            "$normalizedBaseUrl/search"
        }
        val params = mutableListOf(
            "q" to query,
            "format" to "json",
            "pageno" to page.toString()
        )
        language?.let { params += "language" to it }
        categories?.let { params += "categories" to it }
        timeRange?.let { params += "time_range" to it }
        safesearch?.let { value ->
            require(value in 0..2) { "safesearch 只能是 0、1 或 2。" }
            params += "safesearch" to value.toString()
        }

        return endpoint + "?" + params.joinToString(separator = "&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private fun readHttpResponseText(connection: HttpURLConnection, statusCode: Int, maxChars: Int): String {
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return ""
        }
        val output = StringBuilder()
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (output.length <= maxChars) {
                val read = reader.read(buffer)
                if (read == -1) {
                    break
                }
                val remaining = maxChars - output.length
                output.append(buffer, 0, minOf(read, remaining.coerceAtLeast(0)))
                if (read > remaining) {
                    break
                }
            }
        }
        return output.toString()
    }

    private fun formatSearchResults(query: String, page: Int, limit: Int, root: JSONObject): String {
        val rawResults = root.optJSONArray("results") ?: JSONArray()
        val results = JSONArray()
        var skipped = 0
        for (index in 0 until rawResults.length()) {
            val item = rawResults.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            if (title.isBlank() && url.isBlank()) {
                skipped++
                continue
            }
            if (results.length() >= limit) {
                skipped++
                continue
            }
            results.put(
                JSONObject().apply {
                    put("title", title)
                    put("url", url)
                    item.optString("content").trim().takeIf { it.isNotEmpty() }?.let { content ->
                        put("content", truncateSearchSnippet(content))
                    }
                    item.optString("engine").trim().takeIf { it.isNotEmpty() }?.let { put("engine", it) }
                    item.optString("category").trim().takeIf { it.isNotEmpty() }?.let { put("category", it) }
                    item.optString("publishedDate").trim().takeIf { it.isNotEmpty() }?.let {
                        put("publishedDate", it)
                    }
                }
            )
        }

        return JSONObject().apply {
            put("ok", true)
            put("query", root.optString("query").takeIf { it.isNotBlank() } ?: query)
            put("page", page)
            put("returned", results.length())
            put("available", root.optInt("number_of_results", rawResults.length()))
            put("has_more", rawResults.length() > results.length() || skipped > 0)
            put("results", results)
            root.optJSONArray("answers")?.takeIf { it.length() > 0 }?.let { put("answers", it) }
            root.optJSONArray("suggestions")?.takeIf { it.length() > 0 }?.let { put("suggestions", it) }
            root.optJSONArray("infoboxes")?.takeIf { it.length() > 0 }?.let { put("infoboxes", it) }
        }.toString(2)
    }

    private fun truncateSearchSnippet(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= MaxSearchSnippetChars) {
            compact
        } else {
            compact.take(MaxSearchSnippetChars) + "..."
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun truncateOutput(text: String, maxChars: Int = MaxShellOutputChars): String {
        if (text.length <= maxChars) {
            return text
        }
        val half = maxChars / 2
        return text.take(half) + "\n... [输出已截断，共 ${text.length} 字符] ...\n" + text.takeLast(half)
    }

    private fun runShell(context: Context, arguments: String, onShellProcessStarted: ((String, Boolean) -> Unit)?): String {
        val payload = JSONObject(arguments)
        val command = payload.optString("command").trim()
        require(command.isNotEmpty()) { "command 不能为空。" }

        val workDir = payload.optString("work_dir").trim().takeIf { it.isNotEmpty() }
        val background = payload.optBoolean("background", false)
        val confirmationId = payload.optString("confirmation_id").trim().takeIf { it.isNotEmpty() }
        val risk = ShellRiskDetector.detect(command)
        if (risk != null && !consumeShellConfirmation(confirmationId, command, workDir, background)) {
            val id = "confirm_${UUID.randomUUID().toString().take(8)}"
            pendingShellConfirmations[id] = PendingShellConfirmation(
                id = id,
                command = command,
                workDir = workDir,
                background = background,
                risk = risk
            )
            return JSONObject().apply {
                put("ok", false)
                put("needs_confirmation", true)
                put("confirmation_id", id)
                put("command", command)
                put("work_dir", workDir ?: JSONObject.NULL)
                put("background", background)
                put("risk", risk)
                put("message", "该 shell 命令可能修改或删除数据。请确认后再继续执行。")
            }.toString(2)
        }

        BusyBoxManager.ensureInitialized(context)
        val id = ShellProcessManager.start(command, workDir, BusyBoxManager.environmentOverrides())
        onShellProcessStarted?.invoke(id, background)

        if (background) {
            return JSONObject().apply {
                put("ok", true)
                put("mode", "background")
                put("id", id)
                put("command", command)
                put("message", "命令已在后台启动，使用 shell_status 查询状态，使用 shell_stop 停止进程")
            }.toString(2)
        }

        val entry = ShellProcessManager.getProcess(id)
            ?: return JSONObject().apply {
                put("ok", false)
                put("error", "进程启动失败")
            }.toString(2)

        val finished = entry.process.waitFor(ShellShortTimeoutMs, TimeUnit.MILLISECONDS)
        if (finished) {
            val stdout: String
            val stderr: String
            synchronized(entry.outputBuffer) { stdout = entry.outputBuffer.toString() }
            synchronized(entry.errorBuffer) { stderr = entry.errorBuffer.toString() }

            // 前台命令已完成，从进程管理器中清理
            ShellProcessManager.remove(id)

            return JSONObject().apply {
                put("ok", true)
                put("mode", "foreground")
                put("exitCode", entry.process.exitValue())
                put("stdout", truncateOutput(stdout))
                put("stderr", truncateOutput(stderr))
            }.toString(2)
        }

        val stdoutSoFar: String
        val stderrSoFar: String
        synchronized(entry.outputBuffer) { stdoutSoFar = entry.outputBuffer.toString() }
        synchronized(entry.errorBuffer) { stderrSoFar = entry.errorBuffer.toString() }

        return JSONObject().apply {
            put("ok", true)
            put("mode", "timeout_to_background")
            put("id", id)
            put("message", "命令在 ${ShellShortTimeoutMs / 1000} 秒内未完成，已转为后台运行")
            put("stdout_so_far", truncateOutput(stdoutSoFar))
            put("stderr_so_far", truncateOutput(stderrSoFar))
        }.toString(2)
    }

    private fun consumeShellConfirmation(
        confirmationId: String?,
        command: String,
        workDir: String?,
        background: Boolean
    ): Boolean {
        val id = confirmationId ?: return false
        val confirmation = pendingShellConfirmations[id] ?: return false
        val now = System.currentTimeMillis()
        if (!confirmation.active || now - confirmation.createdAtMs > ShellConfirmationTtlMs) {
            pendingShellConfirmations.remove(id)
            return false
        }
        if (confirmation.command != command || confirmation.workDir != workDir || confirmation.background != background) {
            return false
        }
        pendingShellConfirmations.remove(id)
        return true
    }

    private fun checkShellStatus(arguments: String): String {
        val payload = JSONObject(arguments)
        val id = payload.optString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空。" }
        return ShellProcessManager.getStatus(id).toString(2)
    }

    private fun stopShell(arguments: String): String {
        val payload = JSONObject(arguments)
        val id = payload.optString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空。" }
        return ShellProcessManager.stop(id).toString(2)
    }

    private fun scheduleWait(arguments: String): String {
        val payload = JSONObject(arguments)
        val seconds = payload.optInt("seconds", 0)
        require(seconds > 0) { "seconds 必须大于 0。" }
        require(seconds <= 3600) { "seconds 不能超过 3600（1 小时）。" }

        return JSONObject().apply {
            put("ok", true)
            put("wait_seconds", seconds)
            put("message", "将在 ${seconds} 秒后继续对话，届时可查询后台进程状态")
        }.toString(2)
    }

    private fun buildReadFileDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", ReadFileToolName)
                    put("description", "按行读取设备上当前应用有权限访问的文本文件内容。行号从 1 开始。如果不提供行范围参数，默认读取前 200 行。读取中间内容时直接提供 start_line/end_line；即使同时误填 first_lines，也会优先按 start_line/end_line 读取。")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "path",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要读取的文件路径，建议传入绝对路径")
                                        }
                                    )
                                    put(
                                        "first_lines",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "读取文件前多少行。只在不提供 start_line/end_line 时生效；如果需要读取中间内容，不要填写此字段。")
                                        }
                                    )
                                    put(
                                        "start_line",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "起始行号，从 1 开始（传入 0 会自动修正为 1）。读取中间内容时优先使用此字段，可与 end_line 一起使用。")
                                        }
                                    )
                                    put(
                                        "end_line",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "结束行号，从 1 开始，且不能小于 start_line。")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("path"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildListPathDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", ListPathToolName)
                    put("description", "列出目录内容，或者返回单个文件的基础信息")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "path",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要查看的目录或文件路径，建议传入绝对路径")
                                        }
                                    )
                                    put(
                                        "limit",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "目录列表最多返回多少项，默认 100，最大 200")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("path"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildWebSearchDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", WebSearchToolName)
                    put("description", "使用用户在设置中配置的 SearXNG 实例搜索互联网。适合查询最新信息、网页资料、新闻或需要来源链接的问题。应用会通过 /search 端点追加 format=json 请求；不要在参数里传入搜索服务地址。")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "query",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "搜索关键词，使用与用户问题最匹配的自然语言或关键词，最大 300 个字符。")
                                        }
                                    )
                                    put(
                                        "limit",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "最多返回多少条结果，默认 5，最大 10。")
                                        }
                                    )
                                    put(
                                        "page",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "搜索结果页码，默认 1，最大 20。")
                                        }
                                    )
                                    put(
                                        "language",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "可选的搜索语言代码，例如 zh-CN、en-US 或 all。")
                                        }
                                    )
                                    put(
                                        "categories",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "可选的 SearXNG 分类，多个分类用英文逗号分隔，例如 general、news、it、science。")
                                        }
                                    )
                                    put(
                                        "time_range",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "可选的时间范围，例如 day、week、month 或 year。")
                                            put("enum", JSONArray().put("day").put("week").put("month").put("year"))
                                        }
                                    )
                                    put(
                                        "safesearch",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "可选的安全搜索等级：0 关闭，1 中等，2 严格。")
                                            put("enum", JSONArray().put(0).put(1).put(2))
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("query"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildShellDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", ShellToolName)
                    put("description", "在设备上执行 shell 命令。短命令会等待完成并返回输出；长命令可设为后台运行，然后用 shell_status 查询状态。如果命令超过 30 秒未完成会自动转为后台运行。")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "command",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要执行的 shell 命令")
                                        }
                                    )
                                    put(
                                        "work_dir",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "工作目录，不提供则使用默认目录")
                                        }
                                    )
                                    put(
                                        "confirmation_id",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "执行高风险命令前由应用内部填充的确认 ID。模型不要自行生成此字段。")
                                        }
                                    )
                                    put(
                                        "background",
                                        JSONObject().apply {
                                            put("type", "boolean")
                                            put("description", "是否在后台运行。长时间运行的命令应设为 true。")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("command"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildShellStatusDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", ShellStatusToolName)
                    put("description", "查询后台 shell 进程的运行状态和输出")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "id",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "shell 命令返回的进程 ID")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("id"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildShellStopDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", ShellStopToolName)
                    put("description", "手动停止一个后台运行的 shell 进程")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "id",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要停止的进程 ID")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("id"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildWaitDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", WaitToolName)
                    put("description", "等待指定秒数后再继续对话。适用于等待后台 shell 命令执行一段时间后查询其状态。")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "seconds",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "等待的秒数，1 到 3600")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("seconds"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildToolCallDescriptionProperty(): JSONObject {
        return JSONObject().apply {
            put("type", "string")
            put(
                "description",
                "对本次工具执行目的的简短描述，会直接展示给用户作为工具标题。请描述要做什么，不要只重复命令或参数。例如：读取 LocalAiTools.kt 前 200 行、列出 app/src/main 目录、执行 Debug 构建并检查结果。"
            )
        }
    }
}
