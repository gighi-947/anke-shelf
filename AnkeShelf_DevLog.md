# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 决策记录（ADR）→ [docs/adr/README.md](docs/adr/README.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-20）

- 当前开发基线：`main`；骨碌碌阅读交互改造（悬浮气泡 / 侧边评论 / 段落评论 /
  沉浸总览 / 骰点解锁菜单）已全部合入并发布 v1.5.1；五批接手风险修复已合入；
  P5 批次已启动并完成 P5-A 快赢批、P5-B 裂图修复、P5-D 封面系统、
  P5-E1 Cookie 粘贴解析、P5-E2 双端应用内登录（Android WebView +
  Windows pywebview 二级窗）、NGA 主题自适应
  （含 UI 图标规范核查）；多轮架构收敛已完成：
  EventBus→显式回调、API 错误统一到 HTTP/ApiError、reader-lite 状态机
  Step 0–4、TaskManager 统一 NGA/Gululu/Export；
  文档漂移治理已强化
  （AGENTS §5 高漂移清单 + `scripts/check-doc-drift.ps1`）。
  精确提交与远端状态以 `git log` / `git status` 为准。
- 版本线：Windows `v1.5.1`（已发布，AnkeShelf-v1.5.1.zip）；
  Android `android-v1.2.0`（已发布，AnkeShelf-v1.2.0-android.apk）。
- 测试基线（Windows / JS / Android JVM 于 2026-08-20 实跑复核）：
  - Windows Python：`python -m unittest discover tests` = 326 项
    （本机 Python 3.14：4 跳；bundled Python 3.12：全量通过）；
  - JS：`node contracts/tests/textpos.test.js`（15 例）、
    `node contracts/tests/api-contract.test.js`（59 方法一致）、
    `node contracts/tests/api-contract-launch.test.js`（Python 启动失败诊断）、
    `node contracts/tests/bridge-contract.test.js`（桥版本 1，能力含 annotation·assist·gululu）、
    `node contracts/tests/reader-lite-parts.test.js`（9 parts / 62338 字符）、
    `node contracts/tests/reader-lite-textpos.test.js`（跨端折叠 12 例）、
    `node tests/js/reader-session.test.js`、`node tests/js/nga-cookie.test.js` 均 OK；
  - Android JVM：`gradlew testDebugUnitTest` = 206 项（205 过 / 1 跳）；
    DisciplineTest 9 项在岗；
  - Android 真机：ELE-AL00（Android 10）instrumentation 11 / 11 通过；
  - UI 实机 harness：`python -m tests.ui.runner` = 97 项 PASS（需桌面 WebView2）；
  - 骨碌碌正式冒烟 `formal_ui_smoke.js`（桌面 + 430px，含骰点菜单/段落评论/总览/
    抽屉保位断言）全过。
- CI：`windows.yml`、`android.yml`、`nightly.yml`、`contracts.yml`。
- 权威基线：版本线见 README 版本表；测试基线详见 `docs/MAINTENANCE_GUIDE.md` §7；
  待办以 `docs/ARCHITECTURE_ROADMAP.md` 为准（本节为快速快照）。

## 2. 本机环境（Windows 开发机）

- Python：本机 `F:\Users\Administrator\AppData\Local\Python\pythoncore-3.14-64\python.exe`
  （3.14.5，已写入用户 PATH，含 Scripts）；沙箱会话兜底
  `F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`（3.12.13）。
- Node：`F:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe`
- Android 构建：`JAVA_HOME=D:\Android\AndroidStudio\jbr`、
  `GRADLE_USER_HOME=F:\Users\Administrator\.gradle`、
  `ANDROID_HOME=D:\Codex\project1\.tools\android-sdk`；命令
  `android\gradlew.bat -p android testDebugUnitTest assembleDebug`。
- adb：`D:\Codex\project1\.tools\android-sdk\platform-tools\adb.exe`
  （需 `HOME/USERPROFILE=F:\Users\Administrator`）。

## 3. 本地不入库/勿打包内容

- `.local/archive/`：历史归档，含真实 NGA uid/cid 备份（gitignore 覆盖，勿入库）；
- `android/keystore/`、`keystore.properties`、`local.properties`：签名与 SDK 配置；
- `ngapost2md-python/config.ini`：本地 NGA 凭据（打包只带 `.example`）；
- `dist/`、`build/`、`.tools/`：构建产物与工具链。

## 4. 最近流水

### 2026-08-21 android：测试机反馈修复（第十四批）

- 修复：
  - 骨碌碌悬浮按钮上移到安全区以上，不再被底部状态栏遮挡；
  - 悬浮菜单触发区限制为屏幕正中自适应区块（宽 36% / 高 40%），减少误触；
  - 底部换章按钮点击后先显示按压态 120ms 再跳转，动画可感知；
  - 骰子默认解锁问题继续排查：JS 侧 mask 逻辑确认存在，下一步从宿主持久化
    数据侧定位（可能 `gululuUnlockedJson` 非空）。
- 验证：`compileDebugKotlin assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：未打包 UI 修改（第十三批）

- 骨碌碌阅读器右下角新增悬浮入口（AutoAwesome FAB，对齐桌面悬浮按钮样式），
  点击打开骨碌碌总览；悬浮栏隐藏时同步隐藏。
- 说明：本轮仍未打包，仅 `compileDebugKotlin` 校验通过。
### 2026-08-21 android：未打包 UI 修改（第十二批）

- 书架前缀开关已图标化（Title 图标，激活主题色）；
- 底部换章按压反馈加强（34% 主题色背景 + scale(0.96)）；
- 阅读器底部栏收敛为单一「设置」入口，统一设置面板内分三组：
  排版（字号/行高/翻页方式/主题）、阅读辅助（自动滚动/速读/标尺）、字体；
- NGA 补充 `.quote-author` / `.reply-to` 样式，回复引用头部颜色与链接色
  对齐桌面；
- 本轮按用户要求未打包安装，仅 `compileDebugKotlin` 校验通过。
### 2026-08-21 android：未打包 UI 微调（第十一批）

- 书架首页“隐藏前缀”按钮图标化（Title 图标，激活时主题色）；
- 底部换章按钮按压反馈加强：背景色 34% 主题色 + 边框强调 + scale(0.96)，
  过渡 100ms。
- 说明：按用户要求本轮未打包安装。
### 2026-08-21 android：测试机反馈修复（第十批）

- 修复：
  - 悬浮栏自动收起加 350ms 唤出保护，并移除 onProgress 兜底（避免“刚滚完
    快速唤出又被收走”）；JS 滚动通知节流 250ms → 80ms；
  - 底部换章按钮增加按压态（背景色过渡 + 透明度）；
  - 骰点未揭示值再增加 `visibility:hidden` 兜底，解决部分 WebView 数值仍可见；
  - 书架首页新增“隐藏前缀/前缀已隐藏”按钮，一键切换书名括号过滤。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第九批）

- 修复：滚动模式下，除 `onScrollMoved` 外，防抖保存回调 `onProgress` 也作为
  “滚动已发生”兜底自动收起悬浮栏，覆盖部分机型 window scroll 事件未触发的情况。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第八批）

- 修复：
  - 切出应用再返回后系统状态栏重新隐藏（ON_RESUME 沉浸式恢复）；
  - `initGululu` 失败隔离：Gululu 初始化异常不再影响底部换章按钮绑定；
  - 书架页骨碌碌书籍恢复「更新」按钮（直接走 GululuImportService 更新）；
  - 标题括号过滤正则结果补 `.trim()`，与桌面 `displayTitle` 对齐。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第七批）

- 修复：骰点遮罩增加内联样式兜底（`color:transparent; background:currentColor`），
  部分 WebView 对 EPUB 内联 CSS 的 currentColor+transparent 组合不生效导致数值
  仍可见；揭示时同步清内联样式。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第六批）

- 修复：`reader.css` 强制 `html/body { margin:0 !important; padding:0 !important; }`，
  分页模式 `#paged-scroll { max-width:none; margin:0 !important; }`，
  防止骨碌碌 EPUB 自带 `body { margin:0 1em }` 在分页列布局中造成顶部/两侧偏移。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第五批）

