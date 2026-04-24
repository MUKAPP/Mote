# AGENTS.md — Mote 项目指南

## 项目概述

Mote 是一个运行在 Android 设备上的 AI Agent 聊天客户端。它通过 OpenAI 兼容的 Chat Completions API 与大语言模型交互，支持流式响应、工具调用（文件读取、Shell 命令执行等）、思考过程与工具结果穿插展示，以及 Markdown 渲染。

## 技术栈

| 类别          | 技术                                                                  |
| ----------- | ------------------------------------------------------------------- |
| 语言          | Kotlin + Java（prism4j 语法定义）                                        |
| 最低 SDK      | 26 (Android 8.0)                                                    |
| 目标 SDK      | 36                                                                  |
| 编译 SDK      | Android 36.1（`compileSdk.release(36)` + `minorApiLevel = 1`）         |
| Java 编译选项   | Java 11                                                             |
| 构建系统        | Gradle (Kotlin DSL) + Version Catalog (`gradle/libs.versions.toml`) |
| UI 框架       | 传统 View 系统 + ViewBinding（未使用 Compose）                               |
| 架构模式        | MVVM (AndroidViewModel + LiveData)                                  |
| 异步          | Kotlin Coroutines                                                   |
| Markdown 渲染 | 自研原生视图树（MarkdownView + BlockParser + InlineParser + SpannedBuilder） |
| 代码语法高亮      | prism4j 2.0.0（MarkdownCodeSpanRenderer + MarkdownGrammarLocator）     |
| 序列化         | org.json (JSONObject / JSONArray)                                   |
| 网络请求        | HttpURLConnection（无 Retrofit/OkHttp）                                |
| UI 组件       | Material Design 3, RecyclerView, DrawerLayout, Fragment, BlurView   |

## 项目结构

```
app/src/main/java/com/mukapp/mote/
├── MainActivity.kt              # 主界面：DrawerLayout + ChatFragment
├── SettingsActivity.kt          # 设置界面：API 配置、文件权限管理
├── MyApplication.kt             # Application 入口，应用动态主题色
├── data/
│   ├── model/
│   │   └── Models.kt            # 所有数据模型（ChatMessage, ApiSettings, AiToolCall 等）
│   ├── ApiSettingsStore.kt      # API 设置的 SharedPreferences 持久化
│   └── ChatHistoryStore.kt      # 多对话聊天记录的 JSON 文件持久化与索引管理
├── network/
│   └── ChatApiClient.kt         # OpenAI 兼容 API 客户端（流式 SSE + 非流式回退 + 标题生成）
├── tools/
│   ├── LocalAiTools.kt          # AI 工具定义与执行调度（read_file, list_path, shell 等）
│   └── ShellProcessManager.kt   # Shell 进程生命周期管理（前台/后台、输出缓冲）
├── ui/
│   ├── BottomFadeRecyclerView.kt # 自定义 RecyclerView，只在底部物理边缘处显示虚化渐变效果
│   ├── ChatFragment.kt          # 聊天界面 Fragment
│   ├── ChatViewModel.kt         # 聊天业务逻辑（消息收发、工具调用循环、历史管理、编辑/删除/重试）
│   ├── ChatMessageAdapter.kt    # 聊天消息列表适配器（流式更新、assistant 片段渲染、工具结果折叠）
│   ├── ConversationSummaryAdapter.kt # 侧边栏对话摘要列表适配器（当前对话高亮、删除回调）
│   ├── IntermediateStepsHelper.kt # 工具调用摘要文本生成
│   ├── InnerNestedScrollView.kt # 自定义 NestedScrollView，解决嵌套滚动触摸冲突
│   ├── TypingIndicatorView.kt   # 自定义三点生成中动画视图
│   └── markdown/
│       ├── MarkdownView.kt           # Markdown 顶层视图容器，将 AST 映射为原生 View 树
│       ├── MarkdownCodeBlockView.kt  # 代码块视图（MaterialCardView 卡片样式，含语言标签和复制按钮）
│       ├── MarkdownCodeSpanRenderer.kt # 代码语法高亮渲染（prism4j 精确高亮 + 正则回退）
│       ├── MarkdownTableView.kt      # 表格视图（Canvas 绘制带网格线表格）
│       ├── MarkdownThemeUtils.kt     # Markdown 主题色解析工具函数
│       ├── MarkdownGrammarLocator.java # prism4j 语法定位器，语言名→语法定义映射
│       ├── StreamingMarkdownRenderer.kt # 旧版流式渲染器（已弃用，保留供参考）
│       ├── BlockParser.kt            # Markdown 块级解析器
│       ├── InlineParser.kt           # Markdown 行内解析器
│       ├── MdBlock.kt                # Markdown AST 数据模型（MdBlock + InlineElement）
│       ├── SpannedBuilder.kt         # Spanned 构建器（组合 MarkdownCodeSpanRenderer 实现行内代码高亮）
│       ├── TableSpan.kt              # 旧版表格 ReplacementSpan（已弃用，表格改由 MarkdownTableView 渲染）
│       └── prism/                    # prism4j 语法定义文件（10 种语言）
└── util/
    ├── Utils.kt                 # 通用工具函数（角色转换、权限检查、JSON 扩展）
    └── DisplayExt.kt            # dp/sp/px 单位转换扩展属性
```

