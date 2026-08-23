# 安科书架（AnkeShelf）· 跨平台开发日志（AnkeShelf_DevLog）

> 用途：现役开发日志——只保留“当前状态”与“最近流水”。
> 历史记录（全量、按时间轴索引）→ [docs/DEVLOG_ARCHIVE.md](docs/DEVLOG_ARCHIVE.md)
> 经验教训（分类归纳）→ [docs/LESSONS_LEARNED.md](docs/LESSONS_LEARNED.md)
> 架构整合路线图 → [docs/ARCHITECTURE_ROADMAP.md](docs/ARCHITECTURE_ROADMAP.md)
> 决策记录（ADR）→ [docs/adr/README.md](docs/adr/README.md)
> 记录纪律：**此后每一次改动、调试、发布都必须在本文件“最近流水”追加记录**
> （日期 + 提交 + 现象/结论）。

## 1. 当前状态（2026-08-22）

- 当前开发基线：`main`；骨碌碌阅读交互改造（悬浮气泡 / 侧边评论 / 段落评论 /
  沉浸总览 / 骰点解锁菜单）已全部合入并发布 v1.5.1；v1.6.0 / android-v1.3.0 与 v1.6.1 / android-v1.3.1、v1.6.2 / android-v1.3.2 已发布；五批接手风险修复已合入；
  P5 批次已启动并完成 P5-A 快赢批、P5-B 裂图修复、P5-D 封面系统、
  P5-E1 Cookie 粘贴解析、P5-E2 双端应用内登录（Android WebView +
  Windows pywebview 二级窗）、NGA 主题自适应
  （含 UI 图标规范核查）；多轮架构收敛已完成：
  EventBus→显式回调、API 错误统一到 HTTP/ApiError、reader-lite 状态机
  Step 0–4、TaskManager 统一 NGA/Gululu/Export；
  文档漂移治理已强化
  （AGENTS §5 高漂移清单 + `scripts/check-doc-drift.ps1`）；
  2026-08-22 防御性编程审查清理批（第二十二批，见 §4）：
  Web 进度/标注写入错误出口、Android store 损坏显式化、
  ApiContext 服务必填、恢复锚点单点化、双端死表面删除；
  性能优化 A1 翻页单次采样（第二十三批）、A2 空白页判定
  提前退出+代际缓存（第二十四批）、内置字体 WOFF2 无损压缩
  -61%（第二十五批，见 §4）；性能专项收尾并发布
  v1.6.1 / android-v1.3.1（第二十六批）、v1.6.2 / android-v1.3.2（第二十八批）；
  安全对齐评估与修复批（第二十九批）、NGA 内嵌图片进度修复
  （第三十批，见 §4）。
  精确提交与远端状态以 `git log` / `git status` 为准。
- 版本线：Windows `v1.6.2`（已发布，AnkeShelf-v1.6.2.zip）；
  Android `android-v1.3.2`（已发布，AnkeShelf-v1.3.2-android.apk）。
- 测试基线（Windows / JS / Android JVM 于 2026-08-22 实跑复核）：
  - Windows Python：`python -m unittest discover tests` = 334 项
    （本机 Python 3.14：1 项环境性错误 `test_main_guard`
    ——tasklist 在本沙箱返回 None，clean main 同样失败、与代码无关；
    bundled Python 3.12：全量通过）；
  - JS：`node contracts/tests/textpos.test.js`（15 例）、
    `node contracts/tests/api-contract.test.js`（60 方法一致）、
    `node contracts/tests/api-contract-launch.test.js`（Python 启动失败诊断）、
    `node contracts/tests/bridge-contract.test.js`（桥版本 1，能力含 annotation·assist·gululu）、
    `node contracts/tests/reader-lite-parts.test.js`（9 parts / 动态字节校验）、
    `node contracts/tests/reader-lite-textpos.test.js`（跨端折叠 12 例）、
    `node tests/js/reader-save.test.js`（进度写入唯一出口）、
    `node tests/js/paged-blank.test.js`（空白页判定边界）、
    `node tests/js/reader-session.test.js`、`node tests/js/nga-cookie.test.js` 均 OK；
  - Android JVM：`gradlew testDebugUnitTest` = 231 项（230 过 / 1 跳）；
    DisciplineTest 11 项在岗；
  - Android 真机：ELE-AL00（Android 10）instrumentation 11 / 11 通过；
  - UI 实机 harness：`python -m tests.ui.runner` = 97 项 PASS
    （2026-08-22 A1 后复跑全绿；需桌面 WebView2）；
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

### 2026-08-23 docs：NGA 附件音频需求调研（进行中 · 草稿 · 待切换 Agent 继续）

> ⚠️ 本条为**进行中草稿**：未完成、未形成结论、无功能代码提交。仅作进度
> 交接存档，勿当作已完成流水。接手 Agent 从「下一步」继续。

