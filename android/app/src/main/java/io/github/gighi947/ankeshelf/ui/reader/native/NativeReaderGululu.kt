package io.github.gighi947.ankeshelf.ui.reader.native

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一条展平后的公开评论（用于抽屉与弹幕；字段来自 GululuComments.commentToPublic）。 */
internal data class GululuCommentUi(
    val id: Int,
    val author: String,
    val content: String,
    val createdAt: String,
    val likes: Int,
    val paragraphId: String,
    val replyTo: String,
    val depth: Int,
)

/** 公开评论 JSON → 展平列表（子回复按深度缩进；弹幕直接复用同一份数据）。 */
internal fun flattenGululuComments(comments: List<JsonObject>, depth: Int = 0): List<GululuCommentUi> {
    val out = mutableListOf<GululuCommentUi>()
    for (comment in comments) {
        fun str(key: String) = (comment[key] as? JsonPrimitive)?.content.orEmpty()
        fun num(key: String) = (comment[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        out.add(
            GululuCommentUi(
                id = num("id"),
                author = str("author").ifEmpty { "匿名用户" },
                content = str("content"),
                createdAt = str("created_at"),
                likes = num("likes"),
                paragraphId = str("paragraph_id"),
                replyTo = str("reply_user"),
                depth = depth,
            ),
        )
        val children = (comment["children"] as? kotlinx.serialization.json.JsonArray)
            ?.filterIsInstance<JsonObject>() ?: emptyList()
        out.addAll(flattenGululuComments(children, depth + 1))
    }
    return out
}

/**
 * 骨碌碌评论抽屉（对齐桌面侧边抽屉）：当前楼评论 + 段落评论过滤 + 过期提示。
 * 只读展示（离线可读靠缓存回退），没有发言入口。
 */
@Composable
internal fun BoxScope.GululuCommentDrawer(
    visible: Boolean,
    floorId: Int,
    paragraphFilter: String,
    comments: List<GululuCommentUi>,
    stale: Boolean,
    error: String,
    onClearParagraphFilter: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
    ) {
        val shown = remember(comments, paragraphFilter) {
            if (paragraphFilter.isEmpty()) comments else comments.filter { it.paragraphId == paragraphFilter }
        }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.88f)
                .widthIn(max = 360.dp)
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(AnkeSpacing.md),
        ) {
            Text(
                if (paragraphFilter.isEmpty()) "评论 · 第 $floorId 楼" else "段落评论",
                style = MaterialTheme.typography.titleMedium,
            )
            if (paragraphFilter.isNotEmpty()) {
                TextButton(onClick = onClearParagraphFilter) { Text("显示本楼全部评论") }
            }
            if (stale) {
                Text(
                    "网络不可用，显示上次缓存的评论${if (error.isNotEmpty()) "（$error）" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (shown.isEmpty()) {
                Text(
                    if (error.isNotEmpty() && !stale) "评论加载失败：$error" else "本楼暂无评论",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AnkeSpacing.md),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = AnkeSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                items(shown, key = { it.id }) { comment ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (comment.depth.coerceAtMost(2) * 12).dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, AnkeRadius.small)
                            .padding(AnkeSpacing.sm),
                    ) {
                        Text(
                            buildString {
                                append(comment.author)
                                if (comment.replyTo.isNotEmpty()) append(" 回复 @${comment.replyTo}")
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${comment.createdAt} · 赞 ${comment.likes}" +
                                if (comment.paragraphId.isNotEmpty()) " · 段落评论" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 只读弹幕：把当前楼评论循环飘过顶部（无发言框，故不存在自评弹幕）。 */
@Composable
internal fun BoxScope.GululuDanmakuOverlay(enabled: Boolean, comments: List<GululuCommentUi>) {
    if (!enabled || comments.isEmpty()) return
    val transition = rememberInfiniteTransition(label = "danmaku")
    val shift by transition.animateFloat(
        initialValue = 1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(tween(9000), RepeatMode.Restart),
        label = "danmaku-shift",
    )
    val lines = remember(comments) { comments.take(3) }
    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = AnkeSpacing.xl),
    ) {
        lines.forEachIndexed { index, comment ->
            val offset = ((shift + index * 0.4f) % 2f).let { if (it > 1f) it - 2f else it }
            Text(
                comment.content.take(40),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(x = (offset * 260).dp)
                    .background(Color.Black.copy(alpha = 0.28f), AnkeRadius.pill)
                    .padding(horizontal = AnkeSpacing.sm, vertical = AnkeSpacing.xxs),
            )
        }
    }
}

/** 氛围背景：正文之下的淡化图片（仅无凭据 HTTPS，由 JS 侧过滤后传入）。 */
@Composable
internal fun BoxScope.GululuBackgroundOverlay(url: String) {
    if (url.isEmpty()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = 0.22f,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 视效：低成本 Compose 覆盖层（rain/snow/wind 平移淡化条纹、thunder 闪白、quake 抖动）。
 * 遵守动效纪律：只动 transform/opacity，不阻塞输入（`pointerInput` 不拦截）。
 */
@Composable
internal fun BoxScope.GululuVfxOverlay(kind: String) {
    if (kind.isEmpty() || kind == "stop") return
    val transition = rememberInfiniteTransition(label = "vfx")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (kind == "thunder") 2600 else 1400),
            RepeatMode.Restart,
        ),
        label = "vfx-phase",
    )
    val alpha = when (kind) {
        "thunder" -> if (phase > 0.92f) 0.30f else 0.0f
        "quake" -> 0.10f
        else -> 0.16f
    }
    val dx = when (kind) {
        "wind" -> (phase * 40f - 20f)
        "quake" -> ((phase * 8f) % 2f - 1f) * 4f
        else -> 0f
    }
    val dy = when (kind) {
        "rain", "snow" -> phase * 60f - 30f
        else -> 0f
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset(x = dx.dp, y = dy.dp)
            .background(Color.White.copy(alpha = alpha)),
    )
}

/** 秘密弹窗：明文只在这里出现，绝不写回正文 DOM。 */
@Composable
internal fun GululuSecretDialog(
    title: String,
    plaintext: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (title.isNotEmpty()) "秘密：$title" else "秘密") },
        text = {
            if (plaintext != null) {
                Text(plaintext, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "还没有找到能解开它的线索。继续阅读，点亮「收集线索」后再回来试试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 右下角悬浮菜单：评论 / 弹幕 / 音乐 / 总览的快捷入口。 */
@Composable
private fun BoxScope.GululuQuickMenu(
    visible: Boolean,
    barBg: Color,
    fg: Color,
    danmakuOn: Boolean,
    musicPlaying: Boolean,
    onOpenOverview: () -> Unit,
    onOpenComments: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onStopMusic: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = AnkeSpacing.md, bottom = AnkeSpacing.xxl * 6),
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = AnkeSpacing.xxl * 5)
                .background(barBg.copy(alpha = 0.98f), AnkeRadius.large)
                .border(1.dp, fg.copy(alpha = 0.16f), AnkeRadius.large)
                .padding(AnkeSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(AnkeSpacing.xxs),
        ) {
            GululuQuickMenuItem("骨碌碌总览", fg, onClick = onOpenOverview)
            GululuQuickMenuItem("评论", fg, onClick = onOpenComments)
            GululuQuickMenuItem(
                if (danmakuOn) "关闭弹幕" else "开启弹幕",
                fg,
                onClick = onToggleDanmaku,
            )
            GululuQuickMenuItem("停止音乐", fg, enabled = musicPlaying, onClick = onStopMusic)
        }
    }
}

@Composable
private fun GululuQuickMenuItem(
    label: String,
    fg: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (enabled) fg else fg.copy(alpha = 0.35f),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AnkeSpacing.md, vertical = AnkeSpacing.sm),
    )
}

