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
import android.view.KeyEvent
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.android.gms.cast.framework.CastContext
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
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followSslRedirects(true)
            .build()
    }

    private var pendingVideoUrl: String? = null
    private lateinit var downloadManager: DownloadManager
    private var castContext: CastContext? = null
    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
    private var mainWebView: WebView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingVideoUrl?.let { downloadVideoDirect(it) }
        } else {
            Toast.makeText(this, "Permission stockage refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("OhanaBrowser", Context.MODE_PRIVATE)
        downloadManager = getSystemService()!!

        android.os.Handler(mainLooper).postDelayed({
            try { castContext = CastContext.getSharedInstance(this) } catch (_: Exception) {}
            loadBlockLists()
        }, 500)

        setContent {
            MaterialTheme {
                Surface {
                    var isPrivateMode by remember { mutableStateOf(false) }
                    var searchText by remember { mutableStateOf("") }
                    var progress by remember { mutableStateOf(0) }
                    var showHistory by remember { mutableStateOf(false) }
                    var showFavorites by remember { mutableStateOf(false) }
                    var history by remember { mutableStateOf(loadHistory()) }
                    var favorites by remember { mutableStateOf(loadFavorites()) }

                    val goToUrl = { text: String ->
                        val trimmed = text.trim()
                        val url = when {
                            trimmed.isBlank() -> "https://www.google.com"
                            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
                            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
                        }
                        mainWebView?.loadUrl(url)
                        searchText = url
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (isPrivateMode) {
                            Surface(color = Color(0xFF662222), modifier = Modifier.fillMaxWidth()) {
                                Text(" 🔒 NAVIGATION PRIVÉE",
                                    color = Color.White, modifier = Modifier.padding(6.dp),
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
                            value = searchText,
                            onValueChange = { searchText = it },
                            placeholder = { Text("Rechercher ou URL...") },
                            label = { Text("Recherche / URL") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                                        && keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP
                                    ) {
                                        goToUrl(searchText)
                                        true
                                    } else false
                                },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = { goToUrl(searchText) }
                            ),
                            trailingIcon = {
                                Row {
                                    if (searchText.isNotBlank()) {
                                        IconButton(onClick = { searchText = "" }) { Text("✕") }
                                    }
                                }
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(1.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = { mainWebView?.goBack() }) { Text("◀") }
                            IconButton(onClick = { mainWebView?.reload() }) { Text("🔄") }
                            IconButton(onClick = { mainWebView?.goForward() }) { Text("▶") }
                            IconButton(onClick = { showHistory = !showHistory }) { Text("📜") }
                            IconButton(onClick = {
                                val u = mainWebView?.url ?: return@IconButton
                                if (!u.isNullOrBlank() && !u.startsWith("about:")) {
                                    toggleFavorite(u, mainWebView?.title ?: u, favorites)
                                    favorites = loadFavorites()
                                }
                            }) { Text("⭐") }
                            IconButton(onClick = { showFavorites = !showFavorites }) { Text("📂") }
                            IconButton(onClick = {
                                val u = mainWebView?.url ?: return@IconButton
                                if (!u.isNullOrBlank() && !u.startsWith("about:")) {
                                    checkAndDownloadVideo(u)
                                }
                            }) { Text("📥") }
                            IconButton(onClick = {
                                val u = mainWebView?.url ?: return@IconButton
                                if (!u.isNullOrBlank() && !u.startsWith("about:")) {
                                    castVideo(u)
                                }
                            }) { Text("📺") }
                            IconButton(onClick = {
                                isPrivateMode = !isPrivateMode
                                if (isPrivateMode) clearWebData()
                                mainWebView?.clearHistory()
                                searchText = ""
                            }) { Text(if (isPrivateMode) "🔴" else "🔒") }
                        }

                        if (showHistory && !isPrivateMode) {
                            Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(6.dp)) {
                                Column {
                                    Row(Modifier.fillMaxWidth().padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("📜 Historique", fontWeight = FontWeight.Bold)
                                        TextButton(onClick = { showHistory = false }) { Text("✕") }
                                    }
                                    LazyColumn {
                                        items(history) { entry ->
                                            Column(Modifier.clickable {
                                                searchText = entry.url
                                                mainWebView?.loadUrl(entry.url)
                                                showHistory = false
                                            }.padding(6.dp)) {
                                                Text(entry.title, fontWeight = FontWeight.Medium)
                                                Text("${dateFormat.format(Date(entry.timestamp))} · ${entry.url.take(35)}...",
                                                    style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (showFavorites) {
                            Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(6.dp)) {
                                Column {
                                    Row(Modifier.fillMaxWidth().padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("⭐ Favoris", fontWeight = FontWeight.Bold)
                                        TextButton(onClick = { showFavorites = false }) { Text("✕") }
                                    }
                                    LazyColumn {
                                        items(favorites) { entry ->
                                            Column(Modifier.clickable {
                                                searchText = entry.url
                                                mainWebView?.loadUrl(entry.url)
                                                showFavorites = false
                                            }.padding(6.dp)) {
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
                                        mainWebView = this
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.mediaPlaybackRequiresUserGesture = false
                                        settings.setGeolocationEnabled(false)
                                        settings.databaseEnabled = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
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
                                                if (!isPrivateMode && !url.isNullOrBlank() && !url.startsWith("about:")) {
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

                                        loadUrl("https://www.google.com")
                                    }
                                },
                                modifier = Modifier.weight(1f)
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
            Toast.makeText(this, "📺 Vidéo détectée", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Pas de lien vidéo direct", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndDownloadVideo(pageUrl: String) {
        val exts = listOf(".mp4", ".webm", ".m3u8", ".mkv", ".avi")
        val isVideo = exts.any { pageUrl.contains(it, true) }
        if (isVideo) {
            pendingVideoUrl = pageUrl
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> downloadVideoDirect(pageUrl)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> downloadVideoDirect(pageUrl)
                else -> permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            Toast.makeText(this, "⚠️ Pas de lien vidéo direct", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadVideoDirect(videoUrl: String) {
        val uri = Uri.parse(videoUrl)
        val req = DownloadManager.Request(uri)
        req.setTitle("Video")
        req.setDescription("Téléchargé depuis Ohana Browser")
        req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val name = uri.lastPathSegment ?: "video.mp4"
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Ohana_$name")
        downloadManager.enqueue(req)
        Toast.makeText(this, "📥 Téléchargement lancé !", Toast.LENGTH_SHORT).show()
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
