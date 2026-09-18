package com.mukapp.mote.tools

import android.content.Context
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.resolvedSearchProvider
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.optIntOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * AI 工具执行入口与分发。工具定义在 [AiToolDefinitions]；
 * fetch / 搜索 / Shell 的具体实现分别在 [UrlFetchTools]、[WebSearchTools]、[ShellTools]，
 * 确认令牌注册表在 [ToolConfirmations]。
 */
object LocalAiTools {
    private const val Component = "Tools"
    internal const val ReadFileToolName = "read_file"
    internal const val ReadLocalFileToolAlias = "read_local_file"
    internal const val ListPathToolName = "list_path"
    internal const val GetCurrentTimeToolName = "get_current_time"
    internal const val FetchUrlToolName = "fetch_url"
    internal const val FetchWebViewToolName = "fetch_webview"
    internal const val WebSearchToolName = "web_search"
    internal const val ShellToolName = "shell"
    internal const val ShellStatusToolName = "shell_status"
    internal const val ShellStopToolName = "shell_stop"
    const val WaitToolName = "wait"

    /** 可能返回 needs_confirmation 结果的工具，UI 层据此识别历史消息中的确认请求。 */
    private val ConfirmationCapableToolNames =
        setOf(ShellToolName, ReadFileToolName, ReadLocalFileToolAlias, ListPathToolName)

    private const val MaxReadLines = 400
    private const val MaxReadLineChars = 4_000
    private const val MaxReadTotalChars = 48_000
    private const val MaxListEntries = 200
    private const val SensitivePathRisk = "读取应用私有数据"

    /** 单元测试注入点，转发到具体实现模块。 */
    internal var htmlToMarkdownConverter: (String) -> String
        get() = UrlFetchTools.htmlToMarkdownConverter
        set(value) {
            UrlFetchTools.htmlToMarkdownConverter = value
        }
    internal var tavilySearchEndpoint: String
        get() = WebSearchTools.tavilySearchEndpoint
        set(value) {
            WebSearchTools.tavilySearchEndpoint = value
        }
    internal var anysearchSearchEndpoint: String
        get() = WebSearchTools.anysearchSearchEndpoint
        set(value) {
            WebSearchTools.anysearchSearchEndpoint = value
        }

