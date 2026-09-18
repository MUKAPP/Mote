package com.mukapp.mote.tools

import com.mukapp.mote.data.model.SearchProvider
import org.json.JSONArray
import org.json.JSONObject

/** AI 工具的 OpenAI function 定义。所有工具 description 必填，schema 均 additionalProperties=false。 */
internal object AiToolDefinitions {

    private fun buildBaseToolDefinitions(): JSONArray {
        return JSONArray()
            .put(buildReadFileDefinition())
            .put(buildListPathDefinition())
            .put(buildGetCurrentTimeDefinition())
            .put(buildFetchUrlDefinition())
            .put(buildFetchWebViewDefinition())
            .put(buildShellDefinition())
            .put(buildShellStatusDefinition())
            .put(buildShellStopDefinition())
            .put(buildWaitDefinition())
    }

    // 按搜索提供商各缓存一份完整定义。每份都由 buildBaseToolDefinitions() 单独构造，互不共享节点。
    // 调用方（ChatApiClient）只读不改，因此直接返回缓存实例：这些 JSONArray 视为不可变，不要就地修改。
    private val cachedToolDefinitionsWithoutSearch: JSONArray by lazy {
        buildBaseToolDefinitions()
    }
    private val cachedToolDefinitionsWithSearxng: JSONArray by lazy {
        buildBaseToolDefinitions().put(buildSearxngWebSearchDefinition())
    }
    private val cachedToolDefinitionsWithTavily: JSONArray by lazy {
        buildBaseToolDefinitions().put(buildTavilyWebSearchDefinition())
    }
    private val cachedToolDefinitionsWithAnysearch: JSONArray by lazy {
        buildBaseToolDefinitions().put(buildAnysearchWebSearchDefinition())
    }

    fun forSearchProvider(searchProvider: SearchProvider?): JSONArray {
        return when (searchProvider) {
            SearchProvider.Searxng -> cachedToolDefinitionsWithSearxng
            SearchProvider.Tavily -> cachedToolDefinitionsWithTavily
            SearchProvider.Anysearch -> cachedToolDefinitionsWithAnysearch
            null -> cachedToolDefinitionsWithoutSearch
        }
    }

    private fun buildReadFileDefinition(): JSONObject = functionTool(
        name = LocalAiTools.ReadFileToolName,
        description = "按行读取设备上当前应用有权限访问的文本文件内容。行号从 1 开始。如果不提供行范围参数，默认读取前 200 行。读取中间内容时直接提供 start_line/end_line。单行或总输出过长会被截断并置 content_truncated=true，可用行范围分段读取。读取应用私有数据目录（如 shared_prefs、files）需要用户确认。",
        required = listOf("description", "path"),
        properties = toolProperties(
            "path" to stringProperty("要读取的文件路径，建议传入绝对路径"),
            "first_lines" to integerProperty("读取文件前多少行。只在不提供 start_line/end_line 时生效。"),
            "start_line" to integerProperty("起始行号，从 1 开始。读取中间内容时优先使用此字段，可与 end_line 一起使用。"),
            "end_line" to integerProperty("结束行号，从 1 开始，且不能小于 start_line。"),
            "confirmation_id" to stringProperty("读取应用私有数据目录前由应用内部填充的确认 ID。模型不要自行生成此字段。")
        )
    )

    private fun buildListPathDefinition(): JSONObject = functionTool(
        name = LocalAiTools.ListPathToolName,
        description = "列出目录内容，或者返回单个文件的基础信息。查看应用私有数据目录需要用户确认。",
        required = listOf("description", "path"),
        properties = toolProperties(
            "path" to stringProperty("要查看的目录或文件路径，建议传入绝对路径"),
            "limit" to integerProperty("目录列表最多返回多少项，默认 100，最大 200"),
            "confirmation_id" to stringProperty("查看应用私有数据目录前由应用内部填充的确认 ID。模型不要自行生成此字段。")
        )
    )

    private fun buildGetCurrentTimeDefinition(): JSONObject = functionTool(
        name = LocalAiTools.GetCurrentTimeToolName,
        description = "获取设备当前本地时间、时区和 UTC 偏移量。用于回答日期、时间、时区或需要准确当前时间的任务。",
        required = listOf("description"),
        properties = toolProperties()
    )

