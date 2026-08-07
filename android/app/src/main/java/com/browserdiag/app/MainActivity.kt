package com.browserdiag.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BrowserDiag 3.0：Chrome 风格底部导航浏览器 + 内嵌 HTTP 诊断服务器（:8788）。
 * 功能：多标签 / 搜索引擎切换 / UA 切换 / 深色主题 / 油猴脚本 / 书签 / 保存页面 / 分享 /
 * 页面查找 / 翻译 / 媒体嗅探 / 页面资源 / 源码 zip / 语音播报 / 二维码 / 添加到桌面 / 历史。
 * 平时可作普通浏览器使用，也可作为诊断后端供其它 AI 工具通过 HTTP 调用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusBar: TextView
    private lateinit var settings: Settings
    private lateinit var tabs: Tabs
    private lateinit var webContainer: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private val consoleLogs = mutableListOf<JSONObject>()
    private val scriptHandlers = ConcurrentHashMap<String, androidx.webkit.ScriptHandler>()
    private var server: DiagServer? = null
    private var lastTitle: String = ""
    private var tts: TextToSpeech? = null
    private var isDark = false

    // 主题色
    private val C_BAR_LIGHT = 0xFFF0F0F0.toInt()
    private val C_BAR_DARK = 0xFF1E293B.toInt()
    private val C_TEXT_LIGHT = 0xFF202020.toInt()
    private val C_TEXT_DARK = 0xFFE2E8F0.toInt()
    private val C_FIELD_LIGHT = 0xFFFFFFFF.toInt()
    private val C_FIELD_DARK = 0xFF334155.toInt()
    private val C_STATUS_LIGHT = 0xFFE2E8F0.toInt()
    private val C_STATUS_DARK = 0xFF0F172A.toInt()
    private val C_ACCENT = 0xFF1A73E8.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        isDark = settings.darkMode
        buildUi()
        startServer()
        newTab(settings.engine.homeUrl, first = true)
        applyTheme()
    }

    // ==================== 崩溃捕获 ====================
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val text = "BrowserDiag crash @ ${System.currentTimeMillis()}\n$sw"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "browserdiag_crash.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(text.toByteArray()) } }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, "browserdiag_crash.txt").writeText(text)
                }
            } catch (ex: Exception) {
            }
            try {
                File(filesDir, "crash.log").writeText(text)
            } catch (ex: Exception) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(2)
        }
    }

    // ==================== UI 构建 ====================
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ---- 顶部：胶囊地址栏 ----
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val fieldWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 4, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fieldWrap.addView(iconBtn(R.drawable.ic_search, 20) { /* 占位：点击聚焦输入框 */ urlInput.requestFocus() })
        urlInput = EditText(this).apply {
            hint = "搜索或输入网址"
            textSize = 15f
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(urlInput.text.toString()); true } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fieldWrap.addView(urlInput)
        topBar.addView(fieldWrap)
        topBar.addView(iconBtn(R.drawable.ic_arrow, 22) { navigate(urlInput.text.toString()) })
        root.addView(topBar)

        // ---- 查找栏（默认隐藏） ----
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 4, 10, 4)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        findInput = EditText(this).apply {
            hint = "查找内容"
            textSize = 14f
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        findBar.addView(findInput)
        findBar.addView(iconBtn(R.drawable.ic_arrow, 18) { findNext(false) }) // 上一个
        findBar.addView(iconBtn(R.drawable.ic_forward, 18) { findNext(true) }) // 下一个
        findBar.addView(iconBtn(R.drawable.ic_close, 18) { hideFindBar() })
        root.addView(findBar)

        // ---- 状态栏 ----
        statusBar = TextView(this).apply {
            text = "启动中…"
            textSize = 11f
            setPadding(12, 4, 12, 4)
            setSingleLine(true)
        }
        root.addView(statusBar)

        // ---- WebView 容器 ----
        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(webContainer)

        // ---- 底部导航栏（矢量图标） ----
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        bottomBar.addView(navBtn(R.drawable.ic_home) { goHome() })
        bottomBar.addView(navBtn(R.drawable.ic_back) { currentWeb()?.let { if (it.canGoBack()) it.goBack() } })
        bottomBar.addView(navBtn(R.drawable.ic_forward) { currentWeb()?.let { if (it.canGoForward()) it.goForward() } })
        bottomBar.addView(navBtn(R.drawable.ic_refresh) { currentWeb()?.reload() })
        bottomBar.addView(navBtn(R.drawable.ic_tab) { showTabsDialog() })
        bottomBar.addView(navBtn(R.drawable.ic_menu) { showMainMenu() })
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun iconBtn(drawableRes: Int, sizeDp: Int = 24, action: () -> Unit): ImageButton {
        val dp = (sizeDp * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp + 12, dp + 12)
            setOnClickListener { action() }
        }
    }

    private fun navBtn(drawableRes: Int, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(drawableRes)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(0, 52.dp(), 1f)
            setOnClickListener { action() }
        }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** 应用主题（浅色 Chrome 风格 / 深色） */
    private fun applyTheme() {
        val bar = if (isDark) C_BAR_DARK else C_BAR_LIGHT
        val text = if (isDark) C_TEXT_DARK else C_TEXT_LIGHT
        val field = if (isDark) C_FIELD_DARK else C_FIELD_LIGHT
        topBar.setBackgroundColor(bar)
        bottomBar.setBackgroundColor(bar)
        statusBar.setBackgroundColor(if (isDark) C_STATUS_DARK else C_STATUS_LIGHT)
        statusBar.setTextColor(if (isDark) C_TEXT_DARK else 0xFF475569.toInt())
        urlInput.setTextColor(text)
        urlInput.setHintTextColor(if (isDark) 0xFF94A3B8.toInt() else 0xFF94A3B8.toInt())
        findBar.setBackgroundColor(if (isDark) C_BAR_DARK else 0xFFE5E7EB.toInt())
        findInput.setTextColor(text)
        findInput.setHintTextColor(0xFF94A3B8.toInt())
        val tint = if (isDark) C_TEXT_DARK else 0xFF374151.toInt()
        val barChildren = ArrayList<View>()
        barChildren.addAll(topBar.children())
        barChildren.addAll(bottomBar.children())
        barChildren.addAll(findBar.children())
        barChildren.forEach { v ->
            if (v is ImageButton) v.setColorFilter(tint)
        }
        // 地址栏胶囊背景
        (topBar.getChildAt(0) as? LinearLayout)?.setBackgroundColor(field)
        statusBar.text = buildStatusText()
    }

    private fun LinearLayout.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }

    private fun buildStatusText(): String {
        val ip = localIp()
        val errs = synchronized(consoleLogs) { consoleLogs.filter { it.optString("type") == "error" }.size }
        return "API: http://$ip:8788  |  ${settings.engine.label}  |  ${settings.uaMode.label}  |  tab ${tabs.size}  |  console: ${consoleLogs.size} (err $errs)"
    }

    // ==================== 标签管理 ====================
    private fun currentWeb(): WebView? = tabs.current?.webView

    private fun newTab(url: String, first: Boolean = false) {
        if (tabs.size >= 5 && !first) {
            toast("标签已达上限（5 个），请先关闭标签")
            return
        }
        val tab = tabs.create(webContainer, url)
        configureWebView(tab.webView)
        tabs.switchTo(tab.id)
        tab.webView.loadUrl(url)
        urlInput.setText(url)
        if (first) tabs = tabs // no-op
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = settings.uaMode.uaString(WebSettings.getDefaultUserAgent(this@MainActivity))
            textZoom = settings.fontScale
        }

        // 网络 hook + 油猴脚本（document-start）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                val h = WebViewCompat.addDocumentStartJavaScript(wv, NETWORK_HOOK_JS, setOf("*"))
                scriptHandlers["__net_hook_${System.identityHashCode(wv)}"] = h
            } catch (e: Exception) {
            }
        }
        settings.getScripts().filter { it.enabled }.forEach { s ->
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, s.code, patternToOriginRules(s.urlPattern))
            } catch (e: Exception) {
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val entry = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("type", msg.messageLevel().name.lowercase())
                    .put("text", msg.message())
                synchronized(consoleLogs) {
                    consoleLogs.add(entry)
                    if (consoleLogs.size > 500) consoleLogs.removeAt(0)
                }
                runOnUiThread { statusBar.text = buildStatusText() }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                lastTitle = title ?: ""
                tabs.current?.let { t ->
                    if (t.webView === view) {
                        t.title = lastTitle
                        urlInput.setText(t.url)
                    }
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tabs.current?.let { t ->
                    if (t.webView === view) {
                        t.url = url ?: ""
                        settings.addHistory(url ?: "", lastTitle)
                        urlInput.setText(url ?: "")
                    }
                }
                runOnUiThread { statusBar.text = buildStatusText() }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (tabs.current?.webView === view) {
                    urlInput.setText(url ?: "")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                val entry = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("type", "error")
                    .put("text", "page error [$errorCode] $description: $failingUrl")
                synchronized(consoleLogs) {
                    consoleLogs.add(entry)
                    if (consoleLogs.size > 500) consoleLogs.removeAt(0)
                }
                runOnUiThread { statusBar.text = buildStatusText() }
            }

            // 广告拦截：命中规则则返回空响应
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                if (settings.adBlock && request?.url != null) {
                    val host = request.url.host?.lowercase() ?: ""
                    if (host.isNotEmpty() && AD_BLOCK_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            try {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url))
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setMimeType(mimeType ?: "application/octet-stream")
                dm.enqueue(req)
                toast("已加入下载队列")
            } catch (e: Exception) {
                toast("下载失败: ${e.message}")
            }
        })
    }

    private fun showTabsDialog() {
        val all = tabs.all
        val labels = all.mapIndexed { i, t ->
            val title = t.title.ifEmpty { t.url }.ifEmpty { "新标签" }
            val short = if (title.length > 26) title.take(26) + "…" else title
            "${if (i == tabs.all.indexOf(tabs.current)) "● " else "○ "}$short"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("标签页（${all.size}/5）")
            .setItems(labels) { _, which ->
                tabs.switchTo(all[which].id)
                tabs.current?.let { urlInput.setText(it.url) }
            }
            .setPositiveButton("＋ 新标签") { _, _ -> newTab(settings.engine.homeUrl) }
            .setNeutralButton("关闭当前") { _, _ ->
                tabs.current?.let { tabs.destroy(it.id) }
                tabs.current?.let { urlInput.setText(it.url) } ?: run { newTab(settings.engine.homeUrl) }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 导航 ====================
    private fun navigate(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val engine = settings.engine
        val wv = currentWeb() ?: return
        when {
            q.startsWith("http://") || q.startsWith("https://") -> wv.loadUrl(q)
            q.startsWith("about:") -> wv.loadUrl(q)
            q.contains(".") && !q.contains(" ") && !q.contains("/") && !q.contains("?") ->
                wv.loadUrl("https://$q")
            else -> wv.loadUrl(engine.searchUrl.format(URLEncoder.encode(q, "UTF-8")))
        }
        urlInput.clearFocus()
    }

    private fun goHome() {
        currentWeb()?.loadUrl(settings.engine.homeUrl)
        urlInput.setText(settings.engine.homeUrl)
    }

    // ==================== 主菜单（分组 + 矢量图标） ====================
    private fun showMainMenu() {
        val menuCfg = settings.getMenuConfig()
        fun enabled(id: String) = menuCfg[id] != false
        val groups = listOf(
            Pair("📌 页面", listOf(
                MenuItem("bookmark", R.drawable.ic_bookmark, "书签") { showBookmarks() },
                MenuItem("save", R.drawable.ic_save, "保存页面") { savePage() },
                MenuItem("share", R.drawable.ic_share, "分享") { sharePage() },
                MenuItem("find", R.drawable.ic_find, "页面查找") { showFindBar() },
                MenuItem("translate", R.drawable.ic_translate, "翻译本页") { translatePage() },
                MenuItem("widget", R.drawable.ic_widget, "添加到桌面") { addToHome() },
                MenuItem("fullscreen", R.drawable.ic_launch, "全屏模式") { toggleFullscreen() }
            )),
            Pair("🌐 工具", listOf(
                MenuItem("sniff", R.drawable.ic_movie, "嗅探媒体资源") { sniffMedia() },
                MenuItem("resources", R.drawable.ic_folder, "查看页面资源") { pageResources() },
                MenuItem("source", R.drawable.ic_code, "页面源码 (zip)") { downloadSourceZip() },
                MenuItem("qr", R.drawable.ic_qr, "生成二维码") { showQr() },
                MenuItem("tts", R.drawable.ic_mic, "语音播报") { speakPage() },
                MenuItem("netlog", R.drawable.ic_download, "网络日志") { showNetLog() },
                MenuItem("downloads", R.drawable.ic_arrow, "下载管理") { showDownloads() },
                MenuItem("devtools", R.drawable.ic_tools, "开发者工具") { devTools() }
            )),
            Pair("⚙️ 设置", listOf(
                MenuItem("dark", R.drawable.ic_dark, "深色主题：${if (isDark) "开" else "关"}") { toggleDark() },
                MenuItem("engine", R.drawable.ic_search, "搜索引擎：${settings.engine.label}") { showEnginePicker() },
                MenuItem("ua", R.drawable.ic_phone, "UA：${settings.uaMode.label}") { showUaPicker() },
                MenuItem("userscript", R.drawable.ic_extension, "油猴脚本") { showScriptManager() },
                MenuItem("font", R.drawable.ic_find, "字体大小：${settings.fontScale}%") { showFontScale() },
                MenuItem("orientation", R.drawable.ic_refresh, "屏幕方向：${orientationLabel()}") { showOrientation() },
                MenuItem("adblock", R.drawable.ic_close, "广告拦截：${if (settings.adBlock) "开" else "关"}") { toggleAdBlock() },
                MenuItem("debugweb", R.drawable.ic_code, "允许调试网页：${if (settings.debugWeb) "开" else "关"}") { toggleDebugWeb() },
                MenuItem("menuconfig", R.drawable.ic_menu, "定制菜单") { showMenuConfig() },
                MenuItem("history", R.drawable.ic_history, "历史记录") { showHistory() },
                MenuItem("about", R.drawable.ic_info, "关于 / API") { showAbout() }
            ))
        )
        val filtered = groups.map { (title, items) -> title to items.filter { enabled(it.id) } }
            .filter { it.second.isNotEmpty() }

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((groupTitle, items) in filtered) {
            col.addView(TextView(this).apply {
                text = groupTitle
                textSize = 13f
                setPadding(24, 14, 24, 6)
                setTextColor(C_ACCENT)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            items.forEach { item ->
                col.addView(menuRow(item.icon, item.label) {
                    item.action()
                })
            }
        }
        scroll.addView(col)
        AlertDialog.Builder(this)
            .setTitle("功能菜单")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private class MenuItem(val id: String, val icon: Int, val label: String, val action: () -> Unit)

    private fun menuRow(iconRes: Int, label: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 12, 28, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            setOnClickListener {
                action()
                // 关闭对话框：通过标记（findViewWithTag）
                (parent as? ViewGroup)?.let { p ->
                    var cur: View? = this
                    while (cur != null) {
                        if (cur is ScrollView) break
                        cur = cur.parent as? View
                    }
                }
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(if (isDark) C_TEXT_DARK else 0xFF374151.toInt())
                layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(if (isDark) C_TEXT_DARK else C_TEXT_LIGHT)
                setPadding(18, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }

    // ==================== 书签 ====================
    private fun showBookmarks() {
        val bookmarks = settings.getBookmarks()
        if (bookmarks.isEmpty()) {
            addCurrentToBookmarks()
            return
        }
        val labels = bookmarks.map { (n, u) ->
            val t = n.ifEmpty { u }
            if (t.length > 34) t.take(34) + "…" else t
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("书签")
            .setItems(labels) { _, which ->
                currentWeb()?.loadUrl(bookmarks[which].second)
            }
            .setPositiveButton("☆ 收藏当前页") { _, _ -> addCurrentToBookmarks() }
            .setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("删除书签")
                    .setItems(labels) { _, which ->
                        settings.removeBookmark(bookmarks[which].second)
                        toast("已删除")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun addCurrentToBookmarks() {
        val url = currentWeb()?.url ?: return
        if (url.isEmpty()) { toast("当前无页面"); return }
        val name = lastTitle.ifEmpty { url }
        settings.addBookmark(name, url)
        toast("已收藏：$name")
    }

    // ==================== 保存页面 / 分享 / 查找 / 翻译 ====================
    private fun savePage() {
        val wv = currentWeb() ?: return
        val url = wv.url ?: return
        if (url.isEmpty() || url == "about:blank") { toast("当前没有可保存的页面"); return }
        statusBar.text = "正在保存页面…"
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            val name = "page_${System.currentTimeMillis()}.html"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(html.toByteArray()) } }
                    runOnUiThread { statusBar.text = "页面已保存 ✅ $name"; toast("页面已保存到下载目录") }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, name).writeText(html)
                    runOnUiThread { statusBar.text = "页面已保存 ✅ $name"; toast("页面已保存到下载目录") }
                }
            } catch (e: Exception) {
                runOnUiThread { statusBar.text = "保存失败 ❌ ${e.message}"; toast("保存失败：${e.message}") }
            }
        }
    }

    private fun sharePage() {
        val url = currentWeb()?.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${lastTitle.ifEmpty { url }}\n$url")
        }
        startActivity(Intent.createChooser(send, "分享页面"))
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        currentWeb()?.clearMatches()
    }

    private fun findNext(forward: Boolean) {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        currentWeb()?.let { wv ->
            wv.findAllAsync(q)
            wv.findNext(forward)
        }
    }

    private fun translatePage() {
        val url = currentWeb()?.url ?: return
        val tUrl = "https://translate.google.com/translate?u=" + URLEncoder.encode(url, "UTF-8")
        currentWeb()?.loadUrl(tUrl)
        urlInput.setText(tUrl)
    }

    // ==================== 媒体嗅探 / 页面资源 ====================
    private fun sniffMedia() {
        val wv = currentWeb() ?: return
        statusBar.text = "正在嗅探媒体资源…"
        wv.evaluateJavascript(
            "(function(){var out=[];document.querySelectorAll('video,audio').forEach(function(m){if(m.src)out.push(m.src);m.querySelectorAll('source').forEach(function(s){if(s.src)out.push(s.src);});});if(window.__bdNet)window.__bdNet.forEach(function(n){if(/\\.(mp4|m3u8|mp3|webm|flv|m4a|ogg|aac)(\\?|$)/i.test(n.url))out.push(n.url);});return JSON.stringify(out);})()"
        ) { raw ->
            val list = try {
                val v = org.json.JSONTokener(raw ?: "[]").nextValue()
                (v as? JSONArray)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    statusBar.text = "未发现媒体资源"
                    toast("未发现媒体资源")
                    return@runOnUiThread
                }
                statusBar.text = "发现 ${list.size} 个媒体资源"
                showUrlListDialog("媒体资源（点击下载）", list)
            }
        }
    }

    private fun pageResources() {
        val wv = currentWeb() ?: return
        wv.evaluateJavascript(
            "(function(){var out=[];document.querySelectorAll('img[src],script[src],link[href]').forEach(function(e){var u=e.src||e.href;if(u&&u.indexOf('data:')!==0)out.push(u);});return JSON.stringify(out.slice(0,60));})()"
        ) { raw ->
            val list = try {
                val v = org.json.JSONTokener(raw ?: "[]").nextValue()
                (v as? JSONArray)?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (list.isEmpty()) toast("未发现资源") else showUrlListDialog("页面资源（点击打开）", list)
            }
        }
    }

    private fun showUrlListDialog(title: String, urls: List<String>) {
        val labels = urls.map { if (it.length > 60) it.take(60) + "…" else it }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("$title（${urls.size}）")
            .setItems(labels) { _, which ->
                val url = urls[which]
                AlertDialog.Builder(this)
                    .setTitle("操作")
                    .setItems(arrayOf("在浏览器中打开", "下载", "复制链接")) { _, op ->
                        when (op) {
                            0 -> currentWeb()?.loadUrl(url)
                            1 -> {
                                try {
                                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val req = DownloadManager.Request(Uri.parse(url))
                                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    dm.enqueue(req)
                                    toast("已加入下载队列")
                                } catch (e: Exception) {
                                    toast("下载失败：${e.message}")
                                }
                            }
                            2 -> {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("url", url))
                                toast("已复制链接")
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 语音播报 ====================
    private fun speakPage() {
        val wv = currentWeb() ?: return
        wv.evaluateJavascript("document.body?document.body.innerText.slice(0,4000):''") { raw ->
            val text = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            if (text.isBlank()) {
                runOnUiThread { toast("页面没有可朗读的文本") }
                return@evaluateJavascript
            }
            runOnUiThread {
                if (tts == null) {
                    tts = TextToSpeech(this) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.language = Locale.CHINA
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bd_speak")
                            toast("开始朗读（再次点击停止）")
                        }
                    }
                } else {
                    if (tts!!.isSpeaking) {
                        tts!!.stop()
                        toast("已停止朗读")
                    } else {
                        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bd_speak")
                        toast("开始朗读（再次点击停止）")
                    }
                }
            }
        }
    }

    // ==================== 二维码 ====================
    private fun showQr() {
        val url = currentWeb()?.url ?: run { toast("当前无页面"); return }
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (y in 0 until size) for (x in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                setPadding(40, 24, 40, 0)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(img)
                addView(TextView(this@MainActivity).apply {
                    text = url.take(60)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(16, 10, 16, 20)
                })
            }
            AlertDialog.Builder(this)
                .setTitle("页面二维码")
                .setView(col)
                .setPositiveButton("复制链接") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", url))
                    toast("已复制链接")
                }
                .setNegativeButton("关闭", null)
                .show()
        } catch (e: Exception) {
            toast("二维码生成失败：${e.message}")
        }
    }

    // ==================== 添加到桌面 ====================
    private fun addToHome() {
        val url = currentWeb()?.url ?: return
        val title = lastTitle.ifEmpty { url }
        val id = "bd_${url.hashCode()}"
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val info = ShortcutInfoCompat.Builder(this, id)
            .setIntent(intent)
            .setShortLabel(title.take(10))
            .setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .build()
        if (ShortcutManagerCompat.requestPinShortcut(this, info, null)) {
            toast("已请求添加到桌面")
        } else {
            toast("设备不支持固定快捷方式")
        }
    }

    // ==================== 开发者工具 ====================
    private fun devTools() {
        val ip = localIp()
        val wv = currentWeb()
        AlertDialog.Builder(this)
            .setTitle("开发者工具")
            .setMessage(
                "HTTP API：http://$ip:8788\n" +
                    "工具：browser_open/state/console/network/eval/screenshot/perf/report/source/close\n\n" +
                    "当前标签：${wv?.url ?: "无"}\n" +
                    "console 日志：${consoleLogs.size}（错误 ${consoleLogs.filter { it.optString("type") == "error" }.size}）\n\n" +
                    "提示：其它 AI 工具可通过 HTTP 调用本浏览器诊断与操作页面。"
            )
            .setPositiveButton("复制 API 地址") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("api", "http://$ip:8788"))
                toast("已复制 http://$ip:8788")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 深色主题 / 引擎 / UA / 油猴 / 历史 / 关于 ====================
    private fun toggleDark() {
        isDark = !isDark
        settings.darkMode = isDark
        applyTheme()
        toast(if (isDark) "已切换到深色主题" else "已切换到浅色主题")
    }

    private fun showEnginePicker() {
        val engines = SearchEngine.entries
        val labels = engines.map { it.label }.toTypedArray()
        val current = engines.indexOfFirst { it == settings.engine }
        AlertDialog.Builder(this)
            .setTitle("搜索引擎")
            .setSingleChoiceItems(labels, current) { d, which ->
                settings.engine = engines[which]
                d.dismiss()
                toast("已切换：${engines[which].label}")
            }
            .show()
    }

    private fun showUaPicker() {
        val modes = UaMode.entries
        val labels = modes.map { it.label }.toTypedArray()
        val current = modes.indexOfFirst { it == settings.uaMode }
        AlertDialog.Builder(this)
            .setTitle("User-Agent")
            .setSingleChoiceItems(labels, current) { d, which ->
                settings.uaMode = modes[which]
                currentWeb()?.settings?.userAgentString =
                    modes[which].uaString(WebSettings.getDefaultUserAgent(this))
                d.dismiss()
                toast("UA 已切换：${modes[which].label}（刷新页面生效）")
            }
            .show()
    }

    private fun showScriptManager() {
        val scripts = settings.getScripts()
        if (scripts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("油猴脚本")
                .setMessage("暂无脚本\n\n点击下方按钮添加脚本")
                .setPositiveButton("添加") { _, _ -> showAddScriptDialog() }
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        val labels = scripts.map { s ->
            val state = if (s.enabled) "✅" else "⛔"
            "$state ${s.name}  (${s.urlPattern})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("油猴脚本（点击切换启用/停用）")
            .setItems(labels) { _, which ->
                val list = settings.getScripts().toMutableList()
                list[which] = list[which].copy(enabled = !list[which].enabled)
                settings.saveScripts(list)
                toast("脚本已${if (list[which].enabled) "启用" else "停用"}，应用重载中…")
                recreate()
            }
            .setPositiveButton("添加脚本") { _, _ -> showAddScriptDialog() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAddScriptDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val nameInput = EditText(this).apply { hint = "脚本名称" }
        val patternInput = EditText(this).apply {
            hint = "匹配规则（* 全部，如 *youtube.com*）"
            setText("*")
        }
        val codeInput = EditText(this).apply {
            hint = "脚本代码（IIFE 形式）"
            gravity = Gravity.TOP
            minLines = 6
        }
        container.addView(nameInput)
        container.addView(patternInput)
        container.addView(codeInput)
        AlertDialog.Builder(this)
            .setTitle("添加油猴脚本")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                if (name.isEmpty() || code.isEmpty()) {
                    toast("名称和代码不能为空")
                    return@setPositiveButton
                }
                val list = settings.getScripts().toMutableList()
                list.add(
                    Userscript(
                        id = "us_${System.currentTimeMillis()}",
                        name = name,
                        enabled = true,
                        urlPattern = patternInput.text.toString().trim().ifEmpty { "*" },
                        code = code
                    )
                )
                settings.saveScripts(list)
                toast("脚本已添加，应用重载中…")
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistory() {
        val history = settings.getHistory()
        if (history.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("历史记录")
                .setMessage("暂无记录")
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        val labels = history.map { (url, title) ->
            val t = title.ifEmpty { url }
            if (t.length > 40) t.take(40) + "…" else t
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("最近访问（${history.size}）")
            .setItems(labels) { _, which ->
                currentWeb()?.loadUrl(history[which].first)
            }
            .setPositiveButton("清空") { _, _ ->
                settings.clearHistory()
                toast("历史已清空")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAbout() {
        val ip = localIp()
        val ua = currentWeb()?.settings?.userAgentString ?: ""
        AlertDialog.Builder(this)
            .setTitle("BrowserDiag")
            .setMessage(
                "版本：3.0.0\n" +
                    "HTTP API：http://$ip:8788\n" +
                    "功能：多标签 / 搜索引擎 / UA / 深色主题 / 油猴 / 书签 / 源码打包 / 嗅探 / 二维码 / 语音等\n" +
                    "\n当前 UA：\n${ua.take(120)}"
            )
            .setPositiveButton("复制 API 地址") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("api", "http://$ip:8788"))
                toast("已复制 http://$ip:8788")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 源码 zip（保留 v2.1） ====================
    private fun downloadSourceZip() {
        val wv = currentWeb() ?: return
        val currentUrl = wv.url ?: ""
        if (currentUrl.isEmpty() || currentUrl == "about:blank") {
            toast("当前没有可打包的页面")
            return
        }
        statusBar.text = "正在获取网页源码…"
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            val consoleJson = synchronized(consoleLogs) { JSONArray(consoleLogs.toList()).toString() }
            wv.evaluateJavascript("JSON.stringify(window.__bdNet||[])") { netRaw ->
                val netJson = try {
                    val v = org.json.JSONTokener(netRaw ?: "[]").nextValue()
                    v as? String ?: "[]"
                } catch (e: Exception) {
                    "[]"
                }
                SourcePacker.pack(
                    context = this,
                    url = currentUrl,
                    title = lastTitle,
                    html = html,
                    consoleJson = consoleJson,
                    networkJson = netJson,
                    ua = wv.settings.userAgentString
                ) { ok, msg ->
                    runOnUiThread {
                        statusBar.text = if (ok) "源码已保存 ✅ $msg" else "打包失败 ❌ $msg"
                        toast(if (ok) "网页源码已保存：$msg" else "打包失败：$msg")
                    }
                }
            }
        }
    }

    // ==================== 油猴规则 / 服务器 ====================
    private fun patternToOriginRules(pattern: String): Set<String> {
        val p = pattern.trim()
        if (p.isEmpty() || p == "*") return setOf("*")
        val host = p.trim('*').trim().lowercase()
            .removePrefix("https://").removePrefix("http://").trim('/')
        if (host.isEmpty()) return setOf("*")
        return buildSet {
            add("https://$host")
            add("http://$host")
            add("https://*.$host")
            add("http://*.$host")
        }
    }

        // ==================== v3.1 新功能（参考 X 浏览器） ====================

    /** 全屏模式：隐藏顶栏/状态栏/底栏，沉浸式浏览 */
    private fun toggleFullscreen() {
        val isFullscreen = bottomBar.visibility != View.VISIBLE
        if (isFullscreen) {
            // 退出全屏
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            statusBar.visibility = View.VISIBLE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            toast("已退出全屏")
        } else {
            // 进入全屏
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            statusBar.visibility = View.GONE
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            toast("全屏模式（再点菜单可退出）")
        }
    }

    /** 网络日志面板：读取 window.__bdNet 展示请求列表 */
    private fun showNetLog() {
        val wv = currentWeb() ?: return toast("无页面")
        wv.evaluateJavascript("JSON.stringify((window.__bdNet||[]).slice(-100).reverse())") { raw ->
            val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
            if (arr.length() == 0) { toast("暂无网络请求记录"); return@evaluateJavascript }
            val lines = (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i)
                val status = o.optInt("status")
                val color = if (status in 200..399) "🟢" else if (status == 0) "🔴" else "🟡"
                "$color ${o.optString("method")} $status  ${o.optString("url")}"
            }
            AlertDialog.Builder(this)
                .setTitle("网络日志（${arr.length()} 条）")
                .setItems(lines.toTypedArray()) { _, idx ->
                    val o = arr.optJSONObject(idx)
                    copyText(o.optString("url"))
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 下载管理：列出系统 DownloadManager 的下载记录，点击打开 */
    private fun showDownloads() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query()
        q.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL or DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING)
        val cursor: Cursor? = try { dm.query(q) } catch (e: Exception) { null }
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            toast("暂无下载记录")
            return
        }
        val names = mutableListOf<String>()
        val uris = mutableListOf<Uri>()
        do {
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            names.add("$title  (${size / 1024}KB)")
            uris.add(Uri.parse(uri ?: ""))
        } while (cursor.moveToNext())
        cursor.close()
        AlertDialog.Builder(this)
            .setTitle("下载管理（${names.size} 个）")
            .setItems(names.toTypedArray()) { _, idx ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uris[idx], "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: ActivityNotFoundException) {
                    toast("无法打开该文件")
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 广告拦截开关：持久化 + 刷新当前页生效 */
    private fun toggleAdBlock() {
        settings.adBlock = !settings.adBlock
        val on = settings.adBlock
        toast(if (on) "广告拦截已开启" else "广告拦截已关闭")
        currentWeb()?.reload()
    }

    /** 允许调试网页（WebView 远程调试，chrome://inspect 可见） */
    private fun toggleDebugWeb() {
        settings.debugWeb = !settings.debugWeb
        WebView.setWebContentsDebuggingEnabled(settings.debugWeb)
        toast(if (settings.debugWeb) "已开启 WebView 远程调试（chrome://inspect 可连接）" else "已关闭远程调试")
    }

    /** 字体大小调节（50%-200%，textZoom 持久化） */
    private fun showFontScale() {
        val options = arrayOf("50%", "75%", "100%", "125%", "150%", "175%", "200%")
        val values = intArrayOf(50, 75, 100, 125, 150, 175, 200)
        val current = settings.fontScale
        val checked = values.indexOfFirst { it == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("字体大小（当前 ${current}%）")
            .setSingleChoiceItems(options, checked) { _, which ->
                settings.fontScale = values[which]
                tabs.all.forEach { it.webView.settings.textZoom = values[which] }
                toast("字体已调整为 ${values[which]}%")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun orientationLabel(): String = when (settings.screenOrientation) {
        "portrait" -> "竖屏"
        "landscape" -> "横屏"
        else -> "自动"
    }

    /** 屏幕方向：自动 / 竖屏 / 横屏 */
    private fun showOrientation() {
        val options = arrayOf("自动旋转", "竖屏锁定", "横屏锁定")
        val checked = when (settings.screenOrientation) {
            "portrait" -> 1
            "landscape" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("屏幕方向")
            .setSingleChoiceItems(options, checked) { _, which ->
                settings.screenOrientation = when (which) {
                    1 -> "portrait"; 2 -> "landscape"; else -> "auto"
                }
                requestedOrientation = when (which) {
                    1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                toast("屏幕方向：${options[which]}")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 定制菜单：菜单项显隐开关（持久化） */
    private fun showMenuConfig() {
        val allItems = listOf(
            "bookmark" to "书签", "save" to "保存页面", "share" to "分享", "find" to "页面查找",
            "translate" to "翻译本页", "widget" to "添加到桌面", "fullscreen" to "全屏模式",
            "sniff" to "嗅探媒体资源", "resources" to "查看页面资源", "source" to "页面源码zip",
            "qr" to "生成二维码", "tts" to "语音播报", "netlog" to "网络日志", "downloads" to "下载管理",
            "devtools" to "开发者工具", "dark" to "深色主题", "engine" to "搜索引擎",
            "ua" to "UA 切换", "userscript" to "油猴脚本", "font" to "字体大小",
            "orientation" to "屏幕方向", "adblock" to "广告拦截", "debugweb" to "允许调试网页",
            "menuconfig" to "定制菜单", "history" to "历史记录", "about" to "关于"
        )
        val cfg = settings.getMenuConfig().toMutableMap()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        allItems.forEachIndexed { i, (id, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 6, 24, 6)
            }
            val cb = android.widget.CheckBox(this).apply {
                text = label
                textSize = 15f
                isChecked = cfg[id] != false
                setOnCheckedChangeListener { _, isChecked -> cfg[id] = isChecked }
            }
            row.addView(cb)
            col.addView(row)
        }
        scroll.addView(col)
        AlertDialog.Builder(this)
            .setTitle("定制菜单（勾选显示）")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ -> settings.setMenuConfig(cfg); toast("菜单已更新") }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("browserdiag", text))
        toast("已复制")
    }

    private fun startServer() {
        var port = 8788
        var started = false
        while (port < 8792) {
            try {
                val s = DiagServer(
                    port,
                    applicationContext,
                    { tabs.current?.webView },
                    { synchronized(consoleLogs) { consoleLogs.toList() } },
                    { settings },
                    { tabs }
                )
                s.start(500, true)
                server = s
                started = true
                break
            } catch (e: Exception) {
                port++
            }
        }
        if (!started) {
            statusBar.text = "API 服务启动失败（端口 8788-8791 均被占用）"
        } else {
            statusBar.text = buildStatusText()
        }
    }

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("127.") == false }
                ?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        server?.stop()
        tts?.stop()
        tts?.shutdown()
        tabs.destroyAll()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (findBar.visibility == View.VISIBLE) {
            hideFindBar()
            return
        }
        val wv = currentWeb()
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    companion object {
        /** 广告拦截域名黑名单（子域名自动匹配） */
        private val AD_BLOCK_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adsystem.com", "criteo.com",
            "taboola.com", "outbrain.com", "amazon-adsystem.com", "pubmatic.com",
            "rubiconproject.com", "moatads.com", "scorecardresearch.com",
            "advertising.com", "adsrvr.org", "quantserve.com", "lijit.com",
            "openx.net", "casalemedia.com", "smartadserver.com", "adroll.com",
            "revcontent.com", "popads.net", "propellerads.com", "adsterra.com"
        )

        private val NETWORK_HOOK_JS = """
            (function(){
              if (window.__bdHooked) return;
              window.__bdHooked = true;
              window.__bdNet = [];
              function record(u,m,s,t){ window.__bdNet.push({url:String(u).slice(0,300),method:m,status:s,type:t}); if(window.__bdNet.length>300) window.__bdNet.shift(); }
              var op = XMLHttpRequest.prototype.open;
              var sp = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(m,u){ this.__u=u; this.__m=m; return op.apply(this,arguments); };
              XMLHttpRequest.prototype.send = function(){
                this.addEventListener('load', function(){ record(this.__u,this.__m,this.status,'xhr'); });
                this.addEventListener('error', function(){ record(this.__u,this.__m,0,'xhr'); });
                return sp.apply(this,arguments);
              };
              var of = window.fetch;
              window.fetch = function(){
                var u = arguments[0];
                return of.apply(this,arguments).then(function(r){ record(typeof u==='string'?u:u.url,'fetch',r.status,'fetch'); return r; })
                  .catch(function(e){ record(typeof u==='string'?u:u.url,'fetch',0,'fetch'); throw e; });
              };
            })();
        """.trimIndent()
    }
}