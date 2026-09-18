package com.mukapp.mote.tools

import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.SearchProvider
import com.mukapp.mote.data.model.resolvedSearchProvider
import com.mukapp.mote.util.CleartextGuard
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.optIntOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** web_search 工具实现：SearXNG / Tavily / AnySearch 三个搜索提供商。 */
internal object WebSearchTools {
    private const val Component = "Tools"

    private const val DefaultSearchResultLimit = 5
    private const val MaxSearchResultLimit = 10
    private const val MaxTavilySearchResultLimit = 20
    private const val MaxSearchQueryChars = 300
    private const val MaxSearchSnippetChars = 600
    private const val MaxSearchRawContentChars = 4_000
    private const val MaxSearchResponseChars = 1_000_000
    private const val DefaultTavilySearchEndpoint = "https://api.tavily.com/search"
    private const val DefaultAnysearchSearchEndpoint = "https://api.anysearch.com/v1/search"

    internal var tavilySearchEndpoint: String = DefaultTavilySearchEndpoint
    internal var anysearchSearchEndpoint: String = DefaultAnysearchSearchEndpoint

    fun webSearch(settings: ApiSettings, arguments: String): String {
        return when (settings.resolvedSearchProvider()) {
            SearchProvider.Searxng -> searchWithSearxng(settings, arguments)
            SearchProvider.Tavily -> searchWithTavily(settings, arguments)
            SearchProvider.Anysearch -> searchWithAnysearch(settings, arguments)
            null -> throw IllegalArgumentException("搜索服务未配置，无法执行搜索。")
        }
    }

    /** 三个提供商共有的 query/limit 参数解析结果。 */
    private data class SearchQuery(
        val payload: JSONObject,
        val query: String,
        val limit: Int
    )

    private fun parseSearchQuery(arguments: String, maxLimit: Int): SearchQuery {
        val payload = JSONObject(arguments)
        val query = payload.optString("query").trim()
        require(query.isNotEmpty()) { "query 不能为空。" }
        require(query.length <= MaxSearchQueryChars) { "query 不能超过 $MaxSearchQueryChars 个字符。" }

        val limit = payload.optIntOrNull("limit") ?: DefaultSearchResultLimit
        require(limit > 0) { "limit 必须大于 0。" }
        require(limit <= maxLimit) { "limit 不能超过 $maxLimit。" }
        return SearchQuery(payload = payload, query = query, limit = limit)
    }