    private fun buildFetchUrlDefinition(): JSONObject = functionTool(
        name = LocalAiTools.FetchUrlToolName,
        description = "通过 HTTP GET 获取一个 http/https URL 的内容。支持 markdown、 raw 原始网页和 text 纯文本。适合读取搜索结果中的网页、文档或接口文本响应。",
        required = listOf("description", "url"),
        properties = toolProperties(
            "url" to stringProperty("要获取的 URL，只支持 http 或 https。"),
            "output_format" to stringProperty(
                "输出格式：text 提取可读纯文本，raw 返回原始网页，markdown 将 HTML 转为 Markdown。默认 text。",
                enumValues = listOf("text", "raw", "markdown")
            ),
            "max_chars" to integerProperty("返回 content 的最大字符数，默认 20000，最大 100000。")
        )
    )

    private fun buildFetchWebViewDefinition(): JSONObject = functionTool(
        name = LocalAiTools.FetchWebViewToolName,
        description = "使用不可见 WebView 加载完整网页，执行页面 JavaScript 后提取渲染后的内容。适合普通 fetch_url 拿不到动态内容时使用；比 fetch_url 更慢且更耗资源。",
        required = listOf("description", "url"),
        properties = toolProperties(
            "url" to stringProperty("要加载的网页 URL，只支持 http 或 https。"),
            "output_format" to stringProperty(
                "输出格式：text 提取可读纯文本，raw 返回原始网页，markdown 将 HTML 转为 Markdown。默认 text。",
                enumValues = listOf("text", "raw", "markdown")
            ),
            "max_chars" to integerProperty("返回 content 的最大字符数，默认 20000，最大 100000。"),
            "timeout_seconds" to integerProperty("WebView 加载超时时间，默认 20 秒，最大 60 秒。"),
            "settle_ms" to integerProperty("页面 onPageFinished 后继续等待的毫秒数，用于等待异步渲染，默认 1000，最大 10000。")
        )
    )

    private fun buildSearxngWebSearchDefinition(): JSONObject = functionTool(
        name = LocalAiTools.WebSearchToolName,
        description = "使用 SearXNG 搜索互联网。适合查询最新信息、网页资料、新闻或需要来源链接的问题。",
        required = listOf("description", "query"),
        properties = toolProperties(
            "query" to stringProperty("搜索关键词，使用与用户问题最匹配的自然语言或关键词，最大 300 个字符。"),
            "limit" to integerProperty("最多返回多少条结果，默认 5，最大 10。"),
            "page" to integerProperty("搜索结果页码，默认 1，最大 20。"),
            "language" to stringProperty("可选的搜索语言代码，例如 zh-CN、en-US 或 all。"),
            "categories" to stringProperty("可选的分类，多个分类用英文逗号分隔，例如 general、news、it、science。"),
            "time_range" to stringProperty(
                "可选的时间范围，例如 day、week、month 或 year。",
                enumValues = listOf("day", "week", "month", "year")
            ),
            "safesearch" to integerProperty(
                "可选的安全搜索等级：0 关闭，1 中等，2 严格。",
                enumValues = listOf(0, 1, 2)
            )
        )
    )

    private fun buildTavilyWebSearchDefinition(): JSONObject = functionTool(
        name = LocalAiTools.WebSearchToolName,
        description = "使用 Tavily Search 搜索互联网。适合查询最新信息、网页资料、新闻或需要来源链接的问题。",
        required = listOf("description", "query"),
        properties = toolProperties(
            "query" to stringProperty("搜索关键词，使用与用户问题最匹配的自然语言或关键词，最大 300 个字符。"),
            "limit" to integerProperty("最多返回多少条结果，默认 5，最大 20。"),
            "search_depth" to stringProperty(
                "搜索深度：basic 默认平衡，advanced 更深入但更慢，fast/ultra-fast 优先低延迟。",
                enumValues = listOf("basic", "advanced", "fast", "ultra-fast")
            ),
            "topic" to stringProperty(
                "搜索类别：general 通用，news 新闻，finance 金融。",
                enumValues = listOf("general", "news", "finance")
            ),
            "time_range" to stringProperty(
                "可选的时间范围，例如 day、week、month、year，也支持 d、w、m、y。",
                enumValues = listOf("day", "week", "month", "year", "d", "w", "m", "y")
            ),
            "start_date" to stringProperty("只返回该日期之后发布或更新的结果，格式 YYYY-MM-DD。"),
            "end_date" to stringProperty("只返回该日期之前发布或更新的结果，格式 YYYY-MM-DD。"),
            "include_answer" to booleanProperty("是否请求 Tavily 生成简短答案；默认 false。"),
            "chunks_per_source" to integerProperty("advanced 搜索时每个来源返回的内容片段数，1 到 3。"),
            "include_domains" to stringProperty("可选的域名白名单，多个域名用英文逗号分隔。"),
            "exclude_domains" to stringProperty("可选的域名黑名单，多个域名用英文逗号分隔。")
        )
    )

