package com.browserdiag.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 内嵌 HTTP API 服务器（:8788），接口与 Node 版 BrowserDiag（server.js）一致：
 * browser_open/state/eval/console/network/screenshot/perf/report/close。
 * 其它 AI 工具（不支持安装插件）可通过 HTTP 直接调用本 APK 内的浏览器。
 */
class DiagServer(
    private val port: Int,
    private val context: Context,
    private val getWebView: () -> WebView?,
    private val getConsoleLogs: () -> List<JSONObject>,
    private val getSettings: () -> Settings,
    private val getTabs: () -> Tabs?,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Content-Type")
            return r
        }
        val tool = session.uri.removePrefix("/api/").trim('/')
        val params = parseParams(session)
        val result = try {
            when (tool) {
                "browser_state" -> state()
                "browser_console" -> console(params)
                "browser_network" -> network()
                "browser_eval" -> eval(params)
                "browser_open" -> open(params)
                "browser_screenshot" -> screenshot()
                "browser_perf" -> perf()
                "browser_report" -> report()
                "browser_source" -> source()
                "browser_tabs" -> tabs()
                "browser_text" -> text()
                "browser_interactive" -> interactive()
                "browser_history" -> history(params)
                "browser_bookmarks" -> bookmarks(params)
                "browser_http" -> httpRequest(params)
                "browser_netlog" -> netlog()
                "browser_close" -> JSONObject().put("closed", true)
                else -> JSONObject().put("error", "unknown tool: $tool").put("available", tools())
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString())
        }
        val r = newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return r
    }

    private fun tools() = arrayOf(
        "browser_open", "browser_state", "browser_console", "browser_network",
        "browser_eval", "browser_screenshot", "browser_perf", "browser_report",
        "browser_source", "browser_tabs", "browser_text", "browser_interactive",
        "browser_history", "browser_bookmarks", "browser_http", "browser_netlog",
        "browser_close"
    )

    /** 获取当前页面 HTML 源码（供 AI 分析与后续开发） */
    private fun source(): JSONObject {
        val html = evalJs("document.documentElement.outerHTML") ?: ""
        val url = evalJs("location.href") ?: ""
        return JSONObject()
            .put("url", url.trim('"'))
            .put("htmlLength", html.length)
            .put("html", html)
    }

    private fun parseParams(session: IHTTPSession): JSONObject {
        return try {
            val bytes = session.inputStream.readBytes()
            if (bytes.isNotEmpty()) JSONObject(String(bytes, Charsets.UTF_8)) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private fun evalJs(expression: String, timeoutSec: Long = 15): String? {
        val wv = getWebView() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        onMain {
            wv.evaluateJavascript(expression) { value ->
                result = value
                latch.countDown()
            }
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return result
    }

    private fun state(): JSONObject {
        val raw = evalJs("JSON.stringify({title:document.title,url:location.href,ready:document.readyState,body:(document.body?document.body.innerText:'').slice(0,2000)})") ?: "{}"
        val page = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
        val logs = getConsoleLogs()
        val errs = logs.filter { it.optString("type") == "error" }
        return JSONObject().put("page", page).put("consoleCount", logs.size).put("consoleErrors", errs.size)
    }

    private fun console(params: JSONObject): JSONObject {
        val type = params.optString("type")
        val limit = params.optInt("limit", 50)
        var logs = getConsoleLogs()
        if (type.isNotEmpty()) logs = logs.filter { it.optString("type") == type }
        val shown = logs.takeLast(limit).reversed()
        val errs = getConsoleLogs().filter { it.optString("type") == "error" }
        return JSONObject().put("total", logs.size).put("errorCount", errs.size).put("logs", shown)
    }

    private fun network(): JSONObject {
        val raw = evalJs("JSON.stringify((window.__bdNet||[]).slice(-100))") ?: "[]"
        val net = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
        val fails = org.json.JSONArray()
        for (i in 0 until net.length()) {
            val item = net.optJSONObject(i)
            if (item != null && (item.optInt("status") == 0 || item.optInt("status") >= 400)) {
                fails.put(item)
            }
        }
        return JSONObject().put("total", net.length()).put("failures", fails).put("logs", net)
    }

    private fun eval(params: JSONObject): JSONObject {
        val expression = params.optString("expression")
        if (expression.isEmpty()) return JSONObject().put("error", "expression required")
        return JSONObject().put("result", evalJs(expression) ?: "TIMEOUT_OR_NO_PAGE")
    }

    private fun open(params: JSONObject): JSONObject {
        val url = params.optString("url")
        val waitMs = params.optLong("waitMs", 4000)
        val wv = getWebView() ?: return JSONObject().put("error", "webview not ready")
        onMain { wv.loadUrl(url) }
        Thread.sleep(waitMs.coerceIn(1000, 15000))
        return state()
    }

    private fun screenshot(): JSONObject {
        val wv = getWebView() ?: return JSONObject().put("error", "webview not ready")
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        onMain {
            try {
                val b = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                wv.draw(Canvas(b))
                bitmap = b
            } catch (e: Exception) {
            }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        val file = File(context.filesDir, "browserdiag_shot.png")
        bitmap?.let {
            runCatching { file.outputStream().use { s -> it.compress(Bitmap.CompressFormat.PNG, 90, s) } }
        }
        return JSONObject().put("path", file.absolutePath).put("bytes", file.length())
    }

    private fun perf(): JSONObject {
        val raw = evalJs("JSON.stringify((function(){var nav=performance.getEntriesByType('navigation')[0];var res=performance.getEntriesByType('resource');var total=res.reduce(function(s,e){return s+(e.transferSize||0)},0);var slow=res.filter(function(e){return e.duration>2000}).sort(function(a,b){return b.duration-a.duration}).slice(0,10).map(function(e){return {name:e.name.slice(0,120),dur:Math.round(e.duration)}});return {url:location.href,timing:nav?{ttfb:Math.round(nav.responseStart-nav.requestStart),domContentLoaded:Math.round(nav.domContentLoadedEventEnd-nav.startTime),load:Math.round(nav.loadEventEnd-nav.startTime),protocol:nav.nextHopProtocol}:null,resources:{count:res.length,totalBytes:total},slowResources:slow}})())") ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject().put("error", "perf eval failed") }
    }

    private fun report(): JSONObject {
        val state = state()
        val perfRaw = perf()
        val logs = getConsoleLogs()
        val errs = logs.filter { it.optString("type") == "error" }.takeLast(20)
        val issues = org.json.JSONArray()
        if (errs.isNotEmpty()) issues.put("console errors: ${errs.size}")
        val net = network()
        val failCount = net.optJSONArray("failures")?.length() ?: 0
        if (failCount > 0) issues.put("failed/4xx/5xx requests: $failCount")
        val timing = perfRaw.optJSONObject("timing")
        if (timing != null && timing.optInt("ttfb") > 2000) issues.put("slow TTFB: ${timing.optInt("ttfb")}ms")
        if (timing != null && timing.optInt("load") > 10000) issues.put("slow load: ${timing.optInt("load")}ms")
        return JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("page", state.opt("page"))
            .put("issues", issues)
            .put("healthy", issues.length() == 0)
            .put("console", JSONObject().put("total", state.optInt("consoleCount")).put("errorSample", errs))
            .put("network", JSONObject().put("total", net.optInt("total")).put("failures", failCount))
            .put("perf", timing ?: JSONObject.NULL)
            .put("pageErrors", JSONObject.NULL)
    }

    // ==================== v3.1 新增 API（参考 mcp-chrome 能力） ====================

    /** 列出所有标签页（mcp-chrome: get_windows_and_tabs / chrome_switch_tab） */
    private fun tabs(): JSONObject {
        val tbs = getTabs() ?: return JSONObject().put("error", "tabs not ready")
        val arr = JSONArray()
        tbs.all.forEach { t ->
            arr.put(JSONObject()
                .put("id", t.id)
                .put("title", t.title.ifEmpty { t.url })
                .put("url", t.url)
                .put("active", t.visible))
        }
        return JSONObject().put("count", arr.length()).put("tabs", arr)
    }

    /** 提取页面正文文本（mcp-chrome: chrome_get_web_content） */
    private fun text(): JSONObject {
        val raw = evalJs(
            "(function(){var a=document.querySelector('article')||document.body;var t=(a?a.innerText:'').replace(/\\n{3,}/g,'\\n\\n');return JSON.stringify({title:document.title,url:location.href,text:t.slice(0,20000),length:t.length})})()"
        ) ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject().put("error", "text extract failed") }
    }

    /** 查找可点击元素（mcp-chrome: chrome_get_interactive_elements） */
    private fun interactive(): JSONObject {
        val raw = evalJs(
            "(function(){var els=Array.from(document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[onclick],[tabindex]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}).slice(0,60);return JSON.stringify(els.map(function(e,i){function sel(n){if(!n||n===document.body)return '';if(n.id)return '#'+n.id;var p=n.parentElement;var s=p?Array.from(p.children).indexOf(n)+1:1;return sel(p)+'>'+n.tagName.toLowerCase()+':nth-child('+s+')'}return{tag:e.tagName.toLowerCase(),text:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,80),href:e.getAttribute('href')||'',type:e.getAttribute('type')||'',selector:sel(e).slice(0,200)}}))})()"
        ) ?: "[]"
        return try {
            JSONObject().put("count", JSONArray(raw).length()).put("elements", JSONArray(raw))
        } catch (e: Exception) {
            JSONObject().put("error", "interactive extract failed")
        }
    }

    /** 搜索浏览历史（mcp-chrome: chrome_history） */
    private fun history(params: JSONObject): JSONObject {
        val s = getSettings()
        val keyword = params.optString("keyword").lowercase()
        val limit = params.optInt("limit", 20)
        var list = s.getHistory()
        if (keyword.isNotEmpty()) list = list.filter { it.first.contains(keyword, true) || it.second.contains(keyword, true) }
        val arr = JSONArray()
        list.take(limit).forEach { (u, t) -> arr.put(JSONObject().put("url", u).put("title", t)) }
        return JSONObject().put("count", arr.length()).put("items", arr)
    }

    /** 书签查询/添加/删除（mcp-chrome: chrome_bookmark_search/add/delete） */
    private fun bookmarks(params: JSONObject): JSONObject {
        val s = getSettings()
        val action = params.optString("action", "search")
        return when (action) {
            "add" -> {
                val name = params.optString("name", params.optString("title", "书签"))
                val url = params.optString("url")
                if (url.isEmpty()) JSONObject().put("error", "url required")
                else { s.addBookmark(name, url); JSONObject().put("added", true).put("name", name).put("url", url) }
            }
            "delete" -> {
                val url = params.optString("url")
                s.removeBookmark(url)
                JSONObject().put("deleted", true).put("url", url)
            }
            else -> {
                val keyword = params.optString("keyword").lowercase()
                var list = s.getBookmarks()
                if (keyword.isNotEmpty()) list = list.filter { it.first.contains(keyword, true) || it.second.contains(keyword, true) }
                val arr = JSONArray()
                list.forEach { (n, u) -> arr.put(JSONObject().put("name", n).put("url", u)) }
                JSONObject().put("count", arr.length()).put("items", arr)
            }
        }
    }

    /** 自定义 HTTP GET 请求（mcp-chrome: chrome_network_request） */
    private fun httpRequest(params: JSONObject): JSONObject {
        val urlStr = params.optString("url")
        if (urlStr.isEmpty()) return JSONObject().put("error", "url required")
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = params.optInt("timeoutMs", 10000)
            conn.readTimeout = params.optInt("timeoutMs", 10000)
            conn.setRequestProperty("User-Agent", "BrowserDiag/3.1")
            conn.setRequestProperty("Accept", "*/*")
            // 附加请求头（JSON 对象）
            val headers = params.optJSONObject("headers")
            if (headers != null) {
                val it = headers.keys()
                while (it.hasNext()) { val k = it.next(); conn.setRequestProperty(k, headers.optString(k)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val respHeaders = JSONObject()
            conn.headerFields.forEach { (k, v) -> if (k != null) respHeaders.put(k, v.firstOrNull() ?: "") }
            conn.disconnect()
            JSONObject()
                .put("status", code)
                .put("url", urlStr)
                .put("bodyLength", body.length)
                .put("body", body.take(50000))
                .put("headers", respHeaders)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString())
        }
    }

    /** 网络日志（含响应状态，mcp-chrome: chrome_network_capture） */
    private fun netlog(): JSONObject {
        val raw = evalJs("JSON.stringify((window.__bdNet||[]).slice(-200))") ?: "[]"
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return JSONObject().put("count", arr.length()).put("logs", arr)
    }
}

private fun java.io.InputStream.readFully(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) break
        offset += read
    }
}