## 架构与数据流

### MVVM 分层

- **View 层**：`ChatFragment` / `SettingsActivity`，通过 ViewBinding 访问 UI，观察 LiveData 更新界面
- **ViewModel 层**：`ChatViewModel`（AndroidViewModel），持有消息列表和设置状态，协程驱动异步操作
- **Data 层**：`ApiSettingsStore`（SharedPreferences）、`ChatHistoryStore`（多对话 JSON 文件与索引）、`ChatApiClient`（HTTP 网络请求）

### 核心数据流

```
用户输入 → ChatViewModel.sendMessage()
  → 基于 conversationMessagesInternal 构建 workingConversation，并在发送前临时插入 SystemPrompt
  → ChatApiClient.streamChat()（SSE 流式请求，支持非流式回退）
  → 流式接收 content / reasoning_content / tool_calls，并按顺序追加 assistantParts
  → 若返回 toolCalls → 将 assistant tool_calls 消息与 tool 结果追加到 workingConversation → 再次请求
  → 若无 toolCalls → 最终回复 → 将 workingConversation（去除 System 消息）回写到 conversationMessagesInternal → 更新 uiMessages 并持久化
  → 首轮对话完成后，如配置 titleModel，则异步调用标题模型更新对话标题
```

### 多对话历史

- `ChatHistoryStore` 以 `chat_history/conversations/{conversationId}.json` 保存单个对话，以 `chat_history/index.json` 保存当前对话 ID 和索引状态
- `ConversationSummary` 为侧边栏对话列表提供 `id`、`title`、`createdAt`、`updatedAt`、`messageCount` 等摘要信息
- `MainActivity` 使用 `ConversationSummaryAdapter` 渲染 DrawerLayout 内的对话列表，支持新建、切换、删除当前对话
- `ChatViewModel` 暴露 `conversationSummaries` 与 `currentConversationId`，并负责创建新对话、切换对话、删除当前对话、刷新摘要列表
- 首次加载会尝试从旧版 `chat_history/history.json` 迁移到多对话目录结构，并通过 `legacy_migrated.json` 标记迁移状态

### 聊天历史 JSON 格式

单个对话文件的根字段包括：`id`、`title`、`createdAt`、`updatedAt`、`baseUrl`、`model`、`uiMessageCount`、`conversationMessageCount`、`uiMessages`、`conversationMessages`。

消息字段包括：`id`、`role`、`content`、`toolCallId`、`toolName`、`toolArguments`、`excludeFromConversation`、`toolCalls`、`assistantParts`。

`assistantParts` 支持三类片段：

- `markdown`：保存正文 `text`
- `thinking`：保存思考过程 `text`
- `tool`：保存 `toolName`、`toolArguments`、`result`

`AssistantToolPart.isLoading` 仅用于运行时 UI 展示，不写入历史 JSON。

