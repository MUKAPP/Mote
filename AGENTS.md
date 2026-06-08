# AGENTS.md — Mote 项目指南

## 项目概述

Mote 是运行在 Android 设备上的 AI Agent 聊天客户端，通过 OpenAI 兼容的 Chat Completions API 与模型交互。核心能力包括流式响应、工具调用、本地 Shell、思考过程展示、工具结果展示、多对话历史和自研 Markdown 渲染。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin + Java（prism4j 语法定义） |
| Android | minSdk 26，targetSdk 36，compileSdk 36.1 |
| Java | Java 11 |
| 构建 | Gradle Kotlin DSL + Version Catalog |
| UI | 传统 View + ViewBinding，未使用 Compose |
| 架构 | MVVM，AndroidViewModel，LiveData，Coroutines |
| 网络 | OkHttp 4.12.0 |
| 序列化 | org.json |
| Markdown | 自研原生视图树 + prism4j 语法高亮 + RaTeX 公式渲染 |

## 目录速览

```text
app/src/main/java/com/mukapp/mote/
├── MainActivity.kt                 # DrawerLayout 主界面
├── SettingsActivity.kt             # API 设置、权限管理
├── MyApplication.kt                # Application、BusyBox 初始化
├── data/
│   ├── model/Models.kt             # ChatMessage、ApiSettings、AssistantPart 等模型
│   ├── ApiSettingsStore.kt         # API 设置持久化
│   └── ChatHistoryStore.kt         # 多对话 JSON 历史与索引
├── network/ChatApiClient.kt        # OpenAI 兼容请求、SSE、标题生成
├── tools/                          # AI 工具、BusyBox、Shell 风险检测、进程管理
├── ui/                             # 聊天界面、ViewModel、Adapter、侧栏列表
├── ui/markdown/                    # Markdown 解析、渲染、代码高亮、表格
└── util/                           # 通用工具与单位转换
```

## 核心流程

```text
用户输入
→ ChatViewModel.sendMessage()
→ 构建原始 workingRawConversation，基于独立 contextSummaries 临时生成请求用 workingConversation
→ ChatApiClient.streamChat() 流式请求（优先请求 usage，不兼容时降级重试）
→ 累积 content、reasoning_content、tool_calls 到 assistantParts
→ 有工具调用 → 执行 LocalAiTools → 写回上下文 → 继续请求（高风险 Shell 需用户确认）
→ 无工具调用 → 完成回复 → 更新 token usage 锚点 → 持久化
→ 首轮对话结束后可异步生成标题
```

## 重要架构约定

### 三层架构

- **UI 层**：`ChatFragment`、`SettingsActivity`，ViewBinding + LiveData。
- **业务层**：`ChatViewModel`，负责发送、工具循环、Shell 确认、历史管理、编辑/删除/重试/停止。
- **数据层**：`ApiSettingsStore`、`ChatHistoryStore`、`ChatApiClient`。

### 消息体系（三套数据）

- `uiMessagesInternal` — 面向展示。
- `conversationMessagesInternal` — 原始 API 上下文（不含 System；工具链路完成后保留 assistant toolCalls + Tool 消息）。
- `contextSummariesInternal` — 独立保存的上下文压缩摘要。

### 上下文压缩

- 压缩不删除原始消息，而是向 `contextSummariesInternal` 追加新摘要（保留旧摘要，后续摘要可引用旧摘要 ID）。
- 发送请求时只用最新可用摘要；摘要依赖旧摘要时递归解析，临时作为 User 消息放在未压缩消息前发送。
- 删除/编辑/重试消息命中摘要的 `sourceMessageIds` 时，删除命中摘要及其后续依赖；最新摘要被删则自动回退旧摘要。
- 触发值基于 `compressionTriggerLength`，`modelContextLength > 0` 时限制到 80%；至少需要 2 条消息和 2 条用户消息。
- 最近上下文预算约 35%（`1024..32000`），摘要预算约 12%（`512..8192`）。

### Token 计数

- 优先使用 API `usage.inputTokens` 作为锚点；前缀匹配时用"锚点 + 增量估算"，否则回退本地估算。

### 历史存储

- 对话文件：`chat_history/conversations/{conversationId}.json`（`schemaVersion=2`）；索引：`chat_history/index.json`。
- 写入使用临时文件 + `fsync` + 原子移动。对话 ID 匹配 `^[A-Za-z0-9_-]{1,80}$`。
- 损坏对话 JSON 隔离到 `corrupted/` 目录。旧版 `history.json` 首次加载时自动迁移。
- 历史文件同时保存 `uiMessages`、`conversationMessages` 和 `contextSummaries`；旧版 `isContextSummary` 加载时迁移为独立摘要。

