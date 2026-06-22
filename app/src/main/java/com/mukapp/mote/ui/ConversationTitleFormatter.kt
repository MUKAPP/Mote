package com.mukapp.mote.ui

internal object ConversationTitleFormatter {
    const val DefaultTitle = "新对话"
    const val MaxLength = 24

    fun buildFallbackTitle(message: String): String {
        return normalize(message).ifBlank { DefaultTitle }
    }

    fun normalize(value: String): String {
        val compact = value
            .replace(Regex("[\r\n\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', '。', '，', ',', '.', '、', ':', '：')
        return if (compact.length > MaxLength) {
            compact.take(MaxLength).trimEnd() + "..."
        } else {
            compact
        }
    }
}
