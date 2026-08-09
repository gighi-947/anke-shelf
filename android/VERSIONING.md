# 安卓端版本、签名与发布 SOP

> 与 Windows 端 SOP（见根目录 `AnkeShelf_DevLog.md` 第 5.3 节）**并列但不混用**：
> Windows 用 `vX.Y.Z` + `AnkeShelf-vX.Y.Z.zip`；安卓用 `android-vX.Y.Z` + `AnkeShelf-vX.Y.Z-android.apk`。

## 1. 版本线

- 安卓独立版本线：`0.1.0 → 0.2.0 → 0.3.0 → 1.0.0`。
- 唯一版本定义位置：`android/app/build.gradle.kts`（`versionName` / `versionCode`）。
  `versionCode` 每次发布递增 1（当前：versionCode=1，versionName=1.0.0）。
- 里程碑与当前进度（2026-08-08）：

| 版本 | 内容 | 状态 |
|---|---|---|
| v0.1.0 | M2 阅读 MVP（SAF 导入/滚动分页/进度/主题） | 已完成（未发布） |
| v0.2.0 | M3 NGA 下载/热更新/导出 | 已完成（未发布） |
| v0.3.0 | M4 UI/搜索/标注/统计/图片/字体 | 已完成（未发布） |
| v1.0.0 | 功能对齐 Windows v1.2.0（M4 验收通过） | **已发布（android-v1.0.0，2026-08-09）** |

> 因 v0.1.0–v0.3.0 均未发布，首个 Release 建议直接 `android-v1.0.0`
> （versionCode=1，versionName=1.0.0），避免无意义的占位版本。

## 2. 标签与 Release 命名

- Git 标签：`android-vX.Y.Z`（例：`android-v1.0.0`）。
- Release 标题：`安科书架 Android vX.Y.Z`。
- 资产名纯 ASCII 且带版本号：`AnkeShelf-vX.Y.Z-android.apk`。
- 发布推送需用户明确授权（当前仓库未推送 GitHub）。

## 3. 签名

- 正式签名密钥放 `android/keystore/`（**不入库**，`.gitignore` 已忽略），务必单独备份。
- 首次生成：

  ```powershell
  keytool -genkeypair -v -keystore android/keystore/ankeshelf-release.jks `
    -alias ankeshelf -keyalg RSA -keysize 2048 -validity 3650
  ```

- `android/keystore.properties`（不入库）：

  ```properties
  storeFile=keystore/ankeshelf-release.jks
  storePassword=***
  keyAlias=ankeshelf
  keyPassword=***
  ```

- `android/app/build.gradle.kts` 的 release 签名引用示例（正式发布前启用）：

  ```kotlin
  signingConfigs {
      create("release") {
          val props = java.util.Properties().apply {
              val f = rootProject.file("keystore.properties")
              if (f.exists()) f.inputStream().use { load(it) }
          }
          storeFile = rootProject.file(props.getProperty("storeFile"))
          storePassword = props.getProperty("storePassword")
          keyAlias = props.getProperty("keyAlias")
          keyPassword = props.getProperty("keyPassword")
      }
  }
  buildTypes {
      release {
          signingConfig = signingConfigs.getByName("release")
          // ...
      }
  }
  ```

## 4. 发布检查清单

1. 全量回归：`gradlew testDebugUnitTest`（JVM 单测）+ 模拟器/真机手工清单（M4 验收报告
   `docs/ANDROID_M4_ACCEPTANCE.md`）。
2. 更新 `app/build.gradle.kts` 版本号（`versionName` 去掉 `-debug`、`versionCode` 递增），
   同步 README 安卓章节（如有版本引用）与 `AnkeShelf_DevLog.md`。
3. 构建签名 Release：`gradlew assembleRelease`（无签名文件时可用 debug 包演练流程）。
4. 凭据扫描（必须通过）：

   ```powershell
   powershell -ExecutionPolicy Bypass -File android/scripts/check-release.ps1 `
     -ApkPath android/app/build/outputs/apk/release/app-release.apk
   ```

5. 打标签（本地）：

   ```powershell
   git tag -a android-vX.Y.Z -m "AnkeShelf Android vX.Y.Z"
   ```

6. 创建 Release（用户授权后）：

   ```powershell
   gh release create android-vX.Y.Z android/app/build/outputs/apk/release/app-release.apk `
     --title "安科书架 Android vX.Y.Z" --notes "..."
   ```

7. 资产核验（REST API，核对资产名与大小/SHA256 与本地一致）：

   ```powershell
   gh api repos/gighi-947/anke-shelf/releases/tags/android-vX.Y.Z `
     --jq '.assets[] | {name, size}'
   ```

## 5. 凭据红线

- APK/AAB 内禁止出现 `nga_config`、真实 `config.ini`、Cookie、
  `keystore.properties`、`local.properties` 或任何真实凭据（uid/cid）。
- NGA uid/cid 只存应用私有目录（`filesDir/AnkeShelf/nga_config.ini`），
  应用内提供「清除已保存配置」；发布前必须跑 `check-release.ps1`。

## 6. 已知发布注意事项

- Release 标题/说明含中文时，经 PowerShell → gh 管道曾出现乱码；不可靠时用
  `curl`/`Invoke-RestMethod` 直连 `uploads.github.com`（与桌面 SOP 相同教训）。
- 资产名保持纯 ASCII；模拟器测试数据（含真实 NGA 凭据）只存在于设备应用私有目录，
  不会进入 APK。

## 7. 发布记录

| 版本 | 标签 | 日期 | 资产 | SHA256 |
| --- | --- | --- | --- | --- |
| v1.0.0 | `android-v1.0.0` | 2026-08-09 | `AnkeShelf-v1.0.0-android.apk`（16.4MB） | `3F1B212FD6CD966B4FF47599A782C155468F2E31B216CAA5A4961028C4FF7475` |
