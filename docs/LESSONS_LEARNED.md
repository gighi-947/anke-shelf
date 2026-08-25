# AnkeShelf 经验教训（Lessons Learned）

> 文档日期：2026-08-12
> 来源：`AnkeShelf_DevLog` 全量归档（[docs/DEVLOG_ARCHIVE.md](DEVLOG_ARCHIVE.md)）+
> 四份架构评审文档 + 本轮代码调研。
>
> 使用方式：新任务开工前查相关分类；出现同类症状先看对应规则，再走
> “复现 → 最小化 → 假设 → 插桩验证 → 修复 + 回归”五步循环。

## 1. 调试方法论

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| “能写入”≠“写对”：progress.json 有内容但写的是旧锚点 | 验证必须对照“用户实际位置”，不能只看文件有数据 | 9.53 |
| 单测 + 构建通过 ≠ 端到端正确（Compose `remember(record)` 用对象身份做 key） | 关键链路必须跑端到端闭环：滚动/翻页 → 退出 → 重进 | 9.51 / 9.53 |
| 靠推理猜不如探针 | 复现 → 最小化 → 假设 → 插桩/日志验证 → 修复 + 回归，缺一不可 | 9.45 / 9.49 / 9.53 |
| 异步完成事件会“残留旧状态”被当成新事件 | 完成/迟到事件都要有版本、时序或来源校验 | 4.2-10 / 9.43 |
| 改完再补测试是假验证 | 先写能复现的失败测试（红），再修复（绿），保留为回归 | 9.66 / AGENTS |
| 诊断日志要可溯源 | 保存/写入日志带来源标签（flip/scroll/switch/dispose/init/settle），发行前保留 | 9.48 / 9.53 |

## 2. 阅读进度保持（9.43–9.59 十轮血泪，最高优先级）

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| 进度状态横跨 JS 渲染层与 Kotlin 持久层，异步边界多 | 进度持久化只有唯一写入口（`ChapterProgressTracker`），JS 只上报 | 9.43 |
| 分页/滚动共用一个锚点字段，改一边坏一边 | 模式隔离：锚点、采样、恢复、保存各自独立；分页显式 `ratio=-1`，滚动显式 `page=-1` | 9.50 / 9.58 |
| 滚动采样用文档坐标会越界 | 采样用视口坐标：滚动=视口中线 45%，分页=页顶；恢复锚点与采样点严格对应 | 9.45 / 9.48 |
| 图片页 `caretRangeFromPoint` 返回邻近文本 | 采样命中 img/video/audio/svg/canvas/picture 必须跳过，向下找第一个文本行 | 9.49 |
| 全图页只有文本锚点会错位 | 滚动比例（0..1）作为滚动模式专属一等锚点；分页永不读写 | 9.58 |
| 恢复/重排事件也会写盘，污染进度 | 保存只发生在用户动作；恢复/重排/init 只更新 UI | 9.45 |
| 字体/图片未稳定时定位会闪回章首 | 布局稳定门（字体 + 图片就绪）后再做最终定位，8 秒兜底 | 9.45 / 9.47 |
| 退出时迟到事件覆盖刚 flush 的正确值 | dispose 延迟 destroy、tracker 延迟 close、移除 pagehide 兜底、`userMoved` 守卫 | 9.44 / 9.48 / 9.54 |
| 换章时旧章 offset 被异步丢/被新章覆盖 | 换章前先取旧章精确 offset 并落盘；事件上报携带 chapterIndex，旧章不丢弃 | 9.43 / 9.46 |
| 会话内跳章再回来回章首/跳章中间 | 打开书/搜索跳转才恢复 offset；会话内换章一律章首（offset=0） | 9.46 |
| Compose 缓存 key 用对象身份永不刷新 | 进度类缓存只用会变化的键（refresh/数据 id） | 9.51 |
| WebView 方法在后台线程调用必崩 | `evaluateJavascript` 必须 post 主线程；`CountDownLatch` 在后台等 | 9.44 |
| 进度“看起来恢复”但没回归保障 | 每次阅读器改动必跑：滚动/翻页 → 退出 → 重进，连续 3 次一致；滚动↔分页交叉切换 | 9.52 / 9.53 |