- 需求确认（用户）：真实需求主要在**站内附件音频**（作者上传的
  BGM/音效附件），而非外链 [audio]（第三十二批方案 A 已落地在线播放）。
  附件音频在双端管线零处理（Go 原版只处理外链 [audio]），站内附件
  （图床音频 / [attach]）形态未实证。
- 已确认的代码事实：
  1. 附件音频 ≠ 外链：数据形态不同（URL 来源、BBCode 标签、是否需
     Cookie 均待实证）；
  2. **基础设施已就位**：第三十二批已把音乐 cue 播放器做成与书源解耦
     （Android `bindMusicCues` 无条件绑定、桥 `gululuMusic` + MediaPlayer
     同曲再点即停；Windows `bindChapter` 音乐监听提前出 sourceId 门控），
     附件音频可直接复用 cue 形态与播放器；
  3. 原生书 append-only：存量楼层不重写，新内容起生效（新书/热更新
     新增楼层）；
  4. cue 文本进坐标（提取器与 JS TextPos 同源，不漂移）——上轮已定。
- 本轮尝试与阻塞：用本机已配置凭据（load_config + NgaClient 正确加载，
  base_url/cookie/ua 无误）拉 tid 41989465（authorid 62906407）验证
  附件音频形态，NGA API 返回 `code:46 访客不能直接访问`（带/不带
  authorid、page 1/2 均同）——疑似凭据失效或 IP 风控，**附件音频的真实
  形态（是否 img.nga.cn 图床、是否需要 Cookie、BBCode 标签）尚未实证**。
- 下一步（接手 Agent）：
  1. 换 NGA 会话/网络环境重试拉取，或用 web 端手动抓包确认附件音频
     URL 形态与 Cookie 需求；
  2. 确认后写「附件音频」方案调研结论（URL 形态 + Cookie 需求 +
     三态/下载设计 + text_offset 语义）存档 docs/；
  3. 待用户拍板：附件音频走**离线内嵌**（进"内嵌图片"三态管线，
     下载 mp3 进 EPUB）还是**仅在线 cue 播放**（与外链一致）——用户
     倾向离线（"附件音频需求多一些"，离线可确保播放稳定），需确认；
  4. 若立项：复用 NgaImageDownloads/GululuImages 下载框架 + 音乐 cue
     渲染（含 data-textpos-exclude 语义），红测试先行。
- 工作区：干净（无未提交功能改动）；本轮仅本草稿提交。

### 2026-08-23 win/android：NGA 外链音乐支持（方案 A：在线播放，2026-08-23）（第三十二批）

- 背景：NGA 帖子的 [audio]https://…[/audio] 外链音乐在双端 EPUB 渲染链路
  零处理（ngapost2md 的 Markdown 路径有音频能力但阅读器不用 EPUB 外的
  路径），读者看到裸 BBCode 文本。研究确认骨碌碌宿主层音乐播放器已成熟，
  缺的只是"识别 + 接线"，按方案 A（在线播放不拉取文件）实施。
- 转换层（双端同构）：[audio] 仅 https 外链转为骨碌碌同款音乐 cue
  （p.gululu-music-row > button.gululu-music-cue[data-gululu-music-url]，
  kind 文案"外链音乐"，title 显示 URL）；非 https 保留原文（播放桥只收
  https，双端一致降级）。**cue 文本进坐标**（不带 data-textpos-exclude）：
  与骨碌碌一致——提取器与 JS TextPos 同源提取，搜索索引与渲染坐标不漂移
  （研究阶段确认 Kotlin/Python 提取器不识别 exclude 属性，若加 exclude
  反而造成索引错位）。
- 宿主接线：
  - Android reader-lite：音乐 cue/stop 点击分支从 bindGululu 委托移出为
    bindMusicCues()（document 级、init() 无条件调用、幂等）——NGA 书
    （不 initGululu）从此可点播；桥 gululuMusic 与 NativeReaderScreen 的
    MediaPlayer（同曲再点=停止）本就与书源无关，零改动复用。
  - Windows gululu-immersive.js：bindChapter 把音乐 click/keydown 监听
    提前到 sourceId 门控之前绑定（其余背景/视效/自动音乐仍按骨碌碌书源
    门控）；playMusic 的同曲切停/失败提示/https 校验直接复用。
- 回归：tests/test_nga_audio.py 4 项（转换/坐标语义/明文降级/原文不动）；
  NgaFormatHtmlTest 增 3 项同构断言。
- 验证：Windows Python 334 项（+4）；Android JVM 231 项（+3）+
  assembleDebug；bundle 字节校验、bridge/textpos 契约全绿；UI 实机
  harness 97/97（gululu 音乐链路与 NGA 渲染无回归）。
- 语义说明：原生书 append-only——存量楼层保持裸文本（仍可读），新下载/
  新增楼层起为音乐 cue；离线内嵌（方案 B）与站内附件音频（方案 C）未做，
  见研究结论。

### 2026-08-23 android：修复华为 WebView 楼层卡片消失（rgba 分量变量替代 color-mix）（第三十一批）

