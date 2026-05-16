package com.mukapp.mote.util

import android.util.Log
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object MoteLog {
    private const val RootTag = "Mote"
    private const val MaxLogMessageLength = 3500
    private const val MaxFieldValueLength = 160

    fun d(component: String, message: String, error: Throwable? = null) {
        log(Log.DEBUG, component, message, error)
    }

    fun i(component: String, message: String, error: Throwable? = null) {
        log(Log.INFO, component, message, error)
    }

    fun w(component: String, message: String, error: Throwable? = null) {
        log(Log.WARN, component, message, error)
    }

    fun e(component: String, message: String, error: Throwable? = null) {
        log(Log.ERROR, component, message, error)
    }

    fun event(action: String, vararg fields: Pair<String, Any?>): String {
        val metadata = formatFields(fields)
        return if (metadata.isBlank()) action else "$action：$metadata"
    }

    fun fields(vararg fields: Pair<String, Any?>): String = formatFields(fields)

    fun shortId(value: String?, length: Int = 8): String {
        val normalized = value.orEmpty().trim()
        if (normalized.isBlank()) {
            return "空"
        }
        return if (normalized.length <= length) normalized else normalized.take(length)
    }

    fun safeUrlOrigin(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return "未配置"
        }
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
            val host = uri.host.orEmpty()
            if (scheme.isBlank() || host.isBlank()) {
                return@runCatching "已配置"
            }
            val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
            "$scheme://$host$port"
        }.getOrDefault("已配置")
    }

    fun fingerprint(value: String): String {
        if (value.isBlank()) {
            return "空"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(12)
    }

    fun durationMs(startMs: Long): Long {
        return (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
    }

    private fun formatFields(fields: Array<out Pair<String, Any?>>): String {
        return fields
            .filter { (key, _) -> key.isNotBlank() }
            .joinToString(separator = ", ") { (key, value) -> "$key=${formatFieldValue(value)}" }
    }

    private fun formatFieldValue(value: Any?): String {
        val text = when (value) {
            null -> "null"
            is Boolean, is Number -> value.toString()
            is Throwable -> value.javaClass.simpleName
            is Collection<*> -> "${value.size}项"
            is Map<*, *> -> "${value.size}项"
            else -> value.toString()
        }.replace(Regex("\\s+"), " ").trim()

        return if (text.length <= MaxFieldValueLength) {
            text
        } else {
            text.take(MaxFieldValueLength) + "..."
        }
    }

    private fun log(level: Int, component: String, message: String, error: Throwable?) {
        val tag = buildTag(component)
        val safeMessage = message.ifBlank { "日志事件" }
        if (safeMessage.length <= MaxLogMessageLength) {
            write(level, tag, safeMessage, error)
            return
        }

        val chunks = safeMessage.chunked(MaxLogMessageLength)
        chunks.forEachIndexed { index, chunk ->
            val chunkMessage = "$chunk (${index + 1}/${chunks.size})"
            write(level, tag, chunkMessage, if (index == 0) error else null)
        }
    }

    private fun buildTag(component: String): String {
        val normalized = component.trim().takeIf { it.isNotBlank() } ?: return RootTag
        return "$RootTag.$normalized"
    }

    private fun write(level: Int, tag: String, message: String, error: Throwable?) {
        runCatching {
            when (level) {
                Log.DEBUG -> if (error == null) Log.d(tag, message) else Log.d(tag, message, error)
                Log.INFO -> if (error == null) Log.i(tag, message) else Log.i(tag, message, error)
                Log.WARN -> if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
                Log.ERROR -> if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
                else -> Log.println(level, tag, message)
            }
        }
    }
}
