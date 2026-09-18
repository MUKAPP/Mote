package com.mukapp.mote.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.optIntOrNull
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** fetch_url / fetch_webview 工具实现：HTTP 抓取、WebView 渲染抓取与 HTML 文本转换。 */
internal object UrlFetchTools {
    private const val Component = "Tools"

    private const val DefaultFetchMaxChars = 20_000
    private const val MaxFetchMaxChars = 100_000
    private const val MaxFetchResponseBytes = 1_000_000
    private const val MaxFetchRedirects = 5
    // 文本清洗/Markdown 转换输入上限：输出按 max_chars 截断，超过 max_chars 若干倍的输入
    // 几乎不可能进入输出，提前截掉以限制多遍正则与 Flexmark 的最坏处理成本。
    private const val FetchProcessingCharsFactor = 8
    private const val MinFetchProcessingChars = 160_000
    private const val DefaultWebViewTimeoutSeconds = 20
    private const val MaxWebViewTimeoutSeconds = 60
    private const val DefaultWebViewSettleMs = 1_000
    private const val MaxWebViewSettleMs = 10_000
    private const val MaxWebViewExtractChars = 1_000_000

    internal data class WebViewFetchOptions(
        val url: URL,
        val outputFormat: String,
        val maxChars: Int,
        val timeoutSeconds: Int,
        val settleMs: Int
    )

    private data class WebViewExtractedPage(
        val finalUrl: String,
        val title: String,
        val content: String
    )

    private data class MarkdownConversionResult(
        val content: String,
        val converted: Boolean,
        val error: String? = null
    )

    /** fetch_webview 主线程交回 IO 线程的结果载体：重解析（JSON + HTML→Markdown）留给 IO 线程做。 */
    private sealed interface WebViewFetchOutcome {
        /** evaluateJavascript 的原始返回值，待 IO 线程解析。finalUrl 须在销毁 WebView 前于主线程取好。 */
        data class RawExtraction(val value: String?, val finalUrl: String) : WebViewFetchOutcome

        /** 已构造完成的最终 JSON（错误/超时路径，构造成本低，留在主线程）。 */
        data class Completed(val json: String) : WebViewFetchOutcome
    }

    internal var htmlToMarkdownConverter: (String) -> String = { html ->
        FlexmarkHtmlConverter.builder().build().convert(html).trim()
    }

    fun fetchUrl(arguments: String): String {
        val payload = JSONObject(arguments)
        val rawUrl = payload.optString("url").trim()
        require(rawUrl.isNotEmpty()) { "url 不能为空。" }
        val outputFormat = parseFetchOutputFormat(payload)
        val maxChars = parseFetchMaxChars(payload)
        val initialUrl = ToolSupport.normalizeFetchUrl(rawUrl)
        return fetchUrlWithRedirects(initialUrl = initialUrl, outputFormat = outputFormat, maxChars = maxChars)
    }

