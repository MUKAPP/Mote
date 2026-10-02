package com.mukapp.mote.ui.markdown

import org.json.JSONException
import org.json.JSONObject

internal object ToolDetailFormatter {
    const val MaxDisplayChars = 5_000

    data class Preview(val text: String, val totalChars: Int) {
        val isTruncated: Boolean get() = totalChars > text.length
    }

    fun arguments(raw: String): Preview {
        if (raw.isBlank()) {
            return Preview("", 0)
        }
        val source = if (raw.length <= MaxDisplayChars) {
            try {
                JSONObject(raw).toString(2)
            } catch (_: JSONException) {
                raw
            }
        } else {
            raw
        }
        return Preview(source.take(MaxDisplayChars), source.length)
    }

    fun result(raw: String): Preview = Preview(raw.take(MaxDisplayChars), raw.length)

    fun fullText(
        arguments: String,
        result: String,
        parametersLabel: String,
        resultLabel: String
    ): String = buildString {
        if (arguments.isNotBlank()) {
            append(parametersLabel)
            append('\n')
            append(arguments)
            append("\n\n")
        }
        append(resultLabel)
        append('\n')
        append(result)
    }
}
