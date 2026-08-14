# 参与贡献（Contributing）

感谢你对安科书架（AnkeShelf）的关注。仓库是单主干 `main` 开发，
Windows 与 Android 两端独立实现、共享数据契约。

## 开始之前

- 进场先读 [AGENTS.md](AGENTS.md)（双端边界、提交纪律、测试纪律等）。
- 术语见 [docs/GLOSSARY.md](docs/GLOSSARY.md)，长期决策见 [docs/adr/](docs/adr/)。
- 只做任务要求的改动，不顺手“改进”相邻代码；共享文件改动先做 Diff 影响检查。

## 本地开发与验证

Windows 端：

```bat
pip install -r requirements.txt
python -m unittest discover tests
node contracts/tests/api-contract.test.js
node contracts/tests/textpos.test.js
python -m tests.make_test_epub        :: 生成测试样本
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
- 涉及 HEAD/版本线/测试基线/CI 清单的改动，收尾跑文档漂移检查并同步非归档文档。

## Pull Request 清单

- [ ] 说明改动目标、成功标准与验证方式
- [ ] 相关单测 / 契约守卫 / `DisciplineTest` 通过
- [ ] DevLog「最近流水」已补记
- [ ] 共享文件 / 数据契约字段已做 Diff 影响检查（Windows / Android / CI / 文档）
- [ ] 无凭据、敏感数据与大体积二进制混入

> 推送与发布需仓库维护者明确授权；代码以 GNU AGPL-3.0 开源，
> 提交即表示同意在该许可证下分发。
