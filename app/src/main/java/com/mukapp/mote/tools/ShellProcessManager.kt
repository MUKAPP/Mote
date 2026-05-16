package com.mukapp.mote.tools

import com.mukapp.mote.util.MoteLog
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ShellProcessManager {
    private val processes = ConcurrentHashMap<String, ShellProcess>()
    private const val Component = "ShellProcess"
    private const val MaxOutputChars = 65536
    private const val MaxProcesses = 20

    data class ShellProcess(
        val id: String,
        val command: String,
        val process: Process,
        val startTimeMs: Long = System.currentTimeMillis(),
        val outputBuffer: StringBuilder = StringBuilder(),
        val errorBuffer: StringBuilder = StringBuilder(),
        @Volatile var outputFinished: Boolean = false,
        @Volatile var errorFinished: Boolean = false
    ) {
        val isComplete: Boolean get() = !process.isAlive && outputFinished && errorFinished
    }

    fun start(command: String, workDir: String? = null, environment: Map<String, String> = emptyMap()): String {
        val workingDirectory = workDir?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
        require(workingDirectory == null || workingDirectory.exists()) { "工作目录不存在。" }
        require(workingDirectory == null || workingDirectory.isDirectory) { "工作目录不是目录。" }

        evictCompletedProcesses()
        if (processes.size >= MaxProcesses) {
            MoteLog.w(
                Component,
                MoteLog.event("后台 shell 进程数量达到上限", "max" to MaxProcesses)
            )
        }
        require(processes.size < MaxProcesses) { "后台进程数量已达上限，请先停止或查询已有进程。" }

        val id = generateId()
        val builder = ProcessBuilder("sh", "-c", command)
        if (environment.isNotEmpty()) {
            builder.environment().putAll(environment)
        }
        workingDirectory?.let { builder.directory(it) }
        val process = builder.start()
        registerProcess(id, command, process)
        MoteLog.i(
            Component,
            MoteLog.event(
                "已启动 shell 进程",
                "id" to id,
                "commandHash" to MoteLog.fingerprint(command),
                "hasWorkDir" to (workingDirectory != null),
                "environmentKeys" to environment.keys.sorted().joinToString(separator = "+"),
                "activeProcesses" to processes.size
            )
        )
        return id
    }

    fun getProcess(id: String): ShellProcess? = processes[id]

    fun remove(id: String) {
        processes.remove(id)?.let { entry ->
            MoteLog.d(
                Component,
                MoteLog.event(
                    "已移除 shell 进程",
                    "id" to id,
                    "elapsedSeconds" to ((System.currentTimeMillis() - entry.startTimeMs) / 1000)
                )
            )
        }
    }

    fun generateId(): String = "shell_${UUID.randomUUID().toString().take(8)}"

    private fun evictCompletedProcesses() {
        val completed = processes.entries.filter { it.value.isComplete }
        completed.forEach { processes.remove(it.key) }
        if (completed.isNotEmpty()) {
            MoteLog.d(Component, MoteLog.event("已清理完成的 shell 进程", "count" to completed.size))
        }
    }

    fun getStatus(id: String, maxOutputChars: Int = 8000): JSONObject {
        val entry = processes[id]
            ?: return JSONObject().apply {
                put("ok", false)
                put("error", "进程 $id 不存在")
            }

        val isAlive = entry.process.isAlive
        val exitCode = if (!isAlive) entry.process.exitValue() else null

        val stdout: String
        val stderr: String
        synchronized(entry.outputBuffer) { stdout = entry.outputBuffer.toString() }
        synchronized(entry.errorBuffer) { stderr = entry.errorBuffer.toString() }

        val truncatedStdout = truncateOutput(stdout, maxOutputChars)
        val truncatedStderr = truncateOutput(stderr, maxOutputChars)

        if (entry.isComplete) {
            processes.remove(id)
            MoteLog.i(
                Component,
                MoteLog.event(
                    "shell 进程已完成并清理",
                    "id" to id,
                    "exitCode" to exitCode,
                    "elapsedSeconds" to ((System.currentTimeMillis() - entry.startTimeMs) / 1000)
                )
            )
        }

        MoteLog.d(
            Component,
            MoteLog.event(
                "已查询 shell 进程状态",
                "id" to id,
                "running" to isAlive,
                "stdoutChars" to stdout.length,
                "stderrChars" to stderr.length
            )
        )

        return JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("command", entry.command)
            put("running", isAlive)
            put("outputComplete", entry.outputFinished)
            put("errorComplete", entry.errorFinished)
            put("exitCode", exitCode)
            put("elapsedSeconds", (System.currentTimeMillis() - entry.startTimeMs) / 1000)
            put("stdout", truncatedStdout)
            put("stderr", truncatedStderr)
        }
    }

    fun stop(id: String): JSONObject {
        val entry = processes[id]
            ?: return JSONObject().apply {
                put("ok", false)
                put("error", "进程 $id 不存在")
            }

        if (!entry.process.isAlive) {
            val exitCode = entry.process.exitValue()
            processes.remove(id)
            MoteLog.i(
                Component,
                MoteLog.event("shell 进程已结束，无需停止", "id" to id, "exitCode" to exitCode)
            )
            return JSONObject().apply {
                put("ok", true)
                put("message", "进程已结束，无需停止")
                put("exitCode", exitCode)
            }
        }

        entry.process.destroy()
        val exited = try {
            entry.process.waitFor(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited) {
            entry.process.destroyForcibly()
        }
        processes.remove(id)
        MoteLog.i(
            Component,
            MoteLog.event("已停止 shell 进程", "id" to id, "forced" to !exited)
        )
        return JSONObject().apply {
            put("ok", true)
            put("message", if (exited) "进程已停止" else "进程已强制终止")
        }
    }

    private fun registerProcess(id: String, command: String, process: Process) {
        val entry = ShellProcess(id = id, command = command, process = process)
        processes[id] = entry

        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        synchronized(entry.outputBuffer) {
                            entry.outputBuffer.appendLine(line)
                            if (entry.outputBuffer.length > MaxOutputChars) {
                                entry.outputBuffer.delete(0, entry.outputBuffer.length - MaxOutputChars)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                MoteLog.w(Component, MoteLog.event("shell 标准输出读取异常", "id" to id), error)
            } finally {
                entry.outputFinished = true
                MoteLog.d(Component, MoteLog.event("shell 标准输出读取结束", "id" to id))
            }
        }, "ShellStdout-$id").start()

        Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        synchronized(entry.errorBuffer) {
                            entry.errorBuffer.appendLine(line)
                            if (entry.errorBuffer.length > MaxOutputChars) {
                                entry.errorBuffer.delete(0, entry.errorBuffer.length - MaxOutputChars)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                MoteLog.w(Component, MoteLog.event("shell 标准错误读取异常", "id" to id), error)
            } finally {
                entry.errorFinished = true
                MoteLog.d(Component, MoteLog.event("shell 标准错误读取结束", "id" to id))
            }
        }, "ShellStderr-$id").start()
    }

    private fun truncateOutput(text: String, maxOutputChars: Int): String {
        if (text.length <= maxOutputChars) {
            return text
        }

        val half = maxOutputChars / 2
        return text.take(half) + "\n... [输出已截断，共 ${text.length} 字符] ...\n" + text.takeLast(half)
    }
}
