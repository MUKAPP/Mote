package com.mukapp.mote.tools

import com.sun.net.httpserver.HttpServer
import com.mukapp.mote.data.model.ApiSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class LocalAiToolsTest {

    @Test
    fun toolDefinitionsExposeFetchUrlByDefault() {
        val definitions = LocalAiTools.toolDefinitions()

        assertTrue(definitions.hasTool("fetch_url"))
    }

    @Test
    fun toolDefinitionsExposeFetchWebViewByDefault() {
        val definitions = LocalAiTools.toolDefinitions()

        assertTrue(definitions.hasTool("fetch_webview"))
    }

    @Test
    fun toolDefinitionsDoNotExposeWebSearchWhenSearxngUrlIsBlank() {
        val definitions = LocalAiTools.toolDefinitions(ApiSettings(searxngUrl = ""))

        assertFalse(definitions.hasTool("web_search"))
    }

    @Test
    fun toolDefinitionsExposeWebSearchWhenSearxngUrlIsConfigured() {
        val definitions = LocalAiTools.toolDefinitions(
            ApiSettings(searxngUrl = "https://searx.example.org")
        )

        assertTrue(definitions.hasTool("web_search"))
    }

    @Test
    fun webSearchRejectsBlankSearxngUrl() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.webSearch(
                settings = ApiSettings(searxngUrl = ""),
                arguments = JSONObject()
                    .put("description", "搜索 Kotlin 新闻")
                    .put("query", "Kotlin news")
                    .toString()
            )
        }

        assertEquals("SearXNG 地址未配置，无法执行搜索。", error.message)
    }

    @Test
    fun fetchUrlRejectsNonHttpScheme() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.fetchUrl(
                JSONObject()
                    .put("description", "读取本地文件")
                    .put("url", "file:///sdcard/test.txt")
                    .toString()
            )
        }

        assertEquals("url 只支持 http 或 https。", error.message)
    }

    @Test
    fun fetchWebViewOptionsRejectNonHttpScheme() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.parseFetchWebViewOptions(
                JSONObject()
                    .put("description", "渲染本地文件")
                    .put("url", "file:///sdcard/index.html")
                    .toString()
            )
        }

        assertEquals("url 只支持 http 或 https。", error.message)
    }

    @Test
    fun fetchWebViewOptionsValidateTimeoutAndSettleMs() {
        val timeoutError = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.parseFetchWebViewOptions(
                JSONObject()
                    .put("description", "渲染网页")
                    .put("url", "https://example.org")
                    .put("timeout_seconds", 61)
                    .toString()
            )
        }
        val settleError = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.parseFetchWebViewOptions(
                JSONObject()
                    .put("description", "渲染网页")
                    .put("url", "https://example.org")
                    .put("settle_ms", 10_001)
                    .toString()
            )
        }

        assertEquals("timeout_seconds 不能超过 60。", timeoutError.message)
        assertEquals("settle_ms 不能超过 10000。", settleError.message)
    }

    @Test
    fun fetchWebViewOptionsParseDefaultsAndOutputFormat() {
        val options = LocalAiTools.parseFetchWebViewOptions(
            JSONObject()
                .put("description", "渲染网页")
                .put("url", "https://example.org/page")
                .put("output_format", "markdown")
                .put("max_chars", 1234)
                .toString()
        )

        assertEquals("https://example.org/page", options.url.toString())
        assertEquals("markdown", options.outputFormat)
        assertEquals(1234, options.maxChars)
        assertEquals(20, options.timeoutSeconds)
        assertEquals(1000, options.settleMs)
    }

    @Test
    fun fetchUrlExtractsPlainTextFromHtmlByDefault() {
        val server = createFetchTestServer()
        server.start()
        try {
            val result = JSONObject(
                LocalAiTools.fetchUrl(
                    JSONObject()
                        .put("description", "读取 HTML 文本")
                        .put("url", "http://127.0.0.1:${server.address.port}/html")
                        .toString()
                )
            )

            assertEquals(true, result.getBoolean("ok"))
            assertEquals("text", result.getString("output_format"))
            assertTrue(result.getString("content").contains("标题 & 内容"))
            assertTrue(result.getString("content").contains("第一段文本"))
            assertFalse(result.getString("content").contains("secret"))
            assertFalse(result.getString("content").contains("<h1>"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchUrlReturnsRawResponseText() {
        val server = createFetchTestServer()
        server.start()
        try {
            val result = JSONObject(
                LocalAiTools.fetchUrl(
                    JSONObject()
                        .put("description", "读取原始 HTML")
                        .put("url", "http://127.0.0.1:${server.address.port}/html")
                        .put("output_format", "raw")
                        .toString()
                )
            )

            assertEquals(true, result.getBoolean("ok"))
            assertEquals("raw", result.getString("output_format"))
            assertTrue(result.getString("content").contains("<h1>标题 &amp; 内容</h1>"))
            assertTrue(result.getString("content").contains("secret"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchUrlConvertsHtmlToMarkdown() {
        val server = createFetchTestServer()
        server.start()
        try {
            val result = JSONObject(
                LocalAiTools.fetchUrl(
                    JSONObject()
                        .put("description", "读取 Markdown")
                        .put("url", "http://127.0.0.1:${server.address.port}/html")
                        .put("output_format", "markdown")
                        .toString()
                )
            )

            assertEquals(true, result.getBoolean("ok"))
            assertEquals("markdown", result.getString("output_format"))
            assertEquals(true, result.getBoolean("converted"))
            assertTrue(result.getString("content").contains("标题"))
            assertTrue(result.getString("content").contains("内容"))
            assertTrue(result.getString("content").contains("[链接](https://example.org/page)"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fetchUrlFollowsRedirectsAndTruncatesContent() {
        val server = createFetchTestServer()
        server.start()
        try {
            val result = JSONObject(
                LocalAiTools.fetchUrl(
                    JSONObject()
                        .put("description", "读取跳转文本")
                        .put("url", "http://127.0.0.1:${server.address.port}/redirect")
                        .put("max_chars", 5)
                        .toString()
                )
            )

            assertEquals(true, result.getBoolean("ok"))
            assertEquals("http://127.0.0.1:${server.address.port}/plain", result.getString("final_url"))
            assertEquals(1, result.getJSONArray("redirects").length())
            assertEquals(true, result.getBoolean("truncated"))
            assertEquals("纯文本响应", result.getString("content"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun webSearchRequestsSearxngJsonSearchEndpointAndFormatsResults() {
        val requestMethod = AtomicReference("")
        val acceptHeader = AtomicReference("")
        val rawQuery = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/search") { exchange ->
            requestMethod.set(exchange.requestMethod)
            acceptHeader.set(exchange.requestHeaders.getFirst("Accept").orEmpty())
            rawQuery.set(exchange.requestURI.rawQuery.orEmpty())
            val response = JSONObject()
                .put("query", "Kotlin coroutines")
                .put("number_of_results", 2)
                .put(
                    "results",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("title", "Kotlin 协程")
                                .put("url", "https://kotlinlang.org/docs/coroutines-overview.html")
                                .put("content", "协程是在 Kotlin 中编写异步代码的推荐方式。")
                                .put("engine", "example")
                                .put("category", "it")
                        )
                        .put(
                            JSONObject()
                                .put("title", "第二条结果")
                                .put("url", "https://example.org/second")
                        )
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { output -> output.write(response) }
        }
        server.start()

        try {
            val result = JSONObject(
                LocalAiTools.webSearch(
                    settings = ApiSettings(searxngUrl = "http://127.0.0.1:${server.address.port}"),
                    arguments = JSONObject()
                        .put("description", "搜索 Kotlin 协程")
                        .put("query", "Kotlin coroutines")
                        .put("limit", 1)
                        .put("page", 2)
                        .put("language", "zh-CN")
                        .put("categories", "it")
                        .put("time_range", "week")
                        .put("safesearch", 1)
                        .toString()
                )
            )
            val params = parseQuery(rawQuery.get())

            assertEquals("GET", requestMethod.get())
            assertEquals("application/json", acceptHeader.get())
            assertEquals("Kotlin coroutines", params["q"])
            assertEquals("json", params["format"])
            assertEquals("2", params["pageno"])
            assertEquals("zh-CN", params["language"])
            assertEquals("it", params["categories"])
            assertEquals("week", params["time_range"])
            assertEquals("1", params["safesearch"])
            assertEquals(true, result.getBoolean("ok"))
            assertEquals("Kotlin coroutines", result.getString("query"))
            assertEquals(1, result.getInt("returned"))
            assertEquals(true, result.getBoolean("has_more"))
            assertEquals(
                "https://kotlinlang.org/docs/coroutines-overview.html",
                result.getJSONArray("results").getJSONObject(0).getString("url")
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun readFileUsesExplicitLineRangeWhenFirstLinesIsAlsoProvided() {
        val file = createTempLineFile(lineCount = 130)

        val result = readFile(
            JSONObject()
                .put("description", "读取中间行")
                .put("path", file.path)
                .put("first_lines", 21)
                .put("start_line", 100)
                .put("end_line", 120)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(100, result.getInt("start"))
        assertEquals(120, result.getInt("end"))
        assertEquals(21, result.getInt("lines"))
        assertTrue(result.getString("content").contains("100: 第100行"))
        assertTrue(result.getString("content").contains("120: 第120行"))
        assertFalse(result.getString("content").contains("1: 第1行"))
    }

    @Test
    fun readFileTreatsZeroFirstLinesAsPlaceholderWhenLineRangeIsProvided() {
        val file = createTempLineFile(lineCount = 130)

        val result = readFile(
            JSONObject()
                .put("description", "读取中间行")
                .put("path", file.path)
                .put("first_lines", 0)
                .put("start_line", 100)
                .put("end_line", 120)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(100, result.getInt("start"))
        assertEquals(120, result.getInt("end"))
        assertEquals(21, result.getInt("lines"))
        assertTrue(result.getString("content").startsWith("100: 第100行"))
    }

    @Test
    fun readFileStillRejectsZeroFirstLinesWithoutLineRange() {
        val file = createTempLineFile(lineCount = 10)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.readFile(
                JSONObject()
                    .put("description", "读取文件头")
                    .put("path", file.path)
                    .put("first_lines", 0)
                    .toString()
            )
        }

        assertEquals("first_lines 必须大于 0。", error.message)
    }

    @Test
    fun readFileRejectsNonPositiveFirstLinesWhenNoLineRangeIsProvided() {
        val file = createTempLineFile(lineCount = 10)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.readFile(
                JSONObject()
                    .put("description", "读取文件头")
                    .put("path", file.path)
                    .put("first_lines", -1)
                    .toString()
            )
        }

        assertEquals("first_lines 必须大于 0。", error.message)
    }

    @Test
    fun readFileRejectsTooLargeLineRangeEvenWhenFirstLinesIsSmall() {
        val file = createTempLineFile(lineCount = 500)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LocalAiTools.readFile(
                JSONObject()
                    .put("description", "读取过大的中间范围")
                    .put("path", file.path)
                    .put("first_lines", 1)
                    .put("start_line", 1)
                    .put("end_line", 401)
                    .toString()
            )
        }

        assertEquals("单次读取行数不能超过 400。", error.message)
    }

    @Test
    fun readFileNormalizesZeroStartLineToFirstLine() {
        val file = createTempLineFile(lineCount = 10)

        val result = readFile(
            JSONObject()
                .put("description", "读取文件头")
                .put("path", file.path)
                .put("start_line", 0)
                .put("end_line", 2)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(1, result.getInt("start"))
        assertEquals(2, result.getInt("end"))
        assertEquals(2, result.getInt("lines"))
        assertTrue(result.getString("content").startsWith("1: 第1行"))
    }

    @Test
    fun readFileReadsFirstLinesWhenNoLineRangeIsProvided() {
        val file = createTempLineFile(lineCount = 10)

        val result = readFile(
            JSONObject()
                .put("description", "读取文件头")
                .put("path", file.path)
                .put("first_lines", 3)
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals(1, result.getInt("start"))
        assertEquals(3, result.getInt("end"))
        assertEquals(3, result.getInt("lines"))
        assertTrue(result.getString("content").contains("1: 第1行"))
        assertTrue(result.getString("content").contains("3: 第3行"))
        assertFalse(result.getString("content").contains("4: 第4行"))
    }

    private fun readFile(arguments: JSONObject): JSONObject {
        return JSONObject(LocalAiTools.readFile(arguments.toString()))
    }

    private fun JSONArray.hasTool(name: String): Boolean {
        for (index in 0 until length()) {
            val function = optJSONObject(index)?.optJSONObject("function") ?: continue
            if (function.optString("name") == name) {
                return true
            }
        }
        return false
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        return rawQuery.split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) {
                    return@mapNotNull null
                }
                URLDecoder.decode(pair.substring(0, separator), Charsets.UTF_8.name()) to
                        URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8.name())
            }
            .toMap()
    }

    private fun createFetchTestServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/html") { exchange ->
            val response = """
                <!doctype html>
                <html>
                  <head><title>测试页面</title><style>.hidden { display: none; }</style></head>
                  <body>
                    <h1>标题 &amp; 内容</h1>
                    <script>var secret = true;</script>
                    <p>第一段文本</p>
                    <a href="https://example.org/page">链接</a>
                  </body>
                </html>
            """.trimIndent()
            sendResponse(exchange, 200, "text/html; charset=utf-8", response)
        }
        server.createContext("/plain") { exchange ->
            sendResponse(exchange, 200, "text/plain; charset=utf-8", "纯文本响应内容")
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/plain")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        return server
    }

    private fun sendResponse(
        exchange: com.sun.net.httpserver.HttpExchange,
        statusCode: Int,
        contentType: String,
        body: String
    ) {
        val response = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(statusCode, response.size.toLong())
        exchange.responseBody.use { output -> output.write(response) }
    }

    private fun createTempLineFile(lineCount: Int): File {
        val path = createTempFile(prefix = "mote-read-file-test", suffix = ".txt")
        path.writeText(
            (1..lineCount).joinToString(separator = "\n") { lineNumber ->
                "第${lineNumber}行"
            },
            Charsets.UTF_8
        )
        return path.toFile().apply { deleteOnExit() }
    }
}