/** 右下角悬浮入口（对齐桌面悬浮按钮样式）：点击展开/收起快捷菜单。 */
@Composable
internal fun BoxScope.GululuFloatingQuickButton(
    visible: Boolean,
    quickMenuOpen: Boolean,
    barBg: Color,
    fg: Color,
    danmakuOn: Boolean,
    musicPlaying: Boolean,
    onToggleQuickMenu: () -> Unit,
    onOpenOverview: () -> Unit,
    onOpenComments: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onStopMusic: () -> Unit,
) {
    GululuQuickMenu(
        visible = visible && quickMenuOpen,
        barBg = barBg,
        fg = fg,
        danmakuOn = danmakuOn,
        musicPlaying = musicPlaying,
        onOpenOverview = onOpenOverview,
        onOpenComments = onOpenComments,
        onToggleDanmaku = onToggleDanmaku,
        onStopMusic = onStopMusic,
    )
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = AnkeSpacing.md, bottom = AnkeSpacing.xxl * 4),
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        FloatingActionButton(
            onClick = onToggleQuickMenu,
            containerColor = barBg.copy(alpha = 0.96f),
            contentColor = fg,
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = "骨碌碌悬浮菜单")
        }
    }
}

/** 非骨碌碌书的音乐播放菜单：与骨碌碌悬浮菜单同款，仅含当前曲目标题与停止入口。 */
@Composable
private fun BoxScope.GululuMusicQuickMenu(
    visible: Boolean,
    barBg: Color,
    fg: Color,
    playingTitle: String,
    autoPlay: Boolean,
    onToggleAutoPlay: () -> Unit,
    onStopMusic: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = AnkeSpacing.md, bottom = AnkeSpacing.xxl * 6),
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = AnkeSpacing.xxl * 5)
                .background(barBg.copy(alpha = 0.98f), AnkeRadius.large)
                .border(1.dp, fg.copy(alpha = 0.16f), AnkeRadius.large)
                .padding(AnkeSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(AnkeSpacing.xxs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AnkeSpacing.md, vertical = AnkeSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "自动播放",
                    color = fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = autoPlay,
                    onCheckedChange = { onToggleAutoPlay() },
                )
            }
            Text(
                text = "正在播放：$playingTitle",
                color = fg.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = AnkeSpacing.xxl * 6)
                    .padding(horizontal = AnkeSpacing.md, vertical = AnkeSpacing.xs),
            )
            GululuQuickMenuItem("停止音乐", fg, onClick = onStopMusic)
        }
    }
}

