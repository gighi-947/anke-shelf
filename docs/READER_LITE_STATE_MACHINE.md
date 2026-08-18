# reader-lite.js 状态机收敛设计

> 状态：草案，待评审。
> 目标文件：`android/app/src/main/assets/reader/reader-lite.js`（源文件为其
> `reader-lite.parts/` 模块；改 parts 后必须 `bundle-reader-lite.js --write`）。
> 关联纪律：AGENTS.md §3 阅读器与进度保持铁律；ADR-0002 Compose + WebView 阅读架构。

## 1. 背景与问题

当前 `reader-lite.js` 的“加载 → 恢复 → 就绪 → 用户操作 → 重排”流程不是显式状态机，
而是由多个布尔标志和定时器共同隐式表达：

- `state.settled`：是否已向 Kotlin 上报 `onSettled`
- `state.userMoved`：用户是否已滚动/翻页
- `state.restorePending`：是否还有初始 offset 需要恢复
- `state.paged` / `state.huge`：当前渲染模式
- `settleTimer` / `resizeTimer` / `scrollTimer` / `resizeOffset` / `resizeScrolled`
- `tryRestoreAfterSettle()` 递归 setTimeout
- `onResize()` 与 `refresh()` 各维护一套“等布局就绪后恢复”的逻辑

问题：

1. 状态分散在多个模块字段，无法一眼看出“当前处于什么阶段”。
2. `tryRestoreAfterSettle` 递归定时器 + `refresh` + `onResize` 存在多条相似路径，
   容易在改动时漏掉其中一条。
3. 每个历史 bug 的约束（9.48 / 9.53 / 9.54 / 9.58 / 9.59）散落在注释和分支中，
   没有形成可推理的转换表。
4. 后续 P5-C（滚动到底自动翻章）等进度相关功能会继续触碰这段逻辑，风险会放大。

## 2. 收敛目标

把隐式状态收敛为**显式阶段 + 少量事件**，在不改变桥协议、不改变进度语义的前提下，
让控制流线性、可审查、可回放。

### 2.1 显式阶段

```
BOOTSTRAPPING
   │  init() 完成 DOM 挂载 / 首次布局
   ▼
RESTORING
   │  初始 offset 恢复完成 且 layoutReady()
   ▼
READY
   │  用户滚动/翻页、模式切换、resize 等
   ▼
（仍为 READY，但 userMoved=true 或 restorePending=true 时进入受限恢复）
```

用字符串阶段值表达，便于日志与测试：

```js
state.phase = 'bootstrapping' | 'restoring' | 'ready';
```

保留 `state.settled` 作为“是否已向 Kotlin 发过 onSettled”的一次性标记，
但不再用它表达“当前处于哪一阶段”。

### 2.2 保留的显式标志

- `state.userMoved`：用户已主动滚动/翻页；为 true 时，任何自动恢复都不得覆盖用户位置。
- `state.restorePending`：还有未完成的初始/切换恢复；为 true 时，settle 链负责最终定位。
- `state.pagedAnchor` / `state.scrollAnchor` / `state.pagedAnchorPage` /
  `state.pagedAnchorTotal` / `state.scrollRatio`：锚点数据，语义不变。

### 2.3 收敛后的调度入口

所有“等布局就绪后恢复/标记就绪”的路径统一走一个入口：

```js
requestSettle(offset, reason);
```

内部只维护一个 `settleTimer`，通过 `state.phase` 判断是否还需要恢复；
不再出现 `tryRestoreAfterSettle` 递归、`refresh` 单独一套、`onResize` 单独一套。

所有 resize 防抖统一走：

```js
scheduleResize();
```

内部只维护一个 `resizeTimer`，通过 `state.paged` 与 `state.phase` 决定是否重排。

所有滚动保存统一走现有 500ms debounce，不新增进度写入口。

## 3. 不变量（必须始终成立）

1. **桥协议不变**：`BRIDGE_VERSION = 1`，`onReady` 仍为结构化 JSON；
   `onSettled` 每个页面生命周期最多上报一次。
2. **分页/滚动模式隔离**：
   - 分页保存：`saveProgressNow(..., page, total, -1)`；
   - 滚动保存：`saveProgress(..., -1, -1, state.scrollRatio)`；
   - `currentScrollState()` 返回 `{o, r, p}`，p 表示实际分页模式。