- 修复：
  - 顶部悬浮栏下移量 50dp → 30dp，且背景色移到 padding 之前，
    下移区域也有同色模糊背景（不再透明漏字）；
  - 骨碌碌交互元素命中测试 `hitGululuInteractive`：点击骰点/秘密/线索/音乐/
    评论徽标时，宿主不再把该次点击当作“唤出/收起悬浮菜单”。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第四批）

- 修复：骨碌碌楼层上报增加几何兜底（`elementFromPoint` 未命中时扫描
  `.gululu-floor` 矩形，分页/滚动分别选当前列/阅读线以上最后楼层），
  评论抽屉与段落徽标不再依赖单一命中路径。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈修复（第三批）

- 修复：
  - 撤销“正文内容区下移 50px”的做法，恢复原 `topInsetPx`；改为仅顶部悬浮
    操作栏下移 50dp（自动安全区模式），避免分页模式顶部空白；
  - 底部本章进度细条由 2dp 加粗到 4dp；
  - 骰点值/后缀增加元素级 `onclick` 兜底，解决部分机型 document 委托点击
    被阅读器点击处理挡住导致无法揭示；
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈批量修复（第二批）

- 修复：
  - NGA 回复引用评论正文改为 `NgaFormatHtml.renderContentHtml`（此前直接拼
    `raw_content`，BBCode 不渲染）；
  - 分页模式增加 `.nga-floor/.gululu-floor/.nga-quote/.nga-comment/table` 等
    `break-inside:auto` 与紧凑边距覆盖，修复长卡片溢出列边界；
  - 悬浮栏百分比统一为全书进度（与书架同口径：分页=页码/总页数，滚动=
    scroll_ratio/text_offset 比例）；
  - 悬浮栏隐藏后，阅读器底部显示 2dp 细条本章进度条。
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：测试机反馈批量修复（第一批）

- 背景：测试机逐项反馈骨碌碌/阅读器问题。
- 修复：
  - `sanitizeReaderBody` 放行 `data-*` / `aria-*` 属性（骨碌碌骰点/迷雾/
    段落评论/音乐等交互钩子此前被清洗丢失）；
  - 滚动/翻页后自动收起悬浮栏：不再要求 `barsHeld`，悬浮栏可见即收；
  - 顶部安全区自动模式再下移 50dp；
  - 「已下载」页扩展为 NGA + 骨碌碌（骨碌碌书支持直接「更新」与「导出 EPUB」，
    Markdown 导出仅 NGA 显示）；
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：修复骨碌碌楼层卡片容器被清洗丢失（补充根因）

- 背景：上一修只恢复了 EPUB 外部 CSS，测试机反馈楼层卡片仍只有“第xx楼”
  文字颜色恢复，边框/内边距/头部布局没有渲染。
- 根因：`sanitizeReaderBody` 白名单没有 `section/header`，`<section
  class="gululu-floor">` 和 `<header class="floor-head">` 被解包，类名丢失，
  CSS 选择器自然命中不了；同时 `id` 不在全局属性里，`floor-<id>` 锚点也被剥掉。
- 修复：
  - `ALLOWED_TAGS` 增加 `section / article / header / footer / main`；
  - `GLOBAL_ATTRS` 增加 `id`；
  - 新增 2 个 JVM 回归测试（Gululu 楼层容器保留、link stylesheet 提取）。
- 验证：`gradlew testDebugUnitTest` = 206 项（205 过 / 1 跳）；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：修复骨碌碌楼层卡片样式未加载

- 背景：测试机反馈骨碌碌安科的楼层卡片样式丢失。
- 根因：安卓阅读器自建 HTML 壳时只提取章节内 `<style>` 块，没有恢复
  `<link rel="stylesheet">` 外部样式表；骨碌碌 EPUB 的楼层卡片 CSS 位于
  `EPUB/style/main.css`，因此整个 CSS 未进入阅读页。
- 修复：
  - `extractReaderParts` 额外返回 `styleHrefs`（Jsoup 提取 rel=stylesheet 链接）；
  - `NativeReaderScreen` 组装章节时按链接用 `session.readAsset` 读取 EPUB
    自带 CSS，与 `<style>` 块一起内联进阅读页；
  - 保持主题自适应：原 CSS 中的 `.gululu-floor` 边框/内边距等几何样式恢复，
    颜色由 reader.css 的 CSS 变量继续接管。
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：统一下载页入口（NGA / 骨碌碌）

- 背景：用户测试机反馈下载页未完全整合，骨碌碌与 NGA 下载仍分散在两个一级菜单。
- 修复：
  - `DownloadScreen` 页头标题从「NGA 下载」改为「安科下载」；
  - 一级菜单移除独立「骨碌碌导入」，「下载」入口内改为 NGA / 骨碌碌
    FilterChip 切换，复用原 `DownloadPanel` / `GululuPanel` 表单；
  - 同步更新 DownloadScreen/RootNavigation 仪器测试文案（本机编译通过，未跑
    connected 以免卸载测试机应用清数据）。
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据），启动正常。
### 2026-08-20 release：Android android-v1.2.0（对齐 Windows 大版本）

- 版本：Android `android-v1.2.0`（versionCode 3）。
- 产物：`dist/AnkeShelf-v1.2.0-android.apk`（16,843,301 字节，
  SHA256 `8029429244681441BE92F3C7E6C9AC2F9EB127D6CA9E5D627C82980E7ADA3247`）。
