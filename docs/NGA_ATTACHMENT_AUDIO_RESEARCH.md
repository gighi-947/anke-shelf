# NGA 附件音频调研结论（2026-08-23）

> 状态：调研完成，待用户拍板是否立项实施。
> 关联流水：AnkeShelf_DevLog.md 第三十二批（外链音乐方案 A）与第三十三批（本调研）。

## 1. 结论摘要

NGA 帖内附件音频（作者上传的 BGM/音效）**不是 `[audio]` BBCode**，而是
NGA 接口返回的 **HTML 片段**：

```html
<span class="audio" onclick="audioClick(event)">
  <audio src="https://img.nga.cn/attachments/mon_YYYYMM/DD/lsQXX-XXXX.mp3"
         onended="audioEnd(event)" onerror="audioError(event)"/></span>
```

URL 公网可直接访问，**不需要 Cookie**。当前双端渲染管线未处理该 HTML
片段，读者无法播放（详见 §4）。最小修复是在双端 HTML 渲染层把该片段
转换为骨碌碌同款音乐 cue，复用现有宿主播放器在线播放；离线内嵌可作为
后续增强（用户倾向离线，见 §6）。

## 2. 证据

- 本地存档 `ngapost2md-python/verify_dark/41989465(62906407)/post.epub`
  的 XHTML 中出现大量 `<span class="audio"> <audio src="https://img.nga.cn/attachments/mon_2026…/lsQ…mp3" …>`，
  且 `ngapost2md/format.py` 的 `RE_AUDIO_CONTENT` 正是按该 HTML 形态提取
  `src`（对照 Go 原版）。
- `curl -I` 实测 mp3 地址（无 Cookie）：
  - HTTP 200
  - `Content-Type: audio/mpeg`
  - `Content-Length` 约 3.9MB（不同文件 1–4MB 不等）
  - `Server: tencent-cos` / CDN 缓存命中
- NGA 页面 API 在本机环境对 tid 41989465 返回 `code:46 访客不能直接访问`
  （带存档凭据同结果），故原始内容未从 API 实时复核；但 Go 管线的产物
  与解析正则足以锁定该 HTML 形态。

## 3. 现有能力与缺口

- 已有：外链 `[audio]https://…[/audio]` 已在双端转换为 `.gululu-music-cue`
  并在宿主播放（2026-08-23 方案 A）。播放桥 `gululuMusic` / MediaPlayer
  与书源解耦，任何书都可点播。
- 缺口：NGA 返回的 `<span class="audio"> <audio src=…>` 片段目前
  原样穿过渲染器：
  - Android：`<audio>` 在白名单中，但内联 `onclick/onended/onerror`
    被 sanitizer 删除，reader-lite 无对应监听，等于不可播放；
  - Windows：宿主没有 `audioClick` 处理，等于不可播放。
- 下载链路：图片三态（在线/内嵌/无图）不处理 mp3；热更新不重写存量
  楼层，新增楼层起生效。

## 4. 方案对比

| 方案 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| A. 在线 cue（推荐先做） | 双端 HTML 渲染层把 `<span class="audio">` 转成 `.gululu-music-cue`（kind=附件音频，title=文件名或 URL），复用宿主播放器 | 改动小、双端同构、立即可播、URL 公网免 Cookie | 依赖网络；不省流量；不存到本地 |
| B. 离线内嵌（用户倾向） | 在图片三态管线中新增 mp3 下载：online 保持在线 cue；embedded 下载 mp3 进 EPUB/本地资源并改为本地 src；none 移除播放按钮 | 完全离线可播，符合“把书下载到本地”定位 | 改动大：下载器、资源映射、EPUB 打包、阅读器本地资源拦截、断点与失败处理都要动；需处理 1–4MB mp3 与版权/空间 |

## 5. 建议路径

1. 先实施 **方案 A**（在线播放），红测试先行：
   - Android `NgaFormatHtml` 增加 `RE_AUDIO_SPAN` 正则；Windows
     `format_html.py` 同构增加；新增测试锁定转换与 text_offset 语义。
   - 转换后 cue 文本进坐标（与骨碌碌/外链音乐一致）。
2. 实机验证后，若用户仍要离线，再立 **方案 B**：
   - 复用 `NgaImageDownloads` / `GululuImages` 下载框架；
   - EPUB 资源映射扩展 `.mp3` 到 `file:///android_assets` 或 EPUB 本地；
   - 阅读器侧 `shouldInterceptRequest` / `styleHrefs` 已具备本地资源基础。

## 6. 待用户拍板

- 是否先上方案 A 在线播放？
- 离线内嵌（方案 B）是否立项，优先级如何？
