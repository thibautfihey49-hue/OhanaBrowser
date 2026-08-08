package com.example.ohana
import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // 🛡️ Blocage publicités
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient()

    // 📥 Téléchargement
    private var pendingVideoUrl: String? = null
    private lateinit var downloadManager: DownloadManager

    // 📺 Cast
    private var castContext: CastContext? = null

    // 🔑 Permission stockage
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) pendingVideoUrl?.let { downloadVideo(it) }
        else Toast.makeText(this, "Permission stockage refusée", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 📺 Initialiser Cast
        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) { /* Cast non disponible sur cet appareil */ }

        downloadManager = getSystemService()!!
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
                                Row {
                                    TextButton(onClick = { webView?.loadUrl(url) }) {
                                        Text("Aller")
                                    }
                                }
                            }
                        )

                        // ⏮️ Boutons + 📥 Télécharger + 📺 Diffuser
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = { webView?.goBack() }) { Text("◀") }
                            IconButton(onClick = { webView?.reload() }) { Text("🔄") }
                            IconButton(onClick = { webView?.goForward() }) { Text("▶") }
                            IconButton(onClick = {
                                webView?.let { wv ->
                                    val currentUrl = wv.url ?: return@IconButton
                                    detectAndDownloadVideo(currentUrl)
                                }
                            }) { Text("📥") }
                            IconButton(onClick = {
                                webView?.let { wv ->
                                    val currentUrl = wv.url ?: return@IconButton
                                    castVideo(currentUrl)
                                }
                            }) { Text("📺") }
                        }

                        // 🌐 WebView
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest
                                        ): WebResourceResponse? {
                                            val host = request.url.host ?: return null
                                            if (blockedHosts.any { host.contains(it, ignoreCase = true) }) {
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

    // 📺 Diffuser la vidéo sur TV
    private fun castVideo(pageUrl: String) {
        val videoExts = listOf(".mp4", ".webm", ".m3u8", ".mkv", ".avi")
        val isVideoUrl = videoExts.any { pageUrl.contains(it, ignoreCase = true) }

        if (castContext == null) {
            Toast.makeText(this, "📺 Cast non disponible", Toast.LENGTH_SHORT).show()
            return
        }

        if (isVideoUrl) {
            Toast.makeText(this, "📺 Recherche appareil...", Toast.LENGTH_SHORT).show()
            // Lancer la diffusion via le bouton Cast natif
            val uri = Uri.parse(pageUrl)
            Toast.makeText(this, "📺 Vidéo prête à diffuser : ${uri.lastPathSegment}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "⚠️ Ce n'est pas un lien vidéo direct", Toast.LENGTH_SHORT).show()
        }
    }

    // 📥 Détecter et lancer le téléchargement
    private fun detectAndDownloadVideo(pageUrl: String) {
        val videoExts = listOf(".mp4", ".webm", ".m3u8", ".mkv", ".avi")
        val isVideoUrl = videoExts.any { pageUrl.contains(it, ignoreCase = true) }

        if (isVideoUrl) {
            pendingVideoUrl = pageUrl
            checkPermissionAndDownload()
        } else {
            Toast.makeText(this, "⚠️ Ce n'est pas un lien vidéo direct", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionAndDownload() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                pendingVideoUrl?.let { downloadVideo(it) }
            }
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                pendingVideoUrl?.let { downloadVideo(it) }
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun downloadVideo(videoUrl: String) {
        try {
            val uri = Uri.parse(videoUrl)
            val req = DownloadManager.Request(uri).apply {
                setTitle("Vidéo - ${System.currentTimeMillis()}")
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
            lists.forEach { listUrl ->
                try {
                    val req = Request.Builder().url(listUrl).build()
                    client.newCall(req).execute().use { resp ->
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
                } catch (e: Exception) {}
            }
        }.start()
    }
}