- 内容：Android 全量对齐 Windows 专项（批 1–9，详见
  `docs/ANDROID_PARITY_PLAN.md`）：阅读器标注/书签/嵌套目录/进度滑块/阅读辅助
  （自动滚动、速读、标尺、按书字体、代码高亮）；骨碌碌全链路（导入/热更新/评论/
  骰点/迷雾/秘密/线索/音乐/背景/视效/总览）；NGA 下载补页数上限与按目录楼分章；
  数据完整性校验入口与 `gululu_immersive` 契约字段修补。
- 验证：Android `gradlew testDebugUnitTest` = 204 项（203 过 / 1 跳）；
  `assembleRelease` 成功；`check-release.ps1` 凭据扫描 PASS（APK 内
  reader-lite.js 与字体 SHA256 均与源一致）；Windows 326 项、JS 契约全绿。
- 遗留：真机手工验证清单见 `docs/ANDROID_PARITY_PLAN.md` 批 1–9 的备注
  （标注交互、阅读辅助、目录楼分章、联网导入与跨端打开、热更新、骰点跨会话保持、
  段落评论联动、沉浸元素随阅读线切换）。
- 发布动作：`main` 已推送至 origin；标签 `android-v1.2.0` 已推送；GitHub Release
  「安科书架 Android v1.2.0」已发布（Latest，含 `AnkeShelf-v1.2.0-android.apk`，
  远端资产与本地构建 SHA256 一致）。

### 2026-08-20 android：对齐批 8+9 —— 骨碌碌阅读交互（评论 / 骰点 / 秘密 / 沉浸）

- 新增 `reader-lite.parts/60-gululu.js`（宿主层，桥能力追加 `gululu`）：
  - 骰点遮罩与揭示：未解锁组加 `masked`、迷雾块加 `gululu-fog-hidden`；
    单组点击、Alt 整楼、「接下来 10 组」；揭示后上报宿主持久化；
    **分页模式下显隐变化触发重排**（否则页码与内容错位）；
  - 秘密/线索/音乐/停止音乐点击 → 上报宿主（JS 侧**不含任何解密逻辑**）；
  - 段落评论徽标注入：徽标带 `data-textpos-exclude`，不进折叠纯文本，
    因此加载评论不会让 `text_offset` 漂移（纪律测试守护）；
  - 阅读线上下文上报：当前楼、视效、氛围背景（阅读线前最后一个标记生效）、
    自动音乐（每标记只触发一次）；滚动防抖与翻页都会刷新。
- 新增 `data/GululuUnlockStore.kt`（端私有 `gululu_unlocks.json` / `gululu_clues.json`）：
  骰点解锁按书保存、上限 3000 → 裁剪 2000 保留最近、线索按书存标题→口令、
  单书重置只清解锁与线索（不动进度与书签）、用已收集线索逐个试解秘密。
- 新增 `ui/reader/native/NativeReaderGululu.kt`：评论抽屉（当前楼 + 段落过滤 + 过期提示）、
  只读弹幕、氛围背景（Coil）、视效覆盖层、秘密弹窗（明文只在此出现）、沉浸总览
  （本章统计 + 揭示接下来 10 组 / 重置解锁 / 弹幕 / 停止音乐 / 打开评论）。
- 阅读器接线：`session.gululuSourceId`（来自 EPUB `dc:identifier`）为正才启用整套交互；
  评论按楼按需加载（5 分钟缓存 + 离线回退），段落评论计数回灌 WebView 生成徽标；
  音乐用 `MediaPlayer` 循环播放、同曲再点即停、退出释放。
- 降级说明（与桌面的有意差异）：骰点点击音效未实现；桌面的 Canvas 粒子视效在
  Android 改为低成本 Compose 覆盖层（只动 transform/opacity，符合动效纪律）。
- 验证：Android `gradlew testDebugUnitTest` = 204 项（203 过 / 1 跳，含
  `GululuUnlockStoreTest` 6 例与 DisciplineTest 新增「骨碌碌宿主层不得改写正文与坐标」）；
  `assembleDebug` 成功；解包 APK 校验 `reader-lite.js` = 61721 字节且含 `initGululu`
  与徽标排除属性。
- 待真机验证：骰点揭示跨会话保持、段落徽标 ↔ 抽屉联动、自动音乐/背景/视效随阅读线切换、
  重置解锁后进度与书签不受影响。

### 2026-08-20 android：对齐批 7 —— 骨碌碌热更新与增量基线

- `data/GululuUpdate.kt` ← `app/gululu_update.py` 纯逻辑：`snapshot.json` 基线读写
  （端私有 sidecar，不入双端契约）、**append-only 前缀不变量**、增量合并、
  旧书从现有 EPUB 的 `floor-<id>` 锚点做一次性迁移校验。
- `service/GululuUpdater.kt` 编排四条决策：基线损坏→要求完整重导；基线缺失→旧书迁移；
  基线可用→只拉索引 + 新增正文；无新增且图片模式未变→**只刷新基线不重建 EPUB**
  （不扰动进度与标注）。重建复用导入器的 `.part` 原子替换与入架。
- 导入现在也会写基线（批 6 遗留）：下次更新直接走增量，不必每次读锚点迁移。
- 服务与 UI：`GululuImportService` 支持 `action=update`（与导入共用状态/通知/取消）；
  下载页「骨碌碌导入」在本机已有该书时显示「检查更新」。
- 测试（`GululuUpdateTest` 11 例）：基线往返与四类损坏、删除/重排/替换/重复 ID 冲突、
  合并缺正文、导入即建基线、无新增不重建（比对 EPUB mtime）、有新增增量重建并含新锚点、
  远端删楼时原书原样保留、旧书迁移成功与历史不一致拒绝、无本地 EPUB 拒绝更新、
  仅图片模式变化也重建。
- 验证：Android `gradlew testDebugUnitTest` = 204 项（203 过 / 1 跳）、`assembleDebug` 成功。

### 2026-08-20 android：对齐批 6 —— 骨碌碌 EPUB 生成 + 导入前台服务

- 骨碌碌链路第一次**产出可打开的书**：Android 导入的 EPUB 与桌面 ebooklib 产物同构。
- `data/GululuEpub.kt` ← `app/gululu_epub.py` + `gululu_epub_styles.py`：
  - 章节分组：作者标记优先（空标题标记不算），否则每 20 楼一章；缺楼层正文显式失败；
  - 单楼 `<section id="floor-<floorId>">` + 楼层头 + 评论块 + `data-gululu-vfx`；
  - 首章附来源行与作品评论；跨章背景继承写 `data-gululu-background-initial`；
  - 自写 ZIP（mimetype 首条且 STORED）→ `EPUB/content.opf`（`dc:identifier=gululu-<id>`、
    `dc:source` 指向公开页）、`EPUB/chapters/chapter_%04d.xhtml`、`EPUB/style/main.css`、
    `EPUB/nav.xhtml`、`EPUB/toc.ncx`、`EPUB/images/*`、`EPUB/cover.<ext>`；
  - CSS 与桌面 `GULULU_EPUB_CSS` 同一份（两端观感一致）。
