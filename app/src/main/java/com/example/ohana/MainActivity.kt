package com.example.ohana
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var url by remember { mutableStateOf("https://google.com") }
                    var progress by remember { mutableStateOf(0) }
                    var webView by remember { mutableStateOf<WebView?>(null) }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // 📊 Barre de progression (version compatible)
                        if (progress in 1..99) {
                            LinearProgressIndicator(
                                progress = progress / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 🔗 Barre d'adresse
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            singleLine = true,
                            trailingIcon = {
                                TextButton(onClick = { webView?.loadUrl(url) }) {
                                    Text("Aller")
                                }
                            }
                        )

                        // ⏮️ Boutons de navigation
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = { webView?.goBack() }) {
                                Text("◀")
                            }
                            IconButton(onClick = { webView?.reload() }) {
                                Text("🔄")
                            }
                            IconButton(onClick = { webView?.goForward() }) {
                                Text("▶")
                            }
                        }

                        // 🌐 WebView
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                            url?.let { view?.loadUrl(it) }
                                            return false
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            super.onProgressChanged(view, newProgress)
                                            progress = newProgress
                                        }
                                    }
                                    webView = this
                                }
                            },
                            modifier = Modifier.weight(1f),
                            update = { wv ->
                                if (wv.url != url) wv.loadUrl(url)
                            }
                        )
                    }
                }
            }
        }
    }
}
