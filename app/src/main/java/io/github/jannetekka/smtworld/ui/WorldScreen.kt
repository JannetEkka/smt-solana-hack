package io.github.jannetekka.smtworld.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jannetekka.smtworld.smt.SmtFeed

/** The existing SMT World web app (personas talking, the islands), reused as one tab. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WorldScreen(openUrl: (String) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canBack) { web?.goBack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Note("SMT World, live: the same site as on the web", modifier = Modifier.weight(1f))
            TextButton(onClick = { web?.reload() }, modifier = Modifier.focusRing()) { Text("Reload") }
            TextButton(onClick = { openUrl(SmtFeed.SITE) }, modifier = Modifier.focusRing()) { Text("Open in browser") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            canBack = view?.canGoBack() == true
                        }
                    }
                    loadUrl(SmtFeed.SITE)
                    web = this
                }
            },
        )
    }
}
