package com.mukapp.mote.tools

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.optIntOrNull
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object LocalAiTools {
    private const val Component = "Tools"
    private const val ReadFileToolName = "read_file"
    private const val ListPathToolName = "list_path"
    private const val FetchUrlToolName = "fetch_url"
    private const val FetchWebViewToolName = "fetch_webview"
    private const val WebSearchToolName = "web_search"
    private const val ShellToolName = "shell"
    private const val ShellStatusToolName = "shell_status"
    private const val ShellStopToolName = "shell_stop"
    const val WaitToolName = "wait"

    private const val MaxReadLines = 400
    private const val MaxListEntries = 200
    private const val DefaultFetchMaxChars = 20_000
    private const val MaxFetchMaxChars = 100_000
    private const val MaxFetchResponseBytes = 1_000_000
    private const val MaxFetchRedirects = 5
    private const val DefaultWebViewTimeoutSeconds = 20
    private const val MaxWebViewTimeoutSeconds = 60
    private const val DefaultWebViewSettleMs = 1_000
    private const val MaxWebViewSettleMs = 10_000
    private const val MaxWebViewExtractChars = 1_000_000
    private const val DefaultSearchResultLimit = 5
    private const val MaxSearchResultLimit = 10
    private const val MaxTavilySearchResultLimit = 20
    private const val MaxSearchQueryChars = 300
    private const val MaxSearchSnippetChars = 600
    private const val MaxSearchRawContentChars = 4_000
    private const val MaxSearchResponseChars = 1_000_000
    private const val DefaultTavilySearchEndpoint = "https://api.tavily.com/search"
    private const val ShellShortTimeoutMs = 30_000L
    private const val MaxShellOutputChars = 8000
    private const val ShellConfirmationTtlMs = 10 * 60 * 1000L

    private enum class SearchProvider {
        Searxng,
        Tavily
    }

    private data class PendingShellConfirmation(
        val id: String,
        val command: String,
        val workDir: String?,
        val background: Boolean,
        val risk: String,
        val createdAtMs: Long = System.currentTimeMillis(),
        @Volatile var active: Boolean = false
    )

    internal data class WebViewFetchOptions(
        val url: URL,
        val outputFormat: String,
        val maxChars: Int,
        val timeoutSeconds: Int,
        val settleMs: Int
    )

    private data class WebViewExtractedPage(
        val finalUrl: String,
        val title: String,
        val content: String
    )

    private data class MarkdownConversionResult(
        val content: String,
        val converted: Boolean,
        val error: String? = null
    )

    internal var tavilySearchEndpoint: String = DefaultTavilySearchEndpoint

    private val pendingShellConfirmations = ConcurrentHashMap<String, PendingShellConfirmation>()

    internal var htmlToMarkdownConverter: (String) -> String = { html ->
        FlexmarkHtmlConverter.builder().build().convert(html).trim()
    }

    private val cachedBaseToolDefinitions: JSONArray by lazy {
        JSONArray()
            .put(buildReadFileDefinition())
            .put(buildListPathDefinition())
            .put(buildFetchUrlDefinition())
            .put(buildFetchWebViewDefinition())
            .put(buildShellDefinition())
            .put(buildShellStatusDefinition())
            .put(buildShellStopDefinition())
            .put(buildWaitDefinition())
    }

    private val cachedSearxngWebSearchToolDefinition: JSONObject by lazy { buildSearxngWebSearchDefinition() }
    private val cachedTavilyWebSearchToolDefinition: JSONObject by lazy { buildTavilyWebSearchDefinition() }

    fun toolDefinitions(settings: ApiSettings = ApiSettings()): JSONArray {
        val definitions = JSONArray(cachedBaseToolDefinitions.toString())
        val searchProvider = resolveSearchProvider(settings)
        when (searchProvider) {
            SearchProvider.Searxng -> definitions.put(JSONObject(cachedSearxngWebSearchToolDefinition.toString()))
            SearchProvider.Tavily -> definitions.put(JSONObject(cachedTavilyWebSearchToolDefinition.toString()))
            null -> Unit
        }
        MoteLog.d(
            Component,
            MoteLog.event(
                "已构建工具定义",
                "tools" to definitions.length(),
                "webSearchProvider" to (searchProvider?.name ?: "未启用")
            )
        )
        return definitions
    }

    fun executeToolCall(
        context: Context,
        toolCall: AiToolCall,
        settings: ApiSettings = ApiSettings(),
        onShellProcessStarted: ((String, Boolean) -> Unit)? = null
    ): ChatMessage {
        val startMs = System.currentTimeMillis()
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始执行工具",
                "tool" to toolCall.name,
                "toolCallId" to MoteLog.shortId(toolCall.id),
                "argumentKeys" to safeArgumentKeys(toolCall.arguments).joinToString(separator = "+")
            )
        )
        val output = runCatching {
            when (toolCall.name) {
                ReadFileToolName, "read_local_file" -> readFile(toolCall.arguments)
                ListPathToolName -> listPath(toolCall.arguments)
                FetchUrlToolName -> fetchUrl(toolCall.arguments)
                FetchWebViewToolName -> fetchWebView(context, toolCall.arguments)
                WebSearchToolName -> webSearch(settings, toolCall.arguments)
                ShellToolName -> runShell(context, toolCall.arguments, onShellProcessStarted)
                ShellStatusToolName -> checkShellStatus(toolCall.arguments)
                ShellStopToolName -> stopShell(toolCall.arguments)
                WaitToolName -> scheduleWait(toolCall.arguments)
                else -> JSONObject().apply {
                    MoteLog.w(Component, MoteLog.event("收到不支持的工具", "tool" to toolCall.name))
                    put("ok", false)
                    put("error", "不支持的工具：${toolCall.name}")
                }.toString(2)
            }
        }.onFailure { error ->
            MoteLog.w(
                Component,
                MoteLog.event(
                    "工具执行异常",
                    "tool" to toolCall.name,
                    "toolCallId" to MoteLog.shortId(toolCall.id),
                    "error" to error
                )
            )
        }.getOrElse { error ->
            JSONObject().apply {
                put("ok", false)
                put("error", error.message ?: "工具执行失败")
            }.toString(2)
        }

        MoteLog.i(
            Component,
            MoteLog.event(
                "工具执行完成",
                "tool" to toolCall.name,
                "toolCallId" to MoteLog.shortId(toolCall.id),
                "durationMs" to MoteLog.durationMs(startMs),
                *safeToolResultFields(output)
            )
        )

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
        val confirmation = pendingShellConfirmations[confirmationId] ?: run {
            MoteLog.w(
                Component,
                MoteLog.event("Shell 确认令牌不存在", "confirmationId" to MoteLog.shortId(confirmationId))
            )
            return false
        }
        if (now - confirmation.createdAtMs > ShellConfirmationTtlMs) {
            pendingShellConfirmations.remove(confirmationId)
            MoteLog.w(
                Component,
                MoteLog.event("Shell 确认令牌已过期", "confirmationId" to MoteLog.shortId(confirmationId))
            )
            return false
        }
        confirmation.active = true
        MoteLog.i(
            Component,
            MoteLog.event(
                "Shell 确认令牌已激活",
                "confirmationId" to MoteLog.shortId(confirmationId),
                "background" to confirmation.background,
                "risk" to confirmation.risk
            )
        )
        return true
    }

    fun discardPendingShellConfirmation(confirmationId: String) {
        pendingShellConfirmations.remove(confirmationId)?.let { confirmation ->
            MoteLog.i(
                Component,
                MoteLog.event(
                    "Shell 确认令牌已丢弃",
                    "confirmationId" to MoteLog.shortId(confirmationId),
                    "background" to confirmation.background,
                    "risk" to confirmation.risk
                )
            )
        }
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

        MoteLog.d(
            Component,
            MoteLog.event(
                "读取文件工具完成",
                "pathHash" to MoteLog.fingerprint(targetFile.path),
                "size" to fileSize,
                "startLine" to actualStartLine,
                "endLine" to actualEndLine,
                "returnedLines" to selectedLines.size,
                "hasMore" to hasMore
            )
        )
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

            MoteLog.d(
                Component,
                MoteLog.event(
                    "列出目录工具完成",
                    "pathHash" to MoteLog.fingerprint(target.path),
                    "returned" to minOf(children.size, limit),
                    "total" to children.size
                )
            )
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
            MoteLog.d(
                Component,
                MoteLog.event(
                    "查询文件信息工具完成",
                    "pathHash" to MoteLog.fingerprint(target.path),
                    "size" to target.length()
                )
            )
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

    internal fun fetchUrl(arguments: String): String {
        val payload = JSONObject(arguments)
        val rawUrl = payload.optString("url").trim()
        require(rawUrl.isNotEmpty()) { "url 不能为空。" }
        val outputFormat = parseFetchOutputFormat(payload)
        val maxChars = parseFetchMaxChars(payload)
        val initialUrl = normalizeFetchUrl(rawUrl)
        return fetchUrlWithRedirects(initialUrl = initialUrl, outputFormat = outputFormat, maxChars = maxChars)
    }

    private fun parseFetchOutputFormat(payload: JSONObject): String {
        val outputFormat = payload.optString("output_format", "text")
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "text" }
        require(outputFormat in setOf("text", "raw", "markdown")) {
            "output_format 只能是 text、raw 或 markdown。"
        }
        return outputFormat
    }

    private fun parseFetchMaxChars(payload: JSONObject): Int {
        val maxChars = payload.optIntOrNull("max_chars") ?: DefaultFetchMaxChars
        require(maxChars > 0) { "max_chars 必须大于 0。" }
        require(maxChars <= MaxFetchMaxChars) { "max_chars 不能超过 $MaxFetchMaxChars。" }
        return maxChars
    }

    private fun fetchUrlWithRedirects(initialUrl: URL, outputFormat: String, maxChars: Int): String {
        val startMs = System.currentTimeMillis()
        var currentUrl = initialUrl
        val redirects = JSONArray()
        repeat(MaxFetchRedirects + 1) { redirectCount ->
            val connection = (currentUrl.openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", buildFetchAcceptHeader(outputFormat))
                connection.setRequestProperty("User-Agent", "Mote/1.0")

                val statusCode = connection.responseCode
                if (statusCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        MoteLog.w(
                            Component,
                            MoteLog.event(
                                "fetch_url 重定向缺少 Location",
                                "status" to statusCode,
                                "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                                "redirects" to redirects.length(),
                                "durationMs" to MoteLog.durationMs(startMs)
                            )
                        )
                        return JSONObject().apply {
                            put("ok", false)
                            put("url", initialUrl.toString())
                            put("final_url", currentUrl.toString())
                            put("status", statusCode)
                            put("error", "重定向响应缺少 Location。")
                            put("redirects", redirects)
                        }.toString(2)
                    }
                    if (redirectCount >= MaxFetchRedirects) {
                        MoteLog.w(
                            Component,
                            MoteLog.event(
                                "fetch_url 重定向次数超过上限",
                                "status" to statusCode,
                                "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                                "redirects" to redirects.length(),
                                "durationMs" to MoteLog.durationMs(startMs)
                            )
                        )
                        return JSONObject().apply {
                            put("ok", false)
                            put("url", initialUrl.toString())
                            put("final_url", currentUrl.toString())
                            put("status", statusCode)
                            put("error", "重定向次数超过 $MaxFetchRedirects 次。")
                            put("redirects", redirects)
                        }.toString(2)
                    }
                    val nextUrl = normalizeFetchUrl(currentUrl.toURI().resolve(location).toString())
                    redirects.put(
                        JSONObject().apply {
                            put("from", currentUrl.toString())
                            put("to", nextUrl.toString())
                            put("status", statusCode)
                        }
                    )
                    currentUrl = nextUrl
                    return@repeat
                }

                val contentType = connection.contentType.orEmpty()
                val responseBody = readHttpResponseBody(connection, statusCode, MaxFetchResponseBytes)
                if (statusCode !in 200..299) {
                    MoteLog.w(
                        Component,
                        MoteLog.event(
                            "fetch_url 请求失败",
                            "status" to statusCode,
                            "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                            "contentType" to contentType.substringBefore(';').ifBlank { "未返回" },
                            "redirects" to redirects.length(),
                            "responseTruncated" to responseBody.truncated,
                            "durationMs" to MoteLog.durationMs(startMs)
                        )
                    )
                    return JSONObject().apply {
                        put("ok", false)
                        put("url", initialUrl.toString())
                        put("final_url", currentUrl.toString())
                        put("status", statusCode)
                        put("content_type", contentType)
                        put("output_format", outputFormat)
                        put("truncated", responseBody.truncated)
                        put("redirects", redirects)
                        put("error", "URL 请求失败，HTTP $statusCode。")
                        put("content", decodeResponseBody(responseBody.bytes, contentType).take(maxChars))
                    }.toString(2)
                }

                val bodyText = decodeResponseBody(responseBody.bytes, contentType)
                if (!isTextualResponse(contentType, bodyText)) {
                    MoteLog.w(
                        Component,
                        MoteLog.event(
                            "fetch_url 响应不是文本",
                            "status" to statusCode,
                            "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                            "contentType" to contentType.substringBefore(';').ifBlank { "未返回" },
                            "durationMs" to MoteLog.durationMs(startMs)
                        )
                    )
                    return JSONObject().apply {
                        put("ok", false)
                        put("url", initialUrl.toString())
                        put("final_url", currentUrl.toString())
                        put("status", statusCode)
                        put("content_type", contentType)
                        put("output_format", outputFormat)
                        put("truncated", responseBody.truncated)
                        put("redirects", redirects)
                        put("error", "响应看起来不是文本内容，fetch_url 不返回二进制数据。")
                    }.toString(2)
                }
                val isHtml = isHtmlContent(contentType, bodyText)
                val markdownConversion = if (outputFormat == "markdown" && isHtml) {
                    htmlToMarkdown(bodyText)
                } else {
                    null
                }
                val formattedContent = when (outputFormat) {
                    "raw" -> bodyText
                    "markdown" -> markdownConversion?.content ?: bodyText
                    else -> if (isHtml) htmlToPlainText(bodyText) else bodyText
                }
                val truncatedContent = formattedContent.length > maxChars
                MoteLog.i(
                    Component,
                    MoteLog.event(
                        "fetch_url 请求完成",
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                        "outputFormat" to outputFormat,
                        "contentType" to contentType.substringBefore(';').ifBlank { "未返回" },
                        "redirects" to redirects.length(),
                        "converted" to (markdownConversion?.converted ?: false),
                        "truncated" to (responseBody.truncated || truncatedContent),
                        "contentLength" to formattedContent.length,
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
                return JSONObject().apply {
                    put("ok", true)
                    put("url", initialUrl.toString())
                    put("final_url", currentUrl.toString())
                    put("status", statusCode)
                    put("content_type", contentType)
                    put("output_format", outputFormat)
                    put("converted", markdownConversion?.converted ?: false)
                    markdownConversion?.error?.let { put("conversion_error", it) }
                    put("truncated", responseBody.truncated || truncatedContent)
                    put("redirects", redirects)
                    put("content", if (truncatedContent) formattedContent.take(maxChars) else formattedContent)
                }.toString(2)
            } finally {
                runCatching { connection.disconnect() }
            }
        }

        MoteLog.w(
            Component,
            MoteLog.event(
                "fetch_url 重定向处理失败",
                "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                "redirects" to redirects.length(),
                "durationMs" to MoteLog.durationMs(startMs)
            )
        )
        return JSONObject().apply {
            put("ok", false)
            put("url", initialUrl.toString())
            put("final_url", currentUrl.toString())
            put("error", "重定向处理失败。")
            put("redirects", redirects)
        }.toString(2)
    }

    internal fun parseFetchWebViewOptions(arguments: String): WebViewFetchOptions {
        val payload = JSONObject(arguments)
        val rawUrl = payload.optString("url").trim()
        require(rawUrl.isNotEmpty()) { "url 不能为空。" }
        val timeoutSeconds = payload.optIntOrNull("timeout_seconds") ?: DefaultWebViewTimeoutSeconds
        require(timeoutSeconds > 0) { "timeout_seconds 必须大于 0。" }
        require(timeoutSeconds <= MaxWebViewTimeoutSeconds) {
            "timeout_seconds 不能超过 $MaxWebViewTimeoutSeconds。"
        }
        val settleMs = payload.optIntOrNull("settle_ms") ?: DefaultWebViewSettleMs
        require(settleMs >= 0) { "settle_ms 不能小于 0。" }
        require(settleMs <= MaxWebViewSettleMs) { "settle_ms 不能超过 $MaxWebViewSettleMs。" }
        return WebViewFetchOptions(
            url = normalizeFetchUrl(rawUrl),
            outputFormat = parseFetchOutputFormat(payload),
            maxChars = parseFetchMaxChars(payload),
            timeoutSeconds = timeoutSeconds,
            settleMs = settleMs
        )
    }

    private fun fetchWebView(context: Context, arguments: String): String {
        val options = parseFetchWebViewOptions(arguments)
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 fetch_webview",
                "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                "outputFormat" to options.outputFormat,
                "maxChars" to options.maxChars,
                "timeoutSeconds" to options.timeoutSeconds,
                "settleMs" to options.settleMs
            )
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            MoteLog.w(Component, "fetch_webview 被拒绝：不能在主线程同步执行。")
            return JSONObject().apply {
                put("ok", false)
                put("url", options.url.toString())
                put("final_url", options.url.toString())
                put("output_format", options.outputFormat)
                put("error", "fetch_webview 不能在主线程同步执行。")
            }.toString(2)
        }

        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                startFetchWebViewOnMainThread(context.applicationContext, options) { output ->
                    result.set(output)
                    latch.countDown()
                }
            }.onFailure { error ->
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "fetch_webview 初始化失败",
                        "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                        "error" to error
                    )
                )
                result.set(
                    JSONObject().apply {
                        put("ok", false)
                        put("url", options.url.toString())
                        put("final_url", options.url.toString())
                        put("output_format", options.outputFormat)
                        put("error", error.message ?: "WebView 初始化失败。")
                    }.toString(2)
                )
                latch.countDown()
            }
        }
        val completed = latch.await((options.timeoutSeconds + 5).toLong(), TimeUnit.SECONDS)
        if (!completed) {
            MoteLog.w(
                Component,
                MoteLog.event(
                    "fetch_webview 等待超时",
                    "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                    "timeoutSeconds" to options.timeoutSeconds
                )
            )
            return JSONObject().apply {
                put("ok", false)
                put("url", options.url.toString())
                put("final_url", options.url.toString())
                put("output_format", options.outputFormat)
                put("error", "WebView 抓取等待超时。")
            }.toString(2)
        }
        return result.get().orEmpty()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startFetchWebViewOnMainThread(
        context: Context,
        options: WebViewFetchOptions,
        onComplete: (String) -> Unit
    ) {
        val webView = WebView(context)
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        fun finishOnce(output: String) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            onComplete(output)
        }

        fun finishError(message: String, finalUrl: String = webView.url ?: options.url.toString()) {
            MoteLog.w(
                Component,
                MoteLog.event(
                    "fetch_webview 加载失败",
                    "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                    "finalOrigin" to MoteLog.safeUrlOrigin(finalUrl),
                    "messageLength" to message.length
                )
            )
            finishOnce(
                JSONObject().apply {
                    put("ok", false)
                    put("url", options.url.toString())
                    put("final_url", finalUrl)
                    put("output_format", options.outputFormat)
                    put("truncated", false)
                    put("error", message)
                }.toString(2)
            )
        }

        fun extractPage() {
            if (completed.get()) {
                return
            }
            val script = buildWebViewExtractionScript(options.outputFormat)
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    if (completed.get()) {
                        return@evaluateJavascript
                    }
                    runCatching {
                        val page = parseWebViewExtractedPage(value, webView.url ?: options.url.toString())
                        formatWebViewFetchResult(options, page)
                    }.onSuccess { output ->
                        finishOnce(output)
                    }.onFailure { error ->
                        finishError("WebView 内容处理失败：${error.readableMessage()}")
                    }
                }
            }.onFailure { error ->
                finishError("WebView 内容提取失败：${error.readableMessage()}")
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = false
        webView.settings.blockNetworkImage = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.layout(0, 0, 1, 1)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                if (target.isBlank()) {
                    return false
                }
                val allowed = runCatching { normalizeFetchUrl(target) }.isSuccess
                if (!allowed) {
                    finishError("WebView 跳转到了不支持的 URL。", target)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && runCatching { normalizeFetchUrl(url) }.isFailure) {
                    finishError("WebView 跳转到了不支持的 URL。", url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                handler.postDelayed({ extractPage() }, options.settleMs.toLong())
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    finishError("WebView 加载失败：${error?.description ?: "未知错误"}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    finishError("WebView 请求失败，HTTP ${errorResponse?.statusCode ?: 0}。")
                }
            }
        }

        handler.postDelayed({
            finishError("WebView 加载超过 ${options.timeoutSeconds} 秒。")
        }, options.timeoutSeconds * 1000L)

        webView.loadUrl(options.url.toString())
    }

    private fun buildWebViewExtractionScript(outputFormat: String): String {
        val expression = if (outputFormat == "raw" || outputFormat == "markdown") {
            "document.documentElement ? document.documentElement.outerHTML : ''"
        } else {
            "document.body ? (document.body.innerText || document.body.textContent || '') : ''"
        }
        val maxChars = MaxWebViewExtractChars + 1
        return """
            (function() {
                var content = String($expression || '');
                return JSON.stringify({
                    title: document.title || '',
                    url: location.href || '',
                    content: content.substring(0, $maxChars)
                });
            })();
        """.trimIndent()
    }

    private fun parseWebViewExtractedPage(value: String?, fallbackUrl: String): WebViewExtractedPage {
        val decoded = runCatching { JSONTokener(value ?: "").nextValue() }
            .getOrNull()
            ?.toString()
            .orEmpty()
        val payload = runCatching { JSONObject(decoded) }.getOrNull() ?: JSONObject()
        return WebViewExtractedPage(
            finalUrl = payload.optString("url").ifBlank { fallbackUrl },
            title = payload.optString("title"),
            content = payload.optString("content")
        )
    }

    private fun formatWebViewFetchResult(options: WebViewFetchOptions, page: WebViewExtractedPage): String {
        val sourceContent = if (page.content.length > MaxWebViewExtractChars) {
            page.content.take(MaxWebViewExtractChars)
        } else {
            page.content
        }
        val markdownConversion = if (options.outputFormat == "markdown") {
            htmlToMarkdown(sourceContent)
        } else {
            null
        }
        val formattedContent = when (options.outputFormat) {
            "markdown" -> markdownConversion?.content ?: sourceContent
            "raw" -> sourceContent
            else -> sourceContent
        }
        val truncatedContent = formattedContent.length > options.maxChars
        MoteLog.i(
            Component,
            MoteLog.event(
                "fetch_webview 完成",
                "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                "finalOrigin" to MoteLog.safeUrlOrigin(page.finalUrl),
                "outputFormat" to options.outputFormat,
                "converted" to (markdownConversion?.converted ?: false),
                "truncated" to (truncatedContent || page.content.length > MaxWebViewExtractChars),
                "contentLength" to formattedContent.length,
                "titleLength" to page.title.length
            )
        )
        return JSONObject().apply {
            put("ok", true)
            put("url", options.url.toString())
            put("final_url", page.finalUrl)
            put("title", page.title)
            put("output_format", options.outputFormat)
            put("rendered", true)
            put("converted", markdownConversion?.converted ?: false)
            markdownConversion?.error?.let { put("conversion_error", it) }
            put("truncated", truncatedContent || page.content.length > MaxWebViewExtractChars)
            put("content", if (truncatedContent) formattedContent.take(options.maxChars) else formattedContent)
        }.toString(2)
    }

    private fun normalizeFetchUrl(rawUrl: String): URL {
        val uri = URI(rawUrl.trim()).normalize()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "url 只支持 http 或 https。" }
        require(!uri.host.isNullOrBlank()) { "url 缺少主机名。" }
        return uri.toURL()
    }

    private fun buildFetchAcceptHeader(outputFormat: String): String {
        return when (outputFormat) {
            "markdown" -> "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.6"
            else -> "text/*,application/json,application/xml,*/*;q=0.6"
        }
    }

    private data class HttpResponseBody(
        val bytes: ByteArray,
        val truncated: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HttpResponseBody

            if (truncated != other.truncated) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = truncated.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    private fun readHttpResponseBody(
        connection: HttpURLConnection,
        statusCode: Int,
        maxBytes: Int
    ): HttpResponseBody {
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return HttpResponseBody(ByteArray(0), truncated = false)
        }
        val output = ByteArrayOutputStream()
        var truncated = false
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                val remaining = maxBytes - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                if (read > remaining) {
                    output.write(buffer, 0, remaining)
                    truncated = true
                    break
                }
                output.write(buffer, 0, read)
            }
        }
        return HttpResponseBody(bytes = output.toByteArray(), truncated = truncated)
    }

    private fun decodeResponseBody(bytes: ByteArray, contentType: String): String {
        if (bytes.isEmpty()) {
            return ""
        }
        return bytes.toString(resolveCharset(contentType))
    }

    private fun resolveCharset(contentType: String): Charset {
        val charsetName = contentType
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
        return charsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.UTF_8
    }

    private fun isHtmlContent(contentType: String, text: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mediaType == "text/html" || mediaType == "application/xhtml+xml") {
            return true
        }
        val head = text.take(500).lowercase(Locale.ROOT)
        return "<html" in head || "<!doctype html" in head
    }

    private fun isTextualResponse(contentType: String, text: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mediaType.isBlank()) {
            return !text.contains('\u0000')
        }
        return mediaType.startsWith("text/") ||
                mediaType == "application/json" ||
                mediaType == "application/xml" ||
                mediaType == "application/xhtml+xml" ||
                mediaType == "application/javascript" ||
                mediaType == "application/x-javascript" ||
                mediaType == "application/rss+xml" ||
                mediaType == "application/atom+xml" ||
                mediaType.endsWith("+json") ||
                mediaType.endsWith("+xml")
    }

    private fun htmlToMarkdown(html: String): MarkdownConversionResult {
        return runCatching {
            htmlToMarkdownConverter(html).trim()
        }.fold(
            onSuccess = { markdown ->
                MarkdownConversionResult(
                    content = markdown,
                    converted = true
                )
            },
            onFailure = { error ->
                MarkdownConversionResult(
                    content = htmlToPlainText(html),
                    converted = false,
                    error = "Markdown 转换失败，已降级为纯文本：${error.readableMessage()}"
                )
            }
        )
    }

    private fun Throwable.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    private fun htmlToPlainText(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|section|article|header|footer|main|li|tr|h[1-6])>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .let { decodeHtmlEntities(it) }
            .lines()
            .map { line -> line.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                val codePoint = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                runCatching { String(Character.toChars(codePoint)) }.getOrDefault(match.value)
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
                val codePoint = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
                runCatching { String(Character.toChars(codePoint)) }.getOrDefault(match.value)
            }
    }

    internal fun webSearch(settings: ApiSettings, arguments: String): String {
        return when (resolveSearchProvider(settings)) {
            SearchProvider.Searxng -> searchWithSearxng(settings, arguments)
            SearchProvider.Tavily -> searchWithTavily(settings, arguments)
            null -> throw IllegalArgumentException("搜索服务未配置，无法执行搜索。")
        }
    }

    private fun resolveSearchProvider(settings: ApiSettings): SearchProvider? {
        val hasSearxng = settings.searxngUrl.isNotBlank()
        val hasTavily = settings.tavilyApiKey.isNotBlank()
        require(!(hasSearxng && hasTavily)) { "Tavily API Key 和 SearXNG 地址只能配置一个。" }
        return when {
            hasSearxng -> SearchProvider.Searxng
            hasTavily -> SearchProvider.Tavily
            else -> null
        }
    }

    private fun searchWithSearxng(settings: ApiSettings, arguments: String): String {
        val searxngBaseUrl = settings.searxngUrl.trim()
        require(searxngBaseUrl.isNotEmpty()) { "SearXNG 地址未配置，无法执行搜索。" }
        val startMs = System.currentTimeMillis()

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
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 web_search",
                "origin" to MoteLog.safeUrlOrigin(searxngBaseUrl),
                "queryLength" to query.length,
                "queryHash" to MoteLog.fingerprint(query),
                "page" to page,
                "limit" to limit
            )
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
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "web_search 请求失败",
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(searxngBaseUrl),
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
                return JSONObject().apply {
                    put("ok", false)
                    put("status", statusCode)
                    put("error", "SearXNG 请求失败，HTTP $statusCode。")
                    put("body", truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }

            val root = runCatching { JSONObject(responseText) }.getOrElse { error ->
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "web_search 响应 JSON 解析失败",
                        "origin" to MoteLog.safeUrlOrigin(searxngBaseUrl),
                        "responseLength" to responseText.length,
                        "durationMs" to MoteLog.durationMs(startMs),
                        "error" to error
                    )
                )
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "SearXNG 返回的内容不是有效 JSON：${error.message ?: "解析失败"}")
                    put("body", truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }
            formatSearchResults(query = query, page = page, limit = limit, root = root).also { output ->
                val returned = runCatching { JSONObject(output).optInt("returned", 0) }.getOrDefault(0)
                MoteLog.i(
                    Component,
                    MoteLog.event(
                        "web_search 完成",
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(searxngBaseUrl),
                        "returned" to returned,
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun searchWithTavily(settings: ApiSettings, arguments: String): String {
        val apiKey = settings.tavilyApiKey.trim()
        require(apiKey.isNotEmpty()) { "Tavily API Key 未配置，无法执行搜索。" }
        val startMs = System.currentTimeMillis()

        val payload = JSONObject(arguments)
        val query = payload.optString("query").trim()
        require(query.isNotEmpty()) { "query 不能为空。" }
        require(query.length <= MaxSearchQueryChars) { "query 不能超过 $MaxSearchQueryChars 个字符。" }

        val limit = payload.optIntOrNull("limit") ?: DefaultSearchResultLimit
        require(limit > 0) { "limit 必须大于 0。" }
        require(limit <= MaxTavilySearchResultLimit) { "limit 不能超过 $MaxTavilySearchResultLimit。" }

        val requestBody = buildTavilySearchRequest(payload, query, limit)
        val searchUrl = normalizeFetchUrl(tavilySearchEndpoint)
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 web_search",
                "provider" to "Tavily",
                "origin" to MoteLog.safeUrlOrigin(searchUrl.toString()),
                "queryLength" to query.length,
                "queryHash" to MoteLog.fingerprint(query),
                "limit" to limit
            )
        )

        val connection = (searchUrl.openConnection() as HttpURLConnection)
        return try {
            val requestBytes = requestBody.toString().toByteArray(StandardCharsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "Mote/1.0")
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.outputStream.use { output -> output.write(requestBytes) }

            val statusCode = connection.responseCode
            val responseText = readHttpResponseText(connection, statusCode, MaxSearchResponseChars)
            if (statusCode !in 200..299) {
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "web_search 请求失败",
                        "provider" to "Tavily",
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(searchUrl.toString()),
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
                return JSONObject().apply {
                    put("ok", false)
                    put("provider", "tavily")
                    put("status", statusCode)
                    put("error", "Tavily 请求失败，HTTP $statusCode。")
                    put("body", truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }

            val root = runCatching { JSONObject(responseText) }.getOrElse { error ->
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "web_search 响应 JSON 解析失败",
                        "provider" to "Tavily",
                        "origin" to MoteLog.safeUrlOrigin(searchUrl.toString()),
                        "responseLength" to responseText.length,
                        "durationMs" to MoteLog.durationMs(startMs),
                        "error" to error
                    )
                )
                return JSONObject().apply {
                    put("ok", false)
                    put("provider", "tavily")
                    put("error", "Tavily 返回的内容不是有效 JSON：${error.message ?: "解析失败"}")
                    put("body", truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }
            formatTavilySearchResults(query = query, limit = limit, root = root).also { output ->
                val returned = runCatching { JSONObject(output).optInt("returned", 0) }.getOrDefault(0)
                MoteLog.i(
                    Component,
                    MoteLog.event(
                        "web_search 完成",
                        "provider" to "Tavily",
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(searchUrl.toString()),
                        "returned" to returned,
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun buildTavilySearchRequest(payload: JSONObject, query: String, limit: Int): JSONObject {
        return JSONObject().apply {
            put("query", query)
            put("max_results", limit)
            putOptionalTavilyEnum(
                payload = payload,
                target = this,
                key = "search_depth",
                allowedValues = setOf("basic", "advanced", "fast", "ultra-fast")
            )
            putOptionalTavilyEnum(
                payload = payload,
                target = this,
                key = "topic",
                allowedValues = setOf("general", "news", "finance")
            )
            putOptionalTavilyEnum(
                payload = payload,
                target = this,
                key = "time_range",
                allowedValues = setOf("day", "week", "month", "year", "d", "w", "m", "y")
            )
            putOptionalTavilyDate(payload, this, "start_date")
            putOptionalTavilyDate(payload, this, "end_date")
            putOptionalTavilyAnswer(payload, this)
            putOptionalTavilyInt(payload, this, "chunks_per_source", min = 1, max = 3)
            putOptionalTavilyDomains(payload, this, "include_domains")
            putOptionalTavilyDomains(payload, this, "exclude_domains")
        }
    }

    private fun putOptionalTavilyEnum(
        payload: JSONObject,
        target: JSONObject,
        key: String,
        allowedValues: Set<String>
    ) {
        val value = payload.optString(key).trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() } ?: return
        require(value in allowedValues) { "$key 只能是 ${allowedValues.joinToString(separator = "、")}。" }
        target.put(key, value)
    }

    private fun putOptionalTavilyDate(payload: JSONObject, target: JSONObject, key: String) {
        val value = payload.optString(key).trim().takeIf { it.isNotEmpty() } ?: return
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "$key 必须使用 YYYY-MM-DD 格式。" }
        target.put(key, value)
    }

    private fun putOptionalTavilyAnswer(payload: JSONObject, target: JSONObject) {
        if (!payload.has("include_answer")) {
            return
        }
        when (val value = payload.opt("include_answer")) {
            is Boolean -> target.put("include_answer", value)
            is String -> {
                val normalized = value.trim().lowercase(Locale.ROOT)
                require(normalized in setOf("true", "false", "basic", "advanced")) {
                    "include_answer 只能是 true、false、basic 或 advanced。"
                }
                if (normalized == "true" || normalized == "false") {
                    target.put("include_answer", normalized.toBoolean())
                } else {
                    target.put("include_answer", normalized)
                }
            }
            JSONObject.NULL -> Unit
            else -> throw IllegalArgumentException("include_answer 只能是布尔值、basic 或 advanced。")
        }
    }

    private fun putOptionalTavilyInt(payload: JSONObject, target: JSONObject, key: String, min: Int, max: Int) {
        val value = payload.optIntOrNull(key) ?: return
        require(value in min..max) { "$key 必须在 $min 到 $max 之间。" }
        target.put(key, value)
    }

    private fun putOptionalTavilyDomains(payload: JSONObject, target: JSONObject, key: String) {
        val domains = parseTavilyDomainList(payload, key) ?: return
        if (domains.length() > 0) {
            target.put(key, domains)
        }
    }

    private fun parseTavilyDomainList(payload: JSONObject, key: String): JSONArray? {
        if (!payload.has(key)) {
            return null
        }
        val output = JSONArray()
        when (val value = payload.opt(key)) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val domain = value.optString(index).trim()
                    require(domain.isNotEmpty()) { "$key 不能包含空域名。" }
                    output.put(domain)
                }
            }
            is String -> {
                value.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { domain -> output.put(domain) }
            }
            JSONObject.NULL -> Unit
            else -> throw IllegalArgumentException("$key 必须是逗号分隔字符串或字符串数组。")
        }
        return output
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

        return "$endpoint?" + params.joinToString(separator = "&") { (key, value) ->
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
                output.appendRange(buffer, 0, minOf(read, remaining.coerceAtLeast(0)))
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
            put("provider", "searxng")
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

    private fun formatTavilySearchResults(query: String, limit: Int, root: JSONObject): String {
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
                    val score = item.optDouble("score", Double.NaN)
                    if (!score.isNaN() && !score.isInfinite()) {
                        put("score", score)
                    }
                    item.optString("published_date").trim().takeIf { it.isNotEmpty() }?.let {
                        put("published_date", it)
                    }
                    item.optString("raw_content").trim().takeIf { it.isNotEmpty() }?.let { rawContent ->
                        put("raw_content", truncateSearchRawContent(rawContent))
                    }
                    item.optString("favicon").trim().takeIf { it.isNotEmpty() }?.let { put("favicon", it) }
                    item.optJSONArray("images")?.takeIf { it.length() > 0 }?.let { put("images", it) }
                }
            )
        }

        return JSONObject().apply {
            put("ok", true)
            put("provider", "tavily")
            put("query", root.optString("query").takeIf { it.isNotBlank() } ?: query)
            put("returned", results.length())
            put("available", rawResults.length())
            put("has_more", rawResults.length() > results.length() || skipped > 0)
            put("results", results)
            root.optString("answer").trim().takeIf { it.isNotEmpty() }?.let { put("answer", it) }
            root.optJSONArray("images")?.takeIf { it.length() > 0 }?.let { put("images", it) }
            root.optString("response_time").trim().takeIf { it.isNotEmpty() }?.let { put("response_time", it) }
            root.optJSONObject("auto_parameters")?.let { put("auto_parameters", it) }
            root.optJSONObject("usage")?.let { put("usage", it) }
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

    private fun truncateSearchRawContent(text: String): String {
        return if (text.length <= MaxSearchRawContentChars) {
            text
        } else {
            text.take(MaxSearchRawContentChars) + "..."
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
            MoteLog.w(
                Component,
                MoteLog.event(
                    "Shell 命令命中高风险规则，等待用户确认",
                    "confirmationId" to MoteLog.shortId(id),
                    "commandHash" to MoteLog.fingerprint(command),
                    "background" to background,
                    "hasWorkDir" to (workDir != null),
                    "risk" to risk
                )
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

        val aiTempDir = BusyBoxManager.ensureAiTempDir(context)
        val effectiveWorkDir = workDir ?: aiTempDir.path
        val id = ShellProcessManager.start(command, effectiveWorkDir, BusyBoxManager.environmentOverrides(context))
        onShellProcessStarted?.invoke(id, background)
        MoteLog.i(
            Component,
            MoteLog.event(
                "Shell 命令已启动",
                "id" to id,
                "commandHash" to MoteLog.fingerprint(command),
                "background" to background,
                "hasCustomWorkDir" to (workDir != null),
                "workDir" to effectiveWorkDir,
                "riskConfirmed" to (risk != null)
            )
        )

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
            MoteLog.i(
                Component,
                MoteLog.event(
                    "Shell 前台命令完成",
                    "id" to id,
                    "exitCode" to entry.process.exitValue(),
                    "stdoutChars" to stdout.length,
                    "stderrChars" to stderr.length
                )
            )

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
        MoteLog.i(
            Component,
            MoteLog.event(
                "Shell 前台命令超时并转后台",
                "id" to id,
                "timeoutMs" to ShellShortTimeoutMs,
                "stdoutChars" to stdoutSoFar.length,
                "stderrChars" to stderrSoFar.length
            )
        )

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
            MoteLog.w(
                Component,
                MoteLog.event(
                    "Shell 确认消费失败：未激活或已过期",
                    "confirmationId" to MoteLog.shortId(id),
                    "active" to confirmation.active
                )
            )
            return false
        }
        if (confirmation.command != command || confirmation.workDir != workDir || confirmation.background != background) {
            MoteLog.w(
                Component,
                MoteLog.event(
                    "Shell 确认消费失败：请求不匹配",
                    "confirmationId" to MoteLog.shortId(id),
                    "commandHash" to MoteLog.fingerprint(command)
                )
            )
            return false
        }
        pendingShellConfirmations.remove(id)
        MoteLog.i(
            Component,
            MoteLog.event(
                "Shell 确认令牌已消费",
                "confirmationId" to MoteLog.shortId(id),
                "commandHash" to MoteLog.fingerprint(command)
            )
        )
        return true
    }

    private fun checkShellStatus(arguments: String): String {
        val payload = JSONObject(arguments)
        val id = payload.optString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空。" }
        MoteLog.d(Component, MoteLog.event("查询 Shell 进程状态", "id" to id))
        return ShellProcessManager.getStatus(id).toString(2)
    }

    private fun stopShell(arguments: String): String {
        val payload = JSONObject(arguments)
        val id = payload.optString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空。" }
        MoteLog.i(Component, MoteLog.event("请求停止 Shell 进程", "id" to id))
        return ShellProcessManager.stop(id).toString(2)
    }

    private fun scheduleWait(arguments: String): String {
        val payload = JSONObject(arguments)
        val seconds = payload.optInt("seconds", 0)
        require(seconds > 0) { "seconds 必须大于 0。" }
        require(seconds <= 3600) { "seconds 不能超过 3600（1 小时）。" }

        MoteLog.i(Component, MoteLog.event("工具请求等待", "seconds" to seconds))
        return JSONObject().apply {
            put("ok", true)
            put("wait_seconds", seconds)
            put("message", "将在 $seconds 秒后继续对话，届时可查询后台进程状态")
        }.toString(2)
    }

    private fun safeArgumentKeys(arguments: String): List<String> {
        return runCatching {
            val payload = JSONObject(arguments)
            buildList {
                val keys = payload.keys()
                while (keys.hasNext()) {
                    add(keys.next())
                }
            }.sorted()
        }.getOrDefault(emptyList())
    }

    private fun safeToolResultFields(output: String): Array<Pair<String, Any?>> {
        val payload = runCatching { JSONObject(output) }.getOrNull()
        return arrayOf(
            "ok" to payload?.optBoolean("ok", false),
            "needsConfirmation" to (payload?.optBoolean("needs_confirmation", false) ?: false),
            "cancelled" to (payload?.optBoolean("cancelled", false) ?: false),
            "mode" to payload?.optString("mode")?.takeIf { it.isNotBlank() },
            "outputLength" to output.length
        )
    }

    private fun buildReadFileDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", ReadFileToolName)
                    put("description", "按行读取设备上当前应用有权限访问的文本文件内容。行号从 1 开始。如果不提供行范围参数，默认读取前 200 行。读取中间内容时直接提供 start_line/end_line。")
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
                                            put("description", "读取文件前多少行。只在不提供 start_line/end_line 时生效。")
                                        }
                                    )
                                    put(
                                        "start_line",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "起始行号，从 1 开始。读取中间内容时优先使用此字段，可与 end_line 一起使用。")
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

    private fun buildFetchUrlDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", FetchUrlToolName)
                    put("description", "通过 HTTP GET 获取一个 http/https URL 的内容。支持 markdown、 raw 原始网页和 text 纯文本。适合读取搜索结果中的网页、文档或接口文本响应。")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "url",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要获取的 URL，只支持 http 或 https。")
                                        }
                                    )
                                    put(
                                        "output_format",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "输出格式：text 提取可读纯文本，raw 返回原始网页，markdown 将 HTML 转为 Markdown。默认 text。")
                                            put("enum", JSONArray().put("text").put("raw").put("markdown"))
                                        }
                                    )
                                    put(
                                        "max_chars",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "返回 content 的最大字符数，默认 20000，最大 100000。")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("url"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildFetchWebViewDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", FetchWebViewToolName)
                    put("description", "使用不可见 WebView 加载完整网页，执行页面 JavaScript 后提取渲染后的内容。适合普通 fetch_url 拿不到动态内容时使用；比 fetch_url 更慢且更耗资源。")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("description", buildToolCallDescriptionProperty())
                                    put(
                                        "url",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要加载的网页 URL，只支持 http 或 https。")
                                        }
                                    )
                                    put(
                                        "output_format",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "输出格式：text 提取可读纯文本，raw 返回原始网页，markdown 将 HTML 转为 Markdown。默认 text。")
                                            put("enum", JSONArray().put("text").put("raw").put("markdown"))
                                        }
                                    )
                                    put(
                                        "max_chars",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "返回 content 的最大字符数，默认 20000，最大 100000。")
                                        }
                                    )
                                    put(
                                        "timeout_seconds",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "WebView 加载超时时间，默认 20 秒，最大 60 秒。")
                                        }
                                    )
                                    put(
                                        "settle_ms",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "页面 onPageFinished 后继续等待的毫秒数，用于等待异步渲染，默认 1000，最大 10000。")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("description").put("url"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    private fun buildSearxngWebSearchDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", WebSearchToolName)
                    put("description", "使用 SearXNG 搜索互联网。适合查询最新信息、网页资料、新闻或需要来源链接的问题。")
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
                                            put("description", "可选的分类，多个分类用英文逗号分隔，例如 general、news、it、science。")
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

    private fun buildTavilyWebSearchDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", WebSearchToolName)
                    put("description", "使用 Tavily Search 搜索互联网。适合查询最新信息、网页资料、新闻或需要来源链接的问题。")
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
                                            put("description", "最多返回多少条结果，默认 5，最大 20。")
                                        }
                                    )
                                    put(
                                        "search_depth",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "搜索深度：basic 默认平衡，advanced 更深入但更慢，fast/ultra-fast 优先低延迟。")
                                            put(
                                                "enum",
                                                JSONArray()
                                                    .put("basic")
                                                    .put("advanced")
                                                    .put("fast")
                                                    .put("ultra-fast")
                                            )
                                        }
                                    )
                                    put(
                                        "topic",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "搜索类别：general 通用，news 新闻，finance 金融。")
                                            put("enum", JSONArray().put("general").put("news").put("finance"))
                                        }
                                    )
                                    put(
                                        "time_range",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "可选的时间范围，例如 day、week、month、year，也支持 d、w、m、y。")
                                            put(
                                                "enum",
                                                JSONArray()
                                                    .put("day")
                                                    .put("week")
                                                    .put("month")
                                                    .put("year")
                                                    .put("d")
                                                    .put("w")
                                                    .put("m")
                                                    .put("y")
                                            )
                                        }
                                    )
                                    put(
                                        "start_date",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "只返回该日期之后发布或更新的结果，格式 YYYY-MM-DD。")
                                        }
                                    )
                                    put(
                                        "end_date",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "只返回该日期之前发布或更新的结果，格式 YYYY-MM-DD。")
                                        }
                                    )
                                    put(
                                        "include_answer",
                                        JSONObject().apply {
                                            put("type", "boolean")
                                            put("description", "是否请求 Tavily 生成简短答案；默认 false。")
                                        }
                                    )
                                    put(
                                        "chunks_per_source",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "advanced 搜索时每个来源返回的内容片段数，1 到 3。")
                                        }
                                    )
                                    put(
                                        "include_domains",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "可选的域名白名单，多个域名用英文逗号分隔。")
                                        }
                                    )
                                    put(
                                        "exclude_domains",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "可选的域名黑名单，多个域名用英文逗号分隔。")
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
                    put("description",
                        $$"在设备上执行 shell 命令。短命令会等待完成并返回输出；长命令可设为后台运行，用 shell_status 查询状态。如果命令超过 30 秒未完成会自动转为后台运行。未提供工作目录时默认使用 Android/data/包名/files/ai_tmp，临时文件请保存到当前目录、$TMPDIR 或 $MOTE_AI_TMPDIR。"
                    )
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
                                            put("description", "工作目录，不提供则使用 Android/data/包名/files/ai_tmp 作为默认目录")
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