### 双消息列表

- `uiMessagesInternal`：展示给用户的消息（过滤掉 Tool 角色消息，assistant 消息包含有序 `assistantParts`）
- `conversationMessagesInternal`：发送给 API 的上下文消息列表；通常不包含 System 消息，工具调用链路中会保留 assistant `toolCalls` 消息和 Tool 消息

从 UI 重新构建会话时，会过滤 `Tool`、`System` 和 `excludeFromConversation=true` 的消息；但工具调用链路成功完成后，会以真实请求上下文回写 `conversationMessagesInternal`。

### Assistant 片段模型

- `AssistantMarkdownPart`：普通 Markdown 正文
- `AssistantThinkingPart`：模型返回的 `reasoning_content`，在消息流中按实际顺序展示
- `AssistantToolPart`：工具调用与工具结果片段，运行时可通过 `isLoading=true` 表示工具正在执行
- `ToolCallAccumulator`：用于累积流式 `delta.tool_calls` 中分片返回的函数名与参数

### ChatMessageAdapter 差异化更新策略

`submitMessages()` 方法根据新旧列表差异选择不同更新策略：

1. **相同大小+相同顺序**：只更新变化项（≤4 项用 `notifyItemChanged`，否则 `notifyDataSetChanged`），流式更新带 `STREAMING_PAYLOAD`
2. **尾部追加**：`notifyItemRangeInserted`
3. **尾部删除**：`notifyItemRangeRemoved`
4. **其他情况**：`notifyDataSetChanged`

### 消息操作

- **编辑消息**：`editMessage(index)` 将指定用户消息及之后的所有消息移除，并把内容回填到输入框
- **删除消息**：`deleteMessage(index)` 删除指定用户消息及其对应的助手回复
- **重试消息**：`retryMessage(index)` 仅对最后一条助手消息生效；删除该助手消息后，将其前一条用户消息内容放回草稿并移除原用户消息，再调用 `sendMessage()` 重新发送
- **停止生成**：`stopGenerating()` 取消当前请求；若已有可保留的 assistant 内容或工具结果，会按当前状态回写 UI 与会话上下文

## AI 工具体系

Mote 向模型注册了以下工具，定义在 `LocalAiTools.kt`：

| 工具名            | 功能                 | 关键参数                                            |
| -------------- | ------------------ | ----------------------------------------------- |
| `read_file`    | 读取设备上的文本文件         | `description`, `path`, `first_lines` 或 `start_line`/`end_line` |
| `list_path`    | 列出目录内容或文件信息        | `description`, `path`, `limit`                                 |
| `shell`        | 执行 Shell 命令        | `description`, `command`, `work_dir`, `background`             |
| `shell_status` | 查询后台 Shell 进程状态    | `description`, `id`                                            |
| `shell_stop`   | 停止后台 Shell 进程      | `description`, `id`                                            |
| `wait`         | 等待指定秒数（配合后台 Shell） | `description`, `seconds`                                       |

其中 `description` 为必填参数，需要用一句简短中文说明本次工具执行的目的；该文本会直接展示给用户作为工具标题。

工具调用最多循环 200 轮。Shell 命令超过 30 秒自动转为后台运行。

工具执行期间会先插入 `AssistantToolPart(isLoading=true)` 作为加载中片段，工具返回后替换为最终结果。`wait` 工具由 `ChatViewModel` 延迟指定秒数后继续下一轮请求。

## 网络请求与响应解析

`ChatApiClient.streamChat()` 构建 OpenAI 兼容 Chat Completions 请求，主聊天请求包含：

- `model`
- `messages`
- `stream=true`
- `tools`
- `reasoning_effort`（仅当 `settings.reasoningEffort` 非空时包含）

流式解析支持 SSE `delta.content`、`delta.reasoning_content`、`delta.tool_calls` 增量累积；非流式回退支持 `message.content`、`message.reasoning_content`、`message.tool_calls`，并兼容 `content` 为 `JSONArray` 以及 `choices[0].text` 的响应格式。