    private fun buildAnysearchWebSearchDefinition(): JSONObject = functionTool(
        name = LocalAiTools.WebSearchToolName,
        description = "使用 AnySearch 搜索互联网。适合查询最新信息、网页资料、新闻或需要来源链接的问题。",
        required = listOf("description", "query"),
        properties = toolProperties(
            "query" to stringProperty("搜索关键词，使用与用户问题最匹配的自然语言或关键词，最大 300 个字符。"),
            "limit" to integerProperty("最多返回多少条结果，默认 5，最大 10。")
        )
    )

    private fun buildShellDefinition(): JSONObject = functionTool(
        name = LocalAiTools.ShellToolName,
        description = $$"在设备上执行 shell 命令。短命令会等待完成并返回输出；长命令可设为后台运行，用 shell_status 查询状态。如果命令超过 30 秒未完成会自动转为后台运行。未提供工作目录时默认使用 Android/data/包名/files/ai_tmp，临时文件请保存到当前目录、$TMPDIR 或 $MOTE_AI_TMPDIR。",
        required = listOf("description", "command"),
        properties = toolProperties(
            "command" to stringProperty("要执行的 shell 命令"),
            "work_dir" to stringProperty("工作目录，不提供则使用 Android/data/包名/files/ai_tmp 作为默认目录"),
            "confirmation_id" to stringProperty("执行高风险命令前由应用内部填充的确认 ID。模型不要自行生成此字段。"),
            "background" to booleanProperty("是否在后台运行。长时间运行的命令应设为 true。")
        )
    )

    private fun buildShellStatusDefinition(): JSONObject = functionTool(
        name = LocalAiTools.ShellStatusToolName,
        description = "查询后台 shell 进程的运行状态和输出",
        required = listOf("description", "id"),
        properties = toolProperties(
            "id" to stringProperty("shell 命令返回的进程 ID")
        )
    )

    private fun buildShellStopDefinition(): JSONObject = functionTool(
        name = LocalAiTools.ShellStopToolName,
        description = "手动停止一个后台运行的 shell 进程",
        required = listOf("description", "id"),
        properties = toolProperties(
            "id" to stringProperty("要停止的进程 ID")
        )
    )

    private fun buildWaitDefinition(): JSONObject = functionTool(
        name = LocalAiTools.WaitToolName,
        description = "等待指定秒数后再继续对话。适用于等待后台 shell 命令执行一段时间后查询其状态。",
        required = listOf("description", "seconds"),
        properties = toolProperties(
            "seconds" to integerProperty("等待的秒数，1 到 3600")
        )
    )

    /** 组装 OpenAI function 定义骨架：type/function/parameters/required/additionalProperties。 */
    private fun functionTool(
        name: String,
        description: String,
        required: List<String>,
        properties: JSONObject
    ): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put("properties", properties)
                            put("required", JSONArray().apply { required.forEach { put(it) } })
                            put("additionalProperties", false)
                        }
                    )
                }
            )
        }
    }

    /** 所有工具共有的 description 参数，加上各工具自己的参数。 */
    private fun toolProperties(vararg entries: Pair<String, JSONObject>): JSONObject {
        return JSONObject().apply {
            put("description", buildToolCallDescriptionProperty())
            entries.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun stringProperty(description: String, enumValues: List<String>? = null): JSONObject {
        return JSONObject().apply {
            put("type", "string")
            put("description", description)
            enumValues?.let { values -> put("enum", JSONArray().apply { values.forEach { put(it) } }) }
        }
    }

    private fun integerProperty(description: String, enumValues: List<Int>? = null): JSONObject {
        return JSONObject().apply {
            put("type", "integer")
            put("description", description)
            enumValues?.let { values -> put("enum", JSONArray().apply { values.forEach { put(it) } }) }
        }
    }

    private fun booleanProperty(description: String): JSONObject {
        return JSONObject().apply {
            put("type", "boolean")
            put("description", description)
        }
    }

    private fun buildToolCallDescriptionProperty(): JSONObject {
        return JSONObject().apply {
            put("type", "string")
            put(
                "description",
                "对本次工具执行目的的简短描述，会直接展示给用户作为工具标题。请描述要做什么，不要只重复命令或参数。例如：读取 LocalAiTools.kt 前 200 行、列出 app/src/main 目录、执行 Debug 构建并检查结果。"
            )
        }
    }
}
