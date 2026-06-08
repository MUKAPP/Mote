# DESIGN.md — Mote 视觉设计规范

## 设计方向

MD2 + MD3 混合风格，整体以 Material Design 2 为主基调，选择性保留 MD3 的部分组件形态（如毛玻璃顶栏、平滑圆角悬浮输入框）。不使用 Material You 动态取色，采用固定的单主题色方案，通过带透明度的黑/白/主题色叠加营造 two-tone 层次感。

## 色彩系统

### 基本原则

- 只有一种主题色（Primary），不设 Secondary / Tertiary
- 所有容器/卡片颜色使用带 alpha 的黑色或白色叠加在背景上，利用透明度自然适配层级
- 文字颜色使用黑/白 + 透明度
- 按钮等交互元素使用带 alpha 的主题色背景

### 日间模式

| 用途 | 色值 | 说明 |
|------|------|------|
| Primary | `#57A2DB` | 主题色 |
| On Primary | `#FFFFFF` | 主题色上的前景色 |
| Background | `#FFFFFF` | 页面底色 |
| Card | `#14000000` | 一级卡片背景（Black 8%） |
| Card Nested | `#14000000` | 二级嵌套容器（Black 8%，叠加在 Card 上约 16%） |
| Input Background | `#1F000000` | 输入框背景（Black 12%） |
| User Message Card | `#2657A2DB` | 用户消息卡片（Primary 15%） |
| Primary Container | `#1A57A2DB` | Primary 10%，用于选中态等 |
| Primary Alpha 15% | `#2657A2DB` | 按钮/Chip 选中态背景 |
| On Background | `#DE000000` | 主要文字（Black 87%） |
| On Background Secondary | `#99000000` | 次要文字（Black 60%） |
| On Background Disabled | `#61000000` | 禁用态文字（Black 38%） |
| Divider | `#1F000000` | 分割线（Black 12%） |
| Error | `#D32F2F` | 错误提示 |

### 夜间模式

| 用途 | 色值 | 说明 |
|------|------|------|
| Primary | `#88A8E8` | 主题色 |
| On Primary | `#0D0E0F` | 主题色上的前景色 |
| Background | `#0D0E0F` | 页面底色 |
| Card | `#14FFFFFF` | 一级卡片背景（White 8%） |
| Card Nested | `#14FFFFFF` | 二级嵌套容器（White 8%，叠加在 Card 上约 16%） |
| Input Background | `#1FFFFFFF` | 输入框背景（White 12%） |
| User Message Card | `#2688A8E8` | 用户消息卡片（Primary 15%） |
| Primary Container | `#1A88A8E8` | Primary 10% |
| Primary Alpha 15% | `#2688A8E8` | 按钮/Chip 选中态背景 |
| On Background | `#DEFFFFFF` | 主要文字（White 87%） |
| On Background Secondary | `#99FFFFFF` | 次要文字（White 60%） |
| On Background Disabled | `#61FFFFFF` | 禁用态文字（White 38%） |
| Divider | `#1FFFFFFF` | 分割线（White 12%） |
| Error | `#EF5350` | 错误提示 |

### Two-Tone 层级示意

```
背景 (Background)                    不透明底色
├── 卡片层 (Card)                    +8% Black/White
│   └── 嵌套容器 (Card Nested)       再 +8%（累计约 16%）
├── 输入框 (Input Background)         +12% Black/White
└── 用户消息 (User Message Card)      +15% Primary
```

层级通过透明度自然叠加，不超过两层，避免颜色浑浊。

### 代码块颜色

代码块背景使用 Black/White 5%（与基础卡片层相近但更低），叠加在消息卡片上后有明显区分。行内代码背景同代码块背景色。

### 表格颜色

表格 header 和交替行使用低透明度的 `colorSurfaceVariant` 叠加：
- Header：alpha `0x28`（约 16%）
- 交替行：alpha `0x14`（约 8%）

## 排版

| 元素 | 字号 | 字重 | 颜色 |
|------|------|------|------|
| 页面标题 | 20sp | Medium | On Background |
| 卡片标题 / 消息标签 | 14sp | Medium | Primary |
| 正文 | 16sp | Regular | On Background |
| 次要文字 / 时间 | 12sp | Regular | On Background Secondary |
| 代码 | 14sp | Regular (Monospace) | On Background |
| 按钮文字 | 14sp | Medium | Primary 或 On Primary |

字体使用系统默认，代码区域使用 monospace。

## 图标

- 图标库：Material Symbols Rounded
- 命名规则：`ic_{name}.xml`（不加 `_24px` 后缀）
- 默认尺寸：24dp
- 消息操作按钮图标：20dp
- 颜色：跟随文字层级（主要/次要），强调操作使用 Primary

## 组件规范

### 卡片 (Card)

| 属性 | 值 |
|------|-----|
| 圆角 | 连续平滑圆角，16dp（主要卡片）、12dp（嵌套/次要卡片如工具结果） |
| 背景 | Card 色或 Card Nested 色（带透明度叠加） |
| 边框 | 无（不使用 Outlined Card，不绘制 stroke） |
| 阴影 | 默认无 elevation（0dp），依靠色差区分层级；仅底部聊天输入栏保留现有悬浮阴影 |