`ChatApiClient.generateConversationTitle()` 用于标题生成：使用 `settings.titleModel`，请求 `stream=false`、`temperature=0.2`、`max_tokens=48`，只发送 system + user 两条消息，不携带 tools。`titleModel` 为空时跳过模型标题生成，仅使用本地备用标题。

## Markdown 渲染管线

自研 Markdown 渲染管线位于 `ui/markdown/` 目录，采用**原生视图树**架构，支持流式和静态两种模式。`MarkdownView` 对普通 Markdown 和 assistant part 都维护解析缓存，支持 block 级增量更新，避免流式输出时频繁重建完整视图树。

### 渲染架构

`MarkdownView`（继承 `LinearLayout`）作为顶层容器，既可以渲染纯 Markdown 文本，也可以按顺序渲染 assistant 片段列表。Markdown 片段会通过 `BlockParser` 解析为块级 AST；思考片段和工具结果片段会直接生成对应的原生 `View`：

| 块级元素 | 视图组件 | 说明 |
|---------|---------|------|
| 代码块 | `MarkdownCodeBlockView` | MaterialCardView 卡片样式，含语言标签、复制按钮、可横向滚动代码区 |
| 表格 | `MarkdownTableView` | 自定义 View，Canvas 绘制带网格线表格，外层包裹 HorizontalScrollView |
| 标题/段落/列表/引用/分割线 | `TextView` | 通过 `SpannedBuilder` 构建 Spanned 文本设置到 TextView |
| 思考片段 | `MaterialCardView + TextView` | 以内联卡片形式插入消息流，保留先后顺序 |
| 工具结果片段 | `item_tool_result.xml` | 可展开查看参数与结果，和正文共享同一消息流 |

### 代码语法高亮

代码语法高亮由 `MarkdownCodeSpanRenderer` 实现，采用双策略：
1. **主策略**：通过 prism4j（`Prism4j` + `MarkdownGrammarLocator`）进行精确的语法分析和高亮
2. **回退策略**：使用正则匹配进行 Shell 关键字、字符串和注释的高亮

支持的语言：C-like、C、C++、Java、JavaScript、JSON、Kotlin、Markup (HTML/XML)、Python、SQL

### 支持的块级元素

- 标题（Heading，支持 Setext 和 ATX 风格）
- 代码块（CodeBlock，支持反引号围栏 fenced code block；支持语法高亮：C-like/C/C++/Java/JavaScript/JSON/Kotlin/Markup/Python/SQL）
- 无序列表（UnorderedList）
- 有序列表（OrderedList）
- 任务列表（TaskList）
- 引用块（Blockquote，支持嵌套）
- 表格（Table，支持列对齐）
- 段落（Paragraph）
- 水平分割线（HorizontalRule）

### 支持的行内元素

- 普通文本（Text）
- 粗体（Bold）
- 斜体（Italic）
- 删除线（Strikethrough）
- 上标（Superscript）
- 下标（Subscript）
- 行内代码（InlineCode）
- 链接（Link，支持行内链接与引用式链接）
- 自动链接（AutoLink）
- 反斜杠转义

`BlockParser` 会收集形如 `[id]: url "title"` 的链接定义，不将定义行渲染为普通段落；`InlineParser` 支持 `[text][id]` 与 `[text][]` 引用式链接。

### 表格渲染

使用自定义 `MarkdownTableView`（继承 `View`）通过 Canvas 绘制带网格线的表格，支持：
- 表头背景色
- 交替行背景色
- 列对齐（左/中/右）
- 圆角外边框
- 自动列宽计算与扩展
- 外层包裹 HorizontalScrollView 支持横向滚动

## 构建与运行

```bash
# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本（启用混淆和资源压缩）
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行 Instrumented 测试
./gradlew connectedAndroidTest
```

Release 构建启用了 `isMinifyEnabled` 和 `isShrinkResources`，ProGuard 规则定义在 `app/proguard-rules.pro`。

构建配置使用 Java 11 编译选项，并在 `settings.gradle.kts` 中启用 `org.gradle.toolchains.foojay-resolver-convention`。关键依赖版本以 `gradle/libs.versions.toml` 为准，当前包括 AGP 9.1.1、Material 1.12.0、Lifecycle 2.9.4、RecyclerView 1.4.0、BlurView version-3.2.0、prism4j 2.0.0。

