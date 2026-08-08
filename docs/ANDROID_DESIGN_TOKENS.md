# 安科书架 Android 设计令牌规范（Design Tokens）

> 范围：仅安卓端 Compose UI（`ui/theme/Tokens.kt`）。桌面端语义见 `docs/ANDROID_UI_PLAN.md`；阅读器 WebView 由 `readerTheme(settings)` 与 Compose 同源驱动。

## 1. 目标

- 少量、有语义的令牌：间距、圆角、颜色；**相同角色必须复用**。
- 胶囊（pill）只给明确的小型操作或标签（FilterChip、历史 chip、徽标、色点）。
- 颜色按桌面端主题分类（bg / text / primary / accent / error）映射到 Material 3 角色。
- 新增按钮、输入框、卡片等组件一律引用令牌，不引入一次性魔法值（一次性图表细节与 NGA 显式彩色字除外）。

## 2. 间距令牌（`AnkeSpacing`）

| 令牌 | 值 | 用途 |
|---|---|---|
| `xxs` | 2dp | 文字与说明的极紧间距、小按钮上方留白 |
| `xs` | 4dp | 列表行内距、标题与副标题间距 |
| `sm` | 8dp | 组件间距、图标与文字间距、chip 内距 |
| `md` | 12dp | 卡片内距、内容块间距、输入框间距 |
| `lg` | 16dp | 页面水平内边距、区块间距、对话框内距 |
| `xl` | 24dp | 空态留白、大区块间距 |
| `xxl` | 32dp | 底部收尾留白、弹层底部 |

禁止：`6dp / 10dp / 14dp / 20dp` 等零散间距；历史遗留已统一归一到上述令牌。

## 3. 圆角令牌（`AnkeRadius` / `MaterialTheme.shapes`）

| 令牌 | 值 | 组件角色 |
|---|---|---|
| `small` | 8dp | 按钮、分段选择、列表行、搜索结果命中行、封面、设置分组行图标容器 |
| `medium` | 12dp | 卡片、面板、输入框、下拉框、色板卡、统计卡、书卡 |
| `large` | 16dp | 对话框、底部弹层、浮层 |
| `pill` | 全圆角 | FilterChip、历史 chip、计数徽标、色点、颜色选择圆钮 |

`AnkeShapes` 已接入 `MaterialTheme(shapes = AnkeShapes)`，组件优先使用 `MaterialTheme.shapes.small/medium/large`；pill 用 `AnkeRadius.pill`。

## 4. 颜色令牌（`MaterialTheme.colorScheme` / `MaterialTheme.ankeColors`）

桌面 `PALETTES` 四元组与 M3 角色映射：

| 桌面语义 | M3 角色 | 说明 |
|---|---|---|
| bg | `background` / `surface` | 页面底与卡片底（卡片用 surfaceContainerLow 系列分层） |
| text | `onSurface` / `onBackground` | 默认黑/白文字，随主题深浅切换 |
| primary | `primary` / `primaryContainer` | 主题色：按钮、进度条、选中态、页头 |
| accent | `secondary` / `secondaryContainer` | 强调色：标注、次要强调、图标容器 |
| error | `error` / `errorContainer` | 错误与危险操作 |
| — | `outline` / `outlineVariant` | 描边、分隔 |
| — | `onSurfaceVariant` / `surfaceVariant` | 次要文字、输入框标签 |

组件引用方式：`MaterialTheme.colorScheme.primary` 或 `MaterialTheme.ankeColors.primary`（后者为桌面语义别名，新代码推荐）。

## 5. 组件角色速查

| 组件 | 形状 | 颜色 | 间距 |
|---|---|---|---|
| 主按钮 `Button` | `shapes.small` | primary / onPrimary | 页面 lg 起步 |
| 文本按钮 `TextButton` | 默认 | primary | — |
| 输入框 `OutlinedTextField` | `shapes.medium` | surface / outline | 上下 sm |
| 卡片 `Card` | `shapes.medium` | surfaceContainerLow | 内距 md/lg |
| 下拉框 `ExposedDropdownMenuBox` | `shapes.medium` | surface | — |
| 书架封面 | `shapes.small` | surfaceVariant | 网格 md |
| 搜索结果组 | `shapes.medium` | surfaceContainerLow | 组内 sm/md |
| 命中行 | `shapes.small` | surfaceContainerHigh 底 | 行内 sm |
| 徽标/计数 | `AnkeRadius.pill` | surfaceContainerHighest | 内距 sm |
| FilterChip / 历史 chip | `AnkeRadius.pill` | 选中 secondaryContainer | 内距 sm/md |
| 对话框 / 底部弹层 | `shapes.large` | surfaceContainerHigh | 内距 lg |

## 6. 规范与禁令

1. 相同角色必须复用同一令牌；新组件先查速查表，找不到角色才允许新增令牌（新增需在本文档登记）。
2. 胶囊只给明确的小型操作或标签；按钮、输入框、卡片一律不用胶囊。
3. 颜色禁止硬编码十六进制；NGA 显式彩色字（预览示例、`[color]` 还原）与一次性图表细节（柱状图圆角、高亮底色）除外。
4. 阅读器 WebView 颜色必须与 Compose 主题同源（`readerTheme(settings)`），不得单独改 CSS 变量制造不一致。
5. 主题切换后按 §7 做一次同步检查。

## 7. 主题同步检查清单

任选一个主题（如浅色 `default-light`），逐屏核对：

- 书架：背景 = bg；页头标题/竖条 = primary；封面底 = surfaceVariant。
- 搜索：输入框底 = surface、描边 = outline；结果卡 = surfaceContainerLow；chip 选中 = secondaryContainer。
- 统计：卡片 = surfaceContainerLow；柱状图 = primary（75% 透明度）；选中书卡 = primary 12% 底。
- 设置：分组卡 = surfaceContainerLow；分段选择选中 = primaryContainer；色点边框 = outlineVariant。
- 下载：输入框与按钮同上；错误文案 = error。
- 阅读器：正文背景/文字 = bg/text；链接/主题按钮 = primary；无黑底黑字、无白闪。
