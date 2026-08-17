# 安科书架 Android UI 设计方案（M4 前置）

> **状态（2026-08-16 核对）：M4 已验收（2026-08-08），Android v1.0.0 已于
> 2026-08-09 发布；本文档为当时的设计方案，现作历史参考。** 页面语义已按本文
> 实现（设置六 Tab / 搜索按章限量 / 统计卡片），视觉落地以现役代码为准。

> 范围：仅影响 `android/` 与本文档。桌面端 `web/` 代码不改动；本文档描述的语义全部以 Windows v1.2.0 的 `web/js/settings.js`、`fullsearch.js`、`stats.js`、`web/css/reader.css` 为基准。

## 1. 目标

M4 的「设置 / 搜索 / 统计」三个页面在**信息结构、文案、选项语义**上与桌面端对齐，在**视觉与交互**上落地 Android Material Design 3。参考对象：

- 桌面端：设置页 6 Tab + 分区卡片、全文检索按章分组/每章 50 条/续取、统计卡片 + 最近 7 天柱状图 + 按书筛选。
- Material 3 官方规范：NavigationBar、Search、App Bar、Card、Segmented Button、FilterChip、Slider、Switch、Dialog/BottomSheet、Motion（[m3.material.io](https://m3.material.io/)）。
- 非官方参考：[Axiaobo7788/starlight-material-design-theme](https://github.com/Axiaobo7788/starlight-material-design-theme)（npm 包 `starlight-theme-md3`，已本地解包到 `.tools/starlight-theme-md3/`）。采纳其「token-first、tonal surface、克制不喧宾夺主、状态层 + 涟漪、CSS 变量映射」的设计取向，但不引入 Astro/Web 运行时。

## 2. 桌面端 UI 事实（读源码结论）

### 设置页

- 布局：48px 顶栏（返回 + 标题）→ 左侧 160px Tab 列 + 右侧面板（max-width 760px，居中）。
- 6 个 Tab：外观 / 阅读 / 辅助 / 快捷键 / 统计 / 数据。Tab 激活态 = primary 文字 + primary 10% 底色、圆角 8px。
- 每个 Tab 由「分区卡片」（白底、1px 描边、12px 圆角、14px padding）组成，分区标题小号加粗、字母间距 0.02em。
- 行结构：左列 label（13px）+ 说明（11px muted），右列控件（按钮组 / 滑块 + 数值 / 开关 / 色板 / 选择框）。
- 外观：主题模式四选一（跟随系统/浅色/羊皮纸/深色）、亮度滑块 0–70%、预设色板网格（9 套 PALETTES 卡片：三个色点 + 名称）、自定义四色（背景/主题色/强调色/文字色，原生取色器 + 9 快捷色点 + 默认按钮）、主题预览卡。
- 阅读：字号 12–36px、行高 1.2–3.0、页面宽度 50–150%、翻页方式下拉（滚动/自动双页/单页分页/横屏双页强制）、固定顶底栏开关。
- 辅助：标尺 / 逐段 / 速读 / 滚读四个开关行（label + desc + active 按钮）。
- 快捷键：10 个动作 + 录制按钮 + 恢复默认。
- 统计：汇总行 + 「详情」按钮（打开统计弹层）。
- 数据：打开数据目录、卸载并清除数据（双重确认）、版本号。

### 全文检索页

- 全屏覆盖层：48px 顶栏（返回 + 标题 + 「展开全部」+ 大小写敏感/全词匹配复选框）→ 搜索输入框（圆角 10px、focus 变 primary 边框）→ 历史 chips → 状态行（建索引中/出错）→ 汇总行（`共 N 处命中 · X/Y 章有结果（每章最多显示 50 条，可展开更多）`）。
- 结果按章分组：组头 = 折叠箭头（展开旋转 90°）+ 章节标题 + 右侧 `N 处` 圆角计数徽标；默认展开前 5 组；组内每条命中为整行按钮，关键词用 `<mark>`（warning 38% 底色）高亮；「显示本章更多结果（还剩 N 条）」虚线按钮续取。
- 历史每书 ≤10 条，输入时过滤显示；输入防抖 300ms，Enter 立即搜。

### 统计页

- 弹层 560px：标题 + 范围（当前选中书）+ 下拉选择（全部书籍/各书）+ 8 张统计卡（累计阅读/今日阅读/最近 7 天/阅读会话/平均每次/翻页次数/连续阅读/最近阅读）+ 「最近 7 天」柱状图（7 列，primary 色，今天最大）+ 最近阅读书目卡片（可点击切换筛选）+ 关闭按钮。
- 时长格式：`X 秒 / X 分钟 / X 小时 / X 小时 Y 分`；日期格式 `M月D日`。

## 3. 安卓适配原则

1. **语义不变、容器变**：桌面左 Tab 列在手机上改为顶部 `ScrollableTabRow`（六 Tab 等宽居中）；桌面下拉/复选框改为 M3 的 SegmentedButton/FilterChip；桌面弹层统计改为独立路由页面；桌面「快捷键」改为「操作/手势」Tab（Android 无键盘，语义为阅读器手势与音量键）。
2. **移动端原生优先**：返回用系统返回键 + TopAppBar 返回箭头；长列表用 LazyColumn/LazyVerticalGrid；输入用 M3 Search/TextField；选择用 ExposedDropdownMenuBox。
3. **桌面文案沿用**：所有选项名、说明文字、Toast/汇总文案与桌面一致（涉及键名 `custom_bg` 等存储键也一致，保证未来桌面→安卓导入可用）。
4. **两端数据同构**：搜索历史单独存 `filesDir/AnkeShelf/search_history.json`（`{book_id: [q...]}`，每书 ≤10，原子写）；统计/设置/标注继续用现有 JSON store。

## 4. Material 3 落地细则

### 4.1 颜色

- 重构 `ui/theme/Theme.kt`：以桌面 9 套 PALETTES（bg/text/primary/accent）为 seed，手动派生完整 `lightColorScheme`/`darkColorScheme`，至少覆盖：`primary/onPrimary/primaryContainer/onPrimaryContainer`、`secondary/tertiary`、`surface/surfaceContainer(系列)/surfaceVariant`、`onSurface/onSurfaceVariant`、`outline/outlineVariant`、`error`。
- 主题选择与自定义四色直接驱动 Compose 色板 + 阅读器 WebView CSS 变量，两端同源；「颜色铁律」延续：自定义色只作用于默认黑/白文字与界面底色，显式颜色（NGA 彩字）保留。
- 跟随系统模式：M3 首选 `isSystemInDarkTheme()` + `dynamicLightColorScheme/dynamicDarkColorScheme`（API 31+ 用系统壁纸色，31 以下回退桌面色板）。
- 强调色与警示：标注 6 色沿用桌面 `yellow/green/blue/pink/purple/cyan`，在 UI 中用色点 + 名称而非纯色块。

### 4.2 形状

对应桌面圆角与 M3 形状：

| M3 形状 | 数值 | 用途 |
| --- | --- | --- |
| extraSmall | 4dp | 输入框内边角、标签 |
| small | 8dp | 按钮、列表行、搜索结果命中行 |
| medium | 12dp | 分区卡片、章节结果组、统计卡（对齐桌面 12px） |
| large | 16dp | 对话框、底部弹层 |
| full | 圆形 | FilterChip、搜索框（28dp 胶囊）、色点、计数徽标 |

### 4.3 字体

- **界面字体**：系统默认字体（Roboto/厂商 CJK），遵循 M3 TypeScale（titleLarge 22sp、titleMedium 16sp/500、bodyLarge 16sp、bodyMedium 14sp、bodySmall 12sp、labelLarge 14sp/500、labelSmall 11sp/500）。界面不用霞鹜文楷，保持信息密度。
- **阅读正文**：继续用内置霞鹜文楷（assets/fonts），动态加载逻辑不动。
- **数字**：统计数值使用 `FontFeatureSettings("tnum")` 等宽数字，避免跳动。

### 4.4 图标

- 新增 `androidx.compose.material:material-icons-extended`（仅 debug 体积增大，release 由 R8 裁剪）。
- 底部导航（NavigationBar，M3 指示器为 secondaryContainer 药丸）：
  - 书架 `Icons.Outlined.LibraryBooks` / 选中 `Icons.Filled.LibraryBooks`
  - 下载 `Icons.Outlined.FileDownload` / `Icons.Filled.FileDownload`
  - 搜索 `Icons.Outlined.Search` / `Icons.Filled.Search`
  - 设置 `Icons.Outlined.Settings` / `Icons.Filled.Settings`
  - 统计 `Icons.Outlined.Insights` / `Icons.Filled.Insights`
- 阅读器控制条：返回 `ArrowBack`、目录 `MenuBook`、上一章 `SkipPrevious`、下一章 `SkipNext`、字号减/增 `TextDecrease/TextIncrease`、主题 `Palette`、翻页模式 `AutoStories/MenuBook`、书签 `Bookmark`、更多 `MoreVert`。
- 搜索页：`Search`、`Close`、`History`、`ExpandMore`、`SearchOff`（空态）。
- 设置页：每个 Tab 前缀图标（`Palette / FormatSize / Accessibility / TouchApp / Insights / FolderOpen` 等）。

### 4.5 动效与触觉

对照 M3 Motion 与 starlight 主题的动效取向（状态层 150–200ms、控件 200ms、导航/展开 300–400ms、强调减速曲线）：

- **路由切换**：`AnimatedContent`，旧页 80ms 加速淡出 + 上移 8dp，新页 180ms 强调减速淡入 + 下移 16dp（与 starlight `md3-route` 一致）。
- **Tab 切换**：内容 200ms 交叉淡入 + 8dp 垂直滑动；Tab 指示器用 M3 `TabRow` 自带动画。
- **章节结果组展开/收起**：`animateContentSize` + `animateRotation`（箭头 90°），200ms standard。
- **搜索历史**：`AnimatedVisibility` fade + scale（120–160ms）。
- **统计柱状图**：首次进入时柱高 `animateFloatAsState` 从 0 生长（400ms 强调减速）；数值切换时 `animateIntAsState`。
- **状态层**：自定义可点击项使用 `clickable/combinedClickable`（保留 ripple 与 pressed 态），不用裸 `pointerInput`；按钮统一 M3 Button/IconButton。
- **触觉反馈**：长按目录项、长按书架卡片、展开/收起结果组时 `LocalHapticFeedback.current.performHapticFeedback(LongPress)`；翻页按钮轻触由系统按键反馈。
- **减少动效**：Compose 1.11 的 `MotionDurationScale` 会自动按系统「动画时长缩放」（含关闭动画）缩放 `tween` 时长，无需手动读取 `LocalReduceMotion`；自定义动画统一走 `tween`/`animate*AsState` 即可。
- **沉浸式**：阅读器进入/退出系统栏用 200–300ms 渐变（现有实现保留）；控制条滑入滑出保留现有 slide 动画。

## 5. 三个页面的组件级设计

### 5.1 设置页（`ui/settings/SettingsScreen.kt` 重写）

结构：`Scaffold` + `TopAppBar`（返回箭头 + 「设置」）+ `ScrollableTabRow`（六 Tab，图标 + 文字）+ `LazyColumn` 面板。

| Tab | 分区 | 控件（M3） | 存储键 |
| --- | --- | --- | --- |
| 外观 | 主题模式 | SingleChoiceSegmentedButtonRow 四选（跟随系统/浅色/羊皮纸/深色） | `theme_mode/theme` |
| 外观 | 亮度 | Slider 0–70% + 百分比；阅读页叠加黑色遮罩 | `brightness` |
| 外观 | 预设色板 | FlowRow 色板卡（3 色点 + 名称，选中 primary 描边 + container 底色）+ 恢复默认 | `custom_*` |
| 外观 | 自定义颜色 | 4 行：颜色圆钮 → BottomSheet 预设色点 + 十六进制输入 + 默认 | `custom_bg/custom_primary/custom_accent/custom_text` |
| 外观 | 主题预览 | 预览卡（标题 + 示例文字 + 显式彩色文字） | 只读 |
| 阅读 | 排版 | Slider 字号 12–36 / 行高 1.2–3.0 / 页面宽度 50–150%，右侧实时数值 | `font_size/line_height/page_width` |
| 阅读 | 翻页方式 | FilterChip 四选：滚动阅读/自动双页/单页分页/横屏双页（强制） | `pagination/dual_page/auto_dual` |
| 阅读 | 界面 | Switch「固定显示顶底栏」 | `bars_pinned` |
| 辅助 | 辅助功能 | Switch ×4：标尺 / 逐段 / 速读 / 滚读（Android 阅读器同步实现对应 WebView 功能） | `show_ruler` 等 |
| 操作 | 手势 | 信息行（左/中/右点按、滑动翻页）+ Switch「音量键翻页」（阅读器实现） | 本地偏好 |
| 统计 | 阅读统计 | 汇总行（全部书籍 · 已读 X）+ 「查看详细统计」→ 跳统计页 | 只读 |
| 数据 | 数据 | 「打开数据目录」显示路径 + 「清除全部数据」AlertDialog 双重确认；NGA 配置入口提示（在下载页） | — |

每行保持桌面结构：左列 label + desc（bodyMedium + bodySmall/onSurfaceVariant），右列控件；分区用 `Card`（medium 圆角、outline 1dp、surfaceContainerLow 底色可选）。

### 5.2 搜索页（新增 `ui/search/SearchScreen.kt`）

结构：

- `TopAppBar`：返回箭头 + 「全文检索」+ 动作区（「展开/收起全部」TextButton）。
- 书籍选择：`ExposedDropdownMenuBox`（全部书籍或选一本书；桌面是书内搜索，安卓补一个书选择器）。
- 搜索框：M3 风格胶囊 `OutlinedTextField`（圆角 28dp，leading `Search`，trailing 清除按钮），防抖 300ms。
- 选项：FilterChip「大小写敏感」「全词匹配」（中文不受影响，沿用桌面 title 文案）。
- 历史：输入聚焦且有关键词时显示 `AssistChip`（每书 ≤10，过滤匹配）。
- 状态行：索引构建中（600ms 重试）/ 出错；汇总行文案与桌面一致。
- 结果：`LazyColumn` 章节组 `Card`；组头 = `ExpandMore` 旋转 + 章节标题 + `N 处` 徽标；命中行 = 全文按钮，关键词用 `buildAnnotatedString` 高亮（primaryContainer 底、onPrimaryContainer 字，圆角 4dp）；「显示本章更多结果（还剩 N 条）」TextButton（虚线样式可省略）。
- 点击命中：跳转阅读器 `gotoOffset(chapter, offset)`，返回书架后进入对应书与位置。
- 空态：`SearchOff` 图标 + 「没有匹配结果」。

数据层：新增 `data/SearchIndex.kt`（惰性索引、每章限 50、`searchMore`、大小写/全词）、`search_history.json`；与桌面搜索结果结构 `{total_hits, hit_chapters, total_chapters, results:[{chapter_index, chapter_title, chapter_hits, text_len, hits:[{offset, snippet}], more}]}` 对齐。

### 5.3 统计页（新增 `ui/stats/StatsScreen.kt`）

结构：

- `TopAppBar`：返回 + 「阅读统计」。
- 工具栏：当前范围文字（primary，labelLarge）+ `ExposedDropdownMenuBox`（全部书籍/各书）。
- 统计卡：`LazyVerticalGrid` 2 列自适应（桌面 `auto-fit minmax(128px,1fr)` 的移动版），8 张卡：累计阅读/今日阅读/最近 7 天/阅读会话/平均每次/翻页次数/连续阅读/最近阅读；数值 `titleLarge + tnum`，标签 `bodySmall/onSurfaceVariant`。
- 最近 7 天：自定义 `Canvas` 柱状图（7 柱，primary 75% 透明度，圆角 3dp，柱高按最大值缩放，今天可加描边）；柱顶/长按显示「M月D日 · X小时Y分」。
- 最近阅读书目：可横向 `LazyRow` 或纵向卡片列表（累计时长 + 书名 + 作者/会话/最近阅读），选中态 primary 描边 + container 底色；点击切换筛选。
- 空态：`BarChart`/`Insights` 图标 + 「暂无统计数据」。

时长/日期格式与桌面 `Util.fmtDuration/fmtDate` 完全一致（X 秒/分钟/小时、M月D日）。

## 6. 与阅读器/数据层联动

- 设置「亮度」：进入阅读器时在 WebView 外层叠加黑色 `Box(alpha=brightness)`；现有 `ReaderScreen` 增加该参数。
- 设置「辅助」四项：移植到安卓阅读器 WebView（`reader.js` 增加 ruler/paragraph-mask/rsvp/autoscroll 四个开关函数），仅语义移植，不复用桌面代码。
- 搜索跳转：`ReaderScreen` 新增 `initialOffset` 已是现有能力；命中跳转时先加载章节再 `gotoOffset`。
- 统计：`StatsStore` 已就绪；阅读器翻页/心跳接入（桌面 5 秒心跳 + 翻页计数），进度保存处同时累计 `pages_flipped`。
- 标注（M4 后续）：阅读器选择回调 → `annotations.json`；颜色选择 BottomSheet 复用设置页色点样式。

## 7. 实施顺序与验收

1. 主题重构 + 图标 + 底部导航（全部图标化、M3 形状/字体）。
2. 搜索数据层 + 搜索页 + 跳转阅读器。
3. 统计页（复用现有 StatsStore）。
4. 设置页 6 Tab 重写 + 阅读器亮度/辅助联动。
5. `assembleDebug` + 模拟器人工清单：六 Tab 可切换、搜索每章 50 条续取、统计筛选、无 emoji 残留、深色/浅色/羊皮纸下对比度正常。

参考来源：

- 桌面源码：`web/js/settings.js`、`web/js/fullsearch.js`、`web/js/stats.js`、`web/css/reader.css`（604–795 / 870–1035 / 1096–1253 行）。
- Material Design 3：[m3.material.io/components/search](https://m3.material.io/components/search/overview)、[m3.material.io/components/app-bars](https://m3.material.io/components/app-bars/overview)、[m3.material.io/blog/m3-expressive-motion-theming](https://m3.material.io/blog/m3-expressive-motion-theming)。
- starlight-theme-md3 v0.2.1：`dist/css/tokens.css`（M3 颜色/类型/形状/动效 token）、`dist/css/motion.css`（state layer、ripple、route 动效）、`dist/css/components.css`（搜索、卡片、Tab 组件参考）。
