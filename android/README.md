# 安科书架 · 安卓端

安卓端采用 Kotlin + Jetpack Compose 原生重写（外壳），阅读正文使用
`app/src/main/assets/reader/` 下安卓专用精简 WebView 渲染页（不复用桌面
`web/` 代码）。后端语义按 Windows v1.7.1 逐个移植。

> 当前版本：android-v1.4.1（2026-08-22 发布；功能与 Windows v1.7.1 对齐，
> 双端功能与版本状态以根 [README.md](../README.md) 和
> [ANDROID_PARITY_PLAN.md](../docs/ANDROID_PARITY_PLAN.md) 为准）。
> 版本线独立：`android-vX.Y.Z`。

## 本地构建

1. 准备 JDK 17+ 与 Android SDK（本机工具链位于仓库根目录 `.tools/`，
   已加入 `.gitignore`；若使用 Android Studio 则直接用其自带 JDK/SDK）。
2. 确认 `android/local.properties` 存在且包含本机 SDK 路径
   （`sdk.dir=...`；该文件不入库）。
3. 校验工具链（JDK 17+ 与 SDK，可选但推荐）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-toolchain.ps1
```

4. 在 `android/` 目录执行：

```powershell
..\.tools\gradle-dist\gradle-9.4.1\bin\gradle.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## Android Studio 本地环境（2026-08 配置）

- IDE：Android Studio Quail 3 | 2026.1.3，免安装解压版，
  路径 `D:\Android\AndroidStudio`（自带 JBR，无需单独装 JDK）。
- SDK：复用仓库内 `.tools\android-sdk`（platform-tools / build-tools;36.0.0 /
  platforms;android-36），由 `local.properties` 的 `sdk.dir` 指向。
- Gradle 9.4.1：wrapper 分发走腾讯镜像 `mirrors.cloud.tencent.com/gradle`；
  Maven 依赖走阿里云 `maven.aliyun.com`（见 `settings.gradle.kts`）。
- 全局镜像：`%USERPROFILE%\.gradle\init.gradle` 已配置阿里云 Maven 优先，
  对 Android Studio 新建的项目同样生效。
- 说明：阿里云不提供 Android Studio IDE 与 Android SDK 组件镜像，
  IDE 安装包取自 Google 官方 CDN；如需在 Studio 内安装模拟器/系统镜像，
  在 SDK Manager → SDK Update Sites 添加
  `https://mirrors.cloud.tencent.com/AndroidSDK/`。

## 测试

```powershell
gradle testDebugUnitTest          # JVM 单测（数据层/坐标/搜索/格式）
gradle connectedDebugAndroidTest  # 仪器测试（需模拟器或真机）
```

## 仓库与发布纪律

- 目录归属：`android/` 专属安卓；`app/ web/ tests/` 等只归 Windows。
- 分支：`android/<feature>`；提交前缀 `android:`。
- 标签：`android-vX.Y.Z`；资产：`AnkeShelf-vX.Y.Z-android.apk`（纯 ASCII）。
- 发布前执行 `scripts/check-release.ps1` 扫描凭据。
- 详细见 [VERSIONING.md](VERSIONING.md) 与 [docs/ANDROID_ARCHITECTURE.md](../docs/ANDROID_ARCHITECTURE.md)。
