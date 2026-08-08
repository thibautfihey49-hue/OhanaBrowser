package com.example.ohana

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class BrowserEntry(
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followSslRedirects(true)
            .build()
    }

    private var pendingVideoUrl: String? = null
    private lateinit var downloadManager: DownloadManager
    private var castContext: CastContext? = null
    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) pendingVideoUrl?.let { downloadVideo(it) }
        else Toast.makeText(this, "Permission stockage refusée", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("OhanaBrowser", Context.MODE_PRIVATE)
        downloadManager = getSystemService()!!

        // ⚡ Initialisation DIFFÉRÉE : ne ralentit PAS le démarrage
        android.os.Handler(mainLooper).postDelayed({
            try { castContext = CastContext.getSharedInstance(this) } catch (_: Exception) {}
            loadBlockLists()
        }, 800) // Charge après l'affichage → démarrage instantané

        setContent {
            MaterialTheme {
                Surface {
                    var isPrivateMode by remember { mutableStateOf(false) }
                    var url by remember { mutableStateOf("about:blank") }
                    var progress by remember { mutableStateOf(0) }
                    var webView by remember { mutableStateOf<WebView?>(null) }
                    var showHistory by remember { mutableStateOf(false) }
                    var showFavorites by remember { mutableStateOf(false) }
                    var history by remember { mutableStateOf(loadHistory()) }
                    var favorites by remember { mutableStateOf(loadFavorites()) }

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (isPrivateMode) {
                            Surface(color = Color(0xFF662222), modifier = Modifier.fillMaxWidth()) {
                                Text(" 🔒 NAVIGATION PRIVÉE — Aucune donnée sauvegardée",
                                    color = Color.White, modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (progress in 1..99) {
                            LinearProgressIndicator(
                                progress = progress / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            singleLine = true,
                            trailingIcon = {
                                TextButton(onClick = {
                                    if (url.isNotBlank() && !url.startsWith("http")) {
                                        url = "https://$url"
                                    }
                                    webView?.loadUrl(url)
                                }) { Text("Aller") }
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(2.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = { webView?.goBack() }) { Text("◀") }
                            IconButton(onClick = { webView?.reload() }) { Text("🔄") }
                            IconButton(onClick = { webView?.goForward() }) { Text("▶") }
                            IconButton(onClick = { showHistory = !showHistory }) { Text("📜") }
                            IconButton(onClick = {
                                val u = webView?.url ?: return@IconButton
                                if (u != "about:blank") {
                                    toggleFavorite(u, webView?.title ?: u, favorites)
                                    favorites = loadFavorites()
                                }
                            }) { Text("⭐") }
                            IconButton(onClick = { showFavorites = !showFavorites }) { Text("📂") }
                            IconButton(onClick = {
                                webView?.let { wv ->
                                    val u = wv.url ?: return@IconButton
                                    if (u != "about:blank") detectAndDownloadVideo(u)
                                }
                            }) { Text("📥") }
                            IconButton(onClick = {
                                webView?.let { wv ->
                                    val u = wv.url ?: return@IconButton
                                    if (u != "about:blank") castVideo(u)
                                }
                            }) { Text("📺") }
                            IconButton(onClick = {
                                isPrivateMode = !isPrivateMode
                                if (isPrivateMode) clearWebData()
                                webView?.clearHistory()
                                url = "about:blank"
                                webView?.loadUrl(url)
                            }) { Text(if (isPrivateMode) "🔴" else "🔒") }
                        }

                        if (showHistory && !isPrivateMode) {
                            Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("📜 Historique", fontWeight = FontWeight.Bold)
                                        TextButton(onClick = { showHistory = false }) { Text("✕") }
                                    }
                                    LazyColumn {
                                        items(history) { entry ->
                                            Column(Modifier.clickable {
                                                url = entry.url
                                                webView?.loadUrl(entry.url)
                                                showHistory = false
                                            }.padding(8.dp)) {
                                                Text(entry.title, fontWeight = FontWeight.Medium)
                                                Text(
                                                    "${dateFormat.format(Date(entry.timestamp))} · ${entry.url.take(40)}...",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (showFavorites) {
                            Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("⭐ Favoris", fontWeight = FontWeight.Bold)
                                        TextButton(onClick = { showFavorites = false }) { Text("✕") }
                                    }
                                    LazyColumn {
                                        items(favorites) { entry ->
                                            Column(Modifier.clickable {
                                                url = entry.url
                                                webView?.loadUrl(entry.url)
                                                showFavorites = false
                                            }.padding(8.dp)) {
                                                Text(entry.title, fontWeight = FontWeight.Medium)
                                                Text(entry.url, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!showHistory && !showFavorites) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.mediaPlaybackRequiresUserGesture = false
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                                        webViewClient = object : WebViewClient() {
                                            override fun shouldInterceptRequest(
                                                view: WebView?,
                                                request: WebResourceRequest?
                                            ): WebResourceResponse? {
                                                val host = request?.url?.host ?: return null
                                                if (blockedHosts.any { host.contains(it, true) }) {
                                                    return WebResourceResponse("text/plain", "utf-8", null)
                                                }
                                                return null
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                if (!isPrivateMode && !url.isNullOrBlank() && url != "about:blank") {
                                                    addToHistory(url, view?.title ?: url)
                                                    history = loadHistory()
                                                }
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
                                    if (wv.url != url && url.isNotBlank()) {
                                        wv.loadUrl(url)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun clearWebData() {
        CookieManager.getInstance().removeAllCookies {}
        CookieManager.getInstance().flush()
    }

    private fun loadHistory(): List<BrowserEntry> {
        val urls = prefs.getStringSet("history_urls", emptySet()) ?: emptySet()
        val titles = prefs.getStringSet("history_titles", emptySet()) ?: emptySet()
        val times = prefs.getStringSet("history_times", emptySet()) ?: emptySet()
        return urls.zip(titles).zip(times).map {
            BrowserEntry(it.first.first, it.first.second, it.second.toLongOrNull() ?: 0L)
        }.sortedByDescending { it.timestamp }
    }

    private fun addToHistory(url: String, title: String) {
        val history = loadHistory().toMutableList()
        history.removeAll { it.url == url }
        history.add(0, BrowserEntry(url, title))
        if (history.size > 100) history.removeLast()
        prefs.edit()
            .putStringSet("history_urls", history.map { it.url }.toMutableSet())
            .putStringSet("history_titles", history.map { it.title }.toMutableSet())
            .putStringSet("history_times", history.map { it.timestamp.toString() }.toMutableSet())
            .apply()
    }

    private fun loadFavorites(): List<BrowserEntry> {
        val urls = prefs.getStringSet("fav_urls", emptySet()) ?: emptySet()
        val titles = prefs.getStringSet("fav_titles", emptySet()) ?: emptySet()
        return urls.zip(titles).map { BrowserEntry(it.first, it.second) }.sortedBy { it.title }
    }

    private fun toggleFavorite(url: String, title: String, currentFavs: List<BrowserEntry>) {
        val favs = currentFavs.toMutableList()
        val existing = favs.find { it.url == url }
        if (existing != null) {
            favs.remove(existing)
            Toast.makeText(this, "⭐ Retiré des favoris", Toast.LENGTH_SHORT).show()
        } else {
            favs.add(BrowserEntry(url, title))
            Toast.makeText(this, "⭐ Ajouté aux favoris !", Toast.LENGTH_SHORT).show()
        }
        prefs.edit()
            .putStringSet("fav_urls", favs.map { it.url }.toMutableSet())
            .putStringSet("fav_titles", favs.map { it.title }.toMutableSet())
            .apply()
    }

    private fun castVideo(pageUrl: String) {
        val exts = listOf(".mp4", ".webm", ".m3u8", ".mkv", ".avi")
        val isVideo = exts.any { pageUrl.contains(it, true) }
        if (castContext == null) {
            Toast.makeText(this, "📺 Cast non disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (isVideo) {
            Toast.makeText(this, "📺 Vidéo détectée — lancement sur appareil…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Pas de lien vidéo direct", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectAndDownloadVideo(pageUrl: String) {
        val exts = listOf(".mp4", ".webm", ".m3u8", ".mkv", ".avi")
        val isVideo = exts.any { pageUrl.contains(it, true) }
        if (isVideo) {
            pendingVideoUrl = pageUrl
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> downloadVideo(pageUrl)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> downloadVideo(pageUrl)
                else -> permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            Toast.makeText(this, "⚠️ Pas de lien vidéo direct", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadVideo(videoUrl: String) {
        try {
            val uri = Uri.parse(videoUrl)
            val req = DownloadManager.Request(uri).apply {
                setTitle("Vidéo — ${System.currentTimeMillis()}")
                setDescription("Téléchargé depuis Ohana Browser")
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Ohana_${uri.lastPathSegment}")
            }
            downloadManager.enqueue(req)
            Toast.makeText(this, "📥 Téléchargement lancé !", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur : ${e.message}", Toast.LENGTH_SHORT).show()
        }
        pendingVideoUrl = null
    }

    private fun loadBlockLists() {
        Thread {
            val lists = listOf(
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist.to/easylist/easyprivacy.txt"
            )
            lists.forEach { url ->
                try {
                    val req = Request.Builder().url(url).build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful && resp.body != null) {
                            BufferedReader(InputStreamReader(resp.body!!.byteStream())).use { br ->
                                br.lineSequence()
                                    .filter { it.isNotEmpty() && !it.startsWith("!") && it.startsWith("||") }
                                    .map { it.removePrefix("||").split("^").first().trim() }
                                    .filter { it.isNotEmpty() && !it.startsWith("/") }
                                    .forEach { blockedHosts.add(it) }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }.start()
    }
}
