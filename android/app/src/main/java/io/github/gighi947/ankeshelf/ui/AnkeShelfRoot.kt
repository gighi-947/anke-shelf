package io.github.gighi947.ankeshelf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.R
import io.github.gighi947.ankeshelf.ui.shelf.BookshelfScreen

/**
 * 应用外壳（M0 占位）：底部导航 + 各页面路由。
 * 正式 Navigation Compose 路由与各页面在后续里程碑接入。
 */
@Composable
fun AnkeShelfRoot() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.nav_shelf) to "📚",
        stringResource(R.string.nav_download) to "⬇️",
        stringResource(R.string.nav_search) to "🔍",
        stringResource(R.string.nav_settings) to "⚙️",
        stringResource(R.string.nav_stats) to "📊",
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Text(icon) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selected) {
                0 -> BookshelfScreen()
                else -> PlaceholderScreen(tabs[selected].first)
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
        Text(
            "该页面将在后续里程碑实现",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
