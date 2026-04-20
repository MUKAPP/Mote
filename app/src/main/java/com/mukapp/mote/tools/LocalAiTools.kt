package com.mukapp.mote.tools

import android.content.Context
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.util.optIntOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
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

    fun executeToolCall(context: Context, toolCall: AiToolCall): ChatMessage {
        val output = runCatching {
            when (toolCall.name) {
                ReadFileToolName, "read_local_file" -> readFile(toolCall.arguments)
                ListPathToolName -> listPath(toolCall.arguments)
                ShellToolName -> runShell(toolCall.arguments)
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

    @Suppress("UNUSED_PARAMETER")
    private fun readFile(arguments: String): String {
        val payload = JSONObject(arguments)
        val rawPath = payload.optString("path").trim()
        require(rawPath.isNotEmpty()) { "path 不能为空。" }

        val firstLines = payload.optIntOrNull("first_lines")
        val startLine = payload.optIntOrNull("start_line")
        val endLine = payload.optIntOrNull("end_line")

        require(firstLines != null || startLine != null || endLine != null) {
            "必须提供 first_lines，或者同时提供 start_line 和 end_line。"
        }

        val targetFile = File(rawPath).canonicalFile
        require(targetFile.exists() && targetFile.isFile) { "文件不存在。" }
        require(targetFile.canRead()) {
            "文件不可读，当前应用可能没有权限访问该路径。对于外部存储路径，请先在设置页授予文件管理应用权限。"
        }

        val (actualStartLine, actualEndLine) = if (firstLines != null) {
            require(firstLines > 0) { "first_lines 必须大于 0。" }
            require(firstLines <= MaxReadLines) { "first_lines 不能超过 $MaxReadLines。" }
            1 to firstLines
        } else {
            require(startLine != null && endLine != null) {
                "如果不使用 first_lines，则必须同时提供 start_line 和 end_line。"
            }
            require(startLine > 0) { "start_line 必须大于 0。" }
            require(endLine >= startLine) { "end_line 不能小于 start_line。" }
            val requestedLines = endLine - startLine + 1
            require(requestedLines <= MaxReadLines) { "单次读取行数不能超过 $MaxReadLines。" }
            startLine to endLine
        }

        val selectedLines = mutableListOf<String>()
        var totalLines = 0
        targetFile.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            sequence.forEachIndexed { index, line ->
                val lineNumber = index + 1
                totalLines = lineNumber
                if (lineNumber in actualStartLine..actualEndLine) {
                    selectedLines += "$lineNumber: $line"
                }
                if (lineNumber >= actualEndLine) {
                    return@useLines
                }
            }
        }

        val actualReturnedEndLine = if (selectedLines.isEmpty()) {
            actualStartLine - 1
        } else {
            actualStartLine + selectedLines.size - 1
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", targetFile.path.replace('\\', '/'))
            put("requestedStartLine", actualStartLine)
            put("requestedEndLine", actualEndLine)
            put("returnedStartLine", if (selectedLines.isEmpty()) JSONObject.NULL else actualStartLine)
            put("returnedEndLine", if (selectedLines.isEmpty()) JSONObject.NULL else actualReturnedEndLine)
            put("returnedLineCount", selectedLines.size)
            put("totalLines", totalLines)
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

            JSONObject().apply {
                put("ok", true)
                put("path", target.path.replace('\\', '/'))
                put("type", "directory")
                put("returnedCount", minOf(children.size, limit))
                put("totalCount", children.size)
                put(
                    "entries",
                    JSONArray().apply {
                        children.take(limit).forEach { child ->
                            put(
                                JSONObject().apply {
                                    put("name", child.name)
                                    put("path", child.path.replace('\\', '/'))
                                    put("type", if (child.isDirectory) "directory" else "file")
                                    if (child.isFile) {
                                        put("size", child.length())
                                    }
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

    private fun runShell(arguments: String): String {
        val payload = JSONObject(arguments)
        val command = payload.optString("command").trim()
        require(command.isNotEmpty()) { "command 不能为空。" }

        val workDir = payload.optString("work_dir").trim().takeIf { it.isNotEmpty() }
        val background = payload.optBoolean("background", false)
        val id = ShellProcessManager.start(command, workDir)

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
                    put("description", "按行读取设备上当前应用有权限访问的文本文件内容")
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
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
                                            put("description", "起始行号，从 1 开始。需要和 end_line 一起使用。")
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
                            put("required", JSONArray().put("path"))
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
                            put("required", JSONArray().put("path"))
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
                                        "background",
                                        JSONObject().apply {
                                            put("type", "boolean")
                                            put("description", "是否在后台运行。长时间运行的命令应设为 true。")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("command"))
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
                                    put(
                                        "id",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "shell 命令返回的进程 ID")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("id"))
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
                                    put(
                                        "id",
                                        JSONObject().apply {
                                            put("type", "string")
                                            put("description", "要停止的进程 ID")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("id"))
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
                                    put(
                                        "seconds",
                                        JSONObject().apply {
                                            put("type", "integer")
                                            put("description", "等待的秒数，1 到 3600")
                                        }
                                    )
                                }
                            )
                            put("required", JSONArray().put("seconds"))
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }
}
