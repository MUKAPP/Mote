package com.mukapp.mote.tools

import java.net.URI
import java.net.URL
import java.util.Locale

/** fetch / 搜索 / Shell 工具共用的小工具函数。 */
internal object ToolSupport {
    fun normalizeFetchUrl(rawUrl: String): URL {
        val uri = URI(rawUrl.trim()).normalize()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "url 只支持 http 或 https。" }
        require(!uri.host.isNullOrBlank()) { "url 缺少主机名。" }
        return uri.toURL()
    }

    /** 中间截断：保留首尾各一半，超长部分以省略提示替代。 */
    fun truncateOutput(text: String, maxChars: Int): String {
        if (text.length <= maxChars) {
            return text
        }
        val half = maxChars / 2
        return text.take(half) + "\n... [输出已截断，共 ${text.length} 字符] ...\n" + text.takeLast(half)
    }
}