- 现象（用户报告 + 真机取证）：ELE-AL00（HarmonyOS 4.0.0.121，华为
  WebView 14.0.2.306）上 v1.3.2 的楼层卡片修复未生效——新书（tid
  40811445，v1.3.2 后全新下载）楼层卡片边框/背景仍消失，仅剩蓝色左竖线。
- 取证（插桩 release，sanitizeReaderBody 出口 dump）：20 个 .nga-floor
  div 与 v1.3.2 实色内联（border #e0e0e0 / background #fafafa）**完整
  到达 WebView**——生成端与清洗端正常，丢失在渲染层。
- 根因：华为 WebView 对 color-mix 是"parser 接受语法、求值失败回退
  initial"的非标准行为——reader.css 里 `!important` 的 color-mix 声明
  不被丢弃（不同于标准"声明级错误恢复"），以 initial（transparent/
  none）压掉内联实色兜底 → 卡片消失；同规则内无 color-mix 的
  `border-left: var(--reader-primary)` 正常生效 → 只剩蓝竖线。该行为
  一次性解释 v1.3.1（内联同为 color-mix）与 v1.3.2（实色内联被压）
  两代现象。
- 修复：reader.css 全部 10 处代码声明
  `color-mix(in srgb, var(--X) N%, transparent)` → `rgba(var(--X-rgb), N/100)`
  （语义等价、2013 年级兼容）；`--reader-fg-rgb` / `--reader-primary-rgb`
  分量变量成对维护（ReaderHtml.kt 初始注入 + reader-lite applyTheme
  动态更新，非法 hex 回退默认分量）。视觉差异 ≤2/255（color-mix 与
  transparent 混合会把 RGB 通道拉向黑，rgba 反而更贴合"fg 的 N% 透明度"
  的设计意图）；性能持平或略优。
- 回归：ReaderHtmlTest 新增 rgb 变量注入断言与 hexToRgbComponents
  边界（#RRGGBB/#RGB/非法）；DisciplineTest 新增守卫——reader.css
  不得再出现 color-mix token（华为行为不可探测，结构性禁止回归）。
- 验证：Android JVM 228 项（227 过/1 跳）+ assembleDebug；bundle 字节
  校验与三套 JS 契约全绿；**真机复验**（装机后截图像素级分析）：
  深色主题下卡底 #2d2d2d 占阅读区 56.3%（理论值 34×0.94+224×0.06≈46
  吻合）、卡片缝隙 #222222、4px #77bbee 左竖线与边界梯度完整——
  卡片结构全部在位。APK 实检：reader.css/reader-lite.js 均为修复版。
- 插桩说明：取证用的 [floordbg] 一次性日志已随修复分支丢弃，未合入。

### 2026-08-22 android：修复 NGA 内嵌图片下载无进度且可无限卡住（第三十批）

- 现象（用户报告）：内嵌图片模式下载时看不到图片下载进度，最终卡住。
- 根因（NGA 链路三叠加）：`NgaDownloader.downloadImages` ① 串行逐张下载；
  ② 零进度上报——楼层循环最后一次 progress 后直接进入图片下载，
  前台通知与下载页停在楼层阶段，表现为"无进度"；③ `imageHttp` 用无参
  `OkHttpClient()`（无超时），慢速滴流连接可无限拖住任务（readTimeout
  只约束单次 socket read，不约束整图耗时）——表现为"卡住"。
  通知端与 UI 端本就支持任意 stage（`else -> detail` + current/total
  进度条），缺口全在 downloader 侧。
- 修复：
  - 新增 `service/NgaImageDownloads.kt`：6 路并发（对齐 GululuImages）、
    **按完成序**上报进度（先完成的先报，不被最慢一张阻塞显示）、缓存
    图跳过下载但计入进度起点（先报 "2/3" 再 "3/3"）、单图失败不中断
    （计 failed + LogEvents 诊断）、收结果循环每次检查取消、
    perResultTimeoutSec=90 兜底——超时窗口内无任何一张完成即判定整体
    卡死，终止并显式失败（不再无限等）；
  - `downloadImages` 接入 drain：`progress("images", done, total, detail)`
    逐张上报，超时抛 `NgaHttpException`（service 层既有 catch → error
    状态，提示重试或改在线模式）；
  - `imageHttp` 补 connect 15s / read 30s（对齐 GululuImages）；
  - `DownloadPanels` 任务状态文案补 "images" 分支（通知端 else 分支
    已覆盖）。
- 回归：`NgaImageDownloadsTest` 4 项（红→绿）：逐张进度序列含阶段切换
  起点、缓存图跳过且计入进度、取消即停不再派发、挂死连接按超时终止
  （实测不等待）。
- 验证：Android JVM 226 项（225 过/1 跳，+4）+ assembleDebug。
- 注：骨碌碌链路（GululuImages）本有逐张上报与 15/30s 超时，不在本次
  范围；其 `future.get()` 无整体上限属次要残留（单图 socket 级超时仍在），
  未顺手改动。

