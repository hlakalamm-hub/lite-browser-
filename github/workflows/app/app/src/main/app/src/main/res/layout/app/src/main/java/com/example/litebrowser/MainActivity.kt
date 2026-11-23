package com.example.litebrowser

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnToggleMedia: Button
    private lateinit var btnDarkMode: Button

    private var isMediaBlocked = true
    private var isDarkModeEnabled = true
    private val adHostsSet = HashSet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadAdHostsFromAssets()

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        progressBar = findViewById(R.id.progressBar)
        btnToggleMedia = findViewById(R.id.btnToggleMedia)
        btnDarkMode = findViewById(R.id.btnDarkMode)
        val btnHome: Button = findViewById(R.id.btnHome)
        val btnReload: Button = findViewById(R.id.btnReload)

        setupWebViewSettings()
        setupWebViewClients()
        setupButtons(btnHome, btnReload)

        loadUrl("https://www.google.com")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun loadAdHostsFromAssets() {
        adHostsSet.addAll(
            listOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "adnxs.com", "ads.yahoo.com", "amazon-adsystem.com", "taboola.com",
                "outbrain.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
                "analytics.google.com", "pagead2.googlesyndication.com",
                "popads.net", "propellerads.com", "exoclick.com", "adcolony.com",
                "applovin.com", "unityads.unity3d.com", "vungle.com", "chartboost.com"
            )
        )
        try {
            assets.open("hosts.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                    val cleanLine = line.trim().lowercase()
                    if (cleanLine.isNotEmpty() && !cleanLine.startsWith("#")) {
                        adHostsSet.add(cleanLine)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        applyMediaBlockingSettings()
        applyDarkModeSettings()
    }

    private fun applyMediaBlockingSettings() {
        val settings = webView.settings
        settings.loadsImagesAutomatically = !isMediaBlocked
        settings.blockNetworkImage = isMediaBlocked
        settings.mediaPlaybackRequiresUserGesture = isMediaBlocked
    }

    private fun applyDarkModeSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = isDarkModeEnabled
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = if (isDarkModeEnabled) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    private fun setupWebViewClients() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val host = uri.host?.lowercase() ?: ""
                val urlString = uri.toString().lowercase()

                if (isAdHost(host)) {
                    return emptyResponse()
                }

                if (isMediaBlocked) {
                    val blockedExtensions = listOf(".mp4", ".webm", ".gif", ".m3u8", ".ts", ".avi", ".mov", ".webp", ".jpg", ".jpeg", ".png", ".svg")
                    if (blockedExtensions.any { urlString.contains(it) }) {
                        return emptyResponse()
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                urlInput.setText(url)

                if (isMediaBlocked) {
                    val jsBlockMedia = """
                        (function() {
                            document.querySelectorAll('video, audio, source, track').forEach(el => el.remove());
                            var style = document.createElement('style');
                            style.innerHTML = 'img, video, picture, svg, canvas, iframe[src*="youtube"], iframe[src*="vimeo"], iframe[src*="dailymotion"], [class*="ad-"], [id*="ad-"], [class*="banner"] { display: none !important; width: 0 !important; height: 0 !important; }';
                            document.head.appendChild(style);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(jsBlockMedia, null)
                }
            }
        }
    }

    private fun isAdHost(host: String): Boolean {
        var currentHost = host
        while (currentHost.contains(".")) {
            if (adHostsSet.contains(currentHost)) return true
            currentHost = currentHost.substringAfter(".")
        }
        return false
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun setupButtons(btnHome: Button, btnReload: Button) {
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(urlInput.text.toString())
                true
            } else false
        }

        btnToggleMedia.setOnClickListener {
            isMediaBlocked = !isMediaBlocked
            applyMediaBlockingSettings()
            if (isMediaBlocked) {
                btnToggleMedia.text = "🚫 وسائط"
                btnToggleMedia.setBackgroundColor(Color.parseColor("#B00020"))
                Toast.makeText(this, "تم تفعيل حظر الصور والفيديوهات", Toast.LENGTH_SHORT).show()
            } else {
                btnToggleMedia.text = "👁️ وسائط"
                btnToggleMedia.setBackgroundColor(Color.parseColor("#2E7D32"))
                Toast.makeText(this, "تم السماح بالصور والفيديوهات مؤقتاً", Toast.LENGTH_SHORT).show()
            }
            webView.reload()
        }

        btnDarkMode.setOnClickListener {
            isDarkModeEnabled = !isDarkModeEnabled
            applyDarkModeSettings()
            btnDarkMode.text = if (isDarkModeEnabled) "🌙 ليلي: مفعل" else "☀️ ليلي: معطل"
            webView.reload()
        }

        btnHome.setOnClickListener { loadUrl("https://www.google.com") }
        btnReload.setOnClickListener { webView.reload() }
    }

    private fun loadUrl(input: String) {
        var url = input.trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://www.google.com/search?q=${Uri.encode(url)}"
        }
        webView.loadUrl(url)
    }
}
