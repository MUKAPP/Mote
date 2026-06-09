# Mote

Mote 是一款运行在 Android 设备上的 AI Agent 聊天客户端，面向 OpenAI 兼容的 Chat Completions API。它支持流式响应、工具调用、本地 Shell、思考过程展示、多对话历史、本地文件读取、联网搜索和原生 Markdown 渲染。

## 功能特性

- OpenAI 兼容接口：支持自定义 API 地址、API Key、模型、轻量标题模型和上下文压缩模型。
- 流式聊天：支持 `stream=true` 响应、增量内容展示、推理内容展示和 token usage 统计。
- 工具调用：内置文件读取、目录列出、网页抓取、WebView 抓取、联网搜索、Shell 执行、后台进程管理和等待工具。
- 本地 Shell：打包 BusyBox for Android NDK 1.36.1，支持多 ABI，在 Android 设备上提供更完整的命令行环境。
- 风险确认：对高风险 Shell 命令进行静态检测，必须经过用户确认后才会执行。
- 多对话历史：按会话独立 JSON 文件保存，支持标题生成、切换、删除、编辑、重试和停止生成。
- 上下文压缩：在上下文过长时自动总结旧消息，降低长对话请求压力。
- Markdown 渲染：使用原生 View 渲染 Markdown，支持代码高亮、表格、LaTeX 公式和流式增量更新。
- 附件输入：支持添加图片和文件，文件内容过长时会截断后发送。
- 搜索扩展：可配置 SearXNG 或 Tavily，配置后向模型暴露 `web_search` 工具。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin + Java |
| Android | minSdk 26，targetSdk 36，compileSdk 36.1 |
| Java 语言级别 | Java 11 |
| 构建 | Gradle Kotlin DSL + Version Catalog |
| UI | 传统 View + ViewBinding |
| 架构 | MVVM，AndroidViewModel，LiveData，Coroutines |
| 网络 | HttpURLConnection |
| 序列化 | org.json |
| Markdown | 自研原生视图树 + prism4j + RaTeX |

## 项目结构

```text
app/src/main/java/com/mukapp/mote/
├── MainActivity.kt                 # DrawerLayout 主界面
├── SettingsActivity.kt             # API 设置、权限管理
├── MyApplication.kt                # Application、BusyBox 初始化
├── data/                           # 设置、历史记录、数据模型
├── network/                        # OpenAI 兼容请求和 SSE 解析
├── tools/                          # AI 工具、BusyBox、Shell 风险检测和进程管理
├── ui/                             # 聊天界面、ViewModel、Adapter、侧栏列表
├── ui/markdown/                    # Markdown 解析、渲染、代码高亮、表格和公式
└── util/                           # 通用工具
```

## 环境要求

- Android Studio，建议使用支持当前 Android Gradle Plugin 的较新版本。
- Android SDK Platform 36，并安装对应构建工具。
- JDK 使用 Android Studio 自带运行时或与当前 AGP 兼容的版本。
- Windows PowerShell、macOS Terminal 或 Linux Shell。

## 构建与运行

克隆项目后，使用 Android Studio 打开仓库根目录，等待 Gradle 同步完成，然后直接运行 `app` 模块。

也可以在命令行构建：

```powershell
.\gradlew.bat assembleDebug
```

生成的 Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 构建：

```powershell
.\gradlew.bat assembleRelease
```

## 测试

运行单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

运行设备测试：

```powershell
.\gradlew.bat connectedAndroidTest
```

## 使用配置

首次启动后进入设置页，至少填写以下内容：

| 配置项 | 说明 |
| --- | --- |
| API 地址 | OpenAI 兼容接口地址。地址末尾不是 `/chat/completions` 时会自动拼接。 |
| API Key | 接口密钥。本地模型服务通常可以留空。 |
| 模型 | 主聊天模型名称。 |
| 轻量模型 | 用于生成历史对话标题，留空时使用首条消息作为标题。 |
| 压缩模型 | 用于长上下文摘要，留空时使用主模型。 |
| 模型上下文长度 | 用于估算和限制请求上下文，填 `0` 表示不限制。 |
| 压缩长度 | 达到该估算 token 数后触发上下文压缩，填 `0` 表示关闭。 |
| SearXNG 地址 | 配置后启用搜索工具，需与 Tavily API Key 二选一。 |
| Tavily API Key | 配置后启用搜索工具，需与 SearXNG 地址二选一。 |

如果需要让工具读取外部存储文件，需要在设置页授予文件管理权限。该权限由系统设置页手动授予，应用不会自动绕过 Android 的存储限制。

## 内置工具

Mote 会根据设置和模型能力向 AI 暴露工具：

| 工具 | 用途 |
| --- | --- |
| `read_file` | 读取文本文件内容。 |
| `list_path` | 列出目录或文件信息。 |
| `fetch_url` | 获取 HTTP(S) 网页内容。 |
| `fetch_webview` | 使用隐藏 WebView 渲染动态网页并提取内容。 |
| `web_search` | 通过 SearXNG 或 Tavily 搜索网络内容。 |
| `shell` | 执行本地 Shell 命令，长任务可转为后台进程。 |
| `shell_status` | 查询后台 Shell 进程状态。 |
| `shell_stop` | 停止后台 Shell 进程。 |
| `wait` | 等待指定秒数后继续工具循环。 |

Shell 工具具备风险检测机制。命中删除、覆盖、权限破坏等高风险命令时，应用会显示确认条，用户确认后才会执行。

## 数据存储

- API 设置保存在应用私有存储中。
- 对话文件保存在 `chat_history/conversations/{conversationId}.json`。
- 历史索引保存在 `chat_history/index.json`。
- 损坏的对话 JSON 会隔离到 `corrupted/` 目录。
- 工具结果会完整保存到历史记录中，发送给模型前会按上下文预算截断。

## Markdown 支持

Mote 使用原生 Android View 渲染 Markdown，不依赖 WebView 展示聊天内容。当前支持常见块级和行内语法、代码块、行内代码、表格、链接、LaTeX 公式、流式未闭合公式回退显示，以及基于 prism4j 的代码高亮。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 访问 API、网页抓取和搜索服务。 |
| `MANAGE_EXTERNAL_STORAGE` | 在用户授权后允许工具读取系统允许访问的外部文件路径。 |

## 开发约定

- UI 使用传统 View + ViewBinding，不使用 Compose。
- 聊天历史直接以 JSON 文件存储，不使用 Room。
- UI 消息、API 上下文和上下文摘要分离维护。
- 新增设置字段时，需要同步更新设置存储、设置页和布局。
- 新增 AI 工具时，需要同步工具定义、执行逻辑和中间步骤展示。
- 修改 Shell 风险规则时，需要同步更新 `ShellRiskDetectorTest`。
- 修改上下文压缩、摘要失效或 token 估算时，需要同步相关测试。

## 许可证

本项目基于 Apache License 2.0 发布，详见 [LICENSE](LICENSE)。

内置 BusyBox 二进制来自 [Magisk-Modules-Repo/busybox-ndk](https://github.com/Magisk-Modules-Repo/busybox-ndk)，上游版本为 `1.36.1`（二进制版本标识为 `BusyBox v1.36.1-osm0sis`，`module.prop` 的 `versionCode` 为 `13614`）。本项目中的四个 ABI 文件分别对应上游 `busybox-arm64`、`busybox-arm`、`busybox-x86` 和 `busybox-x86_64`。