### 2026-08-22 win/android：安全对齐评估与修复批（第二十九批）

背景：按"声明 ↔ 实现"对照做了一次安全对齐评估（结论：总体良好，
1 个真实实现缺陷 + 1 处声明漂移 + 若干低危残留），本批落地全部可项。

- **修复（android）图床白名单子串匹配缺陷（中危）**：阅读器拦截器原用
  `url.contains("img.nga.cn")` 等子串匹配，恶意 EPUB 可借
  `https://evil.com/img.nga.cn/x.gif` 命中并经 ngaHeaders（携带 NGA
  uid/cid Cookie）把凭据发往任意主机。新增 `ui/reader/ReaderEgress.kt`
  与桌面 `_is_nga_image_url` 同构的主机名精确后缀匹配
  （`host == s` 或 `host.endsWith(".$s")`），ReaderEgressTest 锁定
  子串伪造 / 后缀仿冒 / 大小写 / 畸形 URL 边界。
- **出口门禁（android）**：明文 http 子资源一律拒绝（404 空响应）——
  应用全 https，此前章节内容热链可经 mixed-content 加载明文图片，
  与归档审查"无明文流量"结论相悖；https 外链图片保持放行（与桌面
  CSP `img-src https:` 同策略；骨碌碌在线图片本就是任意 https 图床，
  不做主机枚举白名单）。评估中原设想的"拒绝式全白名单"因此调整为
  明文门禁——凭据外带通道已由主机名修复关闭，全白名单只剩反跟踪
  价值却会破坏内容兼容。
- **API 分发收口（win）**：server.py 原 `getattr(self.api, name, None)`
  分发会把对象任意公共成员（`fullscreen` property、`register` 方法等）
  暴露成 /api 端点；改为 `ApiRegistry.handler(name)` 只认注册清单
  （api_manifest 即完整暴露面），`__getattr__` 仅供进程内直调保留。
  红测试先行（非 handler 属性/方法经 /api 必须 404）。
- **声明对齐（docs）**：README Android 安全条目移除未实现的
  "CSP script-src 'self'"（2026-08-08 已实测 file:// 下 'self' 拦 asset
  而放弃），改为实际防线（jsoup 白名单 + 资源拦截 + 出口门禁）；
  SECURITY.md 同步（含令牌校验范围为 /api 路由的说明、CSP 不采用的
  原因与重评估条件）。
- **依赖审计基线（ci）**：nightly 接入 pip-audit（advisory，
  continue-on-error，不阻塞）；仓库 Dependabot vulnerability alerts
  已开启（API 确认 204）。
- **CSP 路线 B（原型方案，待真机验证后决定落地）**：reader 壳已确认
  零内联脚本（唯一 `<script>` 为外部 src，ReaderHtml.kt:205），拟用
  显式 source 规避当年 'self' 失败：
  `default-src 'none'; script-src file:; style-src 'unsafe-inline' file:; img-src file: data: https:; font-src file:`
  （不给 unsafe-inline → 注入的内联脚本在浏览器层失效；style 内联
  变量为首帧需要，与桌面同策略）。未验证点 = WebView 对 `file:`
  scheme-source 的匹配行为，须真机确认 reader.css/reader-lite.js/
  字体不被拦（15 分钟：加 meta → assembleDebug → 装真机开书看排版
  与 [state] ready 日志）。真机不便，本批未盲合。
- 验证：Android JVM 222 项（221 过/1 跳，+ReaderEgressTest 5 项）+
  assembleDebug；Windows Python 330 项（329 过 + 既有环境项，+分发
  收口 2 项）；api-contract 60 方法一致。

### 2026-08-22 release：v1.6.2 / android-v1.3.2 发布（第二十八批）

- 版本号：Windows `app/__init__.py` → 1.6.2；Android
  `build.gradle.kts` → versionCode 6 / versionName 1.3.2。
- 发布原因：NGA 楼层卡片在旧 WebView（Android 10 实机）丢灰边/卡底；
  Windows 端卡底使用 `--reader-bg` 导致不可见；双端修复后发布补丁版。
- 构建：`AnkeShelf-v1.6.2.zip`（约 42.7MB，sha256 已生成）、
  `AnkeShelf-v1.3.2-android.apk`（约 12.7MB，sha256 已生成）。
- Release：GitHub Releases `v1.6.2` / `android-v1.3.2` 已创建；标签已推送。
- 全量文档漂移扫描后同步：README / CHANGELOG / VERSIONING / SECURITY /
  MAINTENANCE_GUIDE / ROADMAP / ANDROID_ARCHITECTURE / 使用说明.txt。

### 2026-08-22 win：同步检查并修复 NGA 楼层卡片背景与内联兜底（第二十七批续）

- Windows 端 `_css` 的 `.nga-floor` 背景原先写的是
  `color-mix(in srgb, var(--reader-bg) 55%, transparent)`，与页面背景同色，
  视觉上等于没有卡底；改为 `var(--reader-fg) 6%`，与 Android / 引用块一致。