圆角曲线统一使用 `ui/smooth` 中的连续平滑圆角实现；新增圆角背景、卡片、毛玻璃容器或自绘 Canvas 边框时应复用该实现，不新增普通 `<corners>`、`GradientDrawable.cornerRadius` 或 `drawRoundRect`。

### 顶栏 (Toolbar)

- 毛玻璃半透明效果（BlurView）
- 标题居中
- 无 elevation 阴影
- 背景：Background 色 + 一定透明度（让模糊效果可见）

### 聊天输入框 (Chat Input)

- 底部悬浮，使用连续平滑圆角毛玻璃和悬浮阴影；其他毛玻璃/卡片组件不使用阴影
- 内部：附件加号按钮 + 多行 EditText + 圆形发送按钮
- 附件预览：选择图片/文件后在输入框上方显示一行小号摘要，可一键清空
- 发送按钮：Primary 填充色，On Primary 图标色

### 设置页输入框 (TextInputLayout)

- 样式：Filled，无底部线（`boxStrokeWidth=0dp`）
- 背景色：`mote_input_background`（Black/White 8%）
- 四角统一连续平滑圆角 12dp
- 浮动标签 + placeholderText（不在 EditText 上重复设置 hint）

### 消息气泡

| 角色 | 背景 | 特征 |
|------|------|------|
| 用户消息 | User Message Card 色（Primary 10%） | 文本 + 附件摘要，右下角操作按钮 |
| AI 消息 | Card 色（Black/White 5%） | "AI" 标签（Primary 色）+ Markdown 渲染 + 操作按钮 |
| 工具结果 | Card Nested 色 | 可折叠，12dp 圆角，等宽字体显示参数和结果 |

用户消息带有主题色调，AI 消息为中性灰底，视觉上自然区分角色。

### 按钮

| 类型 | 用途 | 样式 |
|------|------|------|
| Filled | 主要操作（发送、确认） | Primary 背景 + On Primary 前景，连续平滑圆角 20dp |
| Tonal | 次要强调（新对话、权限设置） | Primary 15% alpha 背景 + Primary 前景 |
| Text | 低优先级操作（设置、取消） | 无背景 + Primary 前景 |
| Icon | 消息操作（复制、编辑、删除） | 无背景 + On Background Secondary 前景 |

### 对话框 (Dialog)

- 背景：主背景色（Background，不透明）
- 积极按钮：连续平滑胶囊型（cornerSize 50%）+ Primary 填充色 + On Primary 文字
- 消极按钮：Text 样式

### Chip（思考强度选择等）

- 选中态：Primary 15% alpha 背景 + Primary 文字
- 未选中态：Card Nested 色背景 + On Background Secondary 文字
- 无边框，连续平滑胶囊圆角（18dp），不显示 checked icon
- `chipSurfaceColor=transparent` 禁用内部 overlay，确保颜色与按钮一致

### 侧边栏 (Drawer)

- 背景：主背景色（Background），与主内容区一致
- 对话列表项：无边框卡片，16dp 连续平滑圆角，选中态使用 Primary Container 色背景
- 未选中态：Card 色背景
- 品牌区：应用名（Bold）+ 副标题
- 底部：分割线 + 设置按钮（图标与文字间距 12dp）

### Shell 确认条

- 位于输入框上方
- 毛玻璃背景
- 命令预览区域：Card Nested 色背景，12dp 连续平滑圆角，等宽字体
- 风险提示文字：Error 色
- 操作按钮：取消（Text）+ 确认（Filled）

## 间距规范

| 场景 | 值 |
|------|-----|
| 页面水平 padding | 16dp |
| 消息列表项垂直间距 | 8dp |
| 卡片内部 padding | 16dp |
| 侧栏对话项垂直间距 | 4dp |
| 输入框与屏幕边缘 | 16dp（水平）、16dp（底部） |
| 操作按钮区域 padding | 12dp 水平、6dp 上、12dp 下 |

## 动效

- 整体克制，使用轻量动画提升流畅感，不做花哨过渡
- 侧栏滑出：系统默认 DrawerLayout 动画
- 消息出现：新消息从底部轻微滑入 + 淡入（translateY 10dp + alpha，180ms，DecelerateInterpolator）
- 工具结果展开/折叠：高度渐变动画（200ms，DecelerateInterpolator）
- 打字指示器：三点脉冲动画
- 按钮/卡片点击：默认 ripple 效果
- 页面切换（进入设置等）：slide + fade（200~300ms）

## 空状态

- 居中布局：图标（Primary Container 色容器）+ 标题 + 副标题 + 提示卡片
- 图标容器：72dp 正方形，16dp 连续平滑圆角，Primary Container 色背景
- 提示卡片：Card 色背景，16dp 连续平滑圆角

## 设置页

- 无顶部介绍卡片，直接展示功能卡片
- 权限卡片：Card 色背景，状态通过标题/图标颜色区分（granted 用正常文字色，denied 用 Error 色）
- 接口配置/约定卡片：Card 色背景
- 输入框：Filled 样式，Input Background 色，12dp 统一连续平滑圆角，无底部线
- 思考强度选择：ChipGroup，胶囊型 Chip
- 保存按钮：Filled 样式
