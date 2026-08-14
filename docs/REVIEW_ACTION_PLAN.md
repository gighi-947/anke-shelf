# 审查整改计划（Review Action Plan）

> 制定日期：2026-08-14
> 来源：两轮外部审查
> - 第一轮架构债：`H:\ARCHITECTURE_DEBT_REVIEW_20260814.md`
> - 第二轮仓库评审：`H:\AnkeShelf_Review_20260814.md`
> 计划基线：`ab3c6d8`（`main` / `origin/main`，Android JVM 111 过 / 1 跳）
> 配套纪律：AGENTS.md（失败显式化、规模与抽象门槛、业务不变量测试、
> 文档漂移检查）；每项按“成功标准 + 验证方式”推进。

## 1. 审查结论摘要

### 1.1 第一轮：架构债（对 `AnkeShelf-review-20260814`）

- 总体：项目已进入“工程化收紧阶段”，核心业务模型仍需强化。
- P0：BookSession 契约表达不足（`textFn: (Int) -> String?`）；
  核心数据层 null 折叠失败（越界/损坏/IO/权限 → null）。
- P1：Silent fallback（审查示例 `checkZip → false`，核验后已被
  `VerifyResult` 取代，残余并入 P0）；AppContainer 扩散风险。
- P2：UI 大文件；新抽象泛滥（核验：当前稳定）。
- 测试方向：减少“异常输入不会崩”，增加阅读位置一致性 / 数据一致性。

### 1.2 第二轮：仓库评审（对 `AnkeShelf-review-20260814-ab3c6d8`）

评级：工程素养 A- / 可长期维护 B+ / 安全基线 A / 合规 B / 社区友好度 B-。

| 编号 | 问题 | 核验结论 |
| --- | --- | --- |
| P1.1 | CODEOWNERS 单点（`* @gighi-947`） | 成立，需真实第二 owner + 仓库设置 |
| P1.2 | token 校验非常量时间（`==`） | 成立，免费修复 |
| P1.3 | token 走 query string 泄露面 | 成立 |
| P1.4 | `ngapost2md-python/` 缺 LICENSE、上游 commit 未钉、版本号冲突 | 成立（上游 MIT，HEAD `e3b9434` 2026-05-30） |
| P1.5 | 4 个 workflow 均缺 `permissions:` | 成立 |
| P2.1 | Android WebView 无 CSP，单层 jsoup 清洗 | 成立，中期立项 |
| P2.2 | AGENTS.md 与 CONTRIBUTING.md 职责重叠 | 成立，低成本 |
| P2.3 | DevLog 记录纪律对外部贡献门槛高 | 部分成立 |
| P2.4 | 根目录 18 个文件平铺 | 主观，低优先 |
| P2.5 | `reader.js` 8 处中文注释乱码 | 过时：已修复；路线图 §2.2 表格未同步（漂移） |
| P3.1 | `requirements-build.lock --allow-unsafe` 缺说明 | 成立，加注释 |
| P3.2 | `ebooklib==0.20` 版本可疑 | 不成立：PyPI latest 即 0.20 |
| P3.3 | CI 未跑 Python 3.13/3.14 矩阵 | 成立，可选 |
| P3.4 | Issue 模板缺安全报告重定向 | 成立 |
| P3.5 | 仅 `build.bat` 无 `build.sh` | 低优先 |
| P3.6 | 字体文件跨端去重 | 成立，已有路线图待办 |

## 2. 已完成整改（截至 2026-08-14）

| Commit | 内容 | 对应审查项 | 验证 |
| --- | --- | --- | --- |
| `63b6994` | 审查评估落档；AGENTS.md 固化纪律（失败显式化、规模与抽象门槛、业务不变量测试）；路线图 P0 立项 | 第一轮全项基础 | 纯文档 |
| `867e7ea` | 章节读取失败模型：`ChapterReadResult`（Success/NotFound/Corrupt/Io）；`BookSession` / `Epub` / `NativeBook` 读取链收紧；阅读页 `ChapterUiState` 错误分支 | 第一轮 P0×2 | 红→绿；JVM 111 过 / 1 跳；`assembleDebug` 通过 |
| `ab3c6d8` | 文档漂移同步（基线 `867e7ea`，Android 111/1，GLOSSARY 补术语） | 文档纪律 | 漂移扫描 |

说明：`63b6994` 前的基础成果（统一备份包 `96eb2e7`、统一 task_id `b63809f` 等）
不属于本轮审查整改，未重复列出。

## 3. 待办计划

### 批次 A：Windows/CI 快修（✅ 2026-08-14 已完成，含 A1–A5）

