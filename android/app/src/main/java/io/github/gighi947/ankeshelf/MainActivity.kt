package io.github.gighi947.ankeshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.gighi947.ankeshelf.ui.AnkeShelfRoot
import io.github.gighi947.ankeshelf.service.AppContainer

class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as AnkeShelfApp).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnkeShelfRoot(container)
        }
    }

    // 按 Home 退到后台也立即落盘进度（防抖窗口内的最后一次位置不丢）。
    override fun onStop() {
        super.onStop()
        container.progress.flush()
    }
}