### 其他

- 工具结果写入历史时保留完整内容；加入 API 请求前由 `limitToolResultsForContext()` 截断到单条最多 24000 字符。
- `assistantParts` 按顺序保存 `markdown`/`thinking`/`tool` 片段；`AssistantToolPart.isLoading` 仅运行时使用。
- `streamChatWithRetries()` 每次流式请求最多重试 3 次。
- 停止生成会清理待确认 Shell 令牌、停止前台 Shell 并取消协程。

## AI 工具

工具定义和执行入口在 `LocalAiTools.kt`。所有工具 `description` 必填，schema 均 `additionalProperties=false`。工具调用最多循环 200 轮。

| 工具 | 用途 | 关键参数 |
| --- | --- | --- |
| `read_file` | 读取文本文件（别名 `read_local_file`，默认前 200 行，最多 400 行） | `path`, `first_lines` / `start_line`+`end_line` |
| `list_path` | 列出目录或文件信息（默认 100 项，最多 200 项） | `path`, `limit` |
| `fetch_url` | HTTP(S) 获取网页内容（`max_chars` 默认 20000，最大 100000） | `url`, `output_format`, `max_chars` |
| `fetch_webview` | 隐藏 WebView 渲染后提取内容（仅 `fetch_url` 无法获得动态内容时用） | `url`, `output_format`, `max_chars`, `timeout_seconds`, `settle_ms` |
| `web_search` | SearXNG 或 Tavily 搜索（仅 `searxngUrl` 或 `tavilyApiKey` 二选一非空时暴露） | 通用：`query`, `limit`；SearXNG：`page`, `language`, `categories`；Tavily：`search_depth`, `topic`, `time_range` |
| `shell` | 执行 Shell 命令（超 30 秒自动转后台） | `command`, `work_dir`, `background` |
| `shell_status` | 查询后台进程 | `id` |
| `shell_stop` | 停止后台进程 | `id` |
| `wait` | 等待后继续工具循环（`1..3600` 秒） | `seconds` |

- `confirmation_id` 只能由应用在用户确认高风险 Shell 后写入，模型不能自行构造。

## Shell 与 BusyBox

- BusyBox 以 `jniLibs/<abi>/libbusybox.so` 打包（arm64-v8a / armeabi-v7a / x86 / x86_64），启动时初始化 applet 软链接到 `filesDir/shell/bin`。
- AI 临时目录优先使用外部存储 `Android/data/<包名>/files/ai_tmp`，不可用时回退 `filesDir/shell/tmp`。
- Shell 执行时注入 `PATH`（`filesDir/shell/bin` → `filesDir/shell` → 继承）、`BUSYBOX`、`MOTE_SHELL_DIR`、`MOTE_AI_TMPDIR`、`TMPDIR`；未传 `work_dir` 默认在 AI 临时目录执行。
- 后台进程由 `ShellProcessManager` 管理，最多保留 20 个；stdout/stderr 各最多 65536 字符。

## Shell 高风险确认

- `ShellRiskDetector.detect(command)` 静态检测破坏性命令。命中时返回 `needs_confirmation=true`，不执行。
- `ChatViewModel` 通过 `shellConfirmation` 驱动确认条；确认后消费一次性令牌重试。
- 令牌有效期 10 分钟，必须匹配原始 `command`/`work_dir`/`background`。
- 用户取消、停止生成、切换/新建/删除对话时丢弃待确认令牌。
- **修改风险检测规则时必须同步更新 `ShellRiskDetectorTest`。**

## 网络请求

- 主聊天：`ChatApiClient.streamChat()`，固定 `model` + `messages` + `stream=true` + `Accept: text/event-stream`。
- 优先携带 `stream_options.include_usage=true`，不兼容时自动降级重试。`reasoning_effort` 仅非空时发送。
- 流式解析：`delta.content`、`delta.reasoning_content`、`delta.tool_calls`。非流式回退兼容多种格式。
- `usage` 兼容 `input_tokens/prompt_tokens`、`output_tokens/completion_tokens`、cached tokens、reasoning tokens。
- 上下文压缩：`compressConversation()`，`stream=false`、`temperature=0.1`、`max_tokens 256..8192`，不携带 tools；`finish_reason=length` 视为失败。
- 标题生成：`generateConversationTitle()`，`stream=false`、`temperature=0.2`、`max_tokens=48`；`titleModel` 为空时用本地备用标题。

