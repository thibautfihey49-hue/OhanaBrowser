package com.example.ohana
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // 🛡️ Liste des domaines publicitaires à bloquer
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 📥 Charger / Télécharger les listes de blocage
        loadBlockLists()

        setContent {
            MaterialTheme {
                Surface {
                    var url by remember { mutableStateOf("https://google.com") }
                    var progress by remember { mutableStateOf(0) }
                    var webView by remember { mutableStateOf<WebView?>(null) }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // 📊 Barre de progression
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
                            IconButton(onClick = { webView?.goBack() }) { Text("◀") }
                            IconButton(onClick = { webView?.reload() }) { Text("🔄") }
                            IconButton(onClick = { webView?.goForward() }) { Text("▶") }
                        }

                        // 🌐 WebView avec blocage pub
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true

                                    webViewClient = object : WebViewClient() {
                                        // 🛡️ BLOQUER LES PUBLICITÉS
                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest
                                        ): WebResourceResponse? {
                                            val host = request.url.host ?: return null
                                            val isAd = blockedHosts.any { host.contains(it, ignoreCase = true) }
                                            if (isAd) {
                                                return WebResourceResponse("text/plain", "utf-8", null)
                                            }
                                            return null
                                        }

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
                            update = { wv -> if (wv.url != url) wv.loadUrl(url) }
                        )
                    }
                }
            }
        }
    }

    // 📥 Télécharger et charger EasyList + EasyPrivacy
    private fun loadBlockLists() {
        Thread {
            val lists = listOf(
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist.to/easylist/easyprivacy.txt"
            )

            lists.forEach { listUrl ->
                try {
                    val req = Request.Builder().url(listUrl).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            BufferedReader(InputStreamReader(resp.body?.byteStream())).use { br ->
                                br.lineSequence()
                                    .filter { it.isNotEmpty() && !it.startsWith("!") && it.startsWith("||") }
                                    .map { it.removePrefix("||").split("^").first().trim() }
                                    .filter { it.isNotEmpty() }
                                    .forEach { blockedHosts.add(it) }
                            }
                        }
                    }
                } catch (e: Exception) { /* Ignoré si erreur réseau */ }
            }
        }.start()
    }
}