- `data/Epub.kt` 补 `identifier` 字段（桌面早有）：骨碌碌来源识别与批 8 的阅读器
  交互开关都依赖它。
- `service/GululuImporter.kt` ← `app/gululu_service.py` 的 import 路径：
  拉快照 → （内嵌模式）准备图片 → 生成 EPUB 到 `post.epub.part` → **原子替换** →
  注册书架；取消/失败必删 `.part`，替换失败时用备份恢复旧 EPUB 并重新登记。
  快照与图片抓取都是注入点（测试无需网络，也不必打开 client 类）。
- `service/GululuImportService.kt` + `GululuServiceStatus`：前台服务（dataSync）、
  进度通知带取消动作、结束留一条非持续通知；`AndroidManifest` 已登记。
- UI：下载页新增「骨碌碌导入」Tab（`ui/download/GululuPanel.kt`），
  输入即校验链接/ID（复用 `GululuSource.extractBookId` 的桌面同款文案）、
  图片三态选择、进度条与取消。
- 测试：
  - 双端 golden 扩到 `epub_group_cases`（3 例：20 楼兜底 / 作者标记 / 空标题标记回退）
    与 `epub_floor_cases`（2 例：带评论与视效 / 普通楼）；
  - `GululuEpubTest`：结构断言（mimetype STORED 首条、必需条目齐全、
    `gululu-48856` identifier、`floor-<id>` 锚点）+ **round-trip**（自家 `EpubBook`
    能打开并读出标题/章节/正文）；
  - `GululuImporterTest` 5 例：成功落盘并入架、取消不留产物、失败保留已有书并清 `.part`、
    缺楼快照显式失败、内嵌模式图片入包且正文引用 `../images/`。
- 验证：Android `gradlew testDebugUnitTest` = 204 项（203 过 / 1 跳）、`assembleDebug` 成功；
  Windows = 326 项 OK。
- 待办：真机联网导入一本公开安科，确认产物能被 Windows 端直接打开（跨端互通验证）。
- 下一步（批 7）：热更新与增量基线（`snapshot.json` + append-only 前缀校验 +
  旧书一次性迁移 + 失败回滚保住 book_id）。

### 2026-08-20 android：对齐批 5 —— 骨碌碌协议层（助手 / 沉浸 / 评论）

- 范围：仍是纯协议层（无 UI 入口，EPUB 生成与导入服务在批 6）。三块与桌面逐函数对照：
  - `data/GululuAssistant.kt` ← `app/gululu_assistant.py`：内联协议（`<秘密>` /
    `<发现秘密>` / `<引用 id floor>`）、段落折叠（可嵌套、缺标题/缺结束都显式报错）、
    引用块、骰点稳定分组（`<floorId>-g-<n>`，值/后缀拆成可点击 span）、迷雾锁
    （骰点之后的节点整块包进 `gululuFogBlock`）、jumpFloor、sensitive，
    以及 CryptoJS/OpenSSL salted AES 解密（MD5 EVP KDF + AES-CBC + PKCS7）。
  - `data/GululuImmersive.kt` ← `app/gululu_immersive.py`：音乐/自动音乐/停止音乐、
    六类视效（每层只取第一个有效值）、背景区间与清除背景、
    仅接受**无凭据 HTTPS**（含用户名/密码、控制字符、超长一律拒绝）。
  - `data/GululuComments.kt` + `service/GululuCommentService.kt` +
    `GululuClient.fetchComments` ← `app/gululu_comments.py` /
    `gululu_comment_service.py`：一级 100/页、子回复 1000/页按 total 收敛、
    分页提前结束显式失败；公开字段裁剪（不写入原始用户对象）；
    评论块 `details/summary` 计数；端私有缓存
    `gululu_library/<id>/comments/<floorId>.json` + 5 分钟 TTL + **离线回退**。
- 跨端 golden 扩容（同一份 `contracts/fixtures/gululu/ast-cases.json`）：
  - `floor_cases` 15 例：完整楼层管线（沉浸 → 助手 → 骰点/迷雾 → 渲染），
    覆盖内联秘密/线索、同书与跨书引用、折叠（正常/缺标题/缺结束）、引用块、
    骰点+迷雾、jumpFloor（有/无锚点）、sensitive、音乐（手动/自动/停止/非 HTTPS）、
    视效（支持/不支持）、背景（区间/清除/缺结束），并对照 `expected_vfx` 与
    `expected_background` 副产物；
  - `comment_cases` 5 例：子回复、转义与换行、作品评论块、空块、`paragraphId=0`。
  期望值由 Windows 现行实现生成后人工逐条审核锁定（契约规则 4）。
- 结果：Android 侧首次运行即与 Windows **逐字符一致**（21 AST + 15 楼层 + 5 评论）。
- 验证：Android `gradlew testDebugUnitTest` = 176 项（175 过 / 1 跳）、`assembleDebug` 成功；
  Windows = 324 项 OK。
- 下一步（批 6）：骨碌碌 EPUB3 生成（章节分组 / `floor-<id>` 锚点 / 封面 / CSS）
  与导入前台服务（`.part` 原子替换 + 注册书架）。

### 2026-08-20 android：对齐批 4 —— 骨碌碌数据层（来源/客户端/AST/图片三态）

- 范围：只做数据层，不接 UI（导入服务与 EPUB 生成在批 6）。三块与桌面逐函数对照：
  - `data/GululuSource.kt` ← `app/gululu_source.py`：ID/链接/整段文本提取、
    EPUB `dc:identifier` 识别。桌面抛 ValueError，这里返回
    `GululuIdResult.Ok/Err`（文案一致，调用方直接展示）。
  - `service/GululuClient.kt` ← `app/gululu_client.py`：`platform:1` 头、
    `code != 200` 视为业务失败、楼层正文 20 条一批、**缺失楼层显式报错**。
    把信封校验 / 索引校验 / 分批合并拆成公开纯函数
    （`parseDataPayload` / `parseIndexPayloads` / `mergeFloorBatches`），
    沿用 `NgaClient.parsePageFull` 的可单测惯例，无需 MockWebServer。
  - `data/GululuAst.kt` ← `app/gululu_ast.py`：marks（含安全色白名单）、
    paragraph `data-paragraph-id`、heading 降级到 h3–h6、image 仅 HTTPS +
    三态 resolver + avatar 包裹、hardBreak、collapsibleBlock、未知节点可见占位。
    转义与 Python `html.escape(quote=True)` 逐字符对齐（含 `'` → `&#x27;`）。
    扩展点 `GululuNodeRenderer` 对应桌面 `render_assistant_node` /
    `render_immersive_node`（批 5 按此接入，核心渲染不动）。
  - `service/GululuImages.kt` ← `app/gululu_images.py`：三态枚举、正文图片收集
    （仅 HTTPS、去重保序）、6 路并发内嵌、单图 25 MB 上限、**按文件签名判类型**
    （不信 HTTP 头）、逐张失败记录、可取消、资源命名 `images/<sha256 前16位>.<ext>`。
