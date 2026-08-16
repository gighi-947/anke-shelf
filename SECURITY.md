# 安全策略（Security Policy）

## 支持版本

| 版本 | 平台 | 状态 |
| --- | --- | --- |
| v1.4.0 | Windows | 受支持 |
| android-v1.0.0 | Android | 受支持 |

## 报告漏洞

请通过 GitHub Security Advisory（
https://github.com/gighi-947/anke-shelf/security/advisories/new ）私下报告，
不要在公开 Issue 中贴出可复现细节或真实凭据。收到后会尽快评估并在修复后公开披露。

报告请尽量包含：受影响端与版本、复现步骤、影响面判断、日志/截图（脱敏）。

## 安全模型要点

- 本地 HTTP 仅回环监听，启动随机令牌校验；章节响应带 CSP 与 base 注入。
- NGA 凭据只存本机私有目录，仓库与发行包不含真实凭据；发布前跑凭据扫描。
- EPUB 解析带路径穿越防护与条目数/解压体积上限（ZIP 炸弹防护）。
- Android：`allowBackup=false`、最小权限、WebView 仅加载本地资产、章节 HTML 输入清洗。

## 已知限制（见 docs/ANDROID_SECURITY_REVIEW.md）

- Android 章节清洗已改为 jsoup DOM allowlist（2026-08-14，路线图 P2）。
- Android 为加载 https 图片允许混合内容，风险限于图片子资源。
