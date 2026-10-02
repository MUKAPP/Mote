package com.mukapp.mote.ui

internal object ConversationTitleFormatter {
    const val DefaultTitle = "新对话"
    const val MaxLength = 24

    /** 用户手动命名与模型标题统一使用 24 字符上限；手动输入不追加省略号。 */
    const val MaxUserTitleLength = MaxLength

    fun buildFallbackTitle(message: String): String {
        return normalize(message).ifBlank { DefaultTitle }
    }

    fun normalize(value: String): String {
        val compact = compact(value)
        return if (compact.length > MaxLength) {
            compact.take(MaxLength).trimEnd() + "..."
        } else {
            compact
        }
    }

    /** 归一化用户手动输入的标题：只压缩空白并截断，不裁剪引号标点，保留用户原意。 */
    fun normalizeUserTitle(value: String): String {
        return value
            .replace(Regex("[\r\n\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MaxUserTitleLength)
            .trimEnd()
    }

    private fun compact(value: String): String {
        return value
            .replace(Regex("[\r\n\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’', '。', '，', ',', '.', '、', ':', '：')
    }
}
