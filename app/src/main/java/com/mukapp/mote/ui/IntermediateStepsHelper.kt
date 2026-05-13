package com.mukapp.mote.ui

import org.json.JSONObject

object IntermediateStepsHelper {
    fun parseToolSummary(toolName: String, arguments: String): String {
        if (arguments.isBlank()) {
            return toolName
        }

        val json = runCatching { JSONObject(arguments) }.getOrNull() ?: return "$toolName: $arguments"
        json.optString("description").trim().takeIf { it.isNotEmpty() }?.let { return it }

        return when (toolName) {
            "read_file" -> {
                val path = json.optString("path", "")
                val firstLines = json.optInt("first_lines", 0)
                val startLine = json.optInt("start_line", 0)
                val endLine = json.optInt("end_line", 0)
                val lineInfo = when {
                    firstLines > 0 -> " 前${firstLines}行"
                    startLine > 0 && endLine > 0 -> " 行${startLine}-${endLine}"
                    else -> ""
                }
                "read_file: $path$lineInfo"
            }

            "list_path" -> "list_path: ${json.optString("path", "")}"
            "shell" -> "shell: ${json.optString("command", "").trim()}"
            "shell_status" -> "shell_status: 进程 ${json.optString("id", "")}"
            "shell_stop" -> "shell_stop: 进程 ${json.optString("id", "")}"
            "wait" -> "wait: ${json.optInt("seconds", 0)}秒"
            "web_search" -> "web_search: ${json.optString("query", "").trim()}"
            else -> {
                val firstValue = json.keys().asSequence().firstOrNull()
                    ?.let { key -> json.optString(key, "") }
                    ?.takeIf { it.isNotBlank() }
                if (firstValue != null) "$toolName: $firstValue" else toolName
            }
        }
    }
}
