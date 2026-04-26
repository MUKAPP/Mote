package com.mukapp.mote.tools

import android.content.Context
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.util.optIntOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object LocalAiTools {
    private const val ReadFileToolName = "read_file"
    private const val ListPathToolName = "list_path"
    private const val ShellToolName = "shell"
    private const val ShellStatusToolName = "shell_status"
    private const val ShellStopToolName = "shell_stop"
    const val WaitToolName = "wait"

    private const val MaxReadLines = 400
    private const val MaxListEntries = 200
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

    private val cachedToolDefinitions: JSONArray by lazy {
        JSONArray()
            .put(buildReadFileDefinition())
            .put(buildListPathDefinition())
            .put(buildShellDefinition())
            .put(buildShellStatusDefinition())
            .put(buildShellStopDefinition())
            .put(buildWaitDefinition())
    }

    fun toolDefinitions(): JSONArray = cachedToolDefinitions

    fun executeToolCall(
        context: Context,
        toolCall: AiToolCall,
        onShellProcessStarted: ((String, Boolean) -> Unit)? = null
    ): ChatMessage {
        val output = runCatching {
            when (toolCall.name) {
                ReadFileToolName, "read_local_file" -> readFile(toolCall.arguments)
                ListPathToolName -> listPath(toolCall.arguments)
                ShellToolName -> runShell(toolCall.arguments, onShellProcessStarted)
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

    fun activatePendingShellConfirmations() {
        val now = System.currentTimeMillis()
        pendingShellConfirmations.entries.forEach { entry ->
            val confirmation = entry.value
            if (now - confirmation.createdAtMs > ShellConfirmationTtlMs) {
                pendingShellConfirmations.remove(entry.key)
            } else {
                confirmation.active = true
            }
        }
    }

    fun isShellConfirmationRequest(message: ChatMessage): Boolean {
        return message.toolName == ShellToolName && isShellConfirmationRequest(message.content)
    }

    fun isShellConfirmationRequest(content: String): Boolean {
        val payload = runCatching { JSONObject(content) }.getOrNull() ?: return false
        return !payload.optBoolean("ok", true) && payload.optBoolean("needs_confirmation", false)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun readFile(arguments: String): String {
        val payload = JSONObject(arguments)
        val rawPath = payload.optString("path").trim()
        require(rawPath.isNotEmpty()) { "path 不能为空。" }

        val firstLines = payload.optIntOrNull("first_lines")
        val rawStartLine = payload.optIntOrNull("start_line")
        val rawEndLine = payload.optIntOrNull("end_line")

        val targetFile = File(rawPath).canonicalFile
        require(targetFile.exists() && targetFile.isFile) { "文件不存在。" }
        require(targetFile.canRead()) {
            "文件不可读，当前应用可能没有权限访问该路径。对于外部存储路径，请先在设置页授予文件管理应用权限。"
        }

        // 未提供任何行范围参数时，默认读取前 200 行
        val defaultFirstLines = 200

        val (actualStartLine, actualEndLine) = if (firstLines != null) {
            require(firstLines > 0) { "first_lines 必须大于 0。" }
            require(firstLines <= MaxReadLines) { "first_lines 不能超过 $MaxReadLines。" }
            1 to firstLines
        } else if (rawStartLine != null || rawEndLine != null) {
            // 将 0 自动修正为 1，兼容 0-based 行号
            val startLine = maxOf(rawStartLine ?: 1, 1)
            val endLine = maxOf(rawEndLine ?: startLine, startLine)
            val requestedLines = endLine - startLine + 1
            require(requestedLines <= MaxReadLines) { "单次读取行数不能超过 $MaxReadLines。" }
            startLine to endLine
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

    private fun truncateOutput(text: String, maxChars: Int = MaxShellOutputChars): String {
        if (text.length <= maxChars) {
            return text
        }
        val half = maxChars / 2
        return text.take(half) + "\n... [输出已截断，共 ${text.length} 字符] ...\n" + text.takeLast(half)
    }

    private fun runShell(arguments: String, onShellProcessStarted: ((String, Boolean) -> Unit)?): String {
        val payload = JSONObject(arguments)
        val command = payload.optString("command").trim()
        require(command.isNotEmpty()) { "command 不能为空。" }

        val workDir = payload.optString("work_dir").trim().takeIf { it.isNotEmpty() }
        val background = payload.optBoolean("background", false)
        val confirmationId = payload.optString("confirmation_id").trim().takeIf { it.isNotEmpty() }
        val risk = detectShellRisk(command)
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

        val id = ShellProcessManager.start(command, workDir)
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

    private fun detectShellRisk(command: String): String? {
        val normalized = command.lowercase(Locale.ROOT)
        val checks = listOf(
            Regex("(^|[;&|()\\s])rm\\b") to "删除文件或目录",
            Regex("(^|[;&|()\\s])rm\\s+[^\n]*(^|\\s)-[a-z-]*r[fia-]*\\b") to "递归删除文件或目录",
            Regex("(^|[;&|()\\s])rm\\s+[^\n]*(/sdcard|/storage|/data|/system|\\*)") to "删除敏感路径或通配文件",
            Regex("(^|[;&|()\\s])rmdir\\b") to "删除目录",
            Regex("(^|[;&|()\\s])mv\\b") to "移动或覆盖文件",
            Regex("(^|[;&|()\\s])dd\\b") to "低级块设备或文件写入",
            Regex("(^|[;&|()\\s])mkfs(\\.|\\b)") to "格式化文件系统",
            Regex("(^|[;&|()\\s])truncate\\b") to "截断文件",
            Regex("(^|[;&|()\\s])chmod\\s+[^\n]*\\s-r\\b|(^|[;&|()\\s])chmod\\s+-[a-z]*r[a-z]*\\b") to "递归修改文件权限",
            Regex("(^|[;&|()\\s])chown\\s+[^\n]*\\s-r\\b|(^|[;&|()\\s])chown\\s+-[a-z]*r[a-z]*\\b") to "递归修改文件所有者",
            Regex("(^|[;&|()\\s])pm\\s+uninstall\\b") to "卸载应用包",
            Regex("(^|[;&|()\\s])(apt|apt-get|yum|dnf|pacman|apk)\\s+[^\n]*(remove|purge|erase|del)\\b") to "卸载系统软件包",
            Regex("(^|[^>])>>?\\s*[^&\\s]") to "重定向覆盖或追加文件",
            Regex("(^|[;&|()\\s])tee\\s+(-a\\s+)?[^\n]*") to "通过 tee 写入文件"
        )
        return checks.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalized) }?.second
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
                    put("description", "按行读取设备上当前应用有权限访问的文本文件内容。行号从 1 开始。如果不提供行范围参数，默认读取前 200 行。")
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
                                            put("description", "读取文件前多少行。与 start_line/end_line 二选一使用。")
                                        }
                                    )
                                    put(
                                        "start_line",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "起始行号，从 1 开始（传入 0 会自动修正为 1）。需要和 end_line 一起使用。")
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
                                            put("description", "执行高风险命令前由工具返回的确认 ID。只有用户确认后的下一轮对话才可使用。")
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