## Markdown 渲染

- 入口 `MarkdownView`，将 AST 映射为原生 View 树，支持流式 block 级增量更新。
- `ChatMessageAdapter` 优先用 `setParts()` 渲染 assistant 片段，无片段时回退 `setMarkdown()`。
- `MarkdownParseCache` 全局缓存解析结果，历史 Markdown 后台预解析，流式最后片段跳过预解析。
- 代码高亮：`MarkdownCodeSpanRenderer`，主策略 prism4j，失败后正则回退。
- LaTeX 公式：块级支持 `$$...$$` / `\[...\]`，行内支持 `$...$` / `\(...\)`；已闭合公式用 RaTeX 原生渲染，流式未闭合公式保持原文显示。
- 新增语法高亮语言时同步 `MarkdownGrammarLocator` 和 `ui/markdown/prism/`。
- `StreamingMarkdownRenderer` 和 `TableSpan` 为旧实现，保留参考。

## 构建与验证

PowerShell 中使用以下方式调用 `gradlew.bat`，避免 Gradle 进程清理延迟导致卡住：

```powershell
Start-Process -FilePath ".\gradlew.bat" -ArgumentList "assembleDebug", "--no-daemon" -Wait -NoNewWindow
Start-Process -FilePath ".\gradlew.bat" -ArgumentList "assembleRelease", "--no-daemon" -Wait -NoNewWindow
Start-Process -FilePath ".\gradlew.bat" -ArgumentList "testDebugUnitTest", "--no-daemon" -Wait -NoNewWindow
Start-Process -FilePath ".\gradlew.bat" -ArgumentList "connectedAndroidTest", "--no-daemon" -Wait -NoNewWindow
```

- 修改代码后执行合适的测试或构建验证。
- 修改 BusyBox 打包逻辑时至少运行 `testDebugUnitTest` + `assembleDebug`。
- 修改上下文压缩/摘要失效/token 估算时至少运行 `ChatConversationContextHelperTest`。
- Release 已启用混淆，规则在 `app/proguard-rules.pro`。依赖版本以 `gradle/libs.versions.toml` 为准。

## 修改同步清单

| 修改范围 | 需同步更新 |
| --- | --- |
| 数据模型 | `ChatHistoryStore` 序列化/反序列化 |
| `ApiSettings` 设置字段 | `ApiSettingsStore`、`SettingsActivity`、设置页布局 |
| `ConversationSummary`/`SavedConversationState`/历史根字段 | 多对话索引、旧历史迁移、侧栏刷新 |
| `ContextSummary`/上下文压缩/token 估算 | 摘要替换、摘要失效、usage 锚点、历史迁移、`ChatConversationContextHelperTest` |
| `AssistantPart` 字段 | `ChatHistoryStore`、`MarkdownView.setParts()`、`ChatMessageAdapter`、工具结果展开状态 |
| 新增 AI 工具 | `LocalAiTools` 定义 + 执行 + `IntermediateStepsHelper.parseToolSummary` |
| Shell 风险检测 | `ShellRiskDetectorTest`；`confirmation_id` 不暴露为模型可构造输入 |
| API 请求体 | 保持 OpenAI 兼容；注意 `stream_options.include_usage` 降级和 `reasoning_effort` 条件发送 |
| 标题生成 | 保持空 `titleModel` 的本地备用标题兼容 |
| 外部存储权限 | `Utils.kt` + `SettingsActivity.kt` |
| Markdown 渲染/RecyclerView 绑定/LaTeX 公式 | `MarkdownParseCache`、`MarkdownView`、`ChatMessageAdapter`、`ChatFragment.preparseVisibleMessages()`、公式分隔符流式解析与 RaTeX 渲染兼容 |
| 图标更新 | Material Symbols Rounded，命名 `ic_{name}.xml`（不加 `_24px`） |

## 设计决策

- 不使用 Compose，保持传统 View + ViewBinding。
- 不使用 Room，聊天历史直接以 JSON 文件保存到应用私有目录。
- 多对话独立文件存储，侧栏通过摘要索引切换。
- UI 消息和 API 上下文分离，避免展示型中间步骤污染请求上下文。
- Shell 高风险命令必须经用户确认后才能执行。
- Markdown 使用自研原生视图树，避免 WebView 渲染依赖；LaTeX 公式使用 RaTeX 原生 Canvas 渲染。
- 固定单主题色方案（日间 #57A2DB / 夜间 #88A8E8），不使用 Material You 动态取色。
- `MANAGE_EXTERNAL_STORAGE` 权限必须由用户手动授予。
