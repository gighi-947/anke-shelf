# 参与贡献（Contributing）

> ⚠ 开发前必读 [AGENTS.md](AGENTS.md)——真正的开发铁律（双端边界、
> 失败显式化、测试纪律、调试五步循环）都在那里，本文件只是入口。

感谢你对安科书架（AnkeShelf）的关注。仓库是单主干 `main` 开发，
Windows 与 Android 两端独立实现、共享数据契约。

## 开始之前

- 进场先读 [AGENTS.md](AGENTS.md)（双端边界、提交纪律、测试纪律等）。
- 术语见 [docs/GLOSSARY.md](docs/GLOSSARY.md)，长期决策见 [docs/adr/](docs/adr/)。
- 只做任务要求的改动，不顺手“改进”相邻代码；共享文件改动先做 Diff 影响检查。

## 本地开发与验证

Windows 端：

```bat
pip install -r requirements.lock
python -m unittest discover tests
node contracts/tests/api-contract.test.js
node contracts/tests/textpos.test.js
python -m tests.make_test_epub        :: 生成测试样本
```

更新依赖锁（人工维护 `requirements*.in`，锁文件由 pip-tools 生成）：

```bat
pip install pip-tools
pip-compile --generate-hashes --output-file requirements.lock requirements.in
pip-compile --generate-hashes --allow-unsafe --output-file requirements-build.lock requirements-build.in
```

Android 端（见 [android/README.md](android/README.md)）：

```bat
cd android
gradlew.bat testDebugUnitTest assembleDebug
```

发布前凭据扫描：

```bat
powershell -ExecutionPolicy Bypass -File android/scripts/check-release.ps1 -ApkPath android/app/build/outputs/apk/release/app-release.apk
```

## 提交与分支

- 提交前缀：`android:` / `win:` / `docs:`；功能分支 `android/<feature>`、`win/<feature>`。
- 每次改动必须补记 [AnkeShelf_DevLog.md](AnkeShelf_DevLog.md)「最近流水」（日期 + 提交 + 现象/结论）。
  **外部贡献者**只需在 PR 中写明改动摘要与验证结果，DevLog 流水由维护者合并时代写。
- 文档不得写入会过期的事实（HEAD / 提交号 / 版本号 / 测试计数 / 文件行数 /
  日期快照 / CI 清单）；需要现查的就写查询命令。详见 `AGENTS.md` §5
  “文档只写工具推断不出来的内容”。

## Pull Request 清单

- [ ] 说明改动目标、成功标准与验证方式
- [ ] 相关单测 / 契约守卫 / `DisciplineTest` 通过
- [ ] DevLog「最近流水」已补记（外部贡献者：PR 已写明改动摘要与验证即可，由维护者代写）
- [ ] 文档未引入会过期的事实（HEAD / 提交号 / 版本号 / 测试计数 / 文件行数 / 日期快照 / CI 清单）；需要现查的已写为查询命令（AGENTS.md §5）
- [ ] 共享文件 / 数据契约字段已做 Diff 影响检查（Windows / Android / CI / 文档）
- [ ] 无凭据、敏感数据与大体积二进制混入

> 推送与发布需仓库维护者明确授权；代码以 GNU AGPL-3.0 开源，
> 提交即表示同意在该许可证下分发。
