# AGENTS.md — Mote 项目指南

## 项目概述

Mote 是一个运行在 Android 设备上的 AI Agent 聊天客户端。它通过 OpenAI 兼容的 Chat Completions API 与大语言模型交互，支持流式响应、工具调用（文件读取、Shell 命令执行等）、思考过程展示和 Markdown 渲染。

## 技术栈

| 类别          | 技术                                                                  |
| ----------- | ------------------------------------------------------------------- |
| 语言          | Kotlin                                                              |
| 最低 SDK      | 26 (Android 8.0)                                                    |
| 目标 SDK      | 36                                                                  |
| 构建系统        | Gradle (Kotlin DSL) + Version Catalog (`gradle/libs.versions.toml`) |
| UI 框架       | 传统 View 系统 + ViewBinding（未使用 Compose）                               |
| 架构模式        | MVVM (AndroidViewModel + LiveData)                                  |
| 异步          | Kotlin Coroutines                                                   |
| Markdown 渲染 | 自研渲染管线（BlockParser + InlineParser + SpannedBuilder + StreamingMarkdownRenderer） |
| 序列化         | org.json (JSONObject / JSONArray)                                   |
| 网络请求        | HttpURLConnection（无 Retrofit/OkHttp）                                |
| UI 组件       | Material Design 3, RecyclerView, DrawerLayout, Fragment, BlurView   |

## 项目结构

```
app/src/main/java/com/mukapp/mote/
├── MainActivity.kt              # 主界面：DrawerLayout + ChatFragment
├── SettingsActivity.kt          # 设置界面：API 配置、文件权限管理
├── data/
│   ├── model/
│   │   └── Models.kt            # 所有数据模型（ChatMessage, ApiSettings, AiToolCall 等）
│   ├── ApiSettingsStore.kt      # API 设置的 SharedPreferences 持久化
│   └── ChatHistoryStore.kt      # 聊天记录的 JSON 文件持久化
├── network/
│   └── ChatApiClient.kt         # OpenAI 兼容 API 客户端（流式 SSE + 非流式）
├── tools/
│   ├── LocalAiTools.kt          # AI 工具定义与执行调度（read_file, list_path, shell 等）
│   └── ShellProcessManager.kt   # Shell 进程生命周期管理（前台/后台、输出缓冲）
├── ui/
│   ├── ChatFragment.kt          # 聊天界面 Fragment
│   ├── ChatViewModel.kt         # 聊天业务逻辑（消息收发、工具调用循环、历史管理）
│   ├── ChatMessageAdapter.kt    # 聊天消息列表适配器（流式更新、中间步骤渲染）
│   ├── IntermediateStepsHelper.kt # 思考过程和工具调用的展示逻辑
│   ├── InnerNestedScrollView.kt # 自定义 NestedScrollView，解决嵌套滚动触摸冲突
│   └── markdown/
│       ├── StreamingMarkdownRenderer.kt # 流式 Markdown 渲染器入口
│       ├── BlockParser.kt       # Markdown 块级解析器
│       ├── InlineParser.kt      # Markdown 行内解析器
│       ├── MdBlock.kt           # Markdown AST 数据模型（MdBlock + InlineElement）
│       └── SpannedBuilder.kt    # Spanned 构建器 + 代码语法高亮
└── util/
    ├── Utils.kt                 # 通用工具函数（角色转换、权限检查、JSON 扩展）
    └── DisplayExt.kt            # dp/sp/px 单位转换扩展属性
```

## 架构与数据流

### MVVM 分层

- **View 层**：`ChatFragment` / `SettingsActivity`，通过 ViewBinding 访问 UI，观察 LiveData 更新界面
- **ViewModel 层**：`ChatViewModel`（AndroidViewModel），持有消息列表和设置状态，协程驱动异步操作
- **Data 层**：`ApiSettingsStore`（SharedPreferences）、`ChatHistoryStore`（JSON 文件）、`ChatApiClient`（HTTP 网络请求）

### 核心数据流