- **A1（P1.2 + P1.3）token 安全**
  - 改动：`app/server.py` 的 `_authorized` 改用 `secrets.compare_digest`；
    `app/main.py` 启动 URL 的 `?token=` 保留为兼容入口，前端就绪后立即
    `history.replaceState` 抹掉 query；`web/js/` 取 token 改优先 header。
  - 成功标准：header 与 query 两条路径均校验正确；页面加载后 URL 不再含 token。
  - 验证：`tests/test_server.py` 新增 query 兼容与错误 token 回归；`python -m
    unittest discover tests` 全量 231 项 OK；UI harness 92 项 PASS。
- **A2（P1.5）workflow 权限收敛**
  - 改动：`windows.yml` / `android.yml` / `nightly.yml` / `contracts.yml`
    顶层加 `permissions: { contents: read }`。
  - 成功标准：4 个 workflow 均有权限块；触发路径不变。
  - 验证：YAML 语法检查；文档漂移扫描（CI 清单仍 4 个）。
- **A3（P3.4）Issue 模板安全重定向**
  - `bug_report.md` 顶部加“安全漏洞请勿公开 Issue，见 SECURITY.md”。
- **A4（P3.1）构建锁注释**
  - `requirements-build.lock` 头部说明 `--allow-unsafe` 原因。
- **A5（P2.5）路线图表格漂移修正**
  - `docs/ARCHITECTURE_ROADMAP.md` §2.2 的 `reader.js` 行更新为现况
    （629 行、乱码已修复）。

### 批次 B：vendored 合规补齐（P1.4，约半天；✅ 2026-08-14 已完成）

- **B1** `ngapost2md-python/` 补 `LICENSE`（上游 MIT 原文 + 版权声明）。
- **B2** 补 `NOTICE`：上游 `ludoux/ngapost2md`、commit `e3b9434`（2026-05-30）、
  许可证 MIT、重写与提取范围说明。
- **B3** `ngapost2md/__init__.py` 版本号改独立线（如 `0.1.0-ankeshelf`），
  不再与主项目 vX.Y.Z 混淆。
- **B4** 更新 `THIRD_PARTY_NOTICES.md`，移除“上游 commit 待钉”待办。
- 验证：凭据/许可扫描；文档漂移扫描；Python 全量测试不受影响。

### 批次 C：仓库设置（P1.1；C2 可立即开启，C1 ⏸ 暂缓）

> C1 暂缓原因：本项目无第二维护者，CODEOWNERS 拆模块需要真实 backup owner，
> 待有第二人后再开启。C2（branch protection）不依赖第二 owner，
> 可由维护者在 GitHub Settings 独立开启。

- **C1（⏸ 暂缓）** `.github/CODEOWNERS` 按 Windows / Android / contracts /
  docs 拆模块，并指定 backup owner（文件可先行拆分）。
- **C2（可立即开启）** GitHub 仓库 Settings：开启 “Require status checks to
  pass before merging”、禁直接推送 main（不依赖 CODEOWNERS）；有第二 owner
  后再加 “Require review from CODEOWNERS”。
- 验证：新 PR 必须满足 status checks；直接推送 main 被拒绝。

### 批次 D：中期立项（P2.1，Android WebView 纵深防御）

- **D1** 评估 `WebViewAssetLoader`：asset/fonts/images 走自定义
  `https://appassets.androidplatform.net/` origin，使 CSP `'self'` 可用，
  随后收紧 `mixedContentMode` 并补 `<meta CSP>`。
- 成功标准：CSP 生效且不拦字体/图片；reader-lite 行为零变化
  （进度、分页、滚动、图片页、NGA 图床 Referer）。
- 验证：真机回归“滚动/翻页 → 退出 → 重进”、图片页覆盖；
  JVM 111 过 / 1 跳；DisciplineTest 在岗。
- 风险提示：动阅读内核加载链，按阅读器铁律（AGENTS.md §3）执行。

### 批次 E：低优先 / 延后（E1–E4、E6 已于 2026-08-14 完成；E5 延后）

- **E1（P2.2）** CONTRIBUTING.md 顶部醒目标记“开发前必读 AGENTS.md”。
- **E2（P2.3）** 对外贡献说明：外部 PR 只要求“改动摘要 / 验证”，
  DevLog 流水由维护者合并时代写。
- **E3（P2.4）✅** 根目录整理以“缓解”方式落地：README 顶部新增「仓库布局
  （新人导航）」；`使用说明.txt` 不改名（与项目中文命名纪律一致，发行包对
  中文用户友好），治理文档不移动（GitHub 仅在根目录识别 CONTRIBUTING /
  SECURITY）。
- **E4（P3.3）✅** `windows.yml` 测试矩阵 3.12 / 3.13 / 3.14
  （`fail-fast: false`；打包与 manifest 仅 3.12 跑，避免多版本 artifact 冲突）。