## 3. 渲染与排版

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| 仓库里曾同时存在三套阅读器实现，只有一条在跑 | 保持一条主线：Compose 外壳 + WebView 内核 + 现役 `reader-lite.js`；死代码归档删除 | 9.57 |
| Kotlin/JS 分页几何公式漂移，测试保护的是退役 JS | 几何公式统一对称 `P=min(margin, gap-8)`；跨端对照测试必须加载现役 JS | 9.57 |
| 横屏双页超界、拼接错乱 | 分页几何对齐 flow/epub.js：border-box、精确列宽、双页补偶数列；长表格页内滚动 | 4.3 / 9.45 |
| 深浅色切换“黑底黑字”、误转彩色字 | 颜色铁律：只接管默认黑/白文字，带显式颜色的字体一律保留原色 | 4.3 / 6.6 |
| 预加载前后章引入状态问题 | 白屏应从“先渲染骨架/保持上一帧”解决，预加载方案谨慎评估后已回退 | 4.3-17 |
| 26MB 内置字体阻塞 WebView 首屏 | 动态注入 `@font-face` + `fonts.load`，先系统字体渲染，就绪后重排保位 | M2 遗留已解决 |
| 文件注释编码损坏（reader.js 8 处 `?`） | 发现乱码立即修复，避免误导后续维护 | 本轮调研 |

## 4. 跨端契约

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| Python/JS/Kotlin 三端文本折叠语义漂移 | `text_offset` 统一为 UTF-16 code unit；空白折叠、实体表、CDATA 语义三端一致 | 10.3 / 10.6 |
| 注释分隔的文本节点三端行为不同 | 两个文本节点之间只有注释时不插空格（DOM 实证） | 10.5 |
| 契约只靠文档容易漂移 | golden fixtures + Node/Python/Kotlin/UI harness 对照；`contracts/` 机器可验证 | 10.4 |
| API 双清单人工同步会漏（MOCKS 已缺 2 个方法） | 后端 `_HANDLERS` ↔ 前端 `METHODS` 自动对照；新 API 必须同时提交 handler、客户端、测试 | 10.7 / 10.8 / 本轮 |
| Gradle 误判 UP-TO-DATE，旧 JS 入包 | 改 JS 后必须解包校验 APK 内 `assets/reader/reader-lite.js` 内容（计划中改为 SHA-256 自动校验） | 9.57 / 9.59 |

## 5. 打包与发布

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| PyInstaller onefile 冻结 pythonnet 不稳定 | 用 onedir 目录版；pythonnet/clr_loader 文件全量打包 | spec 注释 |
| 用户机器报 `Failed to resolve Python.Runtime.Loader.Initialize` | 先查 .NET Framework ≥4.7.2（建议 4.8）与下载文件是否被“解除锁定”（MOTW） | 本轮 P0 / pythonnet#2459 / PyInstaller#7412 |
| 中文资产名经 PowerShell→gh 被破坏成 `-.zip` | 资产名纯 ASCII + 版本号；上传用 `curl`/`Invoke-RestMethod` 直连 `uploads.github.com` | 4.5-26 / 5.3 |
| 发行版多次残留个人 NGA 凭据 | `config.ini` 只打包 `.example`；压包前扫 dist，发布前凭据扫描是固定步骤 | 4.4-25 / 9.61 |
| Android release 仪器测试触发 R8 三连坑 | 真机测 UI 用“debug 挂 release 签名 + apksigner 重签测试 APK”的临时方案；`ui-test.manifest` 必须 `debugImplementation` | 9.67 |
| gh token 缺 workflow scope、网络抖动 | `gh auth refresh -s workflow`；GitHub 直连间歇断连时等待约 1 分钟重试，不急着上代理 | 9.62 / 9.63 |
| 版本号散落多处容易漏改 | 升级版本时按清单全量替换（`app/__init__.py`、bridge mock、测试、使用说明、README、ngapost2md） | 5.3 / 7 |