    private fun searchWithSearxng(settings: ApiSettings, arguments: String): String {
        val searxngBaseUrl = settings.searxngUrl.trim()
        require(searxngBaseUrl.isNotEmpty()) { "SearXNG 地址未配置，无法执行搜索。" }
        val startMs = System.currentTimeMillis()
        val request = parseSearchQuery(arguments, MaxSearchResultLimit)

        val page = request.payload.optIntOrNull("page") ?: 1
        require(page > 0) { "page 必须大于 0。" }
        require(page <= 20) { "page 不能超过 20。" }

        val searchUrl = buildSearxngSearchUrl(
            baseUrl = searxngBaseUrl,
            query = request.query,
            page = page,
            language = request.payload.optString("language").trim().takeIf { it.isNotEmpty() },
            categories = request.payload.optString("categories").trim().takeIf { it.isNotEmpty() },
            timeRange = request.payload.optString("time_range").trim().takeIf { it.isNotEmpty() },
            safesearch = request.payload.optIntOrNull("safesearch")
        )
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 web_search",
                "origin" to MoteLog.safeUrlOrigin(searxngBaseUrl),
                "queryLength" to request.query.length,
                "queryHash" to MoteLog.fingerprint(request.query),
                "page" to page,
                "limit" to request.limit
            )
        )
        return executeSearchRequest(
            errorName = "SearXNG",
            // SearXNG 的错误输出与日志历史格式不含 provider 字段，保持不变。
            providerKey = null,
            url = URL(searchUrl),
            apiKey = null,
            requestBody = null,
            startMs = startMs
        ) { root ->
            formatSearchResults(query = request.query, page = page, limit = request.limit, root = root)
        }
    }

    private fun searchWithTavily(settings: ApiSettings, arguments: String): String {
        val apiKey = settings.tavilyApiKey.trim()
        require(apiKey.isNotEmpty()) { "Tavily API Key 未配置，无法执行搜索。" }
        val startMs = System.currentTimeMillis()
        val request = parseSearchQuery(arguments, MaxTavilySearchResultLimit)

        val requestBody = buildTavilySearchRequest(request.payload, request.query, request.limit)
        val searchUrl = ToolSupport.normalizeFetchUrl(tavilySearchEndpoint)
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 web_search",
                "provider" to "Tavily",
                "origin" to MoteLog.safeUrlOrigin(searchUrl.toString()),
                "queryLength" to request.query.length,
                "queryHash" to MoteLog.fingerprint(request.query),
                "limit" to request.limit
            )
        )
        return executeSearchRequest(
            errorName = "Tavily",
            providerKey = "tavily",
            url = searchUrl,
            apiKey = apiKey,
            requestBody = requestBody,
            startMs = startMs
        ) { root ->
            formatTavilySearchResults(query = request.query, limit = request.limit, root = root)
        }
    }

    private fun searchWithAnysearch(settings: ApiSettings, arguments: String): String {
        val apiKey = settings.anysearchApiKey.trim()
        require(apiKey.isNotEmpty()) { "AnySearch API Key 未配置，无法执行搜索。" }
        val startMs = System.currentTimeMillis()
        val request = parseSearchQuery(arguments, MaxSearchResultLimit)

        val requestBody = JSONObject()
            .put("query", request.query)
            .put("max_results", request.limit)
        val searchUrl = ToolSupport.normalizeFetchUrl(anysearchSearchEndpoint)
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 web_search",
                "provider" to "AnySearch",
                "origin" to MoteLog.safeUrlOrigin(searchUrl.toString()),
                "queryLength" to request.query.length,
                "queryHash" to MoteLog.fingerprint(request.query),
                "limit" to request.limit
            )
        )
        return executeSearchRequest(
            errorName = "AnySearch",
            providerKey = "anysearch",
            url = searchUrl,
            apiKey = apiKey,
            requestBody = requestBody,
            startMs = startMs
        ) { root ->
            formatAnysearchResults(query = request.query, limit = request.limit, root = root)
        }
    }

    /**
     * 三个提供商共用的请求执行：连接配置、状态码与 JSON 解析检查、统一的错误输出与日志。
     * [requestBody] 非空时以 POST 发送（[apiKey] 非空则带 Bearer 头），否则 GET。
     * [providerKey] 写入错误 JSON 及日志的 provider 字段；为 null 时不写（SearXNG 历史格式）。
     */
    private fun executeSearchRequest(
        errorName: String,
        providerKey: String?,
        url: URL,
        apiKey: String?,
        requestBody: JSONObject?,
        startMs: Long,
        format: (root: JSONObject) -> String
    ): String {
        val providerFields: Array<Pair<String, Any?>> =
            if (providerKey == null) emptyArray() else arrayOf("provider" to errorName)
        CleartextGuard.requireCleartextAllowed(url.protocol, url.host, "$errorName 搜索请求")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mote/1.0")
            if (requestBody != null) {
                val requestBytes = requestBody.toString().toByteArray(StandardCharsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.outputStream.use { output -> output.write(requestBytes) }
            } else {
                connection.requestMethod = "GET"
            }

            val statusCode = connection.responseCode
            val responseText = readHttpResponseText(connection, statusCode, MaxSearchResponseChars)
            if (statusCode !in 200..299) {
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "web_search 请求失败",
                        *providerFields,
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(url.toString()),
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
                return JSONObject().apply {
                    put("ok", false)
                    providerKey?.let { put("provider", it) }
                    put("status", statusCode)
                    put("error", "$errorName 请求失败，HTTP $statusCode。")
                    put("body", ToolSupport.truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }

            val root = runCatching { JSONObject(responseText) }.getOrElse { error ->
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "web_search 响应 JSON 解析失败",
                        *providerFields,
                        "origin" to MoteLog.safeUrlOrigin(url.toString()),
                        "responseLength" to responseText.length,
                        "durationMs" to MoteLog.durationMs(startMs),
                        "error" to error
                    )
                )
                return JSONObject().apply {
                    put("ok", false)
                    providerKey?.let { put("provider", it) }
                    put("error", "$errorName 返回的内容不是有效 JSON：${error.message ?: "解析失败"}")
                    put("body", ToolSupport.truncateOutput(responseText, maxChars = 1200))
                }.toString(2)
            }
            format(root).also { output ->
                val returned = runCatching { JSONObject(output).optInt("returned", 0) }.getOrDefault(0)
                MoteLog.i(
                    Component,
                    MoteLog.event(
                        "web_search 完成",
                        *providerFields,
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(url.toString()),
                        "returned" to returned,
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun buildTavilySearchRequest(payload: JSONObject, query: String, limit: Int): JSONObject {
        return JSONObject().apply {
            put("query", query)
            put("max_results", limit)
            putOptionalTavilyEnum(
                payload = payload,
                target = this,
                key = "search_depth",
                allowedValues = setOf("basic", "advanced", "fast", "ultra-fast")
            )
            putOptionalTavilyEnum(
                payload = payload,
                target = this,
                key = "topic",
                allowedValues = setOf("general", "news", "finance")
            )
            putOptionalTavilyEnum(
                payload = payload,
                target = this,
                key = "time_range",
                allowedValues = setOf("day", "week", "month", "year", "d", "w", "m", "y")
            )
            putOptionalTavilyDate(payload, this, "start_date")
            putOptionalTavilyDate(payload, this, "end_date")
            putOptionalTavilyAnswer(payload, this)
            putOptionalTavilyInt(payload, this, "chunks_per_source", min = 1, max = 3)
            putOptionalTavilyDomains(payload, this, "include_domains")
            putOptionalTavilyDomains(payload, this, "exclude_domains")
        }
    }

    private fun putOptionalTavilyEnum(
        payload: JSONObject,
        target: JSONObject,
        key: String,
        allowedValues: Set<String>
    ) {
        val value = payload.optString(key).trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() } ?: return
        require(value in allowedValues) { "$key 只能是 ${allowedValues.joinToString(separator = "、")}。" }
        target.put(key, value)
    }

    private fun putOptionalTavilyDate(payload: JSONObject, target: JSONObject, key: String) {
        val value = payload.optString(key).trim().takeIf { it.isNotEmpty() } ?: return
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "$key 必须使用 YYYY-MM-DD 格式。" }
        target.put(key, value)
    }

    private fun putOptionalTavilyAnswer(payload: JSONObject, target: JSONObject) {
        if (!payload.has("include_answer")) {
            return
        }
        when (val value = payload.opt("include_answer")) {
            is Boolean -> target.put("include_answer", value)
            is String -> {
                val normalized = value.trim().lowercase(Locale.ROOT)
                require(normalized in setOf("true", "false", "basic", "advanced")) {
                    "include_answer 只能是 true、false、basic 或 advanced。"
                }
                if (normalized == "true" || normalized == "false") {
                    target.put("include_answer", normalized.toBoolean())
                } else {
                    target.put("include_answer", normalized)
                }
            }
            JSONObject.NULL -> Unit
            else -> throw IllegalArgumentException("include_answer 只能是布尔值、basic 或 advanced。")
        }
    }

    private fun putOptionalTavilyInt(payload: JSONObject, target: JSONObject, key: String, min: Int, max: Int) {
        val value = payload.optIntOrNull(key) ?: return
        require(value in min..max) { "$key 必须在 $min 到 $max 之间。" }
        target.put(key, value)
    }

    private fun putOptionalTavilyDomains(payload: JSONObject, target: JSONObject, key: String) {
        val domains = parseTavilyDomainList(payload, key) ?: return
        if (domains.length() > 0) {
            target.put(key, domains)
        }
    }

    private fun parseTavilyDomainList(payload: JSONObject, key: String): JSONArray? {
        if (!payload.has(key)) {
            return null
        }
        val output = JSONArray()
        when (val value = payload.opt(key)) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val domain = value.optString(index).trim()
                    require(domain.isNotEmpty()) { "$key 不能包含空域名。" }
                    output.put(domain)
                }
            }
            is String -> {
                value.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { domain -> output.put(domain) }
            }
            JSONObject.NULL -> Unit
            else -> throw IllegalArgumentException("$key 必须是逗号分隔字符串或字符串数组。")
        }
        return output
    }

    private fun buildSearxngSearchUrl(
        baseUrl: String,
        query: String,
        page: Int,
        language: String?,
        categories: String?,
        timeRange: String?,
        safesearch: Int?
    ): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        require(normalizedBaseUrl.startsWith("http://") || normalizedBaseUrl.startsWith("https://")) {
            "SearXNG 地址需要以 http:// 或 https:// 开头。"
        }

        val endpoint = if (normalizedBaseUrl.endsWith("/search")) {
            normalizedBaseUrl
        } else {
            "$normalizedBaseUrl/search"
        }
        val params = mutableListOf(
            "q" to query,
            "format" to "json",
            "pageno" to page.toString()
        )
        language?.let { params += "language" to it }
        categories?.let { params += "categories" to it }
        timeRange?.let { params += "time_range" to it }
        safesearch?.let { value ->
            require(value in 0..2) { "safesearch 只能是 0、1 或 2。" }
            params += "safesearch" to value.toString()
        }

        return "$endpoint?" + params.joinToString(separator = "&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private fun readHttpResponseText(connection: HttpURLConnection, statusCode: Int, maxChars: Int): String {
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return ""
        }
        val output = StringBuilder()
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (output.length <= maxChars) {
                if (Thread.interrupted()) {
                    throw InterruptedException("网络读取已被中断。")
                }
                val read = reader.read(buffer)
                if (read == -1) {
                    break
                }
                val remaining = maxChars - output.length
                output.appendRange(buffer, 0, minOf(read, remaining.coerceAtLeast(0)))
                if (read > remaining) {
                    break
                }
            }
        }
        return output.toString()
    }

    /**
     * 三个提供商共用的结果收集：跳过无标题无链接项，超出 [limit] 计入 skipped。
     * 返回结果数组与 has_more（原始条数多于收集条数或存在跳过项）。
     */
    private fun collectSearchResults(
        rawResults: JSONArray,
        limit: Int,
        buildItem: (item: JSONObject, title: String, url: String) -> JSONObject
    ): Pair<JSONArray, Boolean> {
        val results = JSONArray()
        var skipped = 0
        for (index in 0 until rawResults.length()) {
            val item = rawResults.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            if (title.isBlank() && url.isBlank()) {
                skipped++
                continue
            }
            if (results.length() >= limit) {
                skipped++
                continue
            }
            results.put(buildItem(item, title, url))
        }
        return results to (rawResults.length() > results.length() || skipped > 0)
    }

    private fun formatSearchResults(query: String, page: Int, limit: Int, root: JSONObject): String {
        val rawResults = root.optJSONArray("results") ?: JSONArray()
        val (results, hasMore) = collectSearchResults(rawResults, limit) { item, title, url ->
            JSONObject().apply {
                put("title", title)
                put("url", url)
                item.optString("content").trim().takeIf { it.isNotEmpty() }?.let { content ->
                    put("content", truncateSearchSnippet(content))
                }
                item.optString("engine").trim().takeIf { it.isNotEmpty() }?.let { put("engine", it) }
                item.optString("category").trim().takeIf { it.isNotEmpty() }?.let { put("category", it) }
                item.optString("publishedDate").trim().takeIf { it.isNotEmpty() }?.let {
                    put("publishedDate", it)
                }
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("provider", "searxng")
            put("query", root.optString("query").takeIf { it.isNotBlank() } ?: query)
            put("page", page)
            put("returned", results.length())
            put("available", root.optInt("number_of_results", rawResults.length()))
            put("has_more", hasMore)
            put("results", results)
            root.optJSONArray("answers")?.takeIf { it.length() > 0 }?.let { put("answers", it) }
            root.optJSONArray("suggestions")?.takeIf { it.length() > 0 }?.let { put("suggestions", it) }
            root.optJSONArray("infoboxes")?.takeIf { it.length() > 0 }?.let { put("infoboxes", it) }
        }.toString(2)
    }

    private fun formatTavilySearchResults(query: String, limit: Int, root: JSONObject): String {
        val rawResults = root.optJSONArray("results") ?: JSONArray()
        val (results, hasMore) = collectSearchResults(rawResults, limit) { item, title, url ->
            JSONObject().apply {
                put("title", title)
                put("url", url)
                item.optString("content").trim().takeIf { it.isNotEmpty() }?.let { content ->
                    put("content", truncateSearchSnippet(content))
                }
                val score = item.optDouble("score", Double.NaN)
                if (!score.isNaN() && !score.isInfinite()) {
                    put("score", score)
                }
                item.optString("published_date").trim().takeIf { it.isNotEmpty() }?.let {
                    put("published_date", it)
                }
                item.optString("raw_content").trim().takeIf { it.isNotEmpty() }?.let { rawContent ->
                    put("raw_content", truncateSearchRawContent(rawContent))
                }
                item.optString("favicon").trim().takeIf { it.isNotEmpty() }?.let { put("favicon", it) }
                item.optJSONArray("images")?.takeIf { it.length() > 0 }?.let { put("images", it) }
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("provider", "tavily")
            put("query", root.optString("query").takeIf { it.isNotBlank() } ?: query)
            put("returned", results.length())
            put("available", rawResults.length())
            put("has_more", hasMore)
            put("results", results)
            root.optString("answer").trim().takeIf { it.isNotEmpty() }?.let { put("answer", it) }
            root.optJSONArray("images")?.takeIf { it.length() > 0 }?.let { put("images", it) }
            root.optString("response_time").trim().takeIf { it.isNotEmpty() }?.let { put("response_time", it) }
            root.optJSONObject("auto_parameters")?.let { put("auto_parameters", it) }
            root.optJSONObject("usage")?.let { put("usage", it) }
        }.toString(2)
    }

    private fun formatAnysearchResults(query: String, limit: Int, root: JSONObject): String {
        val responseCode = root.optInt("code", 0)
        if (responseCode != 0) {
            return JSONObject().apply {
                put("ok", false)
                put("provider", "anysearch")
                put("error", root.optString("message").ifBlank { "AnySearch 搜索失败，错误码 $responseCode。" })
                root.optString("request_id").takeIf { it.isNotBlank() }?.let { put("request_id", it) }
            }.toString(2)
        }

        val data = root.optJSONObject("data") ?: JSONObject()
        val rawResults = data.optJSONArray("results") ?: JSONArray()
        val (results, hasMore) = collectSearchResults(rawResults, limit) { item, title, url ->
            JSONObject().apply {
                put("title", title)
                put("url", url)
                item.optString("snippet").trim().takeIf { it.isNotEmpty() }?.let { snippet ->
                    put("content", truncateSearchSnippet(snippet))
                }
                item.optString("content").trim().takeIf { it.isNotEmpty() }?.let { content ->
                    put("content", truncateSearchSnippet(content))
                }
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("provider", "anysearch")
            put("query", query)
            put("returned", results.length())
            put("available", data.optJSONObject("metadata")?.optInt("total_results", rawResults.length()) ?: rawResults.length())
            put("has_more", hasMore)
            put("results", results)
            root.optString("request_id").takeIf { it.isNotBlank() }?.let { put("request_id", it) }
            data.optJSONObject("metadata")?.let { metadata -> put("metadata", metadata) }
        }.toString(2)
    }

    private fun truncateSearchSnippet(text: String): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= MaxSearchSnippetChars) {
            compact
        } else {
            compact.take(MaxSearchSnippetChars) + "..."
        }
    }

    private fun truncateSearchRawContent(text: String): String {
        return if (text.length <= MaxSearchRawContentChars) {
            text
        } else {
            text.take(MaxSearchRawContentChars) + "..."
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
