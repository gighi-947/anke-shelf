package io.github.gighi947.ankeshelf.ui.reader.native

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookSession
import io.github.gighi947.ankeshelf.service.ngaHeaders
import io.github.gighi947.ankeshelf.ui.reader.extractReaderParts
import io.github.gighi947.ankeshelf.ui.reader.buildReaderHtml
import io.github.gighi947.ankeshelf.ui.reader.ChapterProgressTracker
import io.github.gighi947.ankeshelf.ui.reader.WebViewChapterView
import io.github.gighi947.ankeshelf.ui.reader.WebViewReaderCallbacks
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.readerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.Request
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.text.style.TextAlign
import io.github.gighi947.ankeshelf.data.SpineItem
internal val THEME_CYCLE = listOf("dark", "light", "sepia")

@Composable
internal fun themeColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** 亮度遮罩：阅读器外层黑色覆盖。 */
@Composable
internal fun ReaderBrightnessOverlay(brightness: Double) {
    if (brightness > 0.0) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = brightness.toFloat().coerceIn(0f, 0.7f))),
        )
    }
}

/** 顶部浮动栏：返回 / 章节标题 / 目录。 */
@Composable
internal fun BoxScope.ReaderTopBar(
    visible: Boolean,
    title: String,
    barBg: Color,
    fg: Color,
    onBack: () -> Unit,
    onToggleToc: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg.copy(alpha = 0.96f))
                .statusBarsPadding()
                .padding(horizontal = AnkeSpacing.xs, vertical = AnkeSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = fg)
            }
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = AnkeSpacing.sm),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onToggleToc) {
                Icon(Icons.Filled.Menu, contentDescription = "目录", tint = fg)
            }
        }
    }
}

/** 底部浮动栏：换章 / 字号 / 主题 / 分页切换与进度指示。 */
@Composable
internal fun BoxScope.ReaderBottomBar(
    visible: Boolean,
    barBg: Color,
    fg: Color,
    theme: String,
    pagination: Boolean,
    pageInfo: Pair<Int, Int>,
    scrollRatio: Float,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onFontDec: () -> Unit,
    onFontInc: () -> Unit,
    onThemeChange: (String) -> Unit,
    onTogglePagination: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(horizontal = AnkeSpacing.xs, vertical = AnkeSpacing.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevChapter) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一章", tint = fg)
                }
                IconButton(onClick = onFontDec) {
                    Icon(Icons.Filled.Remove, contentDescription = "减小字号", tint = fg)
                }
                IconButton(onClick = {
                    val next = THEME_CYCLE[(THEME_CYCLE.indexOf(theme) + 1) % THEME_CYCLE.size]
                    onThemeChange(next)
                }) {
                    Icon(Icons.Filled.Palette, contentDescription = "切换主题", tint = fg)
                }
                IconButton(onClick = onFontInc) {
                    Icon(Icons.Filled.Add, contentDescription = "增大字号", tint = fg)
                }
                IconButton(onClick = onNextChapter) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一章", tint = fg)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePagination) {
                    Icon(
                        if (pagination) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewModule,
                        contentDescription = if (pagination) "切换滚动模式" else "切换分页模式",
                        tint = fg,
                    )
                }
                Text(
                    if (pagination && pageInfo.second > 0) {
                        "第 ${pageInfo.first + 1} / ${pageInfo.second} 页"
                    } else {
                        "${(scrollRatio * 100).roundToInt()}%"
                    },
                    modifier = Modifier.weight(1f).padding(end = AnkeSpacing.md),
                    color = fg,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** 目录弹层：遮罩 + 右侧抽屉。 */
@Composable
internal fun BoxScope.ReaderTocDrawer(
    visible: Boolean,
    chapters: List<SpineItem>,
    currentChapter: Int,
    titleFn: (Int) -> String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                .widthIn(max = 280.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(AnkeSpacing.md),
        ) {
            Text("目录", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(chapters) { i, _ ->
                    TextButton(onClick = { onSelect(i) }) {
                        Text(
                            titleFn(i),
                            color = if (i == currentChapter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 图片查看遮罩：长按打开、双击缩放、保存/关闭。 */
@Composable
internal fun ReaderLightbox(
    src: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    imageBytes: suspend (String) -> ByteArray?,
) {
    var zoom by remember(src) { mutableFloatStateOf(1f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* 点空白不退出，防误触 */ },
    ) {
        NativeLightboxImage(
            src = src,
            imageBytes = imageBytes,
            zoom = zoom,
            onZoom = { zoom = it },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AnkeSpacing.sm),
        ) {
            IconButton(onClick = onSave) {
                Icon(Icons.Filled.FileDownload, contentDescription = "保存", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

@Composable
internal fun NativeLightboxImage(
    src: String,
    imageBytes: suspend (String) -> ByteArray?,
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, src) {
        value = withContext(Dispatchers.IO) {
            imageBytes(src)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val bmp = bitmap
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onZoom(if (zoom == 1f) 2f else 1f) },
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("图片加载中…", color = Color.White.copy(alpha = 0.7f))
        }
    }
}
