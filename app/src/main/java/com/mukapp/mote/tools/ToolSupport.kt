package com.mukapp.mote.tools

import java.net.InetAddress
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

    fun isLocalAddress(address: InetAddress): Boolean {
        val bytes = address.address
        // IPv4-mapped IPv6 需要按嵌入的 IPv4 判断，不能只依赖 Inet6Address 的分类。
        val mappedIpv4 = bytes.size == 16 &&
            (0 until 10).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        if (bytes.size == 4 || mappedIpv4) {
            val offset = if (mappedIpv4) 12 else 0
            val first = bytes[offset].toInt() and 0xff
            val second = bytes[offset + 1].toInt() and 0xff
            return first == 0 || first == 10 || first == 127 ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                first in 224..239 ||
                (first == 100 && second in 64..127) ||
                (first == 198 && second in 18..19)
        }
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    /** 主机名仅检查后缀；真正连接使用的 DNS 结果由公网 client 的 Dns 检查。 */
    fun requirePublicHost(host: String) {
        val normalizedHost = host.removePrefix("[").removeSuffix("]")
            .trimEnd('.').lowercase(Locale.ROOT)
        require(normalizedHost != "localhost" && !normalizedHost.endsWith(".localhost") &&
            normalizedHost != "local" && !normalizedHost.endsWith(".local") &&
            normalizedHost != "home.arpa" && !normalizedHost.endsWith(".home.arpa")
        ) { "当前设置不允许抓取本机或局域网地址。" }
        val literalAddress = when {
            ':' in normalizedHost -> InetAddress.getByName(normalizedHost)
            else -> {
                val octets = normalizedHost.split('.')
                if (octets.size == 4 && octets.all { octet ->
                        octet.isNotEmpty() && octet.all { it in '0'..'9' } &&
                            octet.toIntOrNull()?.let { it in 0..255 } == true
                    }
                ) {
                    InetAddress.getByAddress(ByteArray(4) { octets[it].toInt().toByte() })
                } else {
                    null
                }
            }
        }
        require(literalAddress == null || !isLocalAddress(literalAddress)) {
            "当前设置不允许抓取本机或局域网地址。"
        }
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
