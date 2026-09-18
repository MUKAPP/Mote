package com.mukapp.mote.tools

import android.content.Context
import com.mukapp.mote.util.MoteLog
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** shell / shell_status / shell_stop 工具实现：命令执行、高风险确认拦截与后台进程查询。 */
internal object ShellTools {
    private const val Component = "Tools"
    private const val ShellShortTimeoutMs = 30_000L
    private const val MaxShellOutputChars = 8000

    fun runShell(context: Context, arguments: String, onShellProcessStarted: ((String, Boolean) -> Unit)?): String {
        val payload = JSONObject(arguments)
        val command = payload.optString("command").trim()
        require(command.isNotEmpty()) { "command 不能为空。" }

        val workDir = payload.optString("work_dir").trim().takeIf { it.isNotEmpty() }
        val background = payload.optBoolean("background", false)
        val confirmationId = payload.optString("confirmation_id").trim().takeIf { it.isNotEmpty() }
        val risk = ShellRiskDetector.detect(command)
        if (risk != null && !ToolConfirmations.consumeShell(confirmationId, command, workDir, background)) {
            val id = ToolConfirmations.newConfirmationId()
            ToolConfirmations.register(
                ToolConfirmations.PendingToolConfirmation.Shell(
                    id = id,
                    command = command,
                    workDir = workDir,
                    background = background,
                    risk = risk
                )
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
                put("confirmation_type", "shell")
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
            // 进程退出不代表 reader 线程已读完管道残余，等它 drain 完再快照，避免尾部输出丢失。
            val drained = entry.streamsDrained.await(2, TimeUnit.SECONDS)
            if (!drained) {
                MoteLog.w(Component, MoteLog.event("shell 输出流未在期限内读完", "id" to id))
            }
            val stdout = entry.snapshotStdout()
            val stderr = entry.snapshotStderr()

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

        val stdoutSoFar = entry.snapshotStdout()
        val stderrSoFar = entry.snapshotStderr()
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

    fun checkShellStatus(arguments: String): String {
        val payload = JSONObject(arguments)
        val id = payload.optString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空。" }
        MoteLog.d(Component, MoteLog.event("查询 Shell 进程状态", "id" to id))
        return ShellProcessManager.getStatus(id).toString(2)
    }

    fun stopShell(arguments: String): String {
        val payload = JSONObject(arguments)
        val id = payload.optString("id").trim()
        require(id.isNotEmpty()) { "id 不能为空。" }
        MoteLog.i(Component, MoteLog.event("请求停止 Shell 进程", "id" to id))
        return ShellProcessManager.stop(id).toString(2)
    }

    private fun truncateOutput(text: String, maxChars: Int = MaxShellOutputChars): String {
        return ToolSupport.truncateOutput(text, maxChars)
    }
}
