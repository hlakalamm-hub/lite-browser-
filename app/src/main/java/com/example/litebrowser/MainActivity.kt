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
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedCredential(
    val domain: String,
    val username: String,
    val password: String
)

data class HistoryItem(
    val title: String,
    val url: String,
    val time: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnToggleMedia: Button
    private lateinit var btnDarkMode: Button

    private var isMediaBlocked = true
    private var isDarkModeEnabled = true
    private var isOfflineCacheEnabled = true

    private val adHostsSet = HashSet<String>()
    private val credentialsList = mutableListOf<SavedCredential>()
    private val historyList = mutableListOf<HistoryItem>()
    private var isPopupShowing = false

    // مجلد التخزين الدائم غير المحدود على ذاكرة الهاتف
    private lateinit var permanentCacheDir: File

    private val csvPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importPasswordsFromCsv(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permanentCacheDir = File(filesDir, "permanent_web_cache")
        if (!permanentCacheDir.exists()) permanentCacheDir.mkdirs()

        loadAdHostsFromAssets()
        loadSavedCredentials()
        loadHistory()

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

        webView.loadUrl("about:blank")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    // --- نظام التخزين الدائم غير المحدود (Offline Disk Cache) ---

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getCachedResponse(url: String): WebResourceResponse? {
        if (!isOfflineCacheEnabled) return null
        val hash = md5(url)
        val dataFile = File(permanentCacheDir, "$hash.dat")
        val metaFile = File(permanentCacheDir, "$hash.meta")

        if (dataFile.exists() && metaFile.exists()) {
            return try {
                val metaLines = metaFile.readLines()
                val mimeType = metaLines.getOrElse(0) { "text/html" }
                val encoding = metaLines.getOrElse(1) { "UTF-8" }
                WebResourceResponse(mimeType, encoding, FileInputStream(dataFile))
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun fetchAndCacheResource(urlString: String, requestHeaders: Map<String, String>?): WebResourceResponse? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Accept-Encoding", ignoreCase = true)) {
                    conn.setRequestProperty(k, v)
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val contentType = conn.contentType ?: "application/octet-stream"
                val mimeType = contentType.substringBefore(";").trim()
                val encoding = if (contentType.contains("charset=", ignoreCase = true)) {
                    contentType.substringAfter("charset=").substringBefore(";").trim()
                } else "UTF-8"

                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    val hash = md5(urlString)
                    File(permanentCacheDir, "$hash.dat").writeBytes(bytes)
                    File(permanentCacheDir, "$hash.meta").writeText("$mimeType\n$encoding\n$urlString")
                }
                WebResourceResponse(mimeType, encoding, ByteArrayInputStream(bytes))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getCacheSizeMB(): String {
        var totalBytes = 0L
        permanentCacheDir.listFiles()?.forEach { totalBytes += it.length() }
        val mb = totalBytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB", mb)
    }

    private fun clearPermanentCache() {
        permanentCacheDir.listFiles()?.forEach { it.delete() }
        webView.clearCache(true)
        Toast.makeText(this, "تم تفريغ ذاكرة التخزين المؤقت بالكامل", Toast.LENGTH_SHORT).show()
    }

    // --- إدارة سجل التصفح الدائم غير المحدود ---

    private fun loadHistory() {
        historyList.clear()
        val file = File(filesDir, "unlimited_history.json")
        if (!file.exists()) return
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                historyList.add(
                    HistoryItem(
                        obj.optString("title", obj.getString("url")),
                        obj.getString("url"),
                        obj.optString("time", "")
                    )
                )
            }
        } catch (_: Exception) { }
    }

    private fun addToHistory(title: String?, url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        val cleanTitle = if (title.isNullOrBlank()) url else title
        val timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())

        if (historyList.isNotEmpty() && historyList[0].url == url) return

        // سجل غير محدود يحفظ كل الصفحات على ذاكرة الهاتف
        historyList.add(0, HistoryItem(cleanTitle, url, timeStr))
        persistHistory()
    }

    private fun persistHistory() {
        try {
            val array = JSONArray()
            for (item in historyList) {
                val obj = JSONObject().apply {
                    put("title", item.title)
                    put("url", item.url)
                    put("time", item.time)
                }
                array.put(obj)
            }
            File(filesDir, "unlimited_history.json").writeText(array.toString())
        } catch (_: Exception) { }
    }

    private fun showHistoryDialog() {
        if (historyList.isEmpty()) {
            Toast.makeText(this, "سجل التصفح فارغ حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val items = historyList.map { "🕒 ${it.time} - ${it.title}\n🔗 ${it.url}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("سجل التصفح الدائم (${historyList.size} صفحة)")
            .setItems(items) { _, index ->
                loadUrl(historyList[index].url)
            }
            .setNeutralButton("🗑️ مسح السجل") { _, _ ->
                historyList.clear()
                persistHistory()
                Toast.makeText(this, "تم مسح سجل التصفح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
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
        if (cleanDomain.isEmpty() || password.isEmpty()) return

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
            val host = (uri.host ?: urlOrHost).lowercase().removePrefix("www.")
            val parts = host.split(".")
            if (parts.size >= 2) {
                "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
            } else host
        } catch (_: Exception) {
            urlOrHost.lowercase()
        }
    }

    private fun getMatchingCredentialsForCurrentSite(url: String?): List<SavedCredential> {
        if (url.isNullOrBlank() || url == "about:blank") return emptyList()
        val currentRoot = extractRootDomain(url)
        val fullHost = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }

        return credentialsList.filter { cred ->
            val savedRoot = extractRootDomain(cred.domain)
            savedRoot == currentRoot || fullHost.contains(cred.domain) || cred.domain.contains(currentRoot)
        }
    }

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
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر قراءة الملف: تأكد أنه بصيغة CSV صحيحة", Toast.LENGTH_LONG).show()
        }
    }

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

    private fun showPasswordsDialog() {
        val siteMatches = getMatchingCredentialsForCurrentSite(webView.url)
        val optionsList = mutableListOf<String>()

        if (siteMatches.isNotEmpty()) {
            optionsList.add("⚡ تعبئة حساب لهذا الموقع (${siteMatches.size} متاح)")
        }
        optionsList.add("🕒 سجل التصفح الدائم (${historyList.size})")
        optionsList.add("💾 التخزين المحلي (${getCacheSizeMB()}) - ${if (isOfflineCacheEnabled) "مفعل ✅" else "معطل ❌"}")
        optionsList.add("🧹 تفريغ ذاكرة التخزين المؤقت")
        optionsList.add("📂 استيراد كلمات المرور من CSV")
        optionsList.add("📋 عرض كلمات المرور المحفوظة (${credentialsList.size})")
        optionsList.add("🗑️ مسح جميع كلمات المرور")

        AlertDialog.Builder(this)
            .setTitle("البيانات والتخزين وكلمات المرور")
            .setItems(optionsList.toTypedArray()) { _, which ->
                var index = which
                if (siteMatches.isNotEmpty()) {
                    if (index == 0) {
                        showQuickFillPicker(siteMatches)
                        return@setItems
                    }
                    index--
                }
                when (index) {
                    0 -> showHistoryDialog()
                    1 -> {
                        isOfflineCacheEnabled = !isOfflineCacheEnabled
                        webView.settings.cacheMode = if (isOfflineCacheEnabled) {
                            WebSettings.LOAD_CACHE_ELSE_NETWORK
                        } else {
                            WebSettings.LOAD_DEFAULT
                        }
                        Toast.makeText(
                            this,
                            if (isOfflineCacheEnabled) "تم تفعيل الحفظ الدائم لتوفير الإنترنت" else "تم إيقاف الحفظ الدائم",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> clearPermanentCache()
                    3 -> csvPickerLauncher.launch("*/*")
                    4 -> showSavedListDialog()
                    5 -> {
                        credentialsList.clear()
                        persistCredentials()
                        Toast.makeText(this, "تم مسح جميع كلمات المرور", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showQuickFillPicker(matches: List<SavedCredential>) {
        if (matches.size == 1) {
            injectAutoFill(matches[0].username, matches[0].password)
            Toast.makeText(this, "تمت تعبئة بيانات ${matches[0].username}", Toast.LENGTH_SHORT).show()
            return
        }
        val items = matches.map { "👤 ${it.username}\n🔑 ••••••••" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر الحساب لتعبئته:")
            .setItems(items) { _, idx ->
                injectAutoFill(matches[idx].username, matches[idx].password)
                Toast.makeText(this, "تمت التعبئة!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
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
                function setNativeValue(el, value) {
                    if (!el) return;
                    var valueSetter = Object.getOwnPropertyDescriptor(el, 'value')?.set;
                    var prototype = Object.getPrototypeOf(el);
                    var prototypeValueSetter = Object.getPrototypeOf(prototype)?.value ? Object.getOwnPropertyDescriptor(prototype, 'value')?.set : null;
                    if (prototypeValueSetter && valueSetter !== prototypeValueSetter) {
                        prototypeValueSetter.call(el, value);
                    } else if (valueSetter) {
                        valueSetter.call(el, value);
                    } else {
                        el.value = value;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                var passInputs = Array.from(document.querySelectorAll('input[type="password"]')).filter(i => i.offsetParent !== null);
                if (passInputs.length > 0) {
                    setNativeValue(passInputs[0], $safePass);
                }

                var userInputs = Array.from(document.querySelectorAll('input[type="text"], input[type="email"], input[type="tel"], input[name*="user"], input[name*="email"], input[name*="login"], input:not([type])'))
                    .filter(i => i.offsetParent !== null && i.type !== 'password' && i.type !== 'hidden' && i.type !== 'submit' && i.type !== 'search');
                if (userInputs.length > 0) {
                    setNativeValue(userInputs[0], $safeUser);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

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

        @JavascriptInterface
        fun onLoginFieldFocused(url: String) {
            runOnUiThread {
                if (isPopupShowing) return@runOnUiThread
                val matches = getMatchingCredentialsForCurrentSite(url)
                if (matches.isNotEmpty()) {
                    isPopupShowing = true
                    val items = matches.map { "🔑 ${it.username}" }.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("كلمات المرور المحفوظة لـ ${extractRootDomain(url)}")
                        .setItems(items) { _, which ->
                            injectAutoFill(matches[which].username, matches[which].password)
                            Toast.makeText(this@MainActivity, "تمت تعبئة الحساب", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("تجاهل", null)
                        .setOnDismissListener { isPopupShowing = false }
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
        settings.databaseEnabled = true
        // إجبار المتصفح على القراءة من ذاكرة الهاتف أولاً وعدم إعادة التحميل من الإنترنت
        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        @Suppress("DEPRECATION")
        settings.saveFormData = true

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.TRANSPARENT)
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
                val rawUrl = uri.toString()

                // 1. حظر الإعلانات
                if (isAdHost(host)) return emptyResponse()

                // 2. حظر الوسائط الثقيلة إذا كان الحظر مفعلاً
                if (isMediaBlocked) {
                    val blockedExtensions = listOf(".mp4", ".webm", ".gif", ".m3u8", ".ts", ".avi", ".mov", ".webp", ".jpg", ".jpeg", ".png", ".svg")
                    if (blockedExtensions.any { urlString.contains(it) }) return emptyResponse()
                }

                // 3. التحقق من المخزن الدائم على الهاتف أولاً (توفير 100% من الإنترنت للملفات المحملة سابقاً)
                if (isOfflineCacheEnabled && request.method.equals("GET", ignoreCase = true) && rawUrl.startsWith("http")) {
                    val cached = getCachedResponse(rawUrl)
                    if (cached != null) {
                        return cached
                    }
                    // إذا لم يكن محفوظاً من قبل، نحمله ونخزنه على ذاكرة الهاتف للأبد
                    val fetched = fetchAndCacheResource(rawUrl, request.requestHeaders)
                    if (fetched != null) {
                        return fetched
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()

                if (url != null && url != "about:blank") {
                    urlInput.setText(url)
                    addToHistory(view?.title, url)
                }

                if (isMediaBlocked && url != "about:blank") {
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

                val jsSmartPasswordManager = """
                    (function() {
                        if (window._passHooked) return;
                        window._passHooked = true;

                        var lastUser = '';
                        var lastPass = '';

                        document.addEventListener('input', function(e) {
                            var t = e.target;
                            if (!t || t.tagName !== 'INPUT') return;
                            if (t.type === 'password') {
                                lastPass = t.value;
                            } else if (t.type === 'text' || t.type === 'email' || t.type === 'tel' || !t.type) {
                                lastUser = t.value;
                            }
                        }, true);

                        document.addEventListener('focusin', function(e) {
                            var t = e.target;
                            if (!t || t.tagName !== 'INPUT') return;
                            var isLoginField = (t.type === 'password' || t.type === 'email' ||
                                (t.name && /user|email|login|pass|account/i.test(t.name)) ||
                                (t.id && /user|email|login|pass|account/i.test(t.id)));
                            if (isLoginField && !t.value) {
                                AndroidPasswordVault.onLoginFieldFocused(window.location.href);
                            }
                        }, true);

                        document.addEventListener('submit', function(e) {
                            if (lastPass) {
                                AndroidPasswordVault.onCredentialsSubmitted(window.location.href, lastUser, lastPass);
                            }
                        }, true);

                        document.addEventListener('click', function(e) {
                            var btn = e.target.closest('button, input[type="submit"], [role="button"]');
                            if (btn && lastPass) {
                                setTimeout(function() {
                                    AndroidPasswordVault.onCredentialsSubmitted(window.location.href, lastUser, lastPass);
                                }, 400);
                            }
                        }, true);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(jsSmartPasswordManager, null)

                val matches = getMatchingCredentialsForCurrentSite(url)
                if (matches.isNotEmpty()) {
                    injectAutoFill(matches[0].username, matches[0].password)
                    view?.postDelayed({
                        injectAutoFill(matches[0].username, matches[0].password)
                    }, 1200)
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

        btnHome.setOnClickListener {
            urlInput.setText("")
            webView.loadUrl("about:blank")
        }
        btnHome.setOnLongClickListener {
            showHistoryDialog()
            true
        }
        // الضغط العادي على تحديث يجلب الصفحة من الكاش، والضغط المطول يجلب نسخة جديدة من الإنترنت
        btnReload.setOnClickListener { webView.reload() }
        btnReload.setOnLongClickListener {
            val currentUrl = webView.url
            if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
                val hash = md5(currentUrl)
                File(permanentCacheDir, "$hash.dat").delete()
                File(permanentCacheDir, "$hash.meta").delete()
                Toast.makeText(this, "جاري تحديث الصفحة من الإنترنت...", Toast.LENGTH_SHORT).show()
                webView.reload()
            }
            true
        }
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