- 跨端 golden（新增 `contracts/fixtures/gululu/ast-cases.json`，21 例）：
  marks 嵌套顺序、rgb 归一化、危险色丢弃、未知 mark 可见、转义、段落 id、
  空段落、heading 钳制、三态图片、非 HTTPS 拒绝、avatar、折叠块、未知节点。
  Windows 由 `tests/test_contracts.py::GululuAstFixtureTest` 消费，
  Android 由 `GululuAstTest` 消费；两端**逐字符**一致（同构 EPUB 决定 text_offset）。
- 验证：Android `gradlew testDebugUnitTest` = 166 项（165 过 / 1 跳）、`assembleDebug` 成功；
  Windows = 322 项 OK；AST golden 首次运行即双端一致。
- 下一步（批 5）：助手协议（折叠/引用/骰点/迷雾/秘密/线索）、沉浸指令（音乐/背景/视效）、
  评论（分页 + 子回复 + 缓存），全部通过 `GululuNodeRenderer` 接入并扩展同一份 golden。

### 2026-08-20 android：对齐批 3（G8 收尾）—— NGA 目录楼分章 / 页数上限

- 背景：Android `NativeBookWriter` 其实早就支持 `toc_mode=split` 与 `groupFloorsByToc`
  （与桌面 `_group_floors_by_toc` 逐行对照），但没有任何代码**生产** `tocChapters`，
  参数里也没有 `page_limit` / `toc_pid` / `toc_mode`——功能等于关着。
- 改动：
  - 新增 `data/NgaTocParser.kt`：Kotlin 版 `ngapost2md/toc.py`（foldBox 折叠块 →
    章节标题 + `<h4>Day` 分段 → `[url=…pid=N]` 条目），输出直接是 `meta.toc` 的扁平
    结构（lead 在前、随后按 Day 顺序），与桌面 `_serialize_toc` 等价；
    `cleanHtml` 复用 HTML5 实体表并保留 `[昴星团行动]` 这类合法标题内容。
  - `NgaClient.fetchFloorContent(tid, pid)`：按 pid 取单楼正文（目录是可选增强，
    接口失败/无该楼返回空串 → 回退按楼分章，不让整本下载失败）。
  - `NgaDownloadParams` / `download.json` 断点状态 / `defaultsFor` / 前台服务 Intent /
    下载表单 / 更新对话框全链路补上 `pageLimit`、`tocPid`、`tocMode`。
  - 页数上限语义对齐桌面 `cfg.page_download_limit`：首次下载从第 1 页起算，
    热更新只约束"本次新增页"（从断点页起算）。
- 双端 golden（新增 `contracts/fixtures/nga-toc/`）：同一份目录楼 HTML + 期望章节 +
  split 分章边界，Windows 由 `tests/test_contracts.py::NgaTocFixtureTest`
  （`parse_toc` + `_serialize_toc` + `_group_floors_by_toc`）消费，Android 由
  `NgaTocParserTest` 消费（含 index 模式对照与异常回退）。夹具明确要求
  **无条目的折叠块两端都必须丢弃**。
- 验证：Android `gradlew testDebugUnitTest` = 148 项（147 过 / 1 跳）、`assembleDebug` 成功；
  Windows `python -m unittest discover tests` = 321 项 OK；JS 契约全绿。
- 批 3 完成；下一步进入批 4（骨碌碌数据层：来源识别 + 公开 API 客户端 + AST + 图片三态）。

### 2026-08-20 android：对齐批 3（前半）—— 数据完整性入口 / 契约字段修补 / 作者排序

- **跨端数据丢失修复（重要）**：Android `SettingsData` 缺 `gululu_immersive` 字段，
  而 `Settings.save()` 用 `encodeDefaults=true` 全量回写——桌面写的沉浸偏好在
  Android 保存任意设置后会被整块抹掉。新增 `GululuImmersivePrefs`（字段与契约 §4
  同名同缺省）并加往返回归测试（读桌面 JSON → 改字号 → 落盘仍保留 autoMusic/volume）。
- 数据完整性校验入口（roadmap §3.3 #2 关闭）：`data/Storage.kt` 新增
  `verifyJsonFile` / `verifyDataIntegrity`，语义对齐桌面 `storage.verify_json_file`
  （缺失=健康、解析失败=显式报错、顶层非对象=结构损坏，只看版本号不读内容值）；
  设置页「数据」区新增「校验数据完整性」入口与结果弹窗。
- 书架排序补 `author`（并把桌面取值 `title` 作为 `name` 的别名一起接受），
  作者为空的书排到末尾再按标题排序。
- 验证：`gradlew testDebugUnitTest` = 144 项（143 过 / 1 跳），新增
  `DataIntegrityTest` 4 例；`compileDebugKotlin` 通过。
- 批 3 剩余（已在下一条流水完成）：G8 —— NGA 下载参数 `page_limit` / `toc_pid` /
  `toc_mode=split`。

### 2026-08-20 android：对齐批 2 —— 阅读辅助 / 代码高亮 / 按书字体 / 进度精度

- 背景：`autoscroll_speed` / `rsvp_rate` / `show_ruler` / `book_fonts` 四个设置字段
  长期只有定义没有实现（空转），书架百分比只按章号取整。
- 改动：
  - 新增 `reader-lite.parts/48-assist.js`：移植桌面 `highlight.js` 的 tokenizer
    （关键字/字符串/数字/注释/函数/标点 6 类，`.syntax` 类，折叠规则内部无缝，
    不改 text_offset），以及自动滚动/自动翻页（滚动模式逐帧推进 speed×150 px/s，
    分页模式按页停留，到章尾 `requestChapter(1)`）。桥能力追加 `assist`。
  - 代码高亮在 `buildTextWithHighlights` 内**先于建坐标**执行（与桌面同顺序），
    由 DisciplineTest 守护。
  - 新增 `ui/reader/RsvpTokenizer.kt`（纯函数 + 单测）：桌面按空白切词，中文正文
    会退化成"整段一个词"导致速读不可用；这里按字符类别切分（拉丁按词、CJK 两字一块、
    标点吸附前块且不跨空白吸附）。
  - 新增 `ui/reader/native/NativeReaderAssist.kt`：阅读辅助面板（自动滚动 + 速度、
    速读开关、标尺开关、本书字体选择）+ RSVP 覆盖层 + 可拖动阅读标尺；底栏加入口。
  - 按书字体：`buildReaderHtml(..., bookId)` 优先取 `book_fonts[bookId]`
    （对齐桌面 `reader-utils.js` 的 resolveFamily）。
  - 书架百分比：新增 `BookRepository.progressPercent`，= (章索引 + 章内比例) / 总章数；
    章内比例分页用 `page_index/page_total`、滚动用 `scroll_ratio`（都缺省时退回章号占比）。
    桌面用 text_offset/章长需索引就绪，书架列表不打开书籍，故用已持久化的安卓扩展字段近似。
