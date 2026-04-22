package com.mukapp.mote.tools

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ShellProcessManager {
    private val processes = ConcurrentHashMap<String, ShellProcess>()
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

    fun start(command: String, workDir: String? = null): String {
        val id = generateId()
        val builder = ProcessBuilder("sh", "-c", command)
        workDir?.let { builder.directory(File(it)) }
        val process = builder.start()
        evictCompletedProcesses()
        registerProcess(id, command, process)
        return id
    }

    fun getProcess(id: String): ShellProcess? = processes[id]

    fun remove(id: String) {
        processes.remove(id)
    }

    fun generateId(): String = "shell_${System.currentTimeMillis()}"

    private fun evictCompletedProcesses() {
        val completed = processes.entries.filter { it.value.isComplete }
        completed.forEach { processes.remove(it.key) }
        if (processes.size >= MaxProcesses) {
            val oldest = processes.entries.sortedBy { it.value.startTimeMs }
            for (i in 0 until minOf(oldest.size, processes.size - MaxProcesses + 1)) {
                val entry = oldest[i]
                entry.value.process.destroyForcibly()
                processes.remove(entry.key)
            }
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
        }

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
            return JSONObject().apply {
                put("ok", true)
                put("message", "进程已结束，无需停止")
                put("exitCode", exitCode)
            }
        }

        entry.process.destroy()
        return JSONObject().apply {
            put("ok", true)
            put("message", "已发送停止信号")
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
                Log.w("ShellProcess", "进程 $id 标准输出读取异常: ${error.message}")
            } finally {
                entry.outputFinished = true
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
                Log.w("ShellProcess", "进程 $id 标准错误读取异常: ${error.message}")
            } finally {
                entry.errorFinished = true
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
