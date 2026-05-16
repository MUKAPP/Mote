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
| 网络 | HttpURLConnection |
| 序列化 | org.json |
| Markdown | 自研原生视图树 + prism4j 语法高亮 |

## 目录速览

```text
app/src/main/java/com/mukapp/mote/
├── MainActivity.kt                 # DrawerLayout 主界面
├── SettingsActivity.kt             # API 设置、权限管理
├── MyApplication.kt                # Application、动态主题色、BusyBox 初始化
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
→ 构建原始 workingRawConversation，并基于独立 contextSummaries 临时生成请求用 workingConversation
→ ChatApiClient.streamChat() 发起流式请求
→ 按顺序累积 content、reasoning_content、tool_calls 到 assistantParts
→ 有工具调用时执行 LocalAiTools，并把 assistant tool_calls 与 tool 结果写回原始上下文后继续请求
→ Shell 命中高风险命令时暂停，等待用户确认或取消
→ 无工具调用时完成回复，持久化 UI 消息、原始 API 上下文和独立上下文摘要
→ 首轮对话结束后可用 titleModel 异步生成标题
```

## 重要架构约定

- UI 层为 `ChatFragment`、`SettingsActivity`，通过 ViewBinding 和 LiveData 更新界面。
- 业务层集中在 `ChatViewModel`，负责发送消息、工具循环、Shell 确认、历史切换、编辑、删除、重试和停止生成。
- 数据层包括 `ApiSettingsStore`、`ChatHistoryStore`、`ChatApiClient`。
- 聊天消息分为 `uiMessagesInternal`、`conversationMessagesInternal` 和 `contextSummariesInternal`。前者面向展示，后者保留原始 API 上下文，`contextSummariesInternal` 单独保存可替换旧上下文的压缩摘要。
- `conversationMessagesInternal` 通常不含 System 消息；工具链路完成后会保留真实 assistant `toolCalls` 消息和 Tool 消息。
- 上下文压缩不会删除或替换 `conversationMessagesInternal` 中的原始消息；压缩成功会向 `contextSummariesInternal` 追加新摘要并保留旧摘要，后续摘要可把旧摘要 ID 作为 `sourceMessageIds` 的一部分（如 `S2(source=S1/U2/A2)`）。
- 发送请求时只用最新可用摘要；如果最新摘要依赖旧摘要，会递归解析其覆盖的原始消息，并临时把最新摘要作为 User 消息放在未压缩消息前面一起发送。
- 删除、编辑或重试消息时，如果被影响的消息 ID 命中某条摘要的 `sourceMessageIds`，会删除命中的独立摘要及依赖它的后续摘要；如果最新摘要被删而旧摘要仍有效，后续请求会自动回退使用旧摘要，不从 UI 反向恢复原始上下文。
- `assistantParts` 按实际顺序保存 `markdown`、`thinking`、`tool` 片段，`AssistantToolPart.isLoading` 只用于运行时展示，不写入历史。
- 多对话历史保存在 `chat_history/conversations/{conversationId}.json`，索引保存在 `chat_history/index.json`。
- 多对话历史文件同时保存 `uiMessages`、原始 `conversationMessages` 和独立 `contextSummaries`；旧版内嵌在 `conversationMessages` 的 `isContextSummary` 会在加载时迁移为独立摘要。
- 首次加载会尝试迁移旧版 `chat_history/history.json`，并用 `legacy_migrated.json` 标记迁移状态。

## AI 工具

工具定义和执行入口在 `LocalAiTools.kt`。