- **E5（P3.5）** `build.sh`——暂缓（无 Linux/macOS 需求）。
- **E6（P3.6）✅** 字体去重完成：canonical 源 `assets/fonts/`
  （LXGWWenKai-Regular.ttf + OFL.txt）；Windows fonts.py / spec 与 Android
  Gradle assets srcDir 构建期并入，双端重复副本删除。

### 驳回记录

- **P3.2**：`ebooklib==0.20` 为 PyPI 官方最新版（0.19 / 0.18 均存在），
  非私 fork，无需处理。
- **P2.5 原主张**：`reader.js` 乱码已修复，仅路线图表格未同步（见 A5）。

## 4. 执行原则

- 每项按 AGENTS.md：目标驱动（成功标准 + 验证方式）、修复先写复现测试
  （红→绿）、提交前缀 `win:` / `android:` / `docs:`、DevLog 流水补记、
  收尾文档漂移检查。
- 涉及共享文件 / 数据契约 / CI 清单时先做 Diff 影响检查，不扩大触发范围。
- 批次 A / B 可直接开工；C 需用户授权仓库设置；D 单独立项评估后执行。

## 5. 复审（v2）采纳记录（2026-08-14）

复审报告：`H:\AnkeShelf_Review_20260814_v2_ReReview.md`；评级
工程 A / 维护 A- / 安全 A+ / 合规 A- / 社区 B+，13 项问题 100% 决策闭环。
核验：行号引用全部精确，状态判定与实际一致。4 项小瑕疵处理：

- 小瑕疵 1：`ankeshelf.spec` datas 补 `ngapost2md-python/LICENSE` +
  `NOTICE`（AGPL 分发合规）——已落地。
- 小瑕疵 2：批次 C 拆分 C1（暂缓）/ C2（可立即开）——已落地。
- 小瑕疵 3：多窗口 token 流转——记录为未来注意点（当前单窗口不阻塞）。
- 小瑕疵 4：`android/scripts/check-release.ps1` 扩展 APK 内字体
  SHA-256 与 canonical 源比对——已落地。

中期建议（待排期）：README 加「寻求维护者」小节；P2.1 拆独立 GitHub issue
（`enhancement` + `security`）跟踪 WebViewAssetLoader 迁移。

## 6. 复审（review3，防御性编码视角）核验记录（2026-08-14）

审查报告：`H:\review3.md`；视角：防御性编码、复杂度位置、设计约束 vs
运行时补丁。15 项建议核验与处理：

| 项 | 判定 | 处理 |
| --- | --- | --- |
| A Optional 服务注入 | ◐ 成立（噪音，17 处 None 检查），P1 | 待做：ApiContext 字段 required + 测试 NullObject（“假绿”指控证据不足） |
| B1 clamp 重复 | ◐ 部分成立，P2 | 保留 Store 层契约守卫（`text_offset 永远非负`） |
| B2 丢弃 0 偏移 | ❌ 驳回 | DATA_CONTRACT 明确 `0 = 章首/无进度`，`persistOf` 不保存 0 是设计 |
| B3 静默吞错 ×4 | ✅ 成立，P1 | 待做：nga register / EpubError / Android 删文件 / uninstall 加日志或提示 |
| B4 `_fullscreen` 动态属性 | ✅ 成立，P1 | 待做：状态移出 ApiContext |
| B5 `progress_pct` 占位 | ✅ 已修 | `record_to_dict` 移除；`import_books` 显式组装最终值 |
| C1 Protocol 死抽象 | ✅ 已修 | `domain.py` 删两个 Protocol + 对应测试 |
| C2 `Permission` 死分支 | ✅ 已修 | `BookRepoError` 删除该 case |
| C3 `getOrNull()` escape hatch | ◐ 成立，P1 | 待做：AnkeShelfRoot / SearchScreen 打开失败显式处理 |
| C4 `try import ET` 噪音 | ✅ 已修 | `epub.py` 删除 |
| C5 三个相同迁移 | ◐ 保留 | 迁移框架属兼容性基础设施，加注释说明 |
| C6 unreachable return | ✅ 已修 | `system_api.py` 删除 |
| D1 backup 三件套同构 | ✅ 成立，P1 | 待做：抽 `_pick_and_call` helper |
| D2 函数内 import | ✅ 已修 | `system_api.py` 顶部收敛 |
| D3 `callBridge` helper | ◐ 成立，P2 | 待做：改 JS 后按阅读器铁律回归 |
| D4 `bookshelf.js` 兜底 | ◐ 数量夸大（`\|\| ''` 19 处），P3 | 低优先 |
| D5 参数校验集中 | ◐ 成立但数量夸大（image_mode 2 处 / `max(0,int(...))` 6 处），P1 | 待做：`normalize_download_params` |

快批已落地（C1/C2/C4/C6/B5/D2），验证：Python 230 项 OK（-1 Protocol 测试）、
Android JVM 111 过 / 1 跳。行为批与重构批按上表待排期。
