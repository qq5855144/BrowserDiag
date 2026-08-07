package com.browserdiag.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * BrowserDiag 独立 APP：WebView 浏览器 + 内嵌 HTTP 诊断服务器（:8788）。
 * 既可直接浏览/调试网页，也可作为诊断后端供其它 AI 工具通过 HTTP 调用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var statusBar: TextView
    private val consoleLogs = mutableListOf<JSONObject>()
    private var server: DiagServer? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupWebView()
        startServer()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 工具栏：后退 / 前进 / 刷新 / 地址栏 / 前往
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun addButton(text: String, onClick: () -> Unit) {
            toolbar.addView(Button(this).apply {
                this.text = text
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }
        addButton("←") { webView.goBack() }
        addButton("→") { webView.goForward() }
        addButton("↻") { webView.reload() }
        urlInput = EditText(this).apply {
            hint = "输入网址"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        toolbar.addView(urlInput)
        addButton("Go") {
            val u = urlInput.text.toString().trim()
            if (u.isNotEmpty()) webView.loadUrl(if (u.startsWith("http")) u else "https://$u")
        }
        root.addView(toolbar)

        webView = WebView(this)
        webView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        root.addView(webView)

        statusBar = TextView(this).apply {
            text = "启动中…"
            setPadding(12, 8, 12, 8)
        }
        root.addView(statusBar)

        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        WebView.setWebContentsDebuggingEnabled(true)

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
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectNetworkHooks()
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

        webView.loadUrl("https://www.google.com")
        urlInput.setText("https://www.google.com")
    }

    /** 注入网络监听 hook（XHR/fetch）与性能采样函数 */
    private fun injectNetworkHooks() {
        val js = """
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
        webView.evaluateJavascript(js, null)
    }

    private fun startServer() {
        server = DiagServer(
            applicationContext,
            { if (::webView.isInitialized) webView else null },
            { synchronized(consoleLogs) { consoleLogs.toList() } }
        )
        server?.start(500, true)
        updateStatus()
    }

    private fun updateStatus() {
        runOnUiThread {
            val ip = localIp()
            val errs = synchronized(consoleLogs) { consoleLogs.filter { it.optString("type") == "error" }.size }
            statusBar.text = "API: http://$ip:8788  |  console: ${consoleLogs.size} (errors: $errs)"
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

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}