- `app/native_book.py` 楼层内联样式补上 `background` 实色兜底
  （`floor_bg`：浅 #fafafa / 深 #2a2a2a），并对旧主题 dict 无 `floor_bg`
  时回退 `comment_bg`/`quote_bg`。
- `ngapost2md-python/format_html.py` 的 NGA_THEME_LIGHT/DARK 增加
  `floor_bg` 键，保持 Android `NgaFormatHtml` 与桌面同源一致。
- 验证：`python -m unittest tests.test_native_book` OK；`py_compile` OK。

### 2026-08-22 android：修复旧 WebView 上 NGA 楼层卡片边框/背景丢失（第二十七批）

- 真机对比 v1.0.0 与 v1.3.1 截图：v1.3.1 楼层卡片只剩左侧主题色竖线，
  1px 边框与卡片背景丢失；v1.0.0 有完整灰边（#3a3a3a）与浅灰卡底（#2a2a2a）。
- 根因：前几批把 `NativeBook` / `NgaFormatHtml` 的内联样式改为
  `color-mix(...)`；Android 10 WebView 不支持 `color-mix`，整条声明被丢弃，
  而 `var(--reader-primary)` 仍生效，所以只剩蓝色左线。
- 修复：内联样式恢复为下载时浅/深主题实色（旧 WebView 兜底），
  `reader.css` 新增 `.nga-floor` 的 `!important + color-mix` 自适应规则
  （现代 WebView 覆盖为跟随阅读器主题）。
- 验证：Android 全量单测 BUILD SUCCESSFUL；待装机复核楼层卡片边框/背景。

### 2026-08-22 docs：仓库扫描与宣传帖模板版本修正（第二十六批补记）

- 应要求检查扫描仓库改动：工作区干净、本地与 origin/main 同步、无
  未推送提交、无打开 PR；`git diff --check` 无空白错误。
- 运行 `scripts/check-doc-drift.ps1` 抽查文档漂移，发现并修复
  `nga-post-template.bbcode` 中“当前版本”小节仍为 v1.6.0 / android-v1.3.0，
  与标题和安装使用小节的 v1.6.1 / android-v1.3.1 不一致；已改为 v1.6.1 /
  android-v1.3.1 并补充性能专项说明（采样减半 / 空白页缓存 / 字体 WOFF2）。
- 复核 README / CHANGELOG / DevLog §1 / MAINTENANCE_GUIDE §1 / ROADMAP §2.1
  版本与测试基线：均已为 v1.6.1 / android-v1.3.1；CI 全绿。

### 2026-08-22 release：性能专项收尾，v1.6.1 / android-v1.3.1 发布（第二十六批）

- 性能优化专项结束（A1 翻页单次采样、A2 空白页判定、字体 WOFF2 三项
  全部落地；A3 二分去滚动位移经评估暂缓，可行性研究结论已记录）。
- 版本号：Windows `app/__init__.py` → 1.6.1；Android
  `build.gradle.kts` → versionCode 5 / versionName 1.3.1；
  README 版本表与下载链接、CHANGELOG 双端条目、android/VERSIONING.md
  版本线同步。
- 修复 check-release.ps1 凭据扫描的字体必需项（TTF→WOFF2 误报 FAIL），
  发布扫描恢复 PASS。
- 构建与发布：`v1.6.1`（AnkeShelf-v1.6.1.zip 42.1MB + release.txt +
  sha256；对比 v1.6.0 的 46.9MB 减小 4.8MB）、`android-v1.3.1`
  （AnkeShelf-v1.3.1-android.apk 12.7MB + release.txt + sha256）；
  Release APK 过 check-release 凭据扫描与字体/reader-lite 字节实检
  （APK 内仅 woff2、A1/A2 守卫标记在位）。
- 标签 `v1.6.1` 与 `android-v1.3.1` 指向发布提交，GitHub Release 资产
  名纯 ASCII。
- 全量文档漂移检查（AGENTS §5 清单逐项 + check-doc-drift.ps1）并修复：
  使用说明.txt 版本头 v1.5.1→v1.6.1（重建 zip 资产覆盖上传）与字体
  扩展名补 woff/woff2；MAINTENANCE_GUIDE §1 版本表 / §10 当前状态
  刷新至 v1.6.1/android-v1.3.1；ROADMAP 顶部核对块（8-20/v1.5.1→
  8-22/v1.6.1）、§1 当前优先级（A3/字体子集化待拍板）、§2.2 reader.js
  行数、§2.3 架构债前两行证据收敛；ANDROID_ARCHITECTURE 版本表补
  v1.3.0/v1.3.1 两行（v1.3.0 行为既有缺失）；SECURITY.md 支持表更新；
  AGENTS §5 清单修正 DevLog §5 的失效引用（待办以 ROADMAP 为准）。

