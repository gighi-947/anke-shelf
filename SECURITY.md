# 安全策略（Security Policy）

## 支持版本

| 版本 | 平台 | 状态 |
| --- | --- | --- |
| v1.7.0 | Windows | 受支持 |
| android-v1.4.0 | Android | 受支持 |
| v1.6.1 | Windows | 受支持（历史版本） |
| android-v1.3.1 | Android | 受支持（历史版本） |

## 报告漏洞

请通过 GitHub Security Advisory（
https://github.com/gighi-947/anke-shelf/security/advisories/new ）私下报告，
不要在公开 Issue 中贴出可复现细节或真实凭据。收到后会尽快评估并在修复后公开披露。

报告请尽量包含：受影响端与版本、复现步骤、影响面判断、日志/截图（脱敏）。

## 安全模型要点

- 本地 HTTP 仅回环监听（令牌校验覆盖 `/api` 路由），章节响应带 CSP 与 base 注入。
- NGA 凭据只存本机私有目录，仓库与发行包不含真实凭据；发布前跑凭据扫描。
- EPUB 解析带路径穿越防护与条目数/解压体积上限（ZIP 炸弹防护）。
- Android：`allowBackup=false`、最小权限、WebView 仅加载本地资产（`allowFileAccess=false`
  + 资源经拦截器白名单供流）、章节 HTML jsoup 白名单清洗；网络出口门禁——
  NGA 图床按主机名精确后缀匹配代理（与桌面同构，子串伪造拒绝）、
  明文 http 子资源一律拒绝（https 外链图片放行，与桌面 CSP `img-src https:` 同策略）。

## 已知限制（见 docs/archive/ANDROID_SECURITY_REVIEW.md）

- Android 章节清洗已改为 jsoup DOM allowlist（2026-08-14，路线图 P2）。
- Android 为加载 https 图片允许混合内容，风险限于图片子资源。
- Android 未采用 CSP：file:// 页面下 `'self'` 不匹配 asset 子资源（2026-08-08
  实测会拦 reader.css），以输入清洗 + 资源/出口拦截为主要防线；如引入
  WebViewAssetLoader（真 https 源）可重新评估。
