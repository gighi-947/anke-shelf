package io.github.gighi947.ankeshelf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.gighi947.ankeshelf.ui.AnkeShelfRoot
import io.github.gighi947.ankeshelf.ui.theme.AnkeShelfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnkeShelfTheme {
                AnkeShelfRoot()
            }
        }
    }
}
