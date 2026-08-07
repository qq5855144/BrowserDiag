package com.browserdiag.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * BrowserDiag 2.1：底部导航浏览器 + 内嵌 HTTP 诊断服务器（:8788）。
 * 功能：搜索引擎切换 / UA 切换 / 油猴脚本（document-start 注入）/ 网页源码 zip 打包 / 历史。
 * 平时可作普通浏览器使用，也可作为诊断后端供其它 AI 工具通过 HTTP 调用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var statusBar: TextView
    private lateinit var settings: Settings
    private val consoleLogs = mutableListOf<JSONObject>()
    private val scriptHandlers = ConcurrentHashMap<String, ScriptHandler>()
    private var server: DiagServer? = null
    private var lastTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        buildUi()
        setupWebView()
        startServer()
    }

    // ==================== 崩溃捕获（日志写入下载目录） ====================
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
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ---------- 顶部：地址/搜索栏 ----------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        urlInput = EditText(this).apply {
            hint = "输入网址或搜索"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackgroundColor(0xFFF1F5F9.toInt())
            setPadding(14, 6, 14, 6)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(urlInput)

        topBar.addView(Button(this).apply {
            text = "前往"
            setOnClickListener { navigate(urlInput.text.toString()) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        root.addView(topBar)

        // ---------- 状态栏（API 地址提示） ----------
        statusBar = TextView(this).apply {
            text = "启动中…"
            textSize = 12f
            setPadding(12, 6, 12, 6)
            setBackgroundColor(0xFF0F172A.toInt())
            setTextColor(0xFF94A3B8.toInt())
        }
        root.addView(statusBar)

        // ---------- WebView ----------
        webView = try {
            WebView(this)
        } catch (e: Exception) {
            statusBar.text = "WebView 初始化失败: ${e.message}"
            WebView(this).apply { setBackgroundColor(android.graphics.Color.DKGRAY) }
        }
        webView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(webView)

        // ---------- 底部导航栏 ----------
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 6)
            setBackgroundColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun navButton(symbol: String, action: () -> Unit): Button =
            Button(this).apply {
                text = symbol
                textSize = 18f
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        bottomBar.addView(navButton("🏠") { goHome() })
        bottomBar.addView(navButton("◀") { if (webView.canGoBack()) webView.goBack() })
        bottomBar.addView(navButton("▶") { if (webView.canGoForward()) webView.goForward() })
        bottomBar.addView(navButton("⟳") { webView.reload() })
        bottomBar.addView(navButton("☰") { showMainMenu() })
        root.addView(bottomBar)

        setContentView(root)
    }

    // ==================== WebView 配置 ====================
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
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
            // UA 模式
            userAgentString = settings.uaMode.uaString(WebSettings.getDefaultUserAgent(this@MainActivity))
        }
        WebView.setWebContentsDebuggingEnabled(true)

        // 网络 hook：document-start 注入（捕获所有 XHR/fetch）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val hookHandler = WebViewCompat.addDocumentStartJavaScript(webView, NETWORK_HOOK_JS, setOf("*"))
            scriptHandlers["__net_hook__"] = hookHandler
        }
        // 油猴脚本注册
        reapplyScripts()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val entry = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("type", msg.messageLevel().name.lowercase())
                    .put("text", msg.message())
                synchronized(consoleLogs) {
                    consoleLogs.add(entry)
                    if (consoleLogs.size > 500) consoleLogs.removeAt(0)
                }
                updateStatus()
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                lastTitle = title ?: ""
                urlInput.setText(webView.url ?: "")
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { settings.addHistory(it, lastTitle) }
                updateStatus()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                urlInput.setText(url ?: "")
                updateStatus()
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
            ) {
                val entry = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("type", "error")
                    .put("text", "page error [$errorCode] $description: $failingUrl")
                synchronized(consoleLogs) {
                    consoleLogs.add(entry)
                    if (consoleLogs.size > 500) consoleLogs.removeAt(0)
                }
                updateStatus()
            }
        }

        // 文件下载支持（普通浏览器）
        webView.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
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

        // 首次加载：当前搜索引擎首页
        goHome()
    }

    /** 注册当前启用的油猴脚本（启动时调用；变更脚本后通过 recreate() 重建） */
    private fun reapplyScripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        settings.getScripts().filter { it.enabled }.forEach { s ->
            try {
                val h = WebViewCompat.addDocumentStartJavaScript(webView, s.code, patternToOriginRules(s.urlPattern))
                scriptHandlers[s.id] = h
            } catch (e: Exception) {
                // 脚本语法错误等，忽略该脚本
            }
        }
    }

    /** 用户脚本 URL 规则 → WebView origin 规则 */
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

    // ==================== 导航 ====================
    private fun navigate(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val engine = settings.engine
        when {
            q.startsWith("http://") || q.startsWith("https://") -> webView.loadUrl(q)
            q.startsWith("about:") -> webView.loadUrl(q)
            // 形如 example.com 的域名
            q.contains(".") && !q.contains(" ") && !q.contains("/") && !q.contains("?") ->
                webView.loadUrl("https://$q")
            else -> webView.loadUrl(engine.searchUrl.format(URLEncoder.encode(q, "UTF-8")))
        }
        urlInput.clearFocus()
    }

    private fun goHome() {
        val home = settings.engine.homeUrl
        webView.loadUrl(home)
        urlInput.setText(home)
    }

    // ==================== 主菜单 ====================
    private fun showMainMenu() {
        val items = arrayOf(
            "🔍 搜索引擎：${settings.engine.label}",
            "📱 UA 模式：${settings.uaMode.label}",
            "🐒 油猴脚本管理",
            "📦 下载网页源码 (zip)",
            "🕘 历史记录",
            "ℹ️ 关于 / API 地址"
        )
        AlertDialog.Builder(this)
            .setTitle("功能菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEnginePicker()
                    1 -> showUaPicker()
                    2 -> showScriptManager()
                    3 -> downloadSourceZip()
                    4 -> showHistory()
                    5 -> showAbout()
                }
            }
            .show()
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
                webView.settings.userAgentString =
                    modes[which].uaString(WebSettings.getDefaultUserAgent(this))
                d.dismiss()
                toast("UA 已切换：${modes[which].label}（刷新页面生效）")
            }
            .show()
    }

    // ==================== 油猴脚本管理 ====================
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

    // ==================== 网页源码 zip 打包 ====================
    private fun downloadSourceZip() {
        val currentUrl = webView.url ?: ""
        if (currentUrl.isEmpty() || currentUrl == "about:blank") {
            toast("当前没有可打包的页面")
            return
        }
        statusBar.text = "正在获取网页源码…"
        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            val consoleJson = synchronized(consoleLogs) { JSONArray(consoleLogs.toList()).toString() }
            webView.evaluateJavascript("JSON.stringify(window.__bdNet||[])") { netRaw ->
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
                    ua = webView.settings.userAgentString
                ) { ok, msg ->
                    runOnUiThread {
                        statusBar.text = if (ok) "源码已保存 ✅ $msg" else "打包失败 ❌ $msg"
                        toast(if (ok) "网页源码已保存：$msg" else "打包失败：$msg")
                    }
                }
            }
        }
    }

    // ==================== 历史 ====================
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
                webView.loadUrl(history[which].first)
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
        val ua = webView.settings.userAgentString
        AlertDialog.Builder(this)
            .setTitle("BrowserDiag")
            .setMessage(
                "版本：2.1.0\n" +
                    "HTTP API：http://$ip:8788\n" +
                    "支持：搜索引擎切换 / UA 切换 / 油猴脚本 / 源码打包\n" +
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

    // ==================== 服务器 ====================
    private fun startServer() {
        var port = 8788
        var started = false
        while (port < 8792) {
            try {
                val s = DiagServer(
                    port,
                    applicationContext,
                    { if (::webView.isInitialized) webView else null },
                    { synchronized(consoleLogs) { consoleLogs.toList() } }
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
            updateStatus()
        }
    }

    private fun updateStatus() {
        runOnUiThread {
            val ip = localIp()
            val errs = synchronized(consoleLogs) { consoleLogs.filter { it.optString("type") == "error" }.size }
            statusBar.text = "API: http://$ip:8788  |  ${settings.engine.label}  |  ${settings.uaMode.label}  |  console: ${consoleLogs.size} (err $errs)"
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
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    companion object {
        /** XHR/fetch 网络监听 hook（document-start 注入） */
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