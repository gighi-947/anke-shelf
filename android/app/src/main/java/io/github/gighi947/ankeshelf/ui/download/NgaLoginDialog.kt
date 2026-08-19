package io.github.gighi947.ankeshelf.ui.download

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gighi947.ankeshelf.data.parseNgaCookieText

private const val NGA_LOGIN_HOST = "bbs.nga.cn"


private fun clearNgaWebCookies() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
}

/**
 * NGA 登录弹窗：仅允许加载 bbs.nga.cn 域，用户登录后点“完成并提取”从
 * WebView Cookie 中解析 uid/cid 回填配置页；不落日志、不保存 WebView 会话。
 */
@Composable
internal fun NgaLoginDialog(
    onDismiss: () -> Unit,
    onExtracted: (cookieText: String) -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            clearNgaWebCookies()
            onDismiss()
        },
        title = { Text("NGA 登录") },
        text = {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val host = request.url.host.orEmpty()
                                if (host.equals(NGA_LOGIN_HOST, ignoreCase = true)) {
                                    return false
                                }
                                Toast.makeText(
                                    ctx,
                                    "仅允许访问 bbs.nga.cn",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return true
                            }
                        }
                        loadUrl("https://bbs.nga.cn/")
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    val cookie = CookieManager.getInstance().getCookie("https://bbs.nga.cn").orEmpty()
                    val parsed = parseNgaCookieText(cookie)
                    if (parsed.uid.isEmpty() || parsed.cid.isEmpty()) {
                        Toast.makeText(
                            context,
                            "未检测到完整登录 Cookie，请先登录后再试",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@TextButton
                    }
                    clearNgaWebCookies()
                    onExtracted(cookie)
                },
            ) { Text("完成并提取") }
        },
        dismissButton = {
            TextButton(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    clearNgaWebCookies()
                    onDismiss()
                },
            ) { Text("取消") }
        },
    )
}
