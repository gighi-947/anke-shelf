# Android 端性能专项梳理（滚动模式惯性卡顿）

日期：2026-08-08
范围：阅读器滚动/分页、WebView 渲染、进度持久化、图片加载与代理、Compose 重组。

> 状态（2026-08-13 核对）：本报告为 2026-08-08 时点。其后进度持久化整体重写
> （ChapterProgressTracker，归档 §9.43 / §9.48 / §9.50 / §9.58），`reader.js`
> 替换为 `reader-lite.js`，ReaderScreen / ReaderBottomBar 已于 9.57 删除；
> 图片懒加载、OkHttp 缓存、事件委托等优化方向仍然有效。

## 现象与定位

滚动模式下手指离开屏幕后的惯性阶段出现周期性“一顿一顿”。沿热路径梳理后确认以下冗余开销：

| # | 开销 | 位置 | 影响 |
|---|------|------|------|
| 1 | 进度上报（约 1.2s/次）→ `ProgressStore.set()` 同步原子写盘（临时文件 + rename）在主线程执行 | `ReaderScreen.onProgressValue` / `Shelf.kt` | 惯性滚动期间每约 1.2s 一次主线程磁盘 I/O，直接造成周期性掉帧 |
| 2 | 正文图片全部立即加载 + 同步解码（长帖上千张） | 章节 HTML 渲染 / WebView 网络 | 打开章节时突发网络与解码；滚动进入新区域时解码争抢主线程 |
| 3 | WebView 拦截响应不进 WebView 缓存，回看/翻页往返重复下载 | `ReaderScreen.shouldInterceptRequest` | 重复网络 I/O |
| 4 | 图片代理每次请求重新读 ini + JSON 解析 | `shouldInterceptRequest` / `fetchHttpBytes` | 每张图一次磁盘读 |
| 5 | 每张 `<img>` 各挂 2 个 load/error 监听器 | `reader.js bindImages` | 千图级监听器，且每次图片加载触发回调 |
| 6 | `scrollRatio`/`pageInfo` 状态更新触发整个 `ReaderScreen` 重组 | 底部进度条与阅读器同函数 | 每约 1.2s 全屏重组 |
| 7 | 滚动上报无变化值也照发（顶部/底部、微小抖动） | `onPageFinished` 注入的 scroll 监听 | 无效桥接调用 |

## 已实施优化

1. **进度落盘改后台防抖**（`Shelf.kt`）：
   - `ProgressStore.set()` 只更新内存，经单线程调度器 1.5s 合并后延迟落盘；
   - 新增 `flush()`（取消 pending + 立即写盘），阅读器退出/切章、`saveNow` 的 JS 回调完成后调用；
   - `load()` 取消 pending 并清 dirty；相关测试补 `flush()`。
2. **正文图片懒加载 + 异步解码**（`ReaderHtml.kt`）：
   - 渲染期给所有 `<img>` 注入 `loading="lazy" decoding="async"`（已有 loading 属性保持原样），覆盖已下载书籍；
   - 分页模式在 `init`/`setMode` 进入时 `forceEagerImages()` 恢复立即加载，保持“翻页即见图”。
3. **OkHttp 磁盘缓存**（`AppContainer.kt`）：图片代理 client 挂 64MB 磁盘缓存，滚动回看/翻页往返不重复下载。
4. **代理配置快照**（`ReaderScreen.kt`）：进入阅读器时 `remember` 一次 NGA 配置，代理与保存共用，去掉每请求读 ini。
5. **图片监听事件委托**（`reader.js`）：document 捕获阶段监听 load/error（跳过 lightbox 大图），不再每图两个监听器。
6. **Compose 重组隔离**（`ReaderScreen.kt`）：底部控制条抽成 `BoxScope.ReaderBottomBar`，`scrollRatio`/`pageInfo` 更新只重组该子树。
7. **滚动上报去重**：scroll 监听增加 `lastRatio` 阈值（0.2%），顶部/底部与微小抖动不再调桥。

## 设计权衡

- **未启用 `content-visibility: auto`**：虽能跳过屏外楼层布局/绘制，但屏外元素尺寸会退回 `contain-intrinsic-size` 估算，`scrollHeight`/`offsetTop` 失真，滚动模式进度保存/恢复都会漂移。列为后续方向；若启用需同步改为 DOM 锚点式恢复并做长帖回归。
- **进度防抖窗口 1.5s**：滚动停止后最多 1.5s 落盘；正常退出由 `saveNow` 回调 + `DisposableEffect` 双保险立即 flush。极端杀进程可能丢最近约 1.5s 阅读位置，可接受。

## 验证

- `node --check reader.js` 通过；`testDebugUnitTest` 70 条通过（新增懒加载注入 3 条、进度 flush 适配）；`assembleDebug` 通过。
- 建议真机验证：
  - `adb shell dumpsys gfxinfo <pkg> framestats` 对比滚动阶段 jank 帧占比；
  - 长帖滚动回看图片是否秒出（OkHttp 缓存）；
  - 退出阅读器后 progress.json 为最新位置；断网打开章节时屏外图片不加载、可视图片正常。

## 后续可选方向（非本次范围）

- `content-visibility` + DOM 锚点进度恢复（长帖渲染进一步减负）；
- 章节 DOM 虚拟化/分段渲染；
- 图片宽高占位，避免加载完成时布局跳动；
- 分页模式相邻页预渲染。
