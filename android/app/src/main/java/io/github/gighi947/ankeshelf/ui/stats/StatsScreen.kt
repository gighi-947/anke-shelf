package io.github.gighi947.ankeshelf.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.EnrichedStats
import io.github.gighi947.ankeshelf.data.DayEntry
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookUi
import io.github.gighi947.ankeshelf.ui.theme.formatDate
import io.github.gighi947.ankeshelf.ui.theme.formatDuration
import java.time.LocalDate

/** 阅读统计页：桌面 stats.js 弹层的安卓独立路由版（M3 卡片 + 柱状图）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    books: List<BookUi>,
    container: AppContainer,
    refreshKey: Int = 0,
    onBack: () -> Unit,
) {
    val global = remember(refreshKey) { container.stats.getGlobal() }
    val bookStats = remember(books, refreshKey) {
        books.associate { it.record.id to container.stats.getBook(it.record.id) }
    }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var tipText by remember { mutableStateOf("") }

    val current: EnrichedStats = selectedId?.let { bookStats[it] } ?: global
    val scopeLabel = selectedId?.let { id ->
        books.firstOrNull { it.record.id == id }?.record?.title ?: "未命名"
    } ?: "全部书籍"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "阅读统计",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // 范围选择（桌面 stats-toolbar：当前范围 + 下拉）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    scopeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ExposedDropdownMenuBox(
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = if (selectedId == null) "全部书籍" else "切换书目",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                        modifier = Modifier
                            .width(150.dp)
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ExposedDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部书籍") },
                            onClick = {
                                selectedId = null
                                menuExpanded = false
                            },
                        )
                        books.forEach { ui ->
                            DropdownMenuItem(
                                text = {
                                    Text(ui.record.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                onClick = {
                                    selectedId = ui.record.id
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            if (global.total_seconds <= 0 && bookStats.values.all { it.total_seconds <= 0 }) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.BarChart,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "暂无统计数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    // 8 张统计卡（桌面 stat-grid：auto-fit minmax(128px,1fr)）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatCard(formatDuration(current.total_seconds), "累计阅读", Modifier.weight(1f))
                        StatCard(formatDuration(current.today_seconds), "今日阅读", Modifier.weight(1f))
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatCard(formatDuration(current.week_seconds), "最近 7 天", Modifier.weight(1f))
                        StatCard("${current.sessions}", "阅读会话", Modifier.weight(1f))
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatCard(formatDuration(current.avg_session_seconds), "平均每次", Modifier.weight(1f))
                        StatCard("${current.pages_flipped}", "翻页次数", Modifier.weight(1f))
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatCard("${current.streak_days} 天", "连续阅读", Modifier.weight(1f))
                        StatCard(formatDate(current.last_read_at), "最近阅读", Modifier.weight(1f))
                    }
                }

                item {
                    Text(
                        "最近 7 天",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (tipText.isNotEmpty()) {
                                Text(
                                    tipText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            WeekChart(current.days, onTip = { tipText = it })
                        }
                    }
                }

                item {
                    Text(
                        "最近阅读书目",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (bookStats.values.none { it.total_seconds > 0 }) {
                    item {
                        Text(
                            "暂无阅读记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(books.filter { (bookStats[it.record.id]?.total_seconds ?: 0) > 0 }) { ui ->
                                BookStatCard(
                                    ui = ui,
                                    stats = bookStats[ui.record.id] ?: EnrichedStats(),
                                    selected = ui.record.id == selectedId,
                                    onClick = {
                                        selectedId = if (ui.record.id == selectedId) null else ui.record.id
                                    },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 最近 7 天柱状图（对齐桌面 stats-days：primary 柱、MM-DD 标签、点按显示时长）。 */
@Composable
private fun WeekChart(days: Map<String, DayEntry>, onTip: (String) -> Unit) {
    val now = LocalDate.now()
    val rows = (6 downTo 0).map { d ->
        val date = now.minusDays(d.toLong())
        val key = date.toString()
        key to (days[key]?.seconds ?: 0)
    }
    val max = maxOf(1, rows.maxOf { it.second })
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        rows.forEachIndexed { i, (key, secs) ->
            val fraction = if (secs > 0) secs.toFloat() / max else 0f
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(400),
                label = "bar$i",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        onTip(
                            if (secs > 0) {
                                "${key.substring(5).replace('-', '/')} · ${formatDuration(secs)}"
                            } else {
                                "${key.substring(5).replace('-', '/')} · 0 秒"
                            },
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    key.substring(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .fillMaxHeight(animated.coerceIn(0f, 1f))
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookStatCard(
    ui: BookUi,
    stats: EnrichedStats,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .width(180.dp)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                formatDuration(stats.total_seconds),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            )
            Text(
                ui.record.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val parts = buildList {
                if (ui.record.author.isNotBlank()) add(ui.record.author)
                if (stats.sessions > 0) add("${stats.sessions} 次会话")
                if (stats.last_read_at.isNotBlank()) add("最近 ${formatDate(stats.last_read_at)}")
            }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