- 教训：RSVP 分词第一版把全角标点（U+FF0C 等）当成汉字凑进词块（出现"，出"），
  且 `a = b` 的 `=` 被吸附成 `2d6=`；两条都是单测先判红后修正——
  `isCjk` 必须加 `isLetter()` 闸门，标点吸附必须要求"中间无空白"。
- 验证：`gradlew testDebugUnitTest` = 140 项（139 过 / 1 跳）；`assembleDebug` 成功；
  解包 APK 校验 `reader-lite.js` = 50881 字节、`reader.css` 含 `.syntax .tok-kw`；
  JS 契约全绿（桥能力 `...,annotation,assist`）。
- 待办：真机验证自动滚动（滚动/分页两种模式到章尾换章）、速读、标尺拖动、按书字体切换。

### 2026-08-20 android：对齐批 1 —— 阅读器标注交互 / 嵌套目录 / 进度滑块

- 背景：接手评估发现 Android 相比 Windows 缺口很大（骨碌碌全链路零实现、
  标注只有数据层与设置页导出、目录抽屉扁平、无进度滑块、阅读辅助字段空转）。
  先产出差距矩阵与分批规划（新增 `docs/ANDROID_PARITY_PLAN.md`，10 批），
  本次落地第 1 批。
- 改动（均在 `android/` 内 + 共享 contracts/docs）：
  - **折叠规则补齐（前置红线）**：`reader-lite.parts/20-textpos.js` 抽出纯函数
    `foldItems(items)`，补上桌面同款「注入节点（`.hl-mark`/`.syntax`）内部无缝」与
    「仅注释分隔不插空格」规则，并新增 `TextPos.rangeToOffsets`。
    没有这条规则，注入高亮会让本章 `text_offset` 整体后移（进度与标注一起漂移）。
  - 新增 `reader-lite.parts/45-annotation.js`：`applyHighlights`（倒序注入
    `<mark class="hl-mark">`、注入后重建坐标）、`currentSelectionInfo`（选区 →
    text_offset 区间 + 视口矩形）、`bindSelection`（selectionchange 上报 /
    点击高亮上报）、`clearSelection`、`gotoTextOffset`（书签跳转）。
  - 桥能力追加 `annotation`（`BRIDGE_VERSION` 保持 1，能力为追加式扩展）；
    新增桥事件 `onSelection / onHighlightTap`，Kotlin 下发
    `applyHighlights / clearSelection / gotoTextOffset`。
  - `reader-lite.js` 现在可被 Node 加载（`window`/`module` 双守卫），
    仅导出与 DOM 无关的纯函数供契约测试使用。
  - Compose：新增 `ui/reader/native/NativeReaderAnnotations.kt`（选中工具条 6 色 +
    笔记、高亮编辑弹窗、笔记弹窗、标注与书签抽屉）；顶栏加书签开关与标注入口；
    底栏加全书进度滑块（松手才跳转）；目录抽屉改为嵌套（`ui/reader/TocNode.kt` +
    `BookSession.tocNodes()`，缩进 + 当前项高亮 + 自动滚到当前项）。
  - `assets/reader/reader.css` 的 6 色高亮改为与桌面 `COLOR_HEX` 同值。
- 语义决策：`saveProgressNow` 永远 `ratio=-1`（显式跳转/翻页以文本锚点或页码为准），
  滚动比例兜底只出现在防抖采样路径 `saveProgress`——由 DisciplineTest 守护。
  这条不变量是被既有纪律测试逼出来的：第一版让滚动跳转携带 `state.scrollRatio`，
  纪律测试直接判红。
- 测试：新增 `contracts/tests/reader-lite-textpos.test.js`（Windows `textpos.js` 与
  Android `reader-lite.js` 的 `foldItems` 逐项对照，12 例）+
  `TocTreeTest`（5 例）+ DisciplineTest 新增「标注注入不得改变 text_offset 折叠规则」。
- 验证：`gradlew testDebugUnitTest` = 135 项（134 过 / 1 跳）；`assembleDebug` 成功；
  解包 APK 校验 `assets/reader/reader-lite.js` = 45559 字节且含新函数；
  `node contracts/tests/*.test.js` 全绿；Windows 侧 319 项未受影响。
- 待办：真机验证「长按选中 → 高亮/笔记/书签 → 退出重进位置与高亮一致」（批 1 收尾）。

### 2026-08-20 docs：整理路线图（ARCHITECTURE_ROADMAP）

- 背景：路线图 §3/§4 仍保留大量已关闭批次的展开说明，当前待办被淹没。
- 改动：
  - `docs/ARCHITECTURE_ROADMAP.md` 头部精简；§3 改为“已关闭批次简表 /
    暂不实施 / 真实待办 / 保持延后”四段，§4 更新为当前执行顺序；
  - 保留 §2 现状核验（测试基线/代码规模热点/架构债）供漂移扫描使用；
  - 旧 P0–P4 细节不再在路线图展开（历史记录见 DevLog / DEVLOG_ARCHIVE）。
- 验证：`scripts/check-doc-drift.ps1` 通过；路线图 §2 关键行与
  `MAINTENANCE_GUIDE.md` §7 一致。
### 2026-08-20 win/android：对齐前基础审计（消除隐患）

- 背景：安卓对齐桌面版开发启动前，对双端契约、测试基线、任务设施做排查。
- 修复：
  - Windows `Shelf.save` 不再把运行时字段 `progress_pct` 写入 `shelf.json`
    （回归测试 `test_save_does_not_persist_runtime_progress_pct`）；
  - `app/tasks.py` 过期注释修正：NGA/Gululu/Export 均已接入 TaskManager；
  - 本机补齐 jsonschema 后 schema 契约测试 4 项由跳过转为通过；
  - 测试基线同步：Windows 319 项全过、Android JVM 129 过 / 1 跳。
- 结论：双端数据契约字段一致；主要功能差距在骨碌碌（Windows 独有）。
### 2026-08-20 release：Windows v1.5.1 / Android v1.1.0

- 版本：Windows `v1.5.1`；Android `android-v1.1.0`（versionCode 2）。
- 产物：`dist/AnkeShelf-v1.5.1.zip`、`dist/AnkeShelf-v1.1.0-android.apk`。
- 内容：P5-D 封面系统、P5-E1/E2 NGA 凭据傻瓜化、NGA 主题自适应、
  默认封面随主题/色板自适应、NGA 官方表情图直连与文字降级、
  骨碌碌封面本地缓存与热更新同步、书籍管理页、更多管理二级菜单等。
