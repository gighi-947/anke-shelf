pluginManagement {
    repositories {
        // GitHub Actions 的 runner 在海外：阿里云镜像偶发 502，直接走官方仓库；
        // 本机（中国大陆）继续阿里云镜像优先。
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            // 中国大陆镜像优先，官方源兜底（AGP/Kotlin 插件等）。
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
        } else {
            // 中国大陆镜像优先，官方源兜底。
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "AnkeShelfAndroid"
include(":app")

// 注：`--write-locks` 是全局标志，执行 `resolveAndLockAll --write-locks` 时
// 会连带为 settings 项目写出 settings-gradle.lockfile，内容为
// `empty=incomingCatalogForLibs0`（版本目录内部配置，无实际外部依赖）。
// 这是 Gradle 的固有行为、无法在 settings 侧关闭，故该文件按构建噪音处理：
// 已加入 .gitignore，真正生效的锁定只有 android/app/gradle.lockfile。