/** 非骨碌碌书右下角音乐播放悬浮入口：有音乐播放时才显示。 */
@Composable
internal fun BoxScope.GululuMusicQuickButton(
    visible: Boolean,
    quickMenuOpen: Boolean,
    barBg: Color,
    fg: Color,
    playingTitle: String,
    autoPlay: Boolean,
    onToggleQuickMenu: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onStopMusic: () -> Unit,
) {
    GululuMusicQuickMenu(
        visible = visible && quickMenuOpen,
        barBg = barBg,
        fg = fg,
        playingTitle = playingTitle,
        autoPlay = autoPlay,
        onToggleAutoPlay = onToggleAutoPlay,
        onStopMusic = onStopMusic,
    )
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = AnkeSpacing.md, bottom = AnkeSpacing.xxl * 4),
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        FloatingActionButton(
            onClick = onToggleQuickMenu,
            containerColor = barBg.copy(alpha = 0.96f),
            contentColor = fg,
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = "音乐播放控件")
        }
    }
}

/** 沉浸总览：本章骨碌碌元素统计 + 批量解锁 / 重置 / 弹幕 / 音乐控制。 */
@Composable
internal fun BoxScope.GululuOverviewSheet(
    visible: Boolean,
    barBg: Color,
    fg: Color,
    groups: Int,
    lockedGroups: Int,
    secrets: Int,
    clues: Int,
    floors: Int,
    danmaku: Boolean,
    playingTitle: String,
    onRevealNext: () -> Unit,
    onResetUnlocks: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onStopMusic: () -> Unit,
    onOpenComments: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barBg.copy(alpha = 0.98f), AnkeRadius.large)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(AnkeSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "骨碌碌总览",
                        color = fg,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = fg)
                    }
                }
                Text(
                    "本章 $floors 楼 · 骰点 $groups 组（未揭示 $lockedGroups）· 秘密 $secrets · 线索 $clues",
                    color = fg.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (playingTitle.isNotEmpty()) {
                    Text(
                        "正在播放：$playingTitle",
                        color = fg.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = onRevealNext,
                    enabled = lockedGroups > 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = fg),
                ) { Text("揭示接下来 10 组") }
                TextButton(
                    onClick = onOpenComments,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = fg),
                ) { Text("评论") }
                TextButton(
                    onClick = onToggleDanmaku,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = fg),
                ) { Text(if (danmaku) "关闭弹幕" else "开启弹幕") }
                TextButton(
                    onClick = onStopMusic,
                    enabled = playingTitle.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = fg),
                ) { Text("停止音乐") }
                TextButton(
                    onClick = onResetUnlocks,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("重置解锁") }
                Text(
                    "重置只清空本书的骰点揭示与线索，不影响阅读进度与书签。",
                    color = fg.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
