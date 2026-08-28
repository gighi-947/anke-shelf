package io.github.gighi947.ankeshelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结构性纪律测试（Harness Engineering，见 AGENTS.md）：
 * 不测功能，测“边界是否被越过”——UI 令牌、阅读器模式隔离、CI 配置、数据契约。
 * 这类测试失败时应直接指出是哪一类边界被破坏了。
 */
class DisciplineTest {

    private val repoRoot: File = run {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, ".git").exists()) d = d.parentFile
        checkNotNull(d) { "repo root not found (user.dir=${System.getProperty("user.dir")})" }
    }

    private val uiDir =
        File(repoRoot, "android/app/src/main/java/io/github/gighi947/ankeshelf/ui")

    private val allowedSpacing = setOf("0", "2", "4", "8", "12", "16", "24", "32")

    private fun uiSourceFiles(): List<File> = uiDir.walkTopDown()
        .filter {
            it.isFile && it.extension == "kt" &&
                !it.path.contains("${File.separator}theme${File.separator}")
        }
        .toList()

    /**
     * 取文件的"代码行"部分（剥掉 // 与块注释行）。
     *
     * 结构性守卫断言的是"代码里用了什么"，而注释常会解释"为什么不用某 API"
     * （例如 `不用 lockAllConfigurations()：...`）。若直接对整个文件做
     * contains，这类说明性注释会被误判为违规。所有针对配置/源码的守卫
     * 都应走本函数，避免同类误判复发。
     */
    private fun codeOnly(file: File): String = file.readLines()
        .filter {
            val s = it.trimStart()
            // 覆盖 Kotlin 与 YAML 两种注释语法（# 对 .kts 无害：行内无 # 常量断言）
            !s.startsWith("//") && !s.startsWith("*") && !s.startsWith("#")
        }
        .joinToString("\n")

    @Test
    fun `UI 圆角必须走令牌或仅为一次性小细节`() {
        val offenders = uiSourceFiles().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val m = Regex("RoundedCornerShape\\((?:topStart\\s*=\\s*)?([0-9]+(?:\\.[0-9]+)?)\\.dp")
                    .find(line)
                if (m != null && m.groupValues[1].toDouble() >= 8.0) {
                    "${f.name}:${i + 1}: $line"
                } else {
                    null
                }
            }
        }
        assertTrue(
            "非令牌大圆角（>=8dp）应使用 AnkeRadius：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `UI 间距必须使用 AnkeSpacing`() {
        val offenders = uiSourceFiles().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (!line.contains(".padding(") && !line.contains("spacedBy(")) return@mapIndexedNotNull null
                val nums = Regex("([0-9]+(?:\\.[0-9]+)?)\\.dp").findAll(line)
                    .map { it.groupValues[1] }
                    .toList()
                val bad = nums.filter { it !in allowedSpacing }
                if (bad.isNotEmpty()) {
                    "${f.name}:${i + 1}: ${bad.joinToString()} -> ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(
            "间距魔法值应使用 AnkeSpacing：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `分页保存显式 ratio=-1 且滚动保存携带 scrollRatio`() {
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()

        val pagedSaves = js.lines().filter { it.contains("callBridge('saveProgressNow'") }
        assertTrue(
            "分页 saveProgressNow 调用必须显式 ratio=-1（模式隔离）：\n" +
                pagedSaves.joinToString("\n"),
            pagedSaves.isNotEmpty() && pagedSaves.all { it.contains(", -1)") },
        )

        assertTrue(
            "滚动防抖保存必须携带 state.scrollRatio",
            js.contains("callBridge('saveProgress', state.chapterIndex, o, true, -1, -1, state.scrollRatio)"),
        )

        val model = File(
            repoRoot,
            "android/app/src/main/java/io/github/gighi947/ankeshelf/ui/reader/ProgressModel.kt",
        ).readText()
        val scrollEvent = model.substringAfter("data class Scroll(").substringBefore(") : ProgressEvent")
        val pageEvent = model.substringAfter("data class PageTurn(").substringBefore(") : ProgressEvent")
        assertFalse("Scroll 事件不得携带分页字段", scrollEvent.contains("page") || scrollEvent.contains("total"))
        assertFalse("PageTurn 事件不得携带滚动比例", pageEvent.contains("ratio"))

        for (export in listOf(
            "currentScrollState: currentScrollState",
            "geometry: geometry",
            "shouldAutoDual: shouldAutoDual",
            "buildText: TextPos.build",
        )) {
            assertTrue("reader-lite.js 缺少导出：$export", js.contains(export))
        }
    }

    @Test
    fun `空白页判定提前退出并按布局代际缓存（A2）`() {
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()

        assertTrue(
            "命中达阈值应提前判定非空白（判据数学与桌面 paged.js 同构，边界由 tests/js/paged-blank.test.js 锁定）",
            js.contains("hits >= threshold"),
        )
        assertTrue(
            "顶行命中应直接判非空白",
            js.contains("if (ry === 0) return false"),
        )
        assertTrue(
            "prepare 必须推进布局代际作废空白缓存",
            js.contains("layoutGen += 1"),
        )
        assertTrue(
            "isPageBlank 必须走布局代际缓存",
            js.contains("blankCache"),
        )
    }

    @Test
    fun `翻页与重排的 offset 采样纪律（A1 单次采样）`() {
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()

        assertTrue(
            "report 必须接受 offset 复用参数",
            js.contains("function report(doSave, offset)"),
        )
        assertTrue(
            "flipPage 必须单次采样并把结果传给 report",
            js.contains("report(true, o)"),
        )
        assertTrue(
            "doSave=false 的纯 UI 上报不得采样 offset（setMode/resize/settle 每次白付一次页顶扫描）",
            js.contains("var off = doSave ? (typeof offset === 'number' ? offset : currentOffset()) : 0;"),
        )
    }

    @Test
    fun `reader-css 不依赖 color-mix 且主题透明度走 rgb 分量变量`() {
        // 华为 WebView（HarmonyOS 4）对 color-mix 是“parser 接受、求值失败回退
        // initial”的非标准行为：!important 声明压掉内联实色兜底导致楼层卡片
        // 边框/背景全部消失（2026-08-23 真机取证）。透明度一律用
        // rgba(var(--xxx-rgb), a)（2013 年级兼容），变量成对维护见
        // ReaderHtml.kt 与 reader-lite applyTheme。
        val css = File(repoRoot, "android/app/src/main/assets/reader/reader.css").readText()
        assertFalse(
            "reader.css 不得使用 color-mix（华为内核求值失败会压掉兜底）：" +
                css.lineSequence().withIndex().filter { it.value.contains("color-mix") }.map { it.index + 1 }.joinToString(),
            css.contains("color-mix"),
        )
        assertTrue("reader.css 必须使用 --reader-fg-rgb 分量变量", css.contains("--reader-fg-rgb"))
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()
        assertTrue("applyTheme 必须成对维护 --reader-fg-rgb", js.contains("--reader-fg-rgb"))
    }

    @Test
    fun `reader-lite 状态机保持显式 phase 与统一 settle resize 入口`() {
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()

        assertTrue(
            "reader-lite.js 必须保留显式 phase 字段",
            js.contains("phase: 'bootstrapping'"),
        )
        assertTrue(
            "markSettled 必须以 phase===ready 作为唯一已就绪判断",
            js.contains("if (state.phase === 'ready') return;"),
        )
        assertTrue(
            "settle 必须统一走 requestSettle",
            js.contains("function requestSettle(offset, deadline)"),
        )
        assertTrue(
            "resize 必须统一走 scheduleResize",
            js.contains("function scheduleResize()"),
        )
        assertTrue(
            "onResize 应只转发 scheduleResize",
            js.contains("function onResize() {") && js.contains("scheduleResize();"),
        )
        assertTrue(
            "refresh 分页路径必须并入 requestSettle",
            js.contains("requestSettle(state.restorePending ? state.restoreOffset : state.pagedAnchor, 0)"),
        )

        assertFalse(
            "state.settled 已删除，不得复活",
            js.contains("state.settled") || js.contains("settled: false"),
        )
        assertFalse(
            "state.resizeScrolled 已删除（只写不读的死变量）",
            js.contains("resizeScrolled"),
        )
    }

    @Test
    fun `CI 配置只触发 android 路径且不使用弃用动作`() {
        val yml = codeOnly(File(repoRoot, ".github/workflows/android.yml"))
        assertTrue("android.yml 必须仅 android/** 触发", yml.contains("'android/**'"))
        assertFalse(
            "android.yml 不得使用弃用的 @v4 动作",
            yml.contains("actions/checkout@v4") || yml.contains("actions/upload-artifact@v4"),
        )
        assertTrue("android.yml 应使用 setup-java@v5", yml.contains("actions/setup-java@v5"))
        assertTrue("android.yml 应含 reader JS 语法检查", yml.contains("node --check"))
        assertTrue(
            "android.yml 的 run 工作目录已是 android/，bundle 脚本应使用 scripts/ 相对路径",
            yml.contains("run: node scripts/bundle-reader-lite.js"),
        )
        assertFalse(
            "android.yml 不得从 android/ 工作目录重复拼接 android/scripts",
            yml.contains("run: node android/scripts/bundle-reader-lite.js"),
        )
        // release 构建路径守卫：R8/ProGuard 规则错误此前只在发版当天暴露
        // （正式签名缺失 → release 从未在 CI 跑过）。必须保留临时签名 + assembleRelease。
        assertTrue(
            "android.yml 必须验证 release 构建（R8/ProGuard），否则规则错误只在发版当天暴露",
            yml.contains("./gradlew assembleRelease"),
        )
        assertTrue(
            "release 验证必须用一次性临时签名，不得依赖/写入正式 keystore",
            yml.contains("ci-throwaway"),
        )
        assertTrue(
            "必须校验 mapping.txt 存在且非空（证明 R8 真的跑了，而非 UP-TO-DATE 蒙混）",
            yml.contains("mapping.txt"),
        )
        assertTrue(
            "临时签名材料必须清理（if: always() 保证失败时也清）",
            yml.contains("rm -f keystore.properties ci-throwaway.jks"),
        )
    }

    @Test
    fun `依赖必须锁定以保证可复现构建`() {
        // 与 Python 侧 requirements.lock 同目标：版本目录（libs.versions.toml）
        // 只锁直接依赖，传递依赖仍会随时间漂移；gradle.lockfile 固化完整解析图。
        // 守卫效力已实测：篡改锁文件版本会令构建失败
        // （"Dependency version enforced by Dependency Locking"）。
        val lockfile = File(repoRoot, "android/app/gradle.lockfile")
        assertTrue(
            "android/app/gradle.lockfile 必须存在（升级依赖时执行 " +
                "./gradlew resolveAndLockAll --write-locks）",
            lockfile.isFile,
        )
        val content = lockfile.readText()
        assertTrue("锁文件不得为空", content.contains("="))
        // 关键第三方依赖必须被锁定，否则"锁定存在但内容缺失"变成假绿
        for (dep in listOf("org.jsoup:jsoup", "com.squareup.okhttp3")) {
            assertTrue("锁文件必须包含 $dep", content.contains(dep))
        }
        val buildGradle = codeOnly(File(repoRoot, "android/app/build.gradle.kts"))
        assertTrue(
            "app/build.gradle.kts 必须激活依赖锁定",
            buildGradle.contains("activateDependencyLocking()"),
        )
        // 噪音守卫：lockAllConfigurations() 会波及版本目录内部配置，
        // 产出 settings-gradle.lockfile（内容仅 `empty=...`）之类的无用文件。
        assertFalse(
            "不得使用 lockAllConfigurations()（会产生 empty= 噪音锁文件），改为按名锁定",
            buildGradle.contains("lockAllConfigurations()"),
        )
        // `--write-locks` 是全局标志，执行升级命令时会连带写出
        // android/settings-gradle.lockfile（内容仅 `empty=incomingCatalogForLibs0`，
        // 版本目录内部配置，无实际依赖）。无法在 settings 侧关闭，故按构建噪音处理：
        // 要求它必须被 .gitignore 覆盖，绝不能进版本库误导后来者。
        val gitignore = File(repoRoot, ".gitignore").readText()
        assertTrue(
            "settings-gradle.lockfile 必须在 .gitignore 中（`--write-locks` 的必然副产物，" +
                "无实际依赖，不得入库）",
            gitignore.lines().any { it.trim() == "android/settings-gradle.lockfile" },
        )
        assertTrue(
            "android/app/gradle.lockfile 必须入库（可复现构建的唯一事实源）",
            !gitignore.lines().any { it.trim() == "android/app/gradle.lockfile" },
        )
    }

    @Test
    fun `契约 CI 必须目录级触发而非文件白名单`() {
        // 回归背景：contracts.yml 曾用 20+ 行逐文件 paths 白名单，新增一个跨端
        // 文件而忘记往列表里加 → 守卫静默缺失。目录级触发代价是多跑一分钟，
        // 换"永不漏守卫"。
        val yml = codeOnly(File(repoRoot, ".github/workflows/contracts.yml"))
        val pathsBlock = yml.substringAfter("on:").substringBefore("jobs:")
        assertTrue(
            "contracts.yml 必须目录级触发 'app/**'（白名单式逐文件 paths 会静默失效）",
            pathsBlock.contains("'app/**'"),
        )
        assertTrue(
            "contracts.yml 必须目录级触发 'android/app/src/main/java/**'",
            pathsBlock.contains("'android/app/src/main/java/**'"),
        )
        assertFalse(
            "contracts.yml 不得回到逐文件白名单（新增跨端文件会漏守卫）",
            pathsBlock.contains("app/gululu_ast.py") || pathsBlock.contains("web/js/bridge.js"),
        )
    }

    @Test
    fun `JS 守卫测试必须被 CI 自动发现而非人工列举`() {
        // 回归背景：windows.yml 曾人工列举 3 个 JS 测试，导致 reader-save
        // （进度写入唯一出口）与 paged-blank 两个关键守卫长期漏跑。
        val yml = codeOnly(File(repoRoot, ".github/workflows/windows.yml"))
        assertTrue(
            "windows.yml 必须自动发现 tests/js/*.test.js（人工列举会漏跑守卫）",
            yml.contains("Get-ChildItem tests/js -Filter *.test.js"),
        )
        assertTrue(
            "windows.yml 必须自动发现 contracts/tests/*.test.js",
            yml.contains("Get-ChildItem contracts/tests -Filter *.test.js"),
        )
        assertFalse(
            "windows.yml 不得人工列举单个 JS 测试路径",
            yml.contains("node tests/js/reader-session.test.js") ||
                yml.contains("node contracts/tests/textpos.test.js"),
        )
        // 现役守卫文件必须存在（防止"自动发现"但文件已被误删）
        for (guard in listOf(
            "tests/js/reader-save.test.js",
            "tests/js/paged-blank.test.js",
            "tests/js/reader-session.test.js",
        )) {
            assertTrue("守卫文件缺失：$guard", File(repoRoot, guard).isFile)
        }
    }

    @Test
    fun `数据契约扩展字段缺省值向后兼容`() {
        val shelfKt =
            File(repoRoot, "android/app/src/main/java/io/github/gighi947/ankeshelf/data/Shelf.kt").readText()
        assertTrue("page_index 缺省 -1", shelfKt.contains("val page_index: Int = -1"))
        assertTrue("page_total 缺省 -1", shelfKt.contains("val page_total: Int = -1"))
        assertTrue("scroll_ratio 缺省 -1.0", shelfKt.contains("val scroll_ratio: Double = -1.0"))
    }

    @Test
    fun `阅读桥 ready 握手携带版本与能力`() {
        val js =
            File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()
        assertTrue("reader-lite.js 必须声明 BRIDGE_VERSION = 1", js.contains("var BRIDGE_VERSION = 1"))
        assertTrue(
            "ready 握手必须走结构化 JSON payload",
            js.contains("callBridge('onReady', JSON.stringify(bridgeReadyPayload()))"),
        )
        val kotlin = File(
            repoRoot,
            "android/app/src/main/java/io/github/gighi947/ankeshelf/ui/reader/BridgeProtocol.kt",
        ).readText()
        assertTrue("Kotlin 侧协议版本必须为 1", kotlin.contains("const val VERSION = 1"))
    }

    @Test
    fun `标注注入不得改变 text_offset 折叠规则`() {
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()

        // 注入节点（高亮 mark / 代码高亮 span）内部不产生分隔空格：
        // 删掉这条规则会让「注入高亮后 text_offset 整体后移」，进度与标注全部漂移。
        assertTrue(
            "reader-lite.js 必须保留注入节点识别（hl-mark / syntax）",
            js.contains("function isInjectedText(node)") &&
                js.contains("contains('hl-mark')") &&
                js.contains("contains('syntax')"),
        )
        assertTrue(
            "foldItems 必须按 isInj/noSep 决定分隔空格（与桌面 textpos.js 同规则）",
            js.contains("if (sawPrev && !(it.isInj && lastWasInj) && !it.noSep)"),
        )
        assertTrue(
            "注释分隔的相邻文本节点不得插入分隔空格",
            js.contains("function separatedByCommentOnly(a, b)"),
        )
        assertTrue(
            "桥能力必须声明 annotation（宿主据此启用标注交互）",
            js.contains("'annotation'"),
        )
        // 代码高亮同样是显示层注入（.syntax），必须在建坐标之前完成，
        // 否则首次坐标基于未高亮 DOM，注入后 ranges 全部失效。
        assertTrue(
            "代码高亮必须在 buildTextWithHighlights 内先执行",
            js.contains("function buildTextWithHighlights(payload)") &&
                js.substringAfter("function buildTextWithHighlights(payload)")
                    .substringBefore("}")
                    .contains("highlightCodeBlocks()"),
        )
        assertTrue(
            "代码高亮必须给 code 加 .syntax（折叠规则据此无缝）",
            js.contains("code.classList.add('syntax')"),
        )
        // 标注跳转必须以文本锚点/页码落盘：saveProgressNow 永远 ratio=-1，
        // 滚动比例兜底只允许出现在防抖采样路径（saveProgress）。
        assertTrue(
            "gotoTextOffset 分页分支必须显式 ratio=-1",
            js.contains(
                "callBridge('saveProgressNow', state.chapterIndex, target, true, m.current, m.total, -1)",
            ),
        )
        assertTrue(
            "gotoTextOffset 滚动分支必须显式 page/total/ratio=-1",
            js.contains(
                "callBridge('saveProgressNow', state.chapterIndex, target, true, -1, -1, -1)",
            ),
        )
    }

    @Test
    fun `骨碌碌宿主层不得改写正文与坐标`() {
        val js = File(repoRoot, "android/app/src/main/assets/reader/reader-lite.js").readText()

        // 段落评论徽标是宿主注入：必须排除出 text_offset 坐标系，
        // 否则加载评论后同一本书的进度与标注会整体漂移。
        val badgeBlock = js.substringAfter("function applyParagraphComments(payload)")
            .substringBefore("function reportGululuContext")
        assertTrue(
            "段落评论徽标必须带 data-textpos-exclude",
            badgeBlock.contains("badge.setAttribute('data-textpos-exclude', '')"),
        )
        assertTrue(
            "骰点/迷雾只切 class，不得改文本内容",
            js.contains("classList.add('masked')") &&
                js.contains("classList.remove('gululu-fog-hidden')"),
        )
        assertTrue(
            "揭示后必须上报宿主持久化（跨会话保持）",
            js.contains("callBridge('gululuUnlock', groupId)") &&
                js.contains("callBridge('gululuUnlockAll'"),
        )
        assertTrue(
            "分页模式下迷雾显隐必须触发重排（否则页码与内容错位）",
            js.contains("if (state.paged) onResize();"),
        )
        assertTrue("桥能力必须声明 gululu", js.contains("'gululu',"))

        // 秘密明文只走宿主弹窗：JS 侧只上报密文，绝不解密或写回 DOM。
        assertFalse("JS 侧不得出现解密逻辑", js.contains("decrypt") || js.contains("CryptoJS"))
        assertTrue(
            "秘密只上报标题与密文",
            js.contains("'gululuSecret',"),
        )
    }
}