## 6. 安全

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| localhost 服务也会被跨站调用 | 仅回环监听 + 随机令牌（URL query + 请求头双携带）+ API body 限制 | server.py / 10.x |
| 路径穿越攻击面多（含双重编码） | unquote 后再校验；拒绝反斜杠/`..`/绝对路径；只命中 zip 条目名集合 | server.py / 10.13 |
| ZIP 炸弹与超大解压量 | EPUB 加 max_entries/max_total_bytes 可配置上限，超限拒绝 | 10.13 |
| Android 正则清洗对畸形 HTML 有遗漏 | 不可信输入用 jsoup DOM allowlist（项目已有依赖）；正则仅作过渡 | docs/archive/ANDROID_SECURITY_REVIEW / 本轮 P2 |
| 凭据、正文、签名不能进诊断包 | 诊断包只含版本/脱敏设置/日志；发布包与诊断包都做凭据扫描 | 10.11 / 9.61 |

## 7. 网络与环境

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| GitHub 直连间歇性断连（curl/git 均超时） | 等待后重试；本项目从未引入代理 | 9.62 / 9.63 |
| 海外 runner 访问阿里云镜像 502 | CI 按 `GITHUB_ACTIONS` 环境变量切换官方源；本机中国大陆保持镜像 | 9.63 |
| 模拟器 DNS 无法解析 NGA 图床 | 图片/网络链路以真机验证为准，模拟器失败不代表代码问题 | 9.16 / 9.20 |
| 命令环境整体断网时镜像也无效 | 克隆/下载依赖外部出网通道；先确认网络再重试（本轮参考仓库克隆被阻塞） | 本轮 |

## 8. Android 平台陷阱

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| `@JavascriptInterface` 跑在 WebView 后台线程 | 桥回调必须 post 主线程再改 Compose 状态 | 9.43 / 9.44 |
| WebView 生命周期与异步回调竞态 | 加载令牌过滤过期 `onPageFinished`；销毁延迟 200ms；tracker 延迟 400ms 关闭 | 9.44 / 9.46 / 9.48 |
| Compose 测试 API 随 BOM 变动 | 2026.05 BOM 顶层无 `assertExists`，改用 `assertIsDisplayed` | 9.67 |
| UI 间距/圆角魔法值失控 | `AnkeSpacing`/`AnkeRadius` 令牌 + `DisciplineTest` 结构性约束 | 9.64 / AGENTS |

## 9. 工程纪律与文档

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| 快速迭代产生隐性复杂度 | 遵守 Karpathy 四原则：先想后写、简单优先、外科手术式改动、目标驱动 | 9.65 |
| 术语不统一导致误解 | 先查 GLOSSARY，不造同义词 | 9.66 |
| 架构边界只靠口头约定 | `DisciplineTest` 把边界变成测试：令牌、模式隔离、CI 路径、契约默认值 | 9.64 |
| DevLog 持续膨胀到 18 万字符 | 历史归档（DEVLOG_ARCHIVE）+ 教训分类（LESSONS_LEARNED）+ ADR；DevLog 只留当前状态与最近流水 | 10.15 / 本轮 |
| 推送/发布权限不清 | 用户未明确授权前不推送、不发布；发行版必须带 README/LICENSE/OFL/使用说明 | 6.2 |



## 10. 楼层导出（Android 离屏 WebView，2026-08-25）