    private fun parseFetchOutputFormat(payload: JSONObject): String {
        val outputFormat = payload.optString("output_format", "text")
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "text" }
        require(outputFormat in setOf("text", "raw", "markdown")) {
            "output_format 只能是 text、raw 或 markdown。"
        }
        return outputFormat
    }

    private fun parseFetchMaxChars(payload: JSONObject): Int {
        val maxChars = payload.optIntOrNull("max_chars") ?: DefaultFetchMaxChars
        require(maxChars > 0) { "max_chars 必须大于 0。" }
        require(maxChars <= MaxFetchMaxChars) { "max_chars 不能超过 $MaxFetchMaxChars。" }
        return maxChars
    }

    private fun fetchUrlWithRedirects(initialUrl: URL, outputFormat: String, maxChars: Int): String {
        val startMs = System.currentTimeMillis()
        var currentUrl = initialUrl
        val redirects = JSONArray()
        repeat(MaxFetchRedirects + 1) { redirectCount ->
            if (Thread.interrupted()) {
                throw InterruptedException("fetch_url 已被中断。")
            }
            val connection = (currentUrl.openConnection() as HttpURLConnection)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", buildFetchAcceptHeader(outputFormat))
                connection.setRequestProperty("User-Agent", "Mote/1.0")

                val statusCode = connection.responseCode
                if (statusCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        MoteLog.w(
                            Component,
                            MoteLog.event(
                                "fetch_url 重定向缺少 Location",
                                "status" to statusCode,
                                "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                                "redirects" to redirects.length(),
                                "durationMs" to MoteLog.durationMs(startMs)
                            )
                        )
                        return JSONObject().apply {
                            put("ok", false)
                            put("url", initialUrl.toString())
                            put("final_url", currentUrl.toString())
                            put("status", statusCode)
                            put("error", "重定向响应缺少 Location。")
                            put("redirects", redirects)
                        }.toString(2)
                    }
                    if (redirectCount >= MaxFetchRedirects) {
                        MoteLog.w(
                            Component,
                            MoteLog.event(
                                "fetch_url 重定向次数超过上限",
                                "status" to statusCode,
                                "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                                "redirects" to redirects.length(),
                                "durationMs" to MoteLog.durationMs(startMs)
                            )
                        )
                        return JSONObject().apply {
                            put("ok", false)
                            put("url", initialUrl.toString())
                            put("final_url", currentUrl.toString())
                            put("status", statusCode)
                            put("error", "重定向次数超过 $MaxFetchRedirects 次。")
                            put("redirects", redirects)
                        }.toString(2)
                    }
                    val nextUrl = ToolSupport.normalizeFetchUrl(currentUrl.toURI().resolve(location).toString())
                    redirects.put(
                        JSONObject().apply {
                            put("from", currentUrl.toString())
                            put("to", nextUrl.toString())
                            put("status", statusCode)
                        }
                    )
                    currentUrl = nextUrl
                    return@repeat
                }

                val contentType = connection.contentType.orEmpty()
                val responseBody = readHttpResponseBody(connection, statusCode, MaxFetchResponseBytes)
                if (statusCode !in 200..299) {
                    MoteLog.w(
                        Component,
                        MoteLog.event(
                            "fetch_url 请求失败",
                            "status" to statusCode,
                            "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                            "contentType" to contentType.substringBefore(';').ifBlank { "未返回" },
                            "redirects" to redirects.length(),
                            "responseTruncated" to responseBody.truncated,
                            "durationMs" to MoteLog.durationMs(startMs)
                        )
                    )
                    return JSONObject().apply {
                        put("ok", false)
                        put("url", initialUrl.toString())
                        put("final_url", currentUrl.toString())
                        put("status", statusCode)
                        put("content_type", contentType)
                        put("output_format", outputFormat)
                        put("truncated", responseBody.truncated)
                        put("redirects", redirects)
                        put("error", "URL 请求失败，HTTP $statusCode。")
                        put("content", decodeResponseBody(responseBody.bytes, contentType).take(maxChars))
                    }.toString(2)
                }

                val bodyText = decodeResponseBody(responseBody.bytes, contentType)
                if (!isTextualResponse(contentType, bodyText)) {
                    MoteLog.w(
                        Component,
                        MoteLog.event(
                            "fetch_url 响应不是文本",
                            "status" to statusCode,
                            "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                            "contentType" to contentType.substringBefore(';').ifBlank { "未返回" },
                            "durationMs" to MoteLog.durationMs(startMs)
                        )
                    )
                    return JSONObject().apply {
                        put("ok", false)
                        put("url", initialUrl.toString())
                        put("final_url", currentUrl.toString())
                        put("status", statusCode)
                        put("content_type", contentType)
                        put("output_format", outputFormat)
                        put("truncated", responseBody.truncated)
                        put("redirects", redirects)
                        put("error", "响应看起来不是文本内容，fetch_url 不返回二进制数据。")
                    }.toString(2)
                }
                val isHtml = isHtmlContent(contentType, bodyText)
                // raw 直接按 max_chars 截断即可；text/markdown 的清洗与转换先限制输入长度，
                // 避免为最终只保留 max_chars 的输出在整个响应体上做多遍处理。
                val processingLimit = (maxChars.toLong() * FetchProcessingCharsFactor)
                    .coerceAtLeast(MinFetchProcessingChars.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                val inputClipped = outputFormat != "raw" && bodyText.length > processingLimit
                val processingInput = if (inputClipped) bodyText.substring(0, processingLimit) else bodyText
                val markdownConversion = if (outputFormat == "markdown" && isHtml) {
                    htmlToMarkdown(processingInput)
                } else {
                    null
                }
                val formattedContent = when (outputFormat) {
                    "raw" -> bodyText
                    "markdown" -> markdownConversion?.content ?: processingInput
                    else -> if (isHtml) htmlToPlainText(processingInput) else processingInput
                }
                val truncatedContent = formattedContent.length > maxChars
                MoteLog.i(
                    Component,
                    MoteLog.event(
                        "fetch_url 请求完成",
                        "status" to statusCode,
                        "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                        "outputFormat" to outputFormat,
                        "contentType" to contentType.substringBefore(';').ifBlank { "未返回" },
                        "redirects" to redirects.length(),
                        "converted" to (markdownConversion?.converted ?: false),
                        "truncated" to (responseBody.truncated || truncatedContent || inputClipped),
                        "contentLength" to formattedContent.length,
                        "durationMs" to MoteLog.durationMs(startMs)
                    )
                )
                return JSONObject().apply {
                    put("ok", true)
                    put("url", initialUrl.toString())
                    put("final_url", currentUrl.toString())
                    put("status", statusCode)
                    put("content_type", contentType)
                    put("output_format", outputFormat)
                    put("converted", markdownConversion?.converted ?: false)
                    markdownConversion?.error?.let { put("conversion_error", it) }
                    put("truncated", responseBody.truncated || truncatedContent || inputClipped)
                    put("redirects", redirects)
                    put("content", if (truncatedContent) formattedContent.take(maxChars) else formattedContent)
                }.toString(2)
            } finally {
                runCatching { connection.disconnect() }
            }
        }

        MoteLog.w(
            Component,
            MoteLog.event(
                "fetch_url 重定向处理失败",
                "origin" to MoteLog.safeUrlOrigin(currentUrl.toString()),
                "redirects" to redirects.length(),
                "durationMs" to MoteLog.durationMs(startMs)
            )
        )
        return JSONObject().apply {
            put("ok", false)
            put("url", initialUrl.toString())
            put("final_url", currentUrl.toString())
            put("error", "重定向处理失败。")
            put("redirects", redirects)
        }.toString(2)
    }

    fun parseFetchWebViewOptions(arguments: String): WebViewFetchOptions {
        val payload = JSONObject(arguments)
        val rawUrl = payload.optString("url").trim()
        require(rawUrl.isNotEmpty()) { "url 不能为空。" }
        val timeoutSeconds = payload.optIntOrNull("timeout_seconds") ?: DefaultWebViewTimeoutSeconds
        require(timeoutSeconds > 0) { "timeout_seconds 必须大于 0。" }
        require(timeoutSeconds <= MaxWebViewTimeoutSeconds) {
            "timeout_seconds 不能超过 $MaxWebViewTimeoutSeconds。"
        }
        val settleMs = payload.optIntOrNull("settle_ms") ?: DefaultWebViewSettleMs
        require(settleMs >= 0) { "settle_ms 不能小于 0。" }
        require(settleMs <= MaxWebViewSettleMs) { "settle_ms 不能超过 $MaxWebViewSettleMs。" }
        return WebViewFetchOptions(
            url = ToolSupport.normalizeFetchUrl(rawUrl),
            outputFormat = parseFetchOutputFormat(payload),
            maxChars = parseFetchMaxChars(payload),
            timeoutSeconds = timeoutSeconds,
            settleMs = settleMs
        )
    }

    fun fetchWebView(context: Context, arguments: String): String {
        val options = parseFetchWebViewOptions(arguments)
        MoteLog.i(
            Component,
            MoteLog.event(
                "开始 fetch_webview",
                "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                "outputFormat" to options.outputFormat,
                "maxChars" to options.maxChars,
                "timeoutSeconds" to options.timeoutSeconds,
                "settleMs" to options.settleMs
            )
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            MoteLog.w(Component, "fetch_webview 被拒绝：不能在主线程同步执行。")
            return JSONObject().apply {
                put("ok", false)
                put("url", options.url.toString())
                put("final_url", options.url.toString())
                put("output_format", options.outputFormat)
                put("error", "fetch_webview 不能在主线程同步执行。")
            }.toString(2)
        }

        val result = AtomicReference<WebViewFetchOutcome>()
        val latch = CountDownLatch(1)
        val cancelHook = AtomicReference<(() -> Unit)?>()
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val hook = startFetchWebViewOnMainThread(context.applicationContext, options) { outcome ->
                    result.set(outcome)
                    latch.countDown()
                }
                cancelHook.set(hook)
            }.onFailure { error ->
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "fetch_webview 初始化失败",
                        "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                        "error" to error
                    )
                )
                result.set(
                    WebViewFetchOutcome.Completed(
                        buildWebViewErrorJson(options, error.message ?: "WebView 初始化失败。", options.url.toString())
                    )
                )
                latch.countDown()
            }
        }
        val completed = try {
            latch.await((options.timeoutSeconds + 5).toLong(), TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            // 工具被取消：回主线程销毁 WebView（finishOnce 的 CAS 防重复），中断继续上抛。
            Handler(Looper.getMainLooper()).post { cancelHook.get()?.invoke() }
            throw interrupted
        }
        if (!completed) {
            MoteLog.w(
                Component,
                MoteLog.event(
                    "fetch_webview 等待超时",
                    "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                    "timeoutSeconds" to options.timeoutSeconds
                )
            )
            return buildWebViewErrorJson(options, "WebView 抓取等待超时。", options.url.toString())
        }
        // 重解析在 IO 线程完成，主线程只负责交出原始提取值。
        return when (val outcome = result.get()) {
            null -> ""
            is WebViewFetchOutcome.Completed -> outcome.json
            is WebViewFetchOutcome.RawExtraction -> runCatching {
                val page = parseWebViewExtractedPage(outcome.value, outcome.finalUrl)
                formatWebViewFetchResult(options, page)
            }.getOrElse { error ->
                MoteLog.w(
                    Component,
                    MoteLog.event(
                        "fetch_webview 内容处理失败",
                        "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                        "error" to error
                    )
                )
                buildWebViewErrorJson(options, "WebView 内容处理失败：${error.readableMessage()}", outcome.finalUrl)
            }
        }
    }

    private fun buildWebViewErrorJson(options: WebViewFetchOptions, message: String, finalUrl: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("url", options.url.toString())
            put("final_url", finalUrl)
            put("output_format", options.outputFormat)
            put("truncated", false)
            put("error", message)
        }.toString(2)
    }

    /** 在主线程创建并驱动 WebView。返回取消钩子（须在主线程调用），用于工具被取消时销毁 WebView。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startFetchWebViewOnMainThread(
        context: Context,
        options: WebViewFetchOptions,
        onComplete: (WebViewFetchOutcome) -> Unit
    ): () -> Unit {
        val webView = WebView(context)
        val completed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        fun finishOnce(output: WebViewFetchOutcome) {
            if (!completed.compareAndSet(false, true)) {
                return
            }
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            onComplete(output)
        }

        fun finishError(message: String, finalUrl: String = webView.url ?: options.url.toString()) {
            MoteLog.w(
                Component,
                MoteLog.event(
                    "fetch_webview 加载失败",
                    "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                    "finalOrigin" to MoteLog.safeUrlOrigin(finalUrl),
                    "messageLength" to message.length
                )
            )
            finishOnce(WebViewFetchOutcome.Completed(buildWebViewErrorJson(options, message, finalUrl)))
        }

        fun extractPage() {
            if (completed.get()) {
                return
            }
            val script = buildWebViewExtractionScript(options.outputFormat)
            runCatching {
                webView.evaluateJavascript(script) { value ->
                    if (completed.get()) {
                        return@evaluateJavascript
                    }
                    // 只交出原始值；JSON 解析与 HTML→Markdown 由 IO 线程完成，避免主线程重处理。
                    val finalUrl = webView.url ?: options.url.toString()
                    finishOnce(WebViewFetchOutcome.RawExtraction(value, finalUrl))
                }
            }.onFailure { error ->
                finishError("WebView 内容提取失败：${error.readableMessage()}")
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = false
        webView.settings.blockNetworkImage = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.layout(0, 0, 1, 1)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                if (target.isBlank()) {
                    return false
                }
                val allowed = runCatching { ToolSupport.normalizeFetchUrl(target) }.isSuccess
                if (!allowed) {
                    finishError("WebView 跳转到了不支持的 URL。", target)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && runCatching { ToolSupport.normalizeFetchUrl(url) }.isFailure) {
                    finishError("WebView 跳转到了不支持的 URL。", url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                handler.postDelayed({ extractPage() }, options.settleMs.toLong())
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    finishError("WebView 加载失败：${error?.description ?: "未知错误"}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    finishError("WebView 请求失败，HTTP ${errorResponse?.statusCode ?: 0}。")
                }
            }
        }

        handler.postDelayed({
            finishError("WebView 加载超过 ${options.timeoutSeconds} 秒。")
        }, options.timeoutSeconds * 1000L)

        webView.loadUrl(options.url.toString())

        return { finishError("fetch_webview 已被取消。") }
    }

    private fun buildWebViewExtractionScript(outputFormat: String): String {
        val expression = if (outputFormat == "raw" || outputFormat == "markdown") {
            "document.documentElement ? document.documentElement.outerHTML : ''"
        } else {
            "document.body ? (document.body.innerText || document.body.textContent || '') : ''"
        }
        val maxChars = MaxWebViewExtractChars + 1
        return """
            (function() {
                var content = String($expression || '');
                return JSON.stringify({
                    title: document.title || '',
                    url: location.href || '',
                    content: content.substring(0, $maxChars)
                });
            })();
        """.trimIndent()
    }

    private fun parseWebViewExtractedPage(value: String?, fallbackUrl: String): WebViewExtractedPage {
        val decoded = runCatching { JSONTokener(value ?: "").nextValue() }
            .getOrNull()
            ?.toString()
            .orEmpty()
        val payload = runCatching { JSONObject(decoded) }.getOrNull() ?: JSONObject()
        return WebViewExtractedPage(
            finalUrl = payload.optString("url").ifBlank { fallbackUrl },
            title = payload.optString("title"),
            content = payload.optString("content")
        )
    }

    private fun formatWebViewFetchResult(options: WebViewFetchOptions, page: WebViewExtractedPage): String {
        val sourceContent = if (page.content.length > MaxWebViewExtractChars) {
            page.content.take(MaxWebViewExtractChars)
        } else {
            page.content
        }
        val markdownConversion = if (options.outputFormat == "markdown") {
            htmlToMarkdown(sourceContent)
        } else {
            null
        }
        val formattedContent = when (options.outputFormat) {
            "markdown" -> markdownConversion?.content ?: sourceContent
            "raw" -> sourceContent
            else -> sourceContent
        }
        val truncatedContent = formattedContent.length > options.maxChars
        MoteLog.i(
            Component,
            MoteLog.event(
                "fetch_webview 完成",
                "origin" to MoteLog.safeUrlOrigin(options.url.toString()),
                "finalOrigin" to MoteLog.safeUrlOrigin(page.finalUrl),
                "outputFormat" to options.outputFormat,
                "converted" to (markdownConversion?.converted ?: false),
                "truncated" to (truncatedContent || page.content.length > MaxWebViewExtractChars),
                "contentLength" to formattedContent.length,
                "titleLength" to page.title.length
            )
        )
        return JSONObject().apply {
            put("ok", true)
            put("url", options.url.toString())
            put("final_url", page.finalUrl)
            put("title", page.title)
            put("output_format", options.outputFormat)
            put("rendered", true)
            put("converted", markdownConversion?.converted ?: false)
            markdownConversion?.error?.let { put("conversion_error", it) }
            put("truncated", truncatedContent || page.content.length > MaxWebViewExtractChars)
            put("content", if (truncatedContent) formattedContent.take(options.maxChars) else formattedContent)
        }.toString(2)
    }

    private fun buildFetchAcceptHeader(outputFormat: String): String {
        return when (outputFormat) {
            "markdown" -> "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.6"
            else -> "text/*,application/json,application/xml,*/*;q=0.6"
        }
    }

    private data class HttpResponseBody(
        val bytes: ByteArray,
        val truncated: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HttpResponseBody

            if (truncated != other.truncated) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = truncated.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    private fun readHttpResponseBody(
        connection: HttpURLConnection,
        statusCode: Int,
        maxBytes: Int
    ): HttpResponseBody {
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return HttpResponseBody(ByteArray(0), truncated = false)
        }
        val output = ByteArrayOutputStream()
        var truncated = false
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.interrupted()) {
                    throw InterruptedException("网络读取已被中断。")
                }
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                val remaining = maxBytes - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                if (read > remaining) {
                    output.write(buffer, 0, remaining)
                    truncated = true
                    break
                }
                output.write(buffer, 0, read)
            }
        }
        return HttpResponseBody(bytes = output.toByteArray(), truncated = truncated)
    }

    private fun decodeResponseBody(bytes: ByteArray, contentType: String): String {
        if (bytes.isEmpty()) {
            return ""
        }
        return bytes.toString(resolveCharset(contentType))
    }

    private fun resolveCharset(contentType: String): Charset {
        val charsetName = contentType
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
        return charsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.UTF_8
    }

    private fun isHtmlContent(contentType: String, text: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mediaType == "text/html" || mediaType == "application/xhtml+xml") {
            return true
        }
        val head = text.take(500).lowercase(Locale.ROOT)
        return "<html" in head || "<!doctype html" in head
    }

    private fun isTextualResponse(contentType: String, text: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mediaType.isBlank()) {
            return !text.contains(' ')
        }
        return mediaType.startsWith("text/") ||
                mediaType == "application/json" ||
                mediaType == "application/xml" ||
                mediaType == "application/xhtml+xml" ||
                mediaType == "application/javascript" ||
                mediaType == "application/x-javascript" ||
                mediaType == "application/rss+xml" ||
                mediaType == "application/atom+xml" ||
                mediaType.endsWith("+json") ||
                mediaType.endsWith("+xml")
    }

    private fun htmlToMarkdown(html: String): MarkdownConversionResult {
        return runCatching {
            htmlToMarkdownConverter(html).trim()
        }.fold(
            onSuccess = { markdown ->
                MarkdownConversionResult(
                    content = markdown,
                    converted = true
                )
            },
            onFailure = { error ->
                MarkdownConversionResult(
                    content = htmlToPlainText(html),
                    converted = false,
                    error = "Markdown 转换失败，已降级为纯文本：${error.readableMessage()}"
                )
            }
        )
    }

    private fun Throwable.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    private fun htmlToPlainText(html: String): String {
        return html
            .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|section|article|header|footer|main|li|tr|h[1-6])>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .let { decodeHtmlEntities(it) }
            .lines()
            .map { line -> line.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                val codePoint = match.groupValues[1].toIntOrNull() ?: return@replace match.value
                runCatching { String(Character.toChars(codePoint)) }.getOrDefault(match.value)
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
                val codePoint = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
                runCatching { String(Character.toChars(codePoint)) }.getOrDefault(match.value)
            }
    }
}