```
用户输入 → ChatViewModel.sendMessage()
  → 构建 conversationMessages（含 SystemPrompt）
  → ChatApiClient.streamChat()（SSE 流式请求）
  → 若返回 toolCalls → LocalAiTools.executeToolCall() → 结果追加到对话 → 再次请求
  → 若无 toolCalls → 最终回复 → 更新 uiMessages → 持久化
```

### 双消息列表

- `uiMessagesInternal`：展示给用户的消息（过滤掉 Tool 角色消息，含中间步骤）
- `conversationMessagesInternal`：发送给 API 的消息（过滤掉 Tool 角色消息，不含中间步骤）

### ChatMessageAdapter 差异化更新策略

`submitMessages()` 方法根据新旧列表差异选择不同更新策略：

1. **相同大小+相同顺序**：只更新变化项（≤4 项用 `notifyItemChanged`，否则 `notifyDataSetChanged`），流式更新带 `STREAMING_PAYLOAD`
2. **尾部追加**：`notifyItemRangeInserted`
3. **尾部删除**：`notifyItemRangeRemoved`
4. **其他情况**：`notifyDataSetChanged`

## AI 工具体系

Mote 向模型注册了以下工具，定义在 `LocalAiTools.kt`：

| 工具名            | 功能                 | 关键参数                                            |
| -------------- | ------------------ | ----------------------------------------------- |
| `read_file`    | 读取设备上的文本文件         | `path`, `first_lines` 或 `start_line`/`end_line` |
| `list_path`    | 列出目录内容或文件信息        | `path`, `limit`                                 |
| `shell`        | 执行 Shell 命令        | `command`, `work_dir`, `background`             |
| `shell_status` | 查询后台 Shell 进程状态    | `id`                                            |
| `shell_stop`   | 停止后台 Shell 进程      | `id`                                            |
| `wait`         | 等待指定秒数（配合后台 Shell） | `seconds`                                       |

工具调用最多循环 50 轮。Shell 命令超过 30 秒自动转为后台运行。

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

修改代码之后需要进行测试构建。

## 关键设计决策

1. **不使用 Compose**：采用传统 View + ViewBinding 方案
2. **不使用 Room**：聊天记录直接以 JSON 文件存储在应用私有目录
3. **双消息列表**：UI 消息和 API 消息分离，避免中间步骤污染 API 上下文
4. **Shell 进程管理**：前台/后台双模式，后台进程支持状态查询和手动停止，最多保留 20 个进程
5. **流式渲染优化**：50ms 防抖 + 差异化 RecyclerView 更新 + 流式 payload 部分绑定
6. **自研 Markdown 渲染**：不使用 Markwon 等第三方库，采用自研的 BlockParser + InlineParser + SpannedBuilder 管线，支持流式渲染和代码语法高亮（Kotlin/Java/Python/JS/C/Shell/SQL）
7. **IME 动画跟随**：ChatFragment 实现 WindowInsetsAnimationCompat.Callback，输入法弹出/收起时列表内容像素级跟随滚动

## 注意事项

- 修改数据模型时需同步更新 `ChatHistoryStore` 的序列化/反序列化逻辑
- 新增 AI 工具时需在 `LocalAiTools` 中同时添加工具定义和执行逻辑，并在 `IntermediateStepsHelper.parseToolSummary` 中添加摘要解析
- API 请求体固定包含 `stream=true`、`tools` 和 `reasoning_effort`，修改时注意兼容性
- `MANAGE_EXTERNAL_STORAGE` 权限需用户手动授予，相关逻辑在 `Utils.kt` 和 `SettingsActivity.kt`
- `read_file` 工具额外接受 `read_local_file` 作为别名，用于兼容不同模型的工具调用
- Markdown 渲染管线位于 `ui/markdown/` 目录，修改渲染行为时需关注 `StreamingMarkdownRenderer` 的流式/静态两种模式
- `ChatMessageAdapter` 使用 `LruCache<String, StreamingMarkdownRenderer>(16)` 缓存渲染器实例，流式消息调用 `setMarkdown()`，静态消息调用 `renderStatic()`