| 教训 | 规则 | 出处 |
| --- | --- | --- |
| 离屏 WebView 的 `draw()` 只画可见区域 | 不要用 `draw()` 直接导长内容；必须先把 view 尺寸设为完整内容物理尺寸，或分片绘制后拼接 | 第四十批 |
| `capturePicture()` 在新 WebView 上不可靠 | 不依赖 `capturePicture()` 捕获长文档；优先显式控制 view 尺寸 + 分片 | 第四十批 |
| CSS px 与物理 px/密度/倍率必须显式换算 | WebView `layout()` 单位是物理像素；`viewWidth = cssWidth × density × scale`，`viewHeight = contentHeight × density × scale` | 第四十批 |
| `file:///android_asset` 与 `file:///android_fonts` 在离屏 WebView 中不稳定 | 导出渲染器内联 `reader.css`；自定义字体经 `shouldInterceptRequest` 提供并等待 `document.fonts.ready` | 第四十批 |
| 修改共享 HTML 构造/reader.css 会回归阅读链路 | 导出渲染的 CSS 注入应局限在导出渲染器内；阅读公共代码改动必须立即跑 ReaderHtmlTest + 阅读主题回归 | 第四十批 |
| 长楼层会超过 WebView/位图最大纹理尺寸 | 楼层高度超阈值时纵向分片渲染（如 12000px 一片），最后拼接为一张图 | 第四十批（计划） |
| 功能开发应先做最小技术验证，再铺 UI | 先用真实长楼层跑通“离屏渲染成图 → 尺寸/主题/字体/图片检查”，再写页面与分享 | 第四十批 |

### 10.1 楼层导出复盘（2026-08-25，18 轮收敛）

**为什么这一轮会拖这么久？**

不是功能本身难，而是我违反了项目已有的调试纪律。Windows 楼层导出能快速做完，是因为先做了最小技术验证（渲染链路冒烟），再铺 UI。Android 这一轮我反过来了：先写了完整的 Mapper/Html/Renderer/UI，再用真机反馈一点一点修，导致：
- 离屏 WebView 的四个基础事实（`draw()` 只画可见区、`capturePicture()` 不可靠、`layout()` 单位是物理像素、`allowFileAccess` 会拦 `file://` 子资源）没有在第一天用最小样例验证，而是分了好几轮逐一踩坑。
- 前几轮没有探针，靠猜；后几轮加了 `[floor_export]` / `[reading_img]` / `[gululu_music]` 日志后，定位速度立刻变快（缺 systemDark、ClipData 只放第一个 URI、BLOCKED_TAGS 移除 button 都是看日志直接定位）。
- 为修导出，多次改动阅读链路共享代码（`extractReaderParts`、`reader.css`、`WebViewChapterView`、`ReaderHtml` 白名单），每改一次就制造一个阅读回归（主题白底、图片 ERR_ACCESS_DENIED、音乐框被删除）。导出专用逻辑本应完全内聚在 `FloorExportRenderer` / `FloorExportHtml`。

**以后必须遵守的规则：**
1. 任何 WebView/渲染类功能，先写一个 50 行的设备端 spike：渲染一个真实楼层 → 看宽高/主题/字体/图片是否与阅读页一致，再开始写 UI。
2. 探针从第一版就带：`Log.w("AnkeShelf", "[feature] ...")`，不要等用户反馈后再补。
3. 导出/渲染的 CSS 与资源注入只允许发生在导出渲染器内部；阅读公共代码改动必须先跑 `ReaderHtmlTest` + 阅读主题回归。
4. Android WebView 特殊事实必须默背：`layout()` 是物理像素；`draw()` 只画可见区；`capturePicture()` 不可靠；`setAllowFileAccess(false)` 会先于 `shouldInterceptRequest` 拒绝 `file://` 子资源；`sanitizeReaderBody` 的 BLOCKED_TAGS 先于 ALLOWED_TAGS 执行。
5. UI 打磨（进度、对话框、空状态）等核心链路在真机通过后再做，否则 UI 会反复返工。

**这次做得对的：**
- 最终把导出设置持久化在 `settings.json:floor_export`，没有用 localStorage 类易失方案。
- 复用 `buildReaderHtml` + `reader.css` + `readerTheme`，导出与阅读保持一致。
- 分享走 FileProvider + ClipData，生命周期清晰。
- 每一轮保持测试基线：Android JVM 238、Python 342、API 契约 66、reader-lite parts 9。
