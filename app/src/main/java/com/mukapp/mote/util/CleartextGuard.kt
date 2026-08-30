package com.mukapp.mote.util

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * 明文 HTTP 放行策略。
 *
 * Android 的 network-security-config 只能按主机名做后缀匹配，无法表达「仅允许私有网段」，
 * 因此「局域网自建服务（Ollama / LM Studio / SearXNG）可用明文」与「禁止把密钥明文发到公网」
 * 这两个需求无法同时由 NSC 满足。这里在应用层做精确判断：
 * 携带凭据或查询内容的请求，只允许在回环、链路本地和私有网段上使用明文 HTTP。
 *
 * 不适用于 `fetch_url` / `fetch_webview`——那两个工具不携带任何凭据，
 * 抓取明文公网页面是正常用法，不应拦截。
 */
object CleartextGuard {

    private val Ipv4Literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** 常见的内网专用域名后缀（mDNS 与 RFC 8375）。 */
    private val PrivateHostSuffixes = listOf(".local", ".lan", ".internal", ".home.arpa")

    /** 目标是否位于私有网络，可安全使用明文 HTTP。 */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (host.isEmpty()) {
            return false
        }
        if (host == "localhost") {
            return true
        }

        numericAddressOrNull(host)?.let { address ->
            return address.isLoopbackAddress ||
                address.isAnyLocalAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                isUniqueLocalIpv6(address)
        }

        return PrivateHostSuffixes.any { suffix -> host.endsWith(suffix) }
    }

    /** HTTPS 一律放行；HTTP 仅在私有网段放行。 */
    fun isCleartextAllowed(scheme: String, host: String): Boolean {
        if (!scheme.equals("http", ignoreCase = true)) {
            return true
        }
        return isPrivateHost(host)
    }

    fun requireCleartextAllowed(scheme: String, host: String, usage: String) {
        require(isCleartextAllowed(scheme, host)) { blockedMessage(host, usage) }
    }

    fun blockedMessage(host: String, usage: String): String {
        return "$usage 使用明文 HTTP 访问公网地址（$host），已阻止以避免密钥或查询内容被明文传输。请改用 HTTPS，或将服务部署在局域网内。"
    }

    /**
     * 仅在字符串本身就是 IP 字面量时解析，避免触发 DNS 查询。
     * [InetAddress.getByName] 对数值地址是纯解析，不会发起网络请求。
     */
    private fun numericAddressOrNull(host: String): InetAddress? {
        val looksNumeric = Ipv4Literal.matches(host) || host.contains(':')
        if (!looksNumeric) {
            return null
        }
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    /** fc00::/7 唯一本地地址，Java 标准库没有对应判定。 */
    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        if (address !is Inet6Address) {
            return false
        }
        return (address.address.firstOrNull()?.toInt() ?: 0) and 0xFE == 0xFC
    }
}
