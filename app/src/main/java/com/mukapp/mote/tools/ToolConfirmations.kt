package com.mukapp.mote.tools

import com.mukapp.mote.util.MoteLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 待用户确认的工具操作令牌注册表（进程级单例）。
 * 令牌由工具执行侧登记、用户确认后激活、重试执行时按指纹匹配一次性消费。
 */
internal object ToolConfirmations {
    private const val Component = "Tools"
    private const val ToolConfirmationTtlMs = 10 * 60 * 1000L

    /** 待用户确认的工具操作令牌。sealed 载荷决定消费时的指纹匹配方式。 */
    sealed interface PendingToolConfirmation {
        val id: String
        val risk: String
        val createdAtMs: Long
        var active: Boolean

        data class Shell(
            override val id: String,
            val command: String,
            val workDir: String?,
            val background: Boolean,
            override val risk: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
            @Volatile override var active: Boolean = false
        ) : PendingToolConfirmation

        data class SensitivePath(
            override val id: String,
            /** 规范化工具名：read_local_file 别名归一为 read_file。 */
            val toolName: String,
            val canonicalPath: String,
            override val risk: String,
            override val createdAtMs: Long = System.currentTimeMillis(),
            @Volatile override var active: Boolean = false
        ) : PendingToolConfirmation
    }

    private val pendingToolConfirmations = ConcurrentHashMap<String, PendingToolConfirmation>()

    fun newConfirmationId(): String = "confirm_${UUID.randomUUID().toString().take(8)}"

    /** 登记新令牌，顺带清理过期令牌。 */
    fun register(confirmation: PendingToolConfirmation) {
        purgeExpired()
        pendingToolConfirmations[confirmation.id] = confirmation
    }

    fun activate(confirmationId: String): Boolean {
        val now = System.currentTimeMillis()
        val confirmation = pendingToolConfirmations[confirmationId] ?: run {
            MoteLog.w(
                Component,
                MoteLog.event("工具确认令牌不存在", "confirmationId" to MoteLog.shortId(confirmationId))
            )
            return false
        }
        if (now - confirmation.createdAtMs > ToolConfirmationTtlMs) {
            pendingToolConfirmations.remove(confirmationId)
            MoteLog.w(
                Component,
                MoteLog.event("工具确认令牌已过期", "confirmationId" to MoteLog.shortId(confirmationId))
            )
            return false
        }
        confirmation.active = true
        MoteLog.i(
            Component,
            MoteLog.event(
                "工具确认令牌已激活",
                "confirmationId" to MoteLog.shortId(confirmationId),
                "type" to confirmation.javaClass.simpleName,
                "risk" to confirmation.risk
            )
        )
        return true
    }

    /**
     * 清掉从未被查询过的过期令牌。
     * 令牌里带着完整命令文本或路径，而 [activate] 只在命中时才发现过期，
     * 没有这一步的话它们会随进程常驻。注册频率很低，不需要独立定时器。
     */
    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = pendingToolConfirmations.entries
            .filter { (_, confirmation) -> now - confirmation.createdAtMs > ToolConfirmationTtlMs }
            .map { (id, _) -> id }
        if (expired.isEmpty()) {
            return
        }
        expired.forEach { pendingToolConfirmations.remove(it) }
        MoteLog.d(
            Component,
            MoteLog.event("已清理过期工具确认令牌", "count" to expired.size)
        )
    }

    /** ViewModel 销毁时调用，避免命令文本或路径随进程常驻。 */
    fun discardAll() {
        val count = pendingToolConfirmations.size
        if (count == 0) {
            return
        }
        pendingToolConfirmations.clear()
        MoteLog.i(
            Component,
            MoteLog.event("已丢弃全部工具确认令牌", "count" to count)
        )
    }

    fun discard(confirmationId: String) {
        pendingToolConfirmations.remove(confirmationId)?.let { confirmation ->
            MoteLog.i(
                Component,
                MoteLog.event(
                    "工具确认令牌已丢弃",
                    "confirmationId" to MoteLog.shortId(confirmationId),
                    "type" to confirmation.javaClass.simpleName,
                    "risk" to confirmation.risk
                )
            )
        }
    }

    fun consumeShell(
        confirmationId: String?,
        command: String,
        workDir: String?,
        background: Boolean
    ): Boolean = consume(
        confirmationId = confirmationId,
        label = "Shell 确认",
        fields = arrayOf("commandHash" to MoteLog.fingerprint(command))
    ) { confirmation ->
        confirmation is PendingToolConfirmation.Shell &&
                confirmation.command == command &&
                confirmation.workDir == workDir &&
                confirmation.background == background
    }

    fun consumeSensitivePath(
        confirmationId: String?,
        toolName: String,
        canonicalPath: String
    ): Boolean = consume(
        confirmationId = confirmationId,
        label = "敏感路径确认",
        fields = arrayOf("tool" to toolName, "pathHash" to MoteLog.fingerprint(canonicalPath))
    ) { confirmation ->
        confirmation is PendingToolConfirmation.SensitivePath &&
                confirmation.toolName == toolName &&
                confirmation.canonicalPath == canonicalPath
    }

    private fun consume(
        confirmationId: String?,
        label: String,
        fields: Array<Pair<String, Any?>>,
        matches: (PendingToolConfirmation) -> Boolean
    ): Boolean {
        val id = confirmationId ?: return false
        val confirmation = pendingToolConfirmations[id] ?: return false
        val now = System.currentTimeMillis()
        if (!confirmation.active || now - confirmation.createdAtMs > ToolConfirmationTtlMs) {
            pendingToolConfirmations.remove(id)
            MoteLog.w(
                Component,
                MoteLog.event(
                    "${label}消费失败：未激活或已过期",
                    "confirmationId" to MoteLog.shortId(id),
                    "active" to confirmation.active
                )
            )
            return false
        }
        if (!matches(confirmation)) {
            MoteLog.w(
                Component,
                MoteLog.event(
                    "${label}消费失败：请求不匹配",
                    "confirmationId" to MoteLog.shortId(id),
                    *fields
                )
            )
            return false
        }
        pendingToolConfirmations.remove(id)
        MoteLog.i(
            Component,
            MoteLog.event(
                "${label}令牌已消费",
                "confirmationId" to MoteLog.shortId(id),
                *fields
            )
        )
        return true
    }
}
