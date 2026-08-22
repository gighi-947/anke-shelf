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
