package io.github.gighi947.ankeshelf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.Settings
import io.github.gighi947.ankeshelf.data.SettingsPatch
import kotlin.math.roundToInt

private val THEMES = listOf(
    "dark" to "深色",
    "light" to "浅色",
    "sepia" to "羊皮纸",
)

/** 设置页（M2 基础版：主题 / 字号 / 行距 / 翻页模式）。 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = remember { settings.getAll() }
    var theme by remember { mutableStateOf(data.theme) }
    var fontSize by remember { mutableIntStateOf(data.font_size) }
    var lineHeight by remember { mutableFloatStateOf(data.line_height.toFloat()) }
    var pagination by remember { mutableStateOf(data.pagination) }

    fun commit() {
        settings.update(
            SettingsPatch(
                theme = theme,
                font_size = fontSize,
                line_height = lineHeight.toDouble(),
                pagination = pagination,
            ),
        )
        onChanged()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Text("阅读主题", style = MaterialTheme.typography.titleMedium)
        THEMES.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = theme == value, onClick = { theme = value; commit() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = theme == value, onClick = { theme = value; commit() })
                Text(label)
            }
        }

        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text("正文字号：$fontSize px", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { fontSize = it.roundToInt() },
                onValueChangeFinished = { commit() },
                valueRange = 14f..28f,
                steps = 13,
            )
        }

        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text("行距：%.1f".format(lineHeight), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = lineHeight,
                onValueChange = { lineHeight = it },
                onValueChangeFinished = { commit() },
                valueRange = 1.2f..2.6f,
                steps = 13,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("分页模式", style = MaterialTheme.typography.titleMedium)
                Text(
                    "CSS 多栏翻页；横屏且宽高比合适时自动双页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = pagination,
                onCheckedChange = {
                    pagination = it
                    commit()
                },
            )
        }

        Text(
            "版本：0.1.0-debug（M2 阅读 MVP）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