| 工具 | 用途 | 关键参数 |
| --- | --- | --- |
| `read_file` | 读取文本文件 | `description`, `path`, `first_lines` 或 `start_line`/`end_line` |
| `list_path` | 列出目录或文件信息 | `description`, `path`, `limit` |
| `fetch_url` | 获取网页或文本 URL 内容 | `description`, `url`, `output_format`, `max_chars` |
| `fetch_webview` | 用隐藏 WebView 渲染网页后提取内容 | `description`, `url`, `output_format`, `max_chars`, `timeout_seconds`, `settle_ms` |
| `web_search` | 通过 SearXNG 搜索互联网 | `description`, `query`, `limit`, `page`, `language`, `categories`, `time_range`, `safesearch` |
| `shell` | 执行 Shell 命令 | `description`, `command`, `work_dir`, `background`, `confirmation_id` |
| `shell_status` | 查询后台进程 | `description`, `id` |
| `shell_stop` | 停止后台进程 | `description`, `id` |
| `wait` | 等待后继续工具循环 | `description`, `seconds` |

- 所有工具的 `description` 为必填，会直接作为工具标题展示给用户。
- `read_file` 兼容别名 `read_local_file`；读取中间内容时使用 `start_line`/`end_line`，并且优先级高于模型误填的 `first_lines`。
- `fetch_url` 只允许 `http`/`https`，支持 `output_format=text|raw|markdown`；HTML 转 Markdown 使用 `flexmark-html2md-converter`。
- `fetch_webview` 只允许 `http`/`https`，会在主线程创建不可见 WebView，启用 JavaScript 和 DOM Storage，等待页面完成与 `settle_ms` 后提取渲染后的 `innerText` 或 `outerHTML`；仅在 `fetch_url` 无法获得动态内容时使用。
- `web_search` 仅在 `settings.searxngUrl` 非空时暴露；执行时使用该地址请求 `/search?format=json`，模型不能传入或覆盖 SearXNG 地址。
- 工具调用最多循环 200 轮。
- Shell 命令超过 30 秒会自动转为后台进程。
- `confirmation_id` 只能由应用在用户确认高风险 Shell 后写入，模型不能自行构造。

## Shell 与 BusyBox

- BusyBox 以 `jniLibs/<abi>/libbusybox.so` 打包，覆盖 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`。
- `BusyBoxManager.initialize()` 在应用启动后后台初始化，创建 AI 临时目录，并创建 `filesDir/shell/busybox` 与 applet 软链接。
- AI 临时目录优先使用应用专属外部存储 `Android/data/<包名>/files/ai_tmp`；外部目录不可用时回退到 `filesDir/shell/tmp`。
- Shell 执行前会准备 AI 临时目录并调用 `BusyBoxManager.ensureInitialized()`；未显式传入 `work_dir` 时默认在 AI 临时目录执行，并注入 `PATH`、`BUSYBOX`、`MOTE_SHELL_DIR`、`MOTE_AI_TMPDIR`、`TMPDIR`。
- 若原生库不可用，会尝试 `assets/busybox/<abi>/busybox`，再退回系统 `PATH`。
- 前台和后台 Shell 由 `ShellProcessManager` 管理，后台进程支持状态查询和停止，最多保留 20 个。

## Shell 高风险确认

- `ShellRiskDetector.detect(command)` 在执行前静态检测破坏性或写入型命令。
- 命中风险时，`LocalAiTools.runShell()` 返回带 `needs_confirmation=true` 的工具结果，不直接执行命令。
- `ChatViewModel` 通过 `shellConfirmation` 驱动底部确认条；确认后消费一次性令牌并重试原命令。
- 用户取消、停止生成、切换、新建或删除对话时，必须丢弃待确认令牌。
- 待确认令牌有效期 10 分钟，且必须匹配原始 `command`、`work_dir`、`background`。
- 修改风险检测规则时必须同步更新 `ShellRiskDetectorTest`，覆盖误杀和漏检。

## 网络请求

- 主聊天请求在 `ChatApiClient.streamChat()` 中构建，固定包含 `model`、`messages`、`stream=true`；`tools` 来自 `LocalAiTools.toolDefinitions(settings)`，其中搜索工具按 SearXNG 设置动态暴露。
- `reasoning_effort` 仅在 `settings.reasoningEffort` 非空时发送。
- 流式解析支持 SSE `delta.content`、`delta.reasoning_content`、`delta.tool_calls`。
- 非流式回退支持 `message.content`、`message.reasoning_content`、`message.tool_calls`、`content` 数组和 `choices[0].text`。
- 标题生成使用 `ChatApiClient.generateConversationTitle()`，固定 `stream=false`、`temperature=0.2`、`max_tokens=48`，不携带 tools。
- `settings.titleModel` 为空时跳过模型标题生成，使用本地备用标题。

## Markdown 渲染

- Markdown 管线位于 `ui/markdown/`，入口是 `MarkdownView`。
- `MarkdownView` 将 Markdown AST 映射为原生 View 树，并支持流式 block 级增量更新。
- `ChatMessageAdapter` 优先使用 `MarkdownView.setParts()` 渲染 assistant 片段，仅在无片段时回退到 `setMarkdown()`。
- 代码高亮由 `MarkdownCodeSpanRenderer` 实现，主策略为 prism4j，失败后使用正则回退。
- 新增语法高亮语言时，需要同步 `MarkdownGrammarLocator` 和 `ui/markdown/prism/`。
- `StreamingMarkdownRenderer` 和 `TableSpan` 是旧实现，保留参考，不再作为主要渲染路径。

## 构建与验证

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew test
./gradlew connectedAndroidTest
```

