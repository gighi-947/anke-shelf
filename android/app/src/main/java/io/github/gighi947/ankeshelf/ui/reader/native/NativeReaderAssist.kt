package io.github.gighi947.ankeshelf.ui.reader.native

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.ui.reader.RsvpTokenizer
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 阅读标尺：一条可上下拖动的横线（桌面用鼠标 mousemove 跟随，触屏改为拖动定位）。
 * 纯覆盖层，不进入正文 DOM，因此与 text_offset 无关。
 */
@Composable
internal fun BoxScope.ReaderRulerOverlay(visible: Boolean, fg: Color) {
    if (!visible) return
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var y by remember { mutableStateOf(screenHeight / 2) }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .offset(y = y)
            .height(AnkeSpacing.xl)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    val delta = with(density) { dragAmount.toDp() }
                    y = (y + delta).coerceIn(0.dp, screenHeight - AnkeSpacing.xl)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(fg.copy(alpha = 0.55f)),
        )
    }
}

/**
 * 速读（RSVP）覆盖层：从当前阅读位置起逐块闪现，可暂停/调速/关闭。
 * 全在宿主层完成（分词与计时都在 Compose），不改正文与阅读进度。
 */
@Composable
internal fun BoxScope.ReaderRsvpOverlay(
    plainText: String,
    fromOffset: Int,
    rate: Int,
    barBg: Color,
    fg: Color,
    onRateChange: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = remember(plainText, fromOffset) {
        RsvpTokenizer.tokenize(plainText, from = fromOffset)
    }
    var index by remember(tokens) { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(tokens, running, rate) {
        if (!running) return@LaunchedEffect
        val interval = RsvpTokenizer.intervalMs(rate)
        while (index < tokens.size) {
            delay(interval)
            if (index >= tokens.lastIndex) {
                running = false
                break
            }
            index++
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(horizontal = AnkeSpacing.lg)
            .background(barBg.copy(alpha = 0.97f), AnkeRadius.large)
            .border(1.dp, fg.copy(alpha = 0.16f), AnkeRadius.large)
            .padding(AnkeSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            tokens.getOrNull(index).orEmpty().ifEmpty { "（本章已读完）" },
            color = fg,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Text(
            "${(index + 1).coerceAtMost(tokens.size)} / ${tokens.size} · $rate 字/分",
            color = fg.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = AnkeSpacing.sm),
        )
        Row(
            modifier = Modifier.padding(top = AnkeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
        ) {
            IconButton(onClick = { onRateChange((rate - 60).coerceAtLeast(60)) }) {
                Text("－", color = fg, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = { running = !running }) {
                Icon(
                    if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (running) "暂停" else "继续",
                    tint = fg,
                )
            }
            IconButton(onClick = { onRateChange((rate + 60).coerceAtMost(720)) }) {
                Text("＋", color = fg, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭速读", tint = fg)
            }
        }
    }
}

/**
 * 阅读辅助面板（对齐桌面 ViewMenu 的辅助区）：自动滚动 + 速读 + 标尺 + 按书字体。
 * 从底栏进入，点击遮罩关闭。
 */
@Composable
internal fun BoxScope.ReaderAssistSheet(
    visible: Boolean,
    barBg: Color,
    fg: Color,
    autoScroll: Boolean,
    autoScrollSpeed: Double,
    rulerOn: Boolean,
    rsvpOn: Boolean,
    fonts: List<String>,
    bookFont: String,
    onToggleAutoScroll: (Boolean) -> Unit,
    onSpeedChange: (Double) -> Unit,
    onToggleRuler: (Boolean) -> Unit,
    onToggleRsvp: (Boolean) -> Unit,
    onBookFontChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(barBg.copy(alpha = 0.98f), AnkeRadius.large)
            .navigationBarsPadding()
            .padding(AnkeSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
    ) {
        Text("阅读辅助", color = fg, style = MaterialTheme.typography.titleMedium)

        AssistSwitchRow(
            label = "自动滚动",
            detail = "滚动模式匀速推进，分页模式自动翻页；到章尾自动进入下一章",
            checked = autoScroll,
            fg = fg,
            onCheckedChange = onToggleAutoScroll,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "速度 ${"%.1f".format(autoScrollSpeed)}×",
                color = fg.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = autoScrollSpeed.toFloat(),
                onValueChange = { onSpeedChange(it.toDouble()) },
                valueRange = 0.5f..6f,
                modifier = Modifier.padding(start = AnkeSpacing.sm),
            )
        }

        AssistSwitchRow(
            label = "速读（RSVP）",
            detail = "从当前位置逐词闪现，可暂停与调速",
            checked = rsvpOn,
            fg = fg,
            onCheckedChange = onToggleRsvp,
        )
        AssistSwitchRow(
            label = "阅读标尺",
            detail = "一条可拖动的横线，帮助定位当前行",
            checked = rulerOn,
            fg = fg,
            onCheckedChange = onToggleRuler,
        )

        Text(
            "本书字体",
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = AnkeSpacing.sm),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm)) {
            item {
                FilterChip(
                    selected = bookFont.isBlank(),
                    onClick = { onBookFontChange("") },
                    label = { Text("跟随全局") },
                )
            }
            items(fonts) { name ->
                FilterChip(
                    selected = bookFont == name,
                    onClick = { onBookFontChange(name) },
                    label = { Text(fontLabel(name)) },
                )
            }
        }
    }
}

private fun fontLabel(name: String): String = when {
    name == "system" -> "系统默认"
    name.startsWith("sys:") -> "内置霞鹜文楷"
    else -> name.substringBeforeLast('.')
}

@Composable
private fun AssistSwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    fg: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = AnkeSpacing.sm)) {
            Text(label, color = fg, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                color = fg.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(modifier = Modifier.size(AnkeSpacing.xxs))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = AnkeSpacing.sm),
        )
    }
}