### 2026-08-22 win/android：性能优化第三项——内置字体 TTF→WOFF2 无损压缩（第二十五批）

背景：性能可行性研究定位的进书成本项。内置 LXGW WenKai 全量 TTF 26.0MB，
Android 每次开书 @font-face 加载且 settle 链等 fonts.load，Windows 经
/font/system/ 提供；按决策走 WOFF2 无损（不做子集化，生僻字无视觉权衡）。

- `assets/fonts/LXGWWenKai-Regular.ttf` → `.woff2`（fonttools 转换，
  **26,037,854 → 10,186,308 字节，-61%**，APK 32.3MB 对应缩小）；
- 无损验证（逐字形语义比对，非仅字节）：47,813 个字形轮廓坐标/组件
  全部一致；cmap/hmtx/name 等其余表字节级相同；head 仅差 WOFF2 规范
  要求置位的 lossless 标记位（bit 11）与重算的校验和/时间戳；
- Android：reader-lite @font-face 改 `format("woff2")`；铁律实检 APK
  （assets/fonts 仅 woff2、reader-lite.js 引用已更新、Gradle 无 UP-TO-DATE 误判）；
- Windows：逻辑名与设置默认值 ttf→woff2；**旧设置 sys:weidqczfkyxk.ttf
  经别名继续解析**（老用户升级不丢字体，含回归测试）；pyinstaller spec
  与 server MIME（font/woff2 已有）确认；
- 验证：Python 328 项（327→328，+1 别名测试）；Android JVM 217 项 +
  assembleDebug；UI 实机 harness 97/97；parts 字节校验、契约全绿。
- 剩余（可行性研究顺序）：A3 Windows 分页二分去滚动位移（中风险，
  需完整进度回归）。

### 2026-08-22 win/android：性能优化 A2——空白页判定提前退出 + 布局代际缓存（第二十四批）

背景：A1 之后的第二优化项。skipToContent 每次翻页先对目标页跑
isPageBlank——固定扫满 15（单页）/20（双页）个 caretRangeFromPoint 命中
测试，文本页也要付全价；空白段连续时最多 ×5 页 = 75 次命中测试。

- 判定数学提取为纯函数（nonBlankThreshold / isBlankVerdict）：命中数达到
  ceil(25% × 总采样) 后无论剩余采样如何占比都不可能 <25%，可提前判定
  非空白；顶行命中直接非空白。与整扫判据数学等价，文本页通常前 1~2 行
  采样（3~8 次命中测试）即可判定。
- isPageBlank 增加布局代际缓存：同一布局内页面空白性不变，重访页与空白段
  跳过零成本；prepare()（双端字号/窗口/双页/图片重排的统一入口）推进
  layoutGen 作废缓存。
- 双端同构：桌面 paged.js 与 reader-lite 30-paging 同判据同实现；边界由
  tests/js/paged-blank.test.js 统一锁定（3/15 空白、4/15 非空白、5/20
  恰好达标、顶行规则），Android 侧 DisciplineTest 新增 A2 结构守卫
  （Android JVM 216→217 项）。
- 验证：Windows UI 实机 harness 97/97 PASS（含分页/末页/双页全套）；
  Android JVM 217 项（216 过/1 跳）；JS 契约全绿；parts 字节校验通过。
- 后续（按可行性研究顺序）：字体决策（26MB 全量字库子集 vs WOFF2，
  需拍板生僻字回退系统字体的视觉权衡）→ A3 二分去滚动位移。

### 2026-08-22 win/android：性能优化 A1——翻页/定位/滚动 offset 单次采样（第二十三批）

背景：性能可行性研究（数据层基线毫秒级、瓶颈在渲染交互层）确定的第一
优化项。双端翻页路径此前都把页顶 offset 采样跑两遍：Windows 是
`Paged.currentOffset()` 整套「scrollLeft 归零 + log₂(章长) 次 Range
getBoundingClientRect 二分 + 恢复」执行两次（saveProgress 与
updateProgressUI 各一次）；Android 是 `report()` 内部与 `flipPage` 各采样
一次，且 doSave=false 的纯 UI 上报（setMode/requestSettle/scheduleResize/
init finish）每次也白付一次页顶扫描（off 在这些路径从未被使用）。

- Windows（`win/page-turn-single-sampling`）：saveProgress 返回本次采样
  offset，updateProgressUI(sampledOffset) 接受复用值；onPageTurned /
  seekToOffset / 锚点跳转 / 滚动 500ms 防抖四个调用点接通；换章/重排等
  无现成采样的路径保持按需采样，行为不变。
- Android（`android/page-turn-single-sampling`）：report(doSave, offset)
  接受复用；flipPage 单次采样同时供落盘与翻页锚点；doSave=false 路径
  不再采样。
- 守卫：reader-save.test.js 增加单次采样结构断言；DisciplineTest 新增
  采样纪律测试（Android JVM 215→216 项）。
