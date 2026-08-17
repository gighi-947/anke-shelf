# 安科书架 Android 安全检查报告

> 日期：2026-08-08；范围：Android 端（android/）+ 仓库级凭据/CI 审计；结论：核心安全状态良好，本轮修复 1 个中危注入点，另有 1 项本地敏感文件提醒。

## 1. 本轮修复

### 章节 HTML 脚本注入（中危）

- 问题：阅读器把下载/导入的章节 `body` 原样注入 WebView（`javaScriptEnabled=true`），若帖子或 EPUB 含 `<script>`、`on*` 事件、`javascript:` 链接、`<iframe>` 等，可执行任意脚本。
- 处理：`extractReaderParts` 对 body 做输入清洗（`sanitizeReaderBody`）：
  - 删除 `<script>`（含未闭合）、`<iframe>/<object>/<embed>/<base>/<form>`、`<meta http-equiv=refresh>`；
  - 删除所有 `on*` 事件属性；
  - 删除 `javascript:` 形式的 href/src；
  - 保留 `style` 属性与 `<style>` 块（NGA 排版需要）。
- CSP 说明：曾尝试加 CSP meta，但 WebView 的 file:// 页面下 `'self'` 不匹配 asset 子资源（实测 reader.css 被拦），可能连带拦掉 reader.js；故放弃 CSP，以输入清洗 + 既有导航拦截/链接拦截为主要防线。
- 测试：`sanitizeRemovesScriptsAndEventAttrs` 等单测通过。

## 2. 已核查且通过的项目

| 类别 | 结论 |
|---|---|
| 权限最小化 | 仅 INTERNET / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS |
| 备份/导出 | `allowBackup=false`；Service `exported=false` + `foregroundServiceType=dataSync`（targetSdk 36 合规） |
| 网络 | NGA API 仅 https（`https://bbs.nga.cn`）；无明文流量；WebView 图片 `referrerPolicy=no-referrer` |
| 数据与凭据 | NGA uid/cid 仅存应用私有目录；应用内可清除；`check-release.ps1` 扫描 APK（对 debug 包实测 PASS，输出 SHA256） |
| 仓库凭据 | git 历史从未提交 local.properties / keystore / 真实 config.ini / nga_config；工作区跟踪内容无真实凭据 |
| CI | `android.yml` 仅单测+assembleDebug，无密钥/签名材料 |
| WebView 基础 | `allowFileAccess=false`；导航全部拦截；JS 链接拦截；JS 桥仅纯回调（无文件/网络能力） |
| EPUB 解压 | ZipFile 按 entry 名读取，不会越界到文件系统；entry 名做 lowercase 去重 |
| 调试 | WebView 远程调试仅在 debug 构建开启（BuildConfig.DEBUG） |

## 3. 残留风险与建议

1. **本地敏感存档**：`D:\Codex\project1\.local\archive\` 下（cherry-studio-export、ngapost2md-python/verify*/config.ini）含真实 NGA uid/cid。该目录被 `.gitignore` 覆盖、从未入库，但建议尽快删除或移到加密保管处（也占用 F 盘空间）。
2. **mixedContentMode**：为让 file:// 阅读页加载 https 图，全局允许混合内容；风险限于图片子资源（无脚本执行）。后续可评估 WebViewAssetLoader + 自定义 scheme 收紧。
3. **正则清洗局限（已解决，2026-08-14）**：`sanitizeReaderBody()` 已改为 jsoup DOM 级
   白名单清洗（危险标签移除 / 非白名单解包 / 事件属性与 `javascript:` 等链接剔除），
   不再依赖正则“尽力而为”。
4. **图片加载/长按**：模拟器无法解析 img.nga.cn（DNS），需真机补验。

## 4. 建议的例行检查

- 每次发布前跑 `android/scripts/check-release.ps1`（见 `android/VERSIONING.md`）。
- 发布前确认 `android/keystore/` 与 `keystore.properties` 未入库、`local.properties` 未入库。
- 定期搜索工作区未跟踪目录（如 `.local/`、桌面数据目录）是否有真实凭据存档。
