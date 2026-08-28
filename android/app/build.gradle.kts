import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.gighi947.ankeshelf"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gighi947.ankeshelf"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
            // CI/无签名环境不生成 release 签名配置：keystore.properties 不入库，
            // 缺失时留空（debug 构建不受影响，release 构建会提示缺少签名文件）。
            storeFile = props.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式签名在 M5 发布 SOP 中配置（android/keystore/，不入库）。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 依赖锁定：与 Python 侧 requirements.lock 同目标——同一提交可复现构建。
    // 版本目录（libs.versions.toml）只锁直接依赖，传递依赖仍会随时间漂移；
    // gradle.lockfile 固化完整解析图（含传递依赖）。
    //
    // 按名锁定"参与编译/运行/测试"的入向依赖配置，不用 lockAllConfigurations()：
    // 后者会波及 incomingCatalogForLibs 等版本目录内部配置，产出
    // settings-gradle.lockfile（内容仅 `empty=...`）之类的噪音文件。
    // 守卫效力已验证：篡改锁文件版本会令构建失败
    // （"Dependency version enforced by Dependency Locking"）。
    configurations.configureEach {
        if (isCanBeResolved && name.matches(Regex("(?:debug|release)(?:UnitTest|AndroidTest)?(?:Compile|Runtime)Classpath"))) {
            resolutionStrategy.activateDependencyLocking()
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // reader-lite.parts 仅作模块化源码（构建/CI 合并校验），不进 APK。
    androidResources {
        ignoreAssetsPattern = "reader-lite.parts:"
    }

    // 内置字体 canonical 源：仓库根 assets/fonts（双端共享单一副本，构建时并入 APK assets）。
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../assets")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.jvm)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// 生成/更新锁文件的显式入口：./gradlew resolveAndLockAll --write-locks
// （日常构建不写锁，保证 CI 上"锁定文件与解析图不符即失败"的守卫有效）
//
// 只解析"入向依赖"配置：AGP 产生的 *ApiElements / *RuntimeElements 等出向
// 变体配置虽 isCanBeResolved=true，但直接 resolve() 会因变体歧义失败
// （AGP 9 的 merged-test-only-native-libs 等多变体），它们也不携带外部依赖。
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("resolveAndLockAll 需要解析全部依赖配置")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "必须带 --write-locks：./gradlew resolveAndLockAll --write-locks"
        }
    }
    doLast {
        val lockable = configurations.filter {
            it.isCanBeResolved && !it.name.endsWith("Elements", ignoreCase = true)
        }
        lockable.forEach { config ->
            runCatching { config.resolve() }
                .onFailure { logger.lifecycle("skip ${config.name}: ${it.message?.lineSequence()?.first()}") }
        }
        logger.lifecycle("resolved ${lockable.size} configurations into lockfile")
    }
}