- 验证：Windows 319 项、JS 契约全绿（59 方法一致）、Android JVM 129/1、Android assembleRelease 成功。
### 2026-08-20 win：P5-E2 Windows 应用内 NGA 登录（pywebview 二级窗）

- 背景：P5-E2 Android 先行已完成；Windows 端仍需从 F12/Cookie 粘贴配置，
  本次补齐 pywebview 二级窗登录。
- 改动：
  - 新增 `app/nga_login.py`：惰性创建 NGA 登录二级窗（固定 bbs.nga.cn），
    从 WebView2 Cookie 提取 `ngaPassportUid`/`ngaPassportCid` 保存到
    `nga_config.ini`，成功后关窗并清理 WebView Cookie；
  - API 新增 `nga_login_start/status/extract/cancel`；
  - 下载面板「配置」页新增“在应用内登录”按钮与提取/取消操作，轮询登录窗状态；
  - 主窗口关闭时先销毁登录二级窗，避免其阻止应用退出。
- 验证：`python -m unittest discover tests` = 319 项 OK；
  `node contracts/tests/api-contract.test.js` 59 方法一致；JS 语法全绿。
### 2026-08-20 win/android：移除 NGA 下载深/浅色选择

- 背景：主题已自适应，NGA 下载不再需要深/浅色选项。
- 改动：
  - Windows 下载/更新表单移除“主题”下拉框与 theme 参数；
  - Android 下载页移除“主题”FilterChip 与 Intent 的 theme extra；
  - UI harness 移除对已删除下拉框的断言。
- 验证：JS 语法 OK；Android `compileDebugKotlin` BUILD SUCCESSFUL。
### 2026-08-20 win/android：NGA 楼层/引用/评论框随主题自适应

- 背景：NGA 下载时把浅/深主题色写死进章节 HTML，读者切换主题后会出现文字被遮挡。
- 修复：
  - Windows `native_book._css` 改为自适应 CSS，用 `--reader-bg/--reader-fg/--reader-accent` 和 `color-mix` 覆盖楼层/引用/评论内联样式；
  - Web 阅读器在 iframe 根节点注入这三个 CSS 变量；
  - Android `reader.css` 增加 `.nga-quote/.nga-comment/.floor-head/.comment-head/.nga-dice` 的自适应覆盖。
- 效果：不再需要按浅/深模式分别下载；阅读器当前主题自动决定对比度。
- 验证：Python native/server 相关 42 项 OK；JS 语法 OK；Android `assembleDebug` BUILD SUCCESSFUL。
### 2026-08-20 win：默认深色主题映射夜间色板

- 背景：默认 dark 主题在 PALETTES 中没有对应 id，导致封面取到浅色背景。
- 修复：`effectiveCoverColors` 将 `dark` 映射到 `night` 色板，浅色映射到 `default-light`。
- 验证：JS 语法检查 OK。
### 2026-08-20 win：默认封面适配全部预设/自定义色板 + 即时刷新

- 背景：预设色板多套未适配，且主题切换后封面需退出重进才生效。
- 修复：
  - `Theme.coverUrl` 改为直接传递当前有效 `bg`/`fg` 十六进制色值，服务端按实际色板生成骰子 SVG；
  - 主题切换/系统主题变化/设置页色板修改后立即 `Shelf.render()`，无需重启。
- 验证：`tests.test_server` 38 项 OK（含自定义色值用例）。
### 2026-08-20 win：默认封面骰子图随主题自适应

- 背景：默认封面 SVG 固定深色，浅色/羊皮纸主题下不协调。
- 修复：
  - `Theme.coverUrl` 在封面 URL 上附加 `theme=light|sepia|dark`；
  - `server.py` 缺失封面时按主题生成对应背景/线条颜色的骰子 SVG。
- 验证：`tests.test_server` 37 项 OK（含主题参数用例）。
### 2026-08-20 win：NGA 官方表情图直连与失败降级

- 背景：NGA 官方表情图（img4.nga.178.com/ngabbs/post/smile）经本地代理后仍无法显示。
- 修复：
  - `_rewrite_nga_image_src` 对 `/ngabbs/post/smile/` 表情图不再改写为代理，保持直连；
  - 前端若表情图加载失败，不再显示“图片加载失败”大占位，而是降级为 `(表情名)` 文本表情。
- 验证：`tests.test_server` 36 项 OK（表情直连断言更新）。
### 2026-08-20 win：Gululu 标签同步统一 + 最近阅读标签尺寸对齐

- 背景：Gululu 标签仍在封面上，未与 NGA 标签统一；最近阅读标签视觉偏大。
- 修复：
  - `gululuBadge` 改为 `gululu-tag`，与 `nga-tag` 同尺寸同位置；
  - 网格/列表/最近阅读的 Gululu 标签全部移到封面下方/标题区域；
  - 最近阅读新增 `.recent-tags` 容器，标签与书架页一致（9px 小标签）。
- 验证：JS 语法/契约全绿。
### 2026-08-20 win：统一 NGA 标签位置

- 背景：NGA 标签在网格/列表/最近阅读中位置不一致（封面徽标/标题内/正文 meta）。
- 修复：统一为“封面下方/标题区域”的 `.nga-tag` 小标签；最近阅读不再在封面上显示 NGA 徽标，改为标题下方小标签，meta 只保留作者与进度。
- 验证：JS 语法/契约全绿。
### 2026-08-20 win：修复封面同步误清空与 NGA 标签缺失

- 背景：热更新同步封面时，若新 EPUB 提取不到封面会把已有 cover_rel 清空，导致骨碌碌封面消失；同时 NGA 标签从网格封面移除后没有补到别处。
- 修复：
  - `_register_gululu_book` 与 `sync_cover_from_epub` 仅在提取到新封面时更新 cover_rel；提取不到时保留旧封面缓存；
  - 网格卡片在封面下方新增 `NGA` 小标签，避免默认封面文字的同时保留来源标识。
- 验证：JS 语法/契约全绿；gululu/server 相关 53 项测试 OK。
### 2026-08-20 win：骨碌碌封面本地缓存与热更新同步

- 背景：用户要求骨碌碌网络封面本地化缓存，并在每次热更新时同步重载。
- 现状与增强：
  - 导入/更新时 `build_epub(fetch_cover=True)` 会把网络封面写入 EPUB，注册书架时 `extract_cover` 提取到本地 `covers/`；
  - 热更新重建时已通过 `book_register` 重新提取封面；
  - 本次补充：即使热更新判定“已是最新/无新楼层”，也会调用 `sync_cover_from_epub` 从当前 EPUB 重新提取封面并同步 `cover_rel`。
- 验证：`tests.test_gululu_update`/`test_gululu_service`/`test_server` 53 项 OK。
### 2026-08-20 win：前端始终加载 cover_url 强制使用服务端骰子图

