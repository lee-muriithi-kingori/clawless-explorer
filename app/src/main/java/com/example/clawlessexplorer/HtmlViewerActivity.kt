package com.example.clawlessexplorer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * In-app HTML viewer. Renders the file in a WebView with a virtual
 * `https://appassets/` origin so that relative asset paths (images, CSS,
 * JS) in the same directory resolve to local files via shouldInterceptRequest.
 */
class HtmlViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var file: File

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_html_viewer)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        if (!file.exists()) { finish(); return }

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        titleView = findViewById(R.id.titleText)
        titleView.text = file.name

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { webView.reload() }

        with(webView.settings) {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url.startsWith(WEBVIEW_BASE)) {
                    val relative = url.removePrefix(WEBVIEW_BASE).trimStart('/')
                    if (relative.isEmpty()) return null
                    // Strip any extra path segments after the file name — only allow
                    // siblings of the HTML file (same dir or subdirs). Prevents escape.
                    val resolved = File(file.parentFile ?: return null, relative).canonicalFile
                    val baseDir = (file.parentFile ?: return null).canonicalFile
                    if (!resolved.absolutePath.startsWith(baseDir.absolutePath)) return null
                    if (!resolved.exists() || !resolved.canRead()) return null
                    return try {
                        WebResourceResponse(guessMime(resolved.name), "utf-8", resolved.inputStream())
                    } catch (e: Exception) {
                        null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.loadUrl(WEBVIEW_BASE + file.name)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false
        webView.loadUrl("about:blank")
        webView.destroy()
        super.onDestroy()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt" -> "text/plain"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    companion object {
        private const val EXTRA_PATH = "extra_html_path"
        private const val WEBVIEW_BASE = "https://appassets.clawless/"

        fun intent(context: Context, path: String): Intent =
            Intent(context, HtmlViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
            }
    }
}
