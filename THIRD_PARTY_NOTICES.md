# 第三方声明（Third-Party Notices）

## ngapost2md-python

- 来源：https://github.com/ludoux/ngapost2md （Go 实现）
- 关系：`ngapost2md-python/` 为独立 Python 重写，下载流程、数据模型与格式规则
  与上游核心部分等价；表情与匿名映射表由上游 Go 源码提取。
- 许可证：以原仓库 LICENSE 为准。
- 待办：上游参考的具体 commit/tag 尚未单独记录，依赖治理（路线图 P1 依赖锁定）
  时一并补钉。

## 字体：霞鹜文楷 LXGW WenKai

- 来源：https://github.com/lxgw/LxgwWenKai
- 许可证：SIL OFL 1.1；许可证文本见 `web/fonts/OFL.txt` 与
  `android/app/src/main/assets/fonts/OFL.txt`。
- 文件：`web/fonts/weidqczfkyxk.ttf` 与
  `android/app/src/main/assets/fonts/LXGWWenKai-Regular.ttf`（两者 SHA-256 相同，
  分端各存一份；去重为路线图 P3 待办）。

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