- 顺手修复（与 A1 无关的既有漂移）：UI harness `settings_tabs` 断言硬编码
  ===6，自 6d8d3df 加入「书籍管理」Tab（第 7 个）起即 FAIL；改为与
  `SettingsUI.TABS` 定义联动（`win/ui-harness-tabs-follow-definition`）。
- 验证：Windows UI 实机 harness 97/97 PASS（含分页翻转/进度/双页/
  快速连翻/全屏保位全套）；Android JVM 216 项（215 过/1 跳）；JS 契约
  （textpos/api/bridge/parts/跨端折叠/reader-save）全绿；Python 327 项
  （仅既有环境性 main_guard）。
- 后续（按可行性研究顺序）：A2 空白页缓存（per-layout 缓存
  isPageBlank 结果，砍掉每次翻页 15 次命中测试）→ 字体决策（26MB 子集
  vs WOFF2，需拍板）→ A3 二分去滚动位移。

### 2026-08-22 docs：README 顶部增加徽章行

- Logo 下方新增居中徽章行：Windows / Android / Contracts 三条 CI 状态
  （GitHub 原生 workflow badge，链接到对应 workflow 页）、最新 Release、
  平台（Windows | Android）、AGPL-3.0 许可证（链接 LICENSE）、Stars。
- 全部采用动态徽章（shields.io / GitHub Actions），无静态版本号，
  不新增文档漂移面；双端版本线仍以 README 版本表为唯一事实源。
- 7 个徽章 URL 均已逐一验证（HTTP 200）。

### 2026-08-22 win/android：防御性编程审查清理批（第二十二批）

背景：按“设计前提退化成运行时补丁 / 名义安全分支 / 投机抽象 / 碎片化控制流”
四类反模式对双端代码做全面审查后，按优先级清理五路。每路均先写复现测试
（红）再修复（绿），单路分支验证后合并 main。

- **① Web 进度/标注写入错误出口**（`win/progress-annotation-error-exits`）：
  Bridge 失败必 throw，但 reader.js 三处裸 `Api.saveProgress`（防抖/换章/
  锚点跳转）无任何 catch——后端不可用时进度静默丢失且产生 unhandled
  rejection。新增 `web/js/reader-save.js`（ProgressSaver：唯一写入出口，
  失败 console.error + toast 一次、恢复成功后重置）；annotations.js 全部
  写入路径补 try/catch toast 并删除两个永假的 `r.error` 死分支；
  新增 `tests/js/reader-save.test.js`（结构守卫：reader.js 不得直写进度）。
- **② Android store 损坏显式化**（`android/store-corrupt-visibility`）：
  六个权威 store 把 Corrupt/IoError 一律折叠成空默认且仅 logcat 留痕；
  shelf.json 损坏即书架清空、随后空数据覆盖权威文件。新增 `loadGuarded`
  统一入口：Corrupt（已隔离 .corrupt-*）报告 issue 且允许重建；IoError
  （原文件仍在原位）挂起写保护，ProgressStore.flush 显式失败
  （StoreWriteProtectedException），恢复成功后解除；AppContainer 汇总
  `storeLoadIssues`，书架页横幅用户可见；回归测试 StoreProtectionTest 5 项。
- **③ ApiContext 服务必填化**（`win/apicontext-required`）：
  annotations/stats/nga/export/gululu/frontend_ready/window_toggle/nga_login
  全部 Optional + 31 处守卫只为测试部分构造而存在，且读接口把“服务未接线”
  折叠成 idle/空数据、写接口却 503（语义分裂）。必填化后缺接线在构造处
  TypeError 暴露；仅 file_dialog 保留可选（None→系统对话框是真实缺省）；
  toggle_fullscreen 改测真实失败模式（窗口未就绪 RuntimeError→503）；
  9 个 Api 构造点全部补齐装配。
- **④ 恢复锚点单点化**（`android/restore-anchor-single-point`）：
  restoreOffset/Page/Total/Ratio 四个平行 remember 各自维护同一策略，
  restoreRatio 漏接 crossJump——跨章跳转（标注抽屉/书签）回到初始章时
  旧滚动比例优先于跳转锚点（reader-lite 对 ratio∈[0,1] 优先于 text_offset），
  跳转被拉回旧位置。收敛为纯函数 `restoreAnchorFor` + `RestoreAnchor`
  （crossJump 命中时 page/total/ratio 全部让位），RestoreAnchorTest 4 项；
  reader-lite 文本锚点定位失败的静默 catch 补 `[restore:fallback-ratio]`
  诊断日志。
- **⑤ 双端死表面删除**（`win/remove-dead-surfaces` +
  `android/remove-dead-surfaces`）：
  Web 端删除 ReaderSession 零消费者成员（dirty/lastSaved/markSaved/
  isDirty/elapsedSeconds/mode）与 gululu-comments 已下线 inline 评论模式
  全套（约 120 行不可达代码 + 注入样式）；Android 端删除 reader-lite 8 个
  无宿主调用的导出、逐字节相同的双生函数 offsetAtPointPaged/Scroll、
  两臂相同三目、parseJsonSafe 误标 [ann]、只写不读的 state.pageWidth /
  contentWidth()、PagedLayout 零引用常量。
