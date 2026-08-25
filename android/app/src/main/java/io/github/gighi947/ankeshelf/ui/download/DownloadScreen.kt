package io.github.gighi947.ankeshelf.ui.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.NgaConfigPatch
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.SettingsPatch
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.NgaDownloadService
import io.github.gighi947.ankeshelf.service.NgaExport
import io.github.gighi947.ankeshelf.service.NgaServiceStatus
import io.github.gighi947.ankeshelf.service.safeExportName
import io.github.gighi947.ankeshelf.ui.components.ActionIcon
import io.github.gighi947.ankeshelf.ui.components.BookManagementOverlay
import io.github.gighi947.ankeshelf.ui.components.NgaUpdateDialog
import io.github.gighi947.ankeshelf.ui.components.launchNgaUpdate
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class DownloadTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val summary: String,
)

private val DOWNLOAD_TABS = listOf(
    DownloadTab("config", "登录配置", Icons.Filled.Key, "NGA Cookie 凭据，仅存本机"),
    DownloadTab("download", "下载", Icons.Filled.FileDownload, "NGA 帖子 / 骨碌碌公开书籍，统一入口"),
    DownloadTab("library", "已下载", Icons.Filled.FolderOpen, "导出 EPUB/Markdown 与更新"),
    DownloadTab("floor_export", "楼层导出", Icons.Filled.IosShare, "按楼层导出 PNG/WebP，便于分享与补档"),
)

/**
 * NGA 下载页（参照设置页：手机一二级菜单 + 平板主从布局）。
 * - 手机：一级分组列表（登录配置 / 下载·更新 / 已下载），点入二级详情；
 * - 平板/横屏（宽度 ≥600dp）：左侧 NavigationRail + 右侧详情面板；
 * - 所有层级页头都带返回键（一级返回书架，二级返回一级）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    container: AppContainer,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var group by remember { mutableStateOf<String?>(null) }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    // 二级详情页：系统返回/侧滑返回先回下载一级菜单；一级菜单由 Root 处理（回书架）。
    BackHandler(enabled = group != null) { group = null }

    if (isTablet) {
        val active = group ?: DOWNLOAD_TABS.first().id
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { PageHeaderTitle("安科下载") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        DOWNLOAD_TABS.forEach { tab ->
                            NavigationRailItem(
                                selected = active == tab.id,
                                onClick = { group = tab.id },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                VerticalDivider()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    DownloadGroupContent(active, container, onChanged)
                }
            }
        }
    } else {
        if (group == null) {
            DownloadGroupList(onBack = onBack, onSelect = { group = it })
        } else {
            val tab = DOWNLOAD_TABS.first { it.id == group }
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { PageHeaderTitle(tab.label) },
                        navigationIcon = {
                            IconButton(onClick = { group = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    DownloadGroupContent(tab.id, container, onChanged)
                }
            }
        }
    }
}

/** 手机一级菜单：下载分组列表（仿系统设置）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadGroupList(onBack: () -> Unit, onSelect: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { PageHeaderTitle("安科下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = AnkeSpacing.xs),
        ) {
            items(DOWNLOAD_TABS) { tab ->
                ListItem(
                    headlineContent = {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    supportingContent = {
                        Text(
                            tab.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    tab.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(AnkeSpacing.sm),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = AnkeSpacing.xs)
                        .clickable { onSelect(tab.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadGroupContent(
    groupId: String,
    container: AppContainer,
    onChanged: () -> Unit,
) {
    when (groupId) {
        "config" -> ConfigPanel(container)
        "download" -> UnifiedDownloadPanel(container, onChanged)
        "library" -> LibraryPanel(container, onChanged)
        "floor_export" -> FloorExportPanel(container, onChanged)
    }
}

/** 统一下载面板：同一入口内切换 NGA / 骨碌碌来源，不再拆成两个一级菜单。 */
@Composable
private fun UnifiedDownloadPanel(
    container: AppContainer,
    onChanged: () -> Unit,
) {
    var source by remember { mutableStateOf("nga") }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AnkeSpacing.lg, vertical = AnkeSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
        ) {
            FilterChip(
                selected = source == "nga",
                onClick = { source = "nga" },
                label = { Text("NGA 帖子") },
            )
            FilterChip(
                selected = source == "gululu",
                onClick = { source = "gululu" },
                label = { Text("骨碌碌") },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (source) {
                "nga" -> DownloadPanel(container, onChanged)
                else -> GululuPanel(container, onChanged)
            }
        }
    }
}

/* ---------------- 通用 ---------------- */

@Composable
internal fun DownloadList(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AnkeSpacing.lg,
            end = AnkeSpacing.lg,
            top = AnkeSpacing.md,
            bottom = AnkeSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.md),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun DownloadSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(AnkeSpacing.lg)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.padding(top = AnkeSpacing.md)) {
                content()
            }
        }
    }
}