- 背景：服务端已返回骰子 SVG，但前端在 cover_rel 为空时不请求 cover_url，导致旧文字占位仍可能可见。
- 修复：前端不再以 cover_rel 判断是否加载封面图；只要 cover_url 存在就加载。缺失封面时服务端返回骰子 SVG，图片会覆盖任何残余文字占位。
- 验证：JS 语法/契约全绿。
### 2026-08-20 win：缺失封面统一回退服务端骰子 SVG

- 背景：即使前端逻辑正确，旧缓存/旧前端仍可能因 cover_url 请求 404 而显示裂图图标和文字。
- 修复：`server.py` 的 `/cover/<id>` 在找不到封面文件时不再返回 404，而是返回一个纯色无文字的骰子 SVG 占位图。
  这样即使前端使用旧逻辑，也会把骰子图作为真实封面加载，覆盖掉任何文字占位。
- 验证：`tests.test_server` 36 项 OK（含缺失封面回退 SVG 用例）。
### 2026-08-20 win：前端缓存强制刷新

- 背景：用户运行 run_app.py 仍看到旧前端，疑似 WebView 缓存。
- 修复：
  - `web/index.html` 所有 css/js 引用增加 `?v=20260820` 版本参数；
  - `server.py` 对 `index.html` 返回 `Cache-Control: no-cache`，避免入口页被长期缓存；
  - 同步更新本地 `dist/AnkeShelf/_internal/web/index.html`。
- 验证：`tests.test_server` 36 项 OK。
### 2026-08-20 win：NGA 与 Gululu 封面回退逻辑隔离

- 背景：reset_cover 对 NGA 书也尝试重新提取封面，导致 KeyError 日志，且用户要求 NGA 完全不参与封面获取。
- 修复：`reset_cover` 对 `nga_tid > 0` 的书直接清空封面返回，不进入重新提取分支；只有非 NGA（Gululu/EPUB）才尝试恢复内嵌封面。
- 验证：相关 Python 61 项测试 OK。
### 2026-08-20 win/android：默认封面回退机制修正

- 背景：用户指出 Gululu“恢复默认封面”后仍显示文本封面和“图片未加载”，并蔓延到 NGA 无封面书。
- 根因：
  - Gululu 无封面时前端仍走文字占位分支，只有 NGA 走骰子；
  - `reset_cover` 只删除封面，不会重新提取 EPUB 内嵌原始封面。
- 修复：
  - 前端所有封面占位（网格/列表/最近阅读/书籍管理）对 NGA 和 Gululu 统一使用骰子占位，不再生成文字；
  - `reset_cover` 删除自定义封面后尝试从书籍重新提取内嵌封面；提取不到则保持无封面，前端回退骰子；
  - NGA 原生书无内嵌封面，重置后自然保持骰子占位。
- 验证：JS 语法/契约全绿；Python 303 项 OK（4 跳）。

> 更早详细流水已归档到 [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)（2026-08-20 二次归档）。

## 5. 待办与延后项

> 2026-08-18 核对：本节已与代码实际状态同步（此前 P1/P2 多项已落地但未更新，
> 属文档漂移）。细节与延后理由见 [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)。

**已完成（不再列入待办）：**

- P0：发行包启动崩溃——项目侧友好提示 + 文档（`b6fba10`）；章节读取失败模型
  `ChapterReadResult`（`867e7ea`）。
- P1：契约/API 漂移守卫（`c8f90cf`）、Android 桥协议版本 + 进度事件回放
  （`ad034b8`，`ProgressModel` 纯函数 + 7 份 fixtures）、依赖锁定与构建可复现
  （`edaf442`，带哈希 lock + APK SHA-256 比对）、首批 ADR（`docs/adr/` 5 份）。
- P2：jsoup 白名单清洗 `sanitizeReaderBody()`（`d697330`）、Android 错误模型
  `RepoResult`/`ChapterReadResult`/`StoreLoadResult` + review3 残余 null 收敛
  （`9e84c4c`）、Android 诊断闭环 `LogEvents`/`Diagnostics` + task_id 全链路
  （`cb40cee`、`b63809f`）。
- P3（已完成部分）：`reader-lite.js` parts 模块化、`SettingsScreen`/
  `DownloadScreen`/`NativeReaderScreen`/前端 `settings.js`/`nga_download.js`
  拆分；Windows 存储恢复（`isolate_corrupt` + `.bak` +
  `verify_data_integrity`）；统一备份包 `ank-backup/1` 双端落地；
  开源治理（CONTRIBUTING/SECURITY/模板/CODEOWNERS/Dependabot/
  THIRD_PARTY_NOTICES/字体去重/乱码修复）。
- P4：参考仓库 5/8 已克隆并产出 `docs/REFERENCE_MATRIX.md`。

**真实待办（按优先级）：**

- P5（当前批次，2026-08-18 用户 issue）：A 快赢、B NGA 裂图修复、
  D 封面系统、E1 Cookie 粘贴解析已完成；剩余/暂缓：
  - E2 双端应用内登录已完成（Android WebView + Windows pywebview 二级窗）
  - C 滚动到底自动翻章（暂不实施，按用户要求；进度类必跑回归）
  - F NGA 楼中楼评论（暂不实施，按用户要求；最大件）
  子项明细见 ROADMAP §3 P5。
- P3：Android 数据完整性校验入口——已随 Android 对齐批 3 落地（`verify_data_integrity`
  等价实现 + 设置页入口），从待办移除。
- P3（保持延后，等真实痛点）：`BookshelfScreen.kt`/`SearchScreen.kt`/
  `reader.js` 等剩余大文件拆分——路线图原则"只在出现第二个真实调用边界时拆"。
- P3（保持延后）：Android NGA 下载迁入统一 `TaskManager`——路线图明确推迟。
- P4（保持延后）：参考仓库剩余 3 个（readest/Kavita/LibraReader）需网络通道；
  第二书源 / SQLite / 同步 / 插件——等第二个真实实现或量化瓶颈触发。

## 6. 纪律提醒（新会话必守）

- 进场先读 AGENTS.md；历史记录查 docs/DEVLOG_ARCHIVE.md，教训查
  docs/LESSONS_LEARNED.md；
- 改动必补记本文件“最近流水”（日期 + 提交 + 现象/结论）；
- 推送代码/发行版必须用户明确授权；双端共享文件改动先做 Diff 影响检查；
- 改动涉及 HEAD/版本线/测试基线/CI 清单时，收尾做文档漂移检查，同步非归档文档到
  实际状态（README 重点核对版本表与系统要求；归档 DEVLOG_ARCHIVE 不改写）；
- 进度类改动必须跑“滚动/翻页 → 退出 → 重进”回归；改 JS 后校验 APK 内脚本；
- 发布前跑凭据扫描（Windows：检查 dist 无 config.ini/nga_config；Android：
  `android/scripts/check-release.ps1`）。
