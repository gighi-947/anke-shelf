# AnkeShelf 动效审查标准（Animation Standards）

> 来源：借鉴 React Bits（`DavidHDev/react-bits`）`AGENTS/review-animations` 的动效质量规则，
> 仅吸收规则、不复制代码；适用两端 UI（Windows `web/` + Android Compose/WebView 阅读器）。
> 状态：现役（2026-08-18）。

## 适用范围

- Windows 前端 `web/css/*`、`web/js/*`（阅读器、骨碌碌沉浸层、面板/弹窗/Toast）。
- Android Compose UI 与 `assets/reader/*`（阅读器动画、面板过渡）。
- 骨碌碌沉浸层背景/视效属于“循环/背景动画”，单独适用 §3 的暂停/停止与 reduced-motion 规则。

## 1. 硬性规则

| # | 规则 | 说明 |
|---|---|---|
| 1 | 只动画化 `transform` 与 `opacity` | 避免 layout 重排导致滚动/分页进度漂移 |
| 2 | UI 动效默认 ≤300ms | 入场/退场/面板开合/Toast；大型背景过渡可放宽但需注释说明 |
| 3 | 必须支持 `prefers-reduced-motion` | 系统开启减少动态时关闭/降级非必要动画 |
| 4 | 禁止 `transition: all` | 明确列出要过渡的属性 |
| 5 | 禁止无理由 `scale(0)` 退场 / `ease-in` | 退场优先 opacity + 轻微位移；ease-in 仅在有物理含义时使用 |
| 6 | 悬停动画必须配 `@media (hover: hover) and (pointer: fine)` | 触屏设备不依赖 hover 触发 |

## 2. 阅读器专项

- 正文内动画（骰点揭示、迷雾显隐、段落高亮、评论徽标）必须保持 `text_offset` 稳定：
  - 动画只作用于宿主层或 `data-textpos-exclude` 子树；
  - 动画结束后不得改变布局尺寸（或改变后必须重算/保位）。
- 不做干扰阅读的正文动画（如逐字/滚动文字动画），除非有明确产品需求并单独评审。

## 3. 循环/背景动画（骨碌碌沉浸层）

- Canvas/WebGL 循环必须：进入页面开启、离开页面/`pagehide` 暂停、面板关闭可停止。
- 与现有沉浸开关一致：自动音乐 / 背景 / 视效可独立停用。
- 遵守 reduced-motion：开启减少动态时默认不启动背景视效。

## 4. 新增/修改动效检查清单

- [ ] 是否只动 transform / opacity？
- [ ] 时长 ≤300ms（或已注释理由）？
- [ ] 已处理 `prefers-reduced-motion`？
- [ ] 没有 `transition: all` / 无理由 `scale(0)` / 无理由 `ease-in`？
- [ ] hover 动效已配 `@media (hover:hover) and (pointer:fine)`？
- [ ] 阅读器相关：不影响 text_offset / 已在 `data-textpos-exclude` 子树？
- [ ] RAF 循环已做卸载清理？
- [ ] 双端（Windows web / Android）行为一致？

## 5. 来源与许可

- 规则来源：React Bits `AGENTS/review-animations`（MIT + Commons Clause）。
- 本项目仅吸收规则，不复制其组件代码；如未来移植具体效果，须在
  `THIRD_PARTY_NOTICES.md` 登记来源与许可。
