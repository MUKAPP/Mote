package com.mukapp.mote.tools

import com.mukapp.mote.util.MoteLog
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ShellProcessManager {
    private val processes = ConcurrentHashMap<String, ShellProcess>()
    private const val Component = "ShellProcess"
    private const val MaxOutputChars = 65536
    private const val MaxProcesses = 20

    // 共享读取线程池：reader 任务阻塞在 readLine 上，进程结束后线程可被后续进程复用，
    // 避免每个进程各建 2 条一次性裸线程。
    private val readerThreadIndex = AtomicInteger(0)
    private val readerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ShellReader-${readerThreadIndex.incrementAndGet()}")
    }

    data class ShellProcess(
        val id: String,
        val command: String,
        val process: Process,
        val startTimeMs: Long = System.currentTimeMillis(),
        val outputBuffer: StringBuilder = StringBuilder(),
        val errorBuffer: StringBuilder = StringBuilder(),
        @Volatile var outputFinished: Boolean = false,
        @Volatile var errorFinished: Boolean = false,
        /** 两条 reader 任务各 countDown 一次；进程退出后等待它读完管道残余再快照。 */
        val streamsDrained: CountDownLatch = CountDownLatch(2)
    ) {
        val isComplete: Boolean get() = !process.isAlive && outputFinished && errorFinished

        /** 保留输出的尾部快照，最多 [MaxOutputChars] 字符（缓冲内部允许冗余，见 appendWithTrim）。 */
        fun snapshotStdout(): String = snapshotTail(outputBuffer)
        fun snapshotStderr(): String = snapshotTail(errorBuffer)

        private fun snapshotTail(buffer: StringBuilder): String = synchronized(buffer) {
            if (buffer.length <= MaxOutputChars) {
                buffer.toString()
            } else {
                buffer.substring(buffer.length - MaxOutputChars)
            }
        }
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

        val stdout = entry.snapshotStdout()
        val stderr = entry.snapshotStderr()

        val truncatedStdout = truncateOutput(stdout, maxOutputChars)
        val truncatedStderr = truncateOutput(stderr, maxOutputChars)

        if (entry.isComplete) {
            // 完成条目保留在表中，结果可重复查询；由下次 start 前的 evictCompletedProcesses 回收，
            // 避免模型二次查询同一 id 时只得到“进程不存在”。
            MoteLog.i(
                Component,
                MoteLog.event(
                    "shell 进程已完成",
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

    /**
     * 停止并清空所有后台进程。ViewModel 销毁时调用，避免子进程随应用一起变成孤儿。
     */
    fun stopAll() {
        if (processes.isEmpty()) return
        val ids = processes.keys.toList()
        ids.forEach { id -> runCatching { stop(id) } }
        processes.clear()
        MoteLog.i(Component, MoteLog.event("已停止全部 shell 进程", "count" to ids.size))
    }

    private fun registerProcess(id: String, command: String, process: Process) {
        val entry = ShellProcess(id = id, command = command, process = process)
        processes[id] = entry

        readerExecutor.execute {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        appendWithTrim(entry.outputBuffer, line)
                    }
                }
            } catch (error: Exception) {
                MoteLog.w(Component, MoteLog.event("shell 标准输出读取异常", "id" to id), error)
            } finally {
                entry.outputFinished = true
                entry.streamsDrained.countDown()
                MoteLog.d(Component, MoteLog.event("shell 标准输出读取结束", "id" to id))
            }
        }

        readerExecutor.execute {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        appendWithTrim(entry.errorBuffer, line)
                    }
                }
            } catch (error: Exception) {
                MoteLog.w(Component, MoteLog.event("shell 标准错误读取异常", "id" to id), error)
            } finally {
                entry.errorFinished = true
                entry.streamsDrained.countDown()
                MoteLog.d(Component, MoteLog.event("shell 标准错误读取结束", "id" to id))
            }
        }
    }

    /**
     * 追加一行并按需裁剪。缓冲允许膨胀到 2 倍上限才一次性裁回 [MaxOutputChars]，
     * 每次裁剪至少移除 MaxOutputChars 字符，均摊每字符 O(1)，避免逐行整段搬移；
     * 对外快照经 snapshotTail 收口，仍只暴露最后 [MaxOutputChars] 字符。
     */
    private fun appendWithTrim(buffer: StringBuilder, line: String?) {
        synchronized(buffer) {
            buffer.appendLine(line)
            if (buffer.length > MaxOutputChars * 2) {
                buffer.delete(0, buffer.length - MaxOutputChars)
            }
        }
    }

    private fun truncateOutput(text: String, maxOutputChars: Int): String {
        if (text.length <= maxOutputChars) {
            return text
        }

        val half = maxOutputChars / 2
        return text.take(half) + "\n... [输出已截断，共 ${text.length} 字符] ...\n" + text.takeLast(half)
    }
}