修改代码之后需要进行测试构建。

## 关键设计决策

1. **不使用 Compose**：采用传统 View + ViewBinding 方案
2. **不使用 Room**：聊天记录直接以 JSON 文件存储在应用私有目录
3. **多对话历史**：每个对话独立 JSON 文件存储，索引文件记录当前对话，侧边栏通过摘要列表切换对话
4. **双消息列表**：UI 消息和 API 消息分离，避免中间步骤污染 API 上下文
5. **Shell 进程管理**：前台/后台双模式，后台进程支持状态查询和手动停止，最多保留 20 个进程
6. **流式渲染优化**：50ms 防抖 + 差异化 RecyclerView 更新 + 流式 payload 部分绑定 + Markdown block 级增量更新
7. **自研 Markdown 渲染**：采用原生视图树架构（MarkdownView 将 AST 映射为原生 View 树），代码语法高亮使用 prism4j 库（MarkdownCodeSpanRenderer + MarkdownGrammarLocator），支持流式渲染和 10 种语言语法高亮
8. **对话标题生成**：首轮对话后可使用独立 `titleModel` 生成标题；未配置时使用本地备用标题
9. **IME 动画跟随**：ChatFragment 实现 WindowInsetsAnimationCompat.Callback，输入法弹出/收起时列表内容像素级跟随滚动
10. **动态主题色**：MyApplication 应用 DynamicColors，支持 Material You 动态取色
11. **Material Symbols Rounded 图标**：项目统一使用 Material Symbols Rounded 风格图标，从 Google 官方 `material-design-icons` 仓库下载（主题路径 `materialsymbolsrounded`），保存为 `ic_{icon_name}.xml` 格式到 `res/drawable/` 目录，不使用 `_24px` 后缀命名

## 注意事项

- 修改数据模型时需同步更新 `ChatHistoryStore` 的序列化/反序列化逻辑；涉及 `ApiSettings.titleModel` 时还需同步 `ApiSettingsStore`
- 修改 `ConversationSummary`、`SavedConversationState` 或历史文件根字段时，需要同步多对话索引、旧版历史迁移和侧边栏列表刷新逻辑
- 修改 `AssistantPart` 字段时，需要同步 `ChatHistoryStore`、`MarkdownView.setParts()`、`ChatMessageAdapter` 和工具结果展开状态逻辑
- 新增 AI 工具时需在 `LocalAiTools` 中同时添加工具定义和执行逻辑，并在 `IntermediateStepsHelper.parseToolSummary` 中添加摘要解析
- API 主聊天请求体包含 `stream=true` 和 `tools`，`reasoning_effort` 为条件包含（仅当 `settings.reasoningEffort` 非空时添加），修改时注意兼容性
- 标题生成请求不携带 tools，固定 `stream=false`、低温度、短输出；修改标题生成逻辑时需保持对空 `titleModel` 的本地备用标题兼容
- `MANAGE_EXTERNAL_STORAGE` 权限需用户手动授予，相关逻辑在 `Utils.kt` 和 `SettingsActivity.kt`
- `read_file` 工具额外接受 `read_local_file` 作为别名，用于兼容不同模型的工具调用
- Markdown 渲染管线位于 `ui/markdown/` 目录，`MarkdownView` 是渲染入口，将 Markdown AST 映射为原生 View 树；`StreamingMarkdownRenderer` 和 `TableSpan` 为旧版实现，已弃用
- `ChatMessageAdapter` 优先通过 `MarkdownView.setParts()` 渲染 assistant 片段列表；仅在没有片段数据时才回退到 `setMarkdown()`
- `MarkdownCodeSpanRenderer` 使用 prism4j 进行代码语法高亮，新增语言需在 `MarkdownGrammarLocator` 和 `prism/` 目录中添加对应语法定义
- `ChatMessageAdapter` 维护工具结果片段的展开/折叠状态，通过 `expandedToolPartIds` 维护