    fun toolDefinitions(settings: ApiSettings = ApiSettings()): JSONArray {
        val searchProvider = settings.resolvedSearchProvider()
        val definitions = AiToolDefinitions.forSearchProvider(searchProvider)
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
                ReadFileToolName, ReadLocalFileToolAlias ->
                    readFile(toolCall.arguments, SensitivePathGuard.forPrivateData(context))
                ListPathToolName -> listPath(toolCall.arguments, SensitivePathGuard.forPrivateData(context))
                GetCurrentTimeToolName -> getCurrentTime()
                FetchUrlToolName -> UrlFetchTools.fetchUrl(toolCall.arguments)
                FetchWebViewToolName -> UrlFetchTools.fetchWebView(context, toolCall.arguments)
                WebSearchToolName -> WebSearchTools.webSearch(settings, toolCall.arguments)
                ShellToolName -> ShellTools.runShell(context, toolCall.arguments, onShellProcessStarted)
                ShellStatusToolName -> ShellTools.checkShellStatus(toolCall.arguments)
                ShellStopToolName -> ShellTools.stopShell(toolCall.arguments)
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
            if (error.isInterruption()) {
                // 工具被取消（runInterruptible 中断执行线程）：恢复中断位并上抛，让协程取消正常传播。
                Thread.currentThread().interrupt()
                throw error
            }
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

    /** 中断族异常：线程被 runInterruptible 中断（工具取消）时抛出，不应被吞成普通错误结果。 */
    private fun Throwable.isInterruption(): Boolean =
        this is InterruptedException ||
                this is InterruptedIOException ||
                this is ClosedByInterruptException ||
                (cause?.isInterruption() == true)

    fun activatePendingToolConfirmation(confirmationId: String): Boolean =
        ToolConfirmations.activate(confirmationId)

    /** ViewModel 销毁时调用，避免命令文本或路径随进程常驻。 */
    fun discardAllPendingToolConfirmations() = ToolConfirmations.discardAll()

    fun discardPendingToolConfirmation(confirmationId: String) = ToolConfirmations.discard(confirmationId)

    fun isToolConfirmationRequest(message: ChatMessage): Boolean {
        return message.toolName in ConfirmationCapableToolNames && isToolConfirmationRequest(message.content)
    }

    fun isToolConfirmationRequest(content: String): Boolean {
        val payload = runCatching { JSONObject(content) }.getOrNull() ?: return false
        return !payload.optBoolean("ok", true) && payload.optBoolean("needs_confirmation", false)
    }

    /** read_file/list_path 的敏感路径守卫：应用私有数据目录须经用户确认，files/shell 子树豁免。 */
    internal class SensitivePathGuard private constructor(
        private val sensitiveRoots: List<File>,
        private val exemptRoots: List<File>
    ) {
        fun isSensitive(canonicalTarget: File): Boolean =
            sensitiveRoots.any { isUnder(canonicalTarget, it) } &&
                    exemptRoots.none { isUnder(canonicalTarget, it) }

        private fun isUnder(target: File, root: File): Boolean =
            target.path == root.path || target.path.startsWith(root.path + File.separator)

        companion object {
            /** 空守卫：不拦截任何路径（单元测试使用）。 */
            val Disabled = SensitivePathGuard(emptyList(), emptyList())

            fun forPrivateData(context: Context): SensitivePathGuard {
                // /data/data/<pkg> 与 /data/user/0/<pkg> 互为别名，目标路径 canonical 后通常归一；
                // 为防设备差异两个根都登记（canonical 失败退回原始 File 仍可前缀匹配）。
                val roots = listOf(context.dataDir, File("/data/data/${context.packageName}"))
                    .map { root -> runCatching { root.canonicalFile }.getOrDefault(root) }
                    .distinctBy { it.path }
                // 豁免 BusyBox 目录与 AI 临时目录回退位置（filesDir/shell/tmp），避免误伤正常工具链。
                val shellDir = File(context.filesDir, "shell")
                val exempt = listOf(runCatching { shellDir.canonicalFile }.getOrDefault(shellDir))
                return SensitivePathGuard(roots, exempt)
            }
        }
    }

    /**
     * 敏感路径确认检查：目标位于私有数据目录且未持有效确认令牌时，登记令牌并返回 needs_confirmation 结果。
     * 必须在 exists/canRead 检查之前调用，避免通过报错文案探测私有目录内文件的存在性。
     */
    private fun checkSensitivePathConfirmation(
        payload: JSONObject,
        toolName: String,
        target: File,
        guard: SensitivePathGuard
    ): String? {
        if (!guard.isSensitive(target)) {
            return null
        }
        val confirmationId = payload.optString("confirmation_id").trim().takeIf { it.isNotEmpty() }
        if (ToolConfirmations.consumeSensitivePath(confirmationId, toolName, target.path)) {
            return null
        }
        val id = ToolConfirmations.newConfirmationId()
        ToolConfirmations.register(
            ToolConfirmations.PendingToolConfirmation.SensitivePath(
                id = id,
                toolName = toolName,
                canonicalPath = target.path,
                risk = SensitivePathRisk
            )
        )
        MoteLog.w(
            Component,
            MoteLog.event(
                "工具访问应用私有数据目录，等待用户确认",
                "confirmationId" to MoteLog.shortId(id),
                "tool" to toolName,
                "pathHash" to MoteLog.fingerprint(target.path)
            )
        )
        return JSONObject().apply {
            put("ok", false)
            put("needs_confirmation", true)
            put("confirmation_type", "sensitive_path")
            put("confirmation_id", id)
            put("tool", toolName)
            put("path", target.path)
            put("risk", SensitivePathRisk)
            put("message", "目标路径位于应用私有数据目录，可能包含 API 密钥等敏感信息，需要用户确认后才能读取。")
        }.toString(2)
    }

    internal fun readFile(arguments: String, guard: SensitivePathGuard = SensitivePathGuard.Disabled): String {
        val payload = JSONObject(arguments)
        val rawPath = payload.optString("path").trim()
        require(rawPath.isNotEmpty()) { "path 不能为空。" }

        val rawFirstLines = payload.optIntOrNull("first_lines")
        val rawStartLine = payload.optIntOrNull("start_line")
        val rawEndLine = payload.optIntOrNull("end_line")
        val hasExplicitRange = rawStartLine != null || rawEndLine != null
        val firstLines = rawFirstLines?.takeIf { it > 0 || !hasExplicitRange }

        val targetFile = File(rawPath).canonicalFile
        checkSensitivePathConfirmation(payload, ReadFileToolName, targetFile, guard)?.let { return it }
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
        var contentChars = 0
        var contentTruncated = false
        targetFile.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            for ((index, line) in sequence.withIndex()) {
                val lineNumber = index + 1
                lastSeenLine = lineNumber
                if (lineNumber in actualStartLine..actualEndLine) {
                    // 行数上限约束不了单行极长的文件，字符层面再做兜底，避免膨胀上下文与持久化。
                    val clippedLine = if (line.length > MaxReadLineChars) {
                        contentTruncated = true
                        line.take(MaxReadLineChars) + "…[行过长已截断，原 ${line.length} 字符]"
                    } else {
                        line
                    }
                    selectedLines += "$lineNumber: $clippedLine"
                    contentChars += clippedLine.length
                    if (contentChars >= MaxReadTotalChars && lineNumber < actualEndLine) {
                        contentTruncated = true
                        hasMore = true
                        break
                    }
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
                "hasMore" to hasMore,
                "contentTruncated" to contentTruncated
            )
        )
        return JSONObject().apply {
            put("ok", true)
            put("path", targetFile.path.replace('\\', '/'))
            put("size", fileSize)
            put("total_lines", if (totalLinesKnown) lastSeenLine else JSONObject.NULL)
            put("total_lines_known", totalLinesKnown)
            put("has_more", hasMore)
            put("content_truncated", contentTruncated)
            put("start", if (selectedLines.isEmpty()) JSONObject.NULL else actualStartLine)
            put("end", if (selectedLines.isEmpty()) JSONObject.NULL else returnedEnd)
            put("lines", selectedLines.size)
            put("content", selectedLines.joinToString(separator = "\n"))
        }.toString(2)
    }

    private fun listPath(arguments: String, guard: SensitivePathGuard = SensitivePathGuard.Disabled): String {
        val payload = JSONObject(arguments)
        val rawPath = payload.optString("path").trim()
        require(rawPath.isNotEmpty()) { "path 不能为空。" }

        val limit = payload.optIntOrNull("limit") ?: 100
        require(limit > 0) { "limit 必须大于 0。" }
        require(limit <= MaxListEntries) { "limit 不能超过 $MaxListEntries。" }

        val target = File(rawPath).canonicalFile
        checkSensitivePathConfirmation(payload, ListPathToolName, target, guard)?.let { return it }
        require(target.exists()) { "路径不存在。" }
        require(target.canRead()) {
            "路径不可读，当前应用可能没有权限访问该路径。对于外部存储路径，请先在设置页授予文件管理应用权限。"
        }

        return if (target.isDirectory) {
            // 先物化 isDirectory，避免排序比较器对同一文件反复 stat。
            val children = target.listFiles()
                ?.map { child -> child to child.isDirectory }
                ?.sortedWith(
                    compareBy<Pair<File, Boolean>> { !it.second }
                        .thenBy { it.first.name.lowercase(Locale.ROOT) }
                )
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
                        children.take(limit).forEach { (child, isDirectory) ->
                            put(
                                JSONObject().apply {
                                    put("name", child.name)
                                    put("type", if (isDirectory) "dir" else "file")
                                    if (!isDirectory && child.isFile) {
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

    internal fun getCurrentTime(): String {
        val currentTime = OffsetDateTime.now()
        return JSONObject().apply {
            put("ok", true)
            put("current_time", currentTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("timezone", ZoneId.systemDefault().id)
            put("utc_offset", currentTime.offset.id)
            put("unix_timestamp_ms", currentTime.toInstant().toEpochMilli())
        }.toString(2)
    }

    internal fun fetchUrl(arguments: String): String = UrlFetchTools.fetchUrl(arguments)

    internal fun parseFetchWebViewOptions(arguments: String): UrlFetchTools.WebViewFetchOptions =
        UrlFetchTools.parseFetchWebViewOptions(arguments)

    internal fun webSearch(settings: ApiSettings, arguments: String): String =
        WebSearchTools.webSearch(settings, arguments)

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
}