3. **进度写入口不变**：
   - 滚动防抖 500ms；
   - 翻页立即 `saveProgressNow`；
   - 换章/退出由 Kotlin `dispose`/`currentScrollState` 查询 + `flush` 完成；
   - JS 侧不再新增 pagehide 等额外保存路径。
4. **自动恢复不得覆盖用户位置**：
   - `userMoved === true` 时，settle 链只能 `markSettled()`，不能回拉 offset。
5. **settle 语义**：
   - 字体加载完成、分页模式图片全部 `complete` 后才最终定位；
   - 网络/字体卡死时保留 8 秒兜底；
   - 兜底后必须 `markSettled()`，不能永久阻塞 UI。
6. **模式切换**：
   - 切换前取当前 offset 作为跨模式锚点；
   - 切换后旧模式页码/比例字段作废；
   - 新模式按 `text_offset` 恢复，滚动比例只在滚动模式读取。

## 4. 事件与转换（草案）

| 当前阶段 | 事件 | 新阶段 | 动作 |
|---|---|---|---|
| bootstrapping | `init()` 完成首帧挂载 | restoring / bootstrapping | 分页立即 build TextPos；滚动延迟 build；触发首次 restore |
| bootstrapping / restoring | `layoutReady()` 为 true | ready | 最终定位 + `markSettled()` |
| bootstrapping / restoring | 8s 兜底到期 | ready | 按当前锚点兜底定位 + `markSettled()` |
| restoring / ready | `userScroll` / `userFlip` | ready（userMoved=true） | 更新锚点；滚动 500ms 保存；翻页立即保存 |
| ready | `setMode(paged)` | restoring（若布局未就绪）/ ready | 跨模式锚点交接，作废旧模式字段 |
| ready | `resize` | ready | 防抖重排；若布局未就绪转 restoring |
| ready | `refresh()` 兜底 | ready | 若 restorePending 则恢复，否则仅 markSettled |

> 具体转换表在实施时以代码注释形式固化，并作为 `DisciplineTest` 的字符串守卫，
> 防止后续再次退化成散落的 if/timer。

## 5. 实施步骤（小步、每步可验证）

1. **Step 0：加状态字段与日志**
   - 在 `state` 中新增 `phase: 'bootstrapping' | 'restoring' | 'ready'`；
   - 在关键转换点输出 `log('[state] ...')`；
   - 不改行为，跑全部 JS/Android 测试，确认无回归。
2. **Step 1：收敛 settle 链**
   - 将 `tryRestoreAfterSettle` 递归改为 `requestSettle(offset, deadline)` 单定时器；
   - `refresh()` / `finish()` / `setMode()` 统一调用 `requestSettle`；
   - 保留 8s 兜底与 `userMoved` 分支。
3. **Step 2：收敛 resize**
   - 将 `onResize` 的 `resizeOffset` / `resizeScrolled` 收进 `state`，
     并统一 `scheduleResize()`；
   - 与 `requestSettle` 的交互保持单一入口。
4. **Step 3：清理冗余 try/catch 与重复分支**
   - 仅保留边界性 try/catch（如 `currentOffsetSafe`）；
   - 删除不再需要的死分支。
5. **Step 4：补充守卫测试**
   - 在 `DisciplineTest` 中增加对 `requestSettle` / `scheduleResize` /
     `phase` 转换注释的字符串守卫；
   - 如条件允许，为 `reader-lite.js` 增加 Node vm 下的最小 DOM 行为测试。

## 6. 验证方案

每次 Step 必须跑：

```bat
node contracts/tests/reader-lite-parts.test.js
node contracts/tests/bridge-contract.test.js
node contracts/tests/textpos.test.js
node tests/js/reader-session.test.js
cd android
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

发布前还必须：

- 真机/模拟器跑“滚动/翻页 → 退出 → 重进”回归，连续 3 次一致；
- 校验 APK 内 `assets/reader/reader-lite.js` SHA-256 与源码一致；
- 跑 `android/scripts/check-release.ps1`。

## 7. 回滚策略

- 每个 Step 独立提交，若某一步导致进度回归，直接 revert 该提交；
- 桥协议版本不变，旧 APK 与新 JS 不混用；改动未发布前不 bump 版本；
- 若发现无法在本地验证的边界（如真实 WebView 字体时序），停下并向用户说明，
  不凭猜测继续推进。