- **有意保留（守卫保护的决策，未在清理批内单方面拆除）**：phase 三态
  状态机（Step 3 显式决策“phase 取代 settled”，DisciplineTest 断言禁止
  settled 复活）与 BRIDGE_CAPABILITIES 桥能力声明（bridge-contract 与
  DisciplineTest 锁定）。审查发现 phase 的 bootstrapping/restoring 两个
  状态值目前无读取者、capabilities 解析后仅进就绪日志——若未来要简化，
  属于修订守卫的显式决策而非顺手清理。
- 验证：Windows Python 327 项（仅既有环境性 main_guard 错误）；Android
  JVM 215 项（214 过/1 跳）；JS 契约（textpos 15 / api 60 方法 /
  bridge / parts 动态字节 / 跨端折叠 12 / reader-save / reader-session /
  nga-cookie）全绿；reader-lite parts 重建并过 bundle 校验。

### 2026-08-21 release：v1.6.0 / android-v1.3.0 发布与 PR/CI 收尾（第二十一批）

- 版本号全面更新：Windows `app/__init__.py` → 1.6.0；Android
  `build.gradle.kts` → versionCode 4 / versionName 1.3.0；README/CHANGELOG/
  VERSIONING 同步。
- README 截图全部换新：18 张新截图经 Pillow 压缩为 12 张 WebP
  （`docs/screenshots/` 4.9MB → 0.8MB），旧截图全部移除。
- 构建并发布双端 Release：`v1.6.0`（AnkeShelf-v1.6.0.zip + sha256）、
  `android-v1.3.0`（AnkeShelf-v1.3.0-android.apk）；标签最终指向修复后的 main。
- 新增 `nga-post-template.bbcode` 宣传帖模板（bbs code，版本与截图占位符）。
- 修复 Windows CI：`gululu_start_import` 新增 `clear_cache` 后测试 mock 未同步，
  已在 `tests/test_gululu_service.py` 适配并通过。
- 7 个 Dependabot PR 处理完毕：合并 5 个通过项（gradle-wrapper 9.7.0 /
  espresso-core 3.7.0 / coil 3.5.0 / pyinstaller 6.22.2 / cryptography 50.0.0），
  关闭 2 个失败项（core-ktx 1.19.0、okhttp 5.5.0，需更高 compileSdk）。
- 验证：main 与两个 tag 的 Windows / Android / Contracts CI 全绿；
  Python AST 契约与 Android JVM 全量单测通过。

### 2026-08-21 android：骰子遮罩位置与悬浮栏灵敏度（第十九批）

- 骰子遮罩去掉灰色胶囊，改为仅透明文字（保持原始行内盒位置，数值隐藏且
  元素可点）；
- 悬浮栏唤出灵敏度下调：点击位移阈值 50px → 24px；快速滚动结束 400ms 内
  的轻点不再唤出悬浮栏。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：骰子点击与进书卡顿修复（第十八批）

- 骰子仍点不开：原因是遮罩元素 `font-size:0` 时用 `min-width:0.9em` 会计算为 0，
  元素实际尺寸为零，无法命中点击。改为 `min-width:14px; min-height:16px`。
- 进书卡顿：章节 HTML 组装（Jsoup 清洗 + 纯文本提取 + 构建 HTML）移到
  `Dispatchers.Default` 后台线程，主线程先显示 Loading 转圈，避免从书架
  进入阅读器时大章节阻塞主线程。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：骰子遮罩可点击修复（第十七批）

- 修复：上一版用 `visibility:hidden` 隐藏骰点数值，但 visibility:hidden 的
  元素不接收点击，导致“隐藏后点不开”。改为可点击的灰色胶囊遮罩：
  透明文字 + 灰色胶囊背景 + 保持元素可点，揭示时清除全部内联样式。
- 验证：`compileDebugKotlin testDebugUnitTest assembleRelease` BUILD SUCCESSFUL；
  `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：骰子默认全解锁根因修复（第十六批）

- 根因：reader-lite 主 IIFE 在 50-api.js 末尾就关闭了，60-gululu.js 被拼在
  IIFE 之外，`parseJsonSafe` / `state` 均不可见，`initGululu` 一执行就抛
  ReferenceError，骰点遮罩从未生效（宿主持久化 count=0 正常）。
- 修复：把主 IIFE 关闭移到 60-gululu.js 末尾，使 Gululu 部分回到同一闭包内。
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL；
  `assembleRelease` 成功并 `adb install -r` 到测试机（保留数据）。
### 2026-08-21 android：CI 修复（第十五批）

- 修复：骨碌碌悬浮按钮底部偏移 96dp 魔法值改为 `AnkeSpacing.xxl * 3`，
  通过 DisciplineTest 间距令牌守卫。
- 验证：`compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL。
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
