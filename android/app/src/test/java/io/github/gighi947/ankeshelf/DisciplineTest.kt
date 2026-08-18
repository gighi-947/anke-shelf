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
        val yml = File(repoRoot, ".github/workflows/android.yml").readText()
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
}
