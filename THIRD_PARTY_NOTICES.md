# 第三方声明（Third-Party Notices）

## ngapost2md-python

- 来源：https://github.com/ludoux/ngapost2md （Go 实现）
- 上游 commit：`e3b94346c805`（2026-05-30，分支 neo）
- 关系：`ngapost2md-python/` 为独立 Python 重写，下载流程、数据模型与格式规则
  与上游核心部分等价；表情与匿名映射表由上游 Go 源码提取。
- 许可证：MIT；上游原文与引用说明见 `ngapost2md-python/LICENSE` 与
  `ngapost2md-python/NOTICE`。

## 字体：霞鹜文楷 LXGW WenKai

- 来源：https://github.com/lxgw/LxgwWenKai
- 许可证：SIL OFL 1.1；许可证文本见 `assets/fonts/OFL.txt`。
- 文件：`assets/fonts/LXGWWenKai-Regular.ttf`（双端 canonical 单一源；Windows
  发行包以 `web/fonts/weidqczfkyxk.ttf` 逻辑名提供，Android APK 以
  `assets/fonts/LXGWWenKai-Regular.ttf` 打包）。

## PyCA cryptography

- 来源：https://github.com/pyca/cryptography
- 用途：Windows 端兼容解密骨碌碌全能助手生成的 CryptoJS AES 秘密内容。
- 版本：49.0.0；许可证：Apache-2.0 OR BSD-3-Clause。

## 设计参考（思路借鉴，独立实现）

凡直接对照算法、几何公式或数据结构处，源码注释均已标注出处：

| 项目 | 许可证 | 借鉴内容 |
| --- | --- | --- |
| Readest | AGPL-3.0 | 前后端分离、EPUB 流水线、主题令牌、进度防抖与去重 |
| flow | AGPL-3.0 | 自动双页、CSS multi-column 几何、双页补偶数列、搜索分组 |
| epub.js | BSD-2-Clause | 分页列几何、Auto spread、forceEvenPages 思路 |
| Foliate | GPL-3.0-or-later | multi-column 分页、大小写/全词搜索选项 |
| Koodo Reader | AGPL-3.0 | 设置 Tab 导航、色板选择、搜索结果分页跳转 |
| KOReader | AGPL-3.0 | 主题预设色板、日夜自定义配色 |
| Legado | GPL-3.0 | 章内进度字段语义、onPause 保存时机 |
| daisyUI | MIT | CSS 变量组织设计令牌 |