- 修改代码后需要执行合适的测试或构建验证。
- 修改 BusyBox 打包相关逻辑时，至少运行 `testDebugUnitTest` 和 `assembleDebug`。
- Release 已启用混淆和资源压缩，规则在 `app/proguard-rules.pro`。
- 关键依赖版本以 `gradle/libs.versions.toml` 为准。

## 修改同步清单

- 修改数据模型时，同步更新 `ChatHistoryStore` 的序列化和反序列化。
- 修改 `ApiSettings.titleModel`、`ApiSettings.searxngUrl` 或其他设置字段时，同步更新 `ApiSettingsStore`。
- 修改 `ConversationSummary`、`SavedConversationState` 或历史根字段时，同步多对话索引、旧历史迁移和侧栏刷新逻辑。
- 修改 `ContextSummary` 或上下文压缩策略时，同步请求时摘要替换、摘要失效规则、历史迁移和 `ChatConversationContextHelperTest`。
- 修改 `AssistantPart` 字段时，同步 `ChatHistoryStore`、`MarkdownView.setParts()`、`ChatMessageAdapter` 和工具结果展开状态。
- 新增 AI 工具时，同步 `LocalAiTools` 的工具定义、执行逻辑和 `IntermediateStepsHelper.parseToolSummary`。
- 修改 Shell 风险检测时，同步测试，且不要把 `confirmation_id` 暴露为模型可构造的可信输入。
- 修改 API 请求体时，保持 OpenAI 兼容性，注意 `stream=true`、`tools` 和条件发送的 `reasoning_effort`。
- 修改标题生成逻辑时，保持空 `titleModel` 的本地备用标题兼容。
- 修改外部存储权限时，同步 `Utils.kt` 和 `SettingsActivity.kt`。
- 更新图标时使用 Material Symbols Rounded，文件命名为 `ic_{icon_name}.xml`，不使用 `_24px` 后缀。

## 设计决策

- 不使用 Compose，保持传统 View + ViewBinding。
- 不使用 Room，聊天历史直接以 JSON 文件保存到应用私有目录。
- 多对话独立文件存储，侧栏通过摘要索引切换。
- UI 消息和 API 上下文分离，避免展示型中间步骤污染请求上下文。
- Shell 高风险命令必须经用户确认后才能执行。
- Markdown 使用自研原生视图树，避免 WebView 渲染依赖。
- 动态主题色由 `MyApplication` 应用 Material You DynamicColors。
- `MANAGE_EXTERNAL_STORAGE` 权限必须由用户手动授予。
