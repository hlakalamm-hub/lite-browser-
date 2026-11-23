package com.example.litebrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

data class SavedCredential(
    val domain: String,
    val username: String,
    val password: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnToggleMedia: Button
    private lateinit var btnDarkMode: Button

    private var isMediaBlocked = true
    private var isDarkModeEnabled = true
    private val adHostsSet = HashSet<String>()
    private val credentialsList = mutableListOf<SavedCredential>()

    // أداة اختيار ملف CSV من ذاكرة الهاتف
    private val csvPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importPasswordsFromCsv(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadAdHostsFromAssets()
        loadSavedCredentials()

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        progressBar = findViewById(R.id.progressBar)
        btnToggleMedia = findViewById(R.id.btnToggleMedia)
        btnDarkMode = findViewById(R.id.btnDarkMode)
        val btnHome: Button = findViewById(R.id.btnHome)
        val btnReload: Button = findViewById(R.id.btnReload)
        val btnPasswords: Button = findViewById(R.id.btnPasswords)

        setupWebViewSettings()
        setupWebViewClients()
        setupButtons(btnHome, btnReload, btnPasswords)

        loadUrl("https://www.google.com")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    // --- إدارة كلمات المرور ---

    private fun loadSavedCredentials() {
        credentialsList.clear()
        val prefs = getSharedPreferences("LiteBrowserVault", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("credentials", "[]") ?: "[]"
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                credentialsList.add(
                    SavedCredential(
                        obj.getString("domain"),
                        obj.getString("username"),
                        obj.getString("password")
                    )
                )
            }
        } catch (_: Exception) { }
    }

    private fun persistCredentials() {
        val array = JSONArray()
        for (cred in credentialsList) {
            val obj = JSONObject().apply {
                put("domain", cred.domain)
                put("username", cred.username)
                put("password", cred.password)
            }
            array.put(obj)
        }
        getSharedPreferences("LiteBrowserVault", Context.MODE_PRIVATE)
            .edit()
            .putString("credentials", array.toString())
            .apply()
    }

    private fun saveOrUpdateCredential(domain: String, username: String, password: String) {
        val cleanDomain = extractRootDomain(domain)
        if (cleanDomain.isEmpty() || username.isEmpty() || password.isEmpty()) return

        val existingIndex = credentialsList.indexOfFirst {
            it.domain.equals(cleanDomain, ignoreCase = true) && it.username == username
        }
        if (existingIndex != -1) {
            credentialsList[existingIndex] = SavedCredential(cleanDomain, username, password)
        } else {
            credentialsList.add(SavedCredential(cleanDomain, username, password))
        }
        persistCredentials()
    }

    private fun extractRootDomain(urlOrHost: String): String {
        return try {
            val uri = if (urlOrHost.startsWith("http")) Uri.parse(urlOrHost) else Uri.parse("https://$urlOrHost")
            (uri.host ?: urlOrHost).removePrefix("www.").lowercase()
        } catch (_: Exception) {
            urlOrHost.lowercase()
        }
    }

    // استيراد كلمات المرور من ملف CSV (تنسيق Chrome / Edge / Brave)
    private fun importPasswordsFromCsv(uri: Uri) {
        try {
            var importedCount = 0
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var isFirstLine = true
                    var urlIdx = 1
                    var userIdx = 2
                    var passIdx = 3

                    reader.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val cols = parseCsvLine(line)

                        if (isFirstLine) {
                            isFirstLine = false
                            // تحديد ترتيب الأعمدة تلقائياً من ترويسة الملف
                            cols.forEachIndexed { index, header ->
                                when (header.trim().lowercase()) {
                                    "url", "website", "origin" -> urlIdx = index
                                    "username", "login", "email" -> userIdx = index
                                    "password" -> passIdx = index
                                }
                            }
                            if (cols.any { it.equals("password", ignoreCase = true) }) return@forEachLine
                        }

                        if (cols.size > maxOf(urlIdx, userIdx, passIdx)) {
                            val rawUrl = cols[urlIdx].trim()
                            val user = cols[userIdx].trim()
                            val pass = cols[passIdx].trim()
                            if (rawUrl.isNotEmpty() && pass.isNotEmpty()) {
                                saveOrUpdateCredential(rawUrl, user, pass)
                                importedCount++
                            }
                        }
                    }
                }
            }
            Toast.makeText(this, "تم استيراد $importedCount كلمة مرور بنجاح!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر قراءة الملف: تأكد أنه بصيغة CSV صحيحة", Toast.LENGTH_LONG).show()
        }
    }

    // تحليل سطر CSV مع دعم الفواصل داخل علامات التنصيص
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }

    // نافذة عرض وإدارة كلمات المرور
    private fun showPasswordsDialog() {
        val options = arrayOf(
            "📂 استيراد من ملف CSV (Chrome/Edge)",
            "📋 عرض كلمات المرور المحفوظة (${credentialsList.size})",
            "🗑️ مسح جميع كلمات المرور"
        )

        AlertDialog.Builder(this)
            .setTitle("مدير كلمات المرور")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> csvPickerLauncher.launch("*/*")
                    1 -> showSavedListDialog()
                    2 -> {
                        credentialsList.clear()
                        persistCredentials()
                        Toast.makeText(this, "تم مسح جميع كلمات المرور", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showSavedListDialog() {
        if (credentialsList.isEmpty()) {
            Toast.makeText(this, "لا توجد كلمات مرور محفوظة بعد", Toast.LENGTH_SHORT).show()
            return
        }

        val items = credentialsList.map { "${it.domain}\n👤 ${it.username}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("اختر حساباً لنسخ كلمة المرور أو تعبئتها")
            .setItems(items) { _, index ->
                val selected = credentialsList[index]
                val actions = arrayOf(
                    "⚡ تعبئة تلقائية في الصفحة الحالية",
                    "📋 نسخ كلمة المرور (${selected.password})",
                    "👤 نسخ اسم المستخدم",
                    "❌ حذف هذا الحساب"
                )
                AlertDialog.Builder(this)
                    .setTitle(selected.domain)
                    .setItems(actions) { _, act ->
                        when (act) {
                            0 -> injectAutoFill(selected.username, selected.password)
                            1 -> copyToClipboard("Password", selected.password)
                            2 -> copyToClipboard("Username", selected.username)
                            3 -> {
                                credentialsList.removeAt(index)
                                persistCredentials()
                                Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("رجوع", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "تم النسخ إلى الحافظة", Toast.LENGTH_SHORT).show()
    }

    private fun injectAutoFill(username: String, password: String) {
        val safeUser = JSONObject.quote(username)
        val safePass = JSONObject.quote(password)
        val js = """
            (function() {
                var passInputs = document.querySelectorAll('input[type="password"]');
                if (passInputs.length > 0) {
                    var passField = passInputs[0];
                    passField.value = $safePass;
                    passField.dispatchEvent(new Event('input', { bubbles: true }));
                    passField.dispatchEvent(new Event('change', { bubbles: true }));

                    var inputs = Array.from(document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], input:not([type])'));
                    var userField = inputs.find(i => i.offsetParent !== null);
                    if (userField) {
                        userField.value = $safeUser;
                        userField.dispatchEvent(new Event('input', { bubbles: true }));
                        userField.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // جسر لاستقبال كلمات المرور الجديدة عند تسجيل الدخول في أي موقع
    inner class PasswordBridge {
        @JavascriptInterface
        fun onCredentialsSubmitted(url: String, username: String, password: String) {
            if (password.isBlank()) return
            runOnUiThread {
                val domain = extractRootDomain(url)
                val alreadySaved = credentialsList.any {
                    it.domain == domain && it.username == username && it.password == password
                }
                if (!alreadySaved) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("حفظ كلمة المرور؟")
                        .setMessage("هل تريد حفظ بيانات الدخول لموقع ($domain)؟\nالمستخدم: $username")
                        .setPositiveButton("حفظ") { _, _ ->
                            saveOrUpdateCredential(domain, username, password)
                            Toast.makeText(this@MainActivity, "تم حفظ كلمة المرور!", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("تجاهل", null)
                        .show()
                }
            }
        }
    }

    // --- إعدادات المتصفح الأساسية ---

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
        webView.addJavascriptInterface(PasswordBridge(), "AndroidPasswordVault")
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

                if (isAdHost(host)) return emptyResponse()

                if (isMediaBlocked) {
                    val blockedExtensions = listOf(".mp4", ".webm", ".gif", ".m3u8", ".ts", ".avi", ".mov", ".webp", ".jpg", ".jpeg", ".png", ".svg")
                    if (blockedExtensions.any { urlString.contains(it) }) return emptyResponse()
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                urlInput.setText(url)

                // 1. حظر الوسائط إذا كان مفعلاً
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

                // 2. مراقبة نماذج تسجيل الدخول لحفظ كلمات المرور الجديدة
                val jsCaptureLogin = """
                    (function() {
                        if (window._passHooked) return;
                        window._passHooked = true;
                        document.addEventListener('submit', function(e) {
                            var form = e.target;
                            var passInput = form.querySelector('input[type="password"]');
                            if (passInput && passInput.value) {
                                var userInput = form.querySelector('input[type="text"], input[type="email"], input[type="tel"], input:not([type])');
                                var userVal = userInput ? userInput.value : '';
                                AndroidPasswordVault.onCredentialsSubmitted(window.location.href, userVal, passInput.value);
                            }
                        }, true);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(jsCaptureLogin, null)

                // 3. التعبئة التلقائية إذا كان الموقع محفوظاً مسبقاً
                if (url != null) {
                    val currentDomain = extractRootDomain(url)
                    val match = credentialsList.firstOrNull { currentDomain.contains(it.domain) }
                    if (match != null) {
                        injectAutoFill(match.username, match.password)
                    }
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

    private fun setupButtons(btnHome: Button, btnReload: Button, btnPasswords: Button) {
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

        btnPasswords.setOnClickListener { showPasswordsDialog() }

        btnDarkMode.setOnClickListener {
            isDarkModeEnabled = !isDarkModeEnabled
            applyDarkModeSettings()
            btnDarkMode.text = if (isDarkModeEnabled) "🌙 ليلي" else "☀️ نهاري"
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
