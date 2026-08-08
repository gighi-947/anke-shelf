package io.github.gighi947.ankeshelf.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.data.SettingsPatch
import kotlin.math.roundToInt

private val THEME_CYCLE = listOf("dark", "light", "sepia")

/** 底部控制条（独立子组合：进度/页码状态只重组此子树，不连累整个阅读器）。 */
@Composable
fun BoxScope.ReaderBottomBar(
    barsVisible: Boolean,
    readerSettings: SettingsData,
    pageInfo: PageInfo,
    scrollRatio: Float,
    onChapter: (Int) -> Unit,
    onSettingsPatch: (SettingsPatch) -> Unit,
) {
    AnimatedVisibility(
        visible = barsVisible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            val fraction = if (readerSettings.pagination && pageInfo.total > 0) {
                (pageInfo.page + 1f) / pageInfo.total
            } else {
                scrollRatio.coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onChapter(-1) }) { Text("上一章") }
                TextButton(onClick = {
                    onSettingsPatch(
                        SettingsPatch(font_size = (readerSettings.font_size - 1).coerceAtLeast(14)),
                    )
                }) { Text("A-") }
                TextButton(onClick = {
                    val next = THEME_CYCLE[(THEME_CYCLE.indexOf(readerSettings.theme) + 1) % THEME_CYCLE.size]
                    onSettingsPatch(SettingsPatch(theme = next))
                }) { Text("主题") }
                TextButton(onClick = {
                    onSettingsPatch(
                        SettingsPatch(font_size = (readerSettings.font_size + 1).coerceAtMost(28)),
                    )
                }) { Text("A+") }
                TextButton(onClick = { onChapter(1) }) { Text("下一章") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onSettingsPatch(SettingsPatch(pagination = !readerSettings.pagination))
                }) {
                    Text(
                        // 显示当前翻页模式；点击切换为另一种。
                        text = if (readerSettings.pagination) "分页" else "滚动",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    text = if (readerSettings.pagination && pageInfo.total > 0) {
                        "第 ${pageInfo.page + 1} / ${pageInfo.total} 页"
                    } else {
                        "${(scrollRatio * 100).roundToInt()}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
            }
        }
    }
}
