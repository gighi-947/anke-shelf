# 安卓端版本与发布 SOP

## 版本线

- 版本号与 Windows 完全独立：`0.1.0 → 0.2.0 → 0.3.0 → 1.0.0`。
- `versionName` / `versionCode` 只改 `app/build.gradle.kts`；`versionCode`
  每次发布递增 1。
- 里程碑：v0.1.0 阅读 MVP；v0.2.0 NGA 下载/热更新/导出；v0.3.0 搜索/标注/
  统计/设置全 Tab；v1.0.0 功能对齐 Windows v1.2.0。

## 标签与 Release 命名

- Git 标签：`android-vX.Y.Z`（Windows 仍用 `vX.Y.Z`）。
- Release 标题：`安科书架 Android vX.Y.Z`。
- 资产名纯 ASCII：`AnkeShelf-vX.Y.Z-android.apk`。

## 发布检查清单

1. 全量单测 + 仪器测试通过。
2. 生成签名 Release：`gradle assembleRelease`（密钥在
   `android/keystore/`，与 `keystore.properties` 均不入库，务必备份）。
3. 执行 `scripts/check-release.ps1 -ApkPath <apk>` 扫描凭据。
4. 打标签并创建 Release（本地推送需用户明确授权）。
5. 用 GitHub REST API 核验资产名与大小。

## 凭据红线

- APK/AAB 内禁止出现 `nga_config`、真实 `config.ini`、Cookie、
  `keystore.properties`、`local.properties` 或任何真实凭据。
- NGA uid/cid 只存应用私有目录，提供「清除已保存配置」。
