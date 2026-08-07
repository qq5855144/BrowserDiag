package com.browserdiag.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android 内嵌诊断 HTTP API。
 * 默认仅监听 loopback；所有操作均要求调用方提供随机 API Token。
 */
class DiagServer(
    listenHost: String,
    port: Int,
    private val context: Context,
    private val getWebView: () -> WebView?,
    private val getConsoleLogs: () -> List<JSONObject>,
    private val getSettings: () -> Settings,
    private val getTabs: () -> Tabs?,
    private val apiToken: String,
) : NanoHTTPD(listenHost, port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            addCorsHeaders(r)
            return r
        }
        if (!isAuthorized(session)) {
            return jsonResponse(
                Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "unauthorized")
            )
        }
        if (session.method != Method.GET && session.method != Method.POST) {
            return jsonResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "method not allowed")
            )
        }

        val tool = session.uri.removePrefix("/api/").trim('/')
        if (tool.isEmpty() || tool !in tools()) {
            return jsonResponse(
                Response.Status.NOT_FOUND,
                JSONObject().put("error", "unknown tool: $tool").put("available", tools())
            )
        }

        val params = try {
            parseParams(session)
        } catch (e: IllegalArgumentException) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", e.message ?: "invalid request")
            )
        }
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
                "browser_close" -> close()
                else -> JSONObject().put("error", "unknown tool: $tool")
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString())
        }
        return jsonResponse(Response.Status.OK, result)
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
        return evalObject(
            "(function(){var h=document.documentElement?document.documentElement.outerHTML:'';" +
                "return JSON.stringify({url:location.href,htmlLength:h.length," +
                "truncated:h.length>$MAX_SOURCE_CHARS,html:h.slice(0,$MAX_SOURCE_CHARS)})})()"
        )
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val headers = session.headers
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        val auth = header("Authorization")
        val bearer = auth?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')?.trim()
        val direct = header("X-BrowserDiag-Token")?.trim()
        val candidate = bearer?.takeIf { it.isNotEmpty() } ?: direct ?: return false
        return MessageDigest.isEqual(
            apiToken.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8)
        )
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-BrowserDiag-Token"
        )
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        val response = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
        addCorsHeaders(response)
        return response
    }

    private fun parseParams(session: IHTTPSession): JSONObject {
        val params = JSONObject()
        session.parameters.forEach { (key, values) ->
            params.put(key, values.firstOrNull() ?: "")
        }

        val declaredLength = session.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_REQUEST_BYTES) {
            throw IllegalArgumentException("request body too large")
        }

        if (session.method != Method.POST || declaredLength == null || declaredLength <= 0) {
            return params
        }
        val bytes = session.inputStream.readExactly(declaredLength.toInt())
        if (bytes.isNotEmpty()) {
            val body = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid JSON body")
            }
            body.keys().forEach { key -> params.put(key, body.get(key)) }
        }
        return params
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private fun <T> onMainResult(timeoutSec: Long = 5, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrNull()
        }
        val latch = CountDownLatch(1)
        var result: T? = null
        onMain {
            result = runCatching(block).getOrNull()
            latch.countDown()
        }
        return if (latch.await(timeoutSec, TimeUnit.SECONDS)) result else null
    }

    private fun evalJs(expression: String, timeoutSec: Long = 15): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        onMain {
            val wv = getWebView()
            if (wv == null) {
                latch.countDown()
                return@onMain
            }
            wv.evaluateJavascript(expression) { value ->
                result = value
                latch.countDown()
            }
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return result
    }

    private fun evalValue(expression: String): Any? {
        val raw = evalJs(expression) ?: return null
        return decodeEvalValue(raw)
    }

    private fun decodeEvalValue(raw: String): Any? {
        if (raw == "null" || raw == "undefined") return null
        return try {
            JSONTokener(raw).nextValue()
        } catch (e: Exception) {
            raw
        }
    }

    private fun evalObject(expression: String): JSONObject {
        val value = evalValue(expression)
        return when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
            else -> JSONObject()
        }
    }

    private fun evalArray(expression: String): JSONArray {
        val value = evalValue(expression)
        return when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrDefault(JSONArray())
            else -> JSONArray()
        }
    }

    private fun state(): JSONObject {
        val page = evalObject(
            "JSON.stringify({title:document.title,url:location.href,ready:document.readyState," +
                "body:(document.body?document.body.innerText:'').slice(0,2000)})"
        )
        val logs = getConsoleLogs()
        val errs = logs.filter { it.optString("type") == "error" }
        return JSONObject().put("page", page).put("consoleCount", logs.size).put("consoleErrors", errs.size)
    }

    private fun console(params: JSONObject): JSONObject {
        val type = params.optString("type")
        val limit = params.optInt("limit", 50).coerceIn(1, 200)
        var logs = getConsoleLogs()
        if (type.isNotEmpty()) logs = logs.filter { it.optString("type") == type }
        val shown = logs.takeLast(limit).reversed()
        val errs = getConsoleLogs().filter { it.optString("type") == "error" }
        return JSONObject().put("total", logs.size).put("errorCount", errs.size).put("logs", shown)
    }

    private fun network(): JSONObject {
        val net = evalArray("JSON.stringify((window.__bdNet||[]).slice(-100))")
        val fails = JSONArray()
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
        val raw = evalJs(expression) ?: return JSONObject().put("error", "TIMEOUT_OR_NO_PAGE")
        return JSONObject().put("result", decodeEvalValue(raw) ?: JSONObject.NULL)
    }

    private fun open(params: JSONObject): JSONObject {
        val url = validatedHttpUrl(params.optString("url"))
            ?: return JSONObject().put("error", "valid http(s) url required")
        val waitMs = params.optLong("waitMs", 4000)
        val loaded = onMainResult {
            val wv = getWebView() ?: return@onMainResult false
            wv.loadUrl(url.toString())
            true
        } ?: false
        if (!loaded) return JSONObject().put("error", "webview not ready")
        Thread.sleep(waitMs.coerceIn(1000, 15000))
        return state()
    }

    private fun screenshot(): JSONObject {
        val shot = onMainResult<Bitmap> {
            val wv = getWebView() ?: error("webview not ready")
            if (wv.width <= 0 || wv.height <= 0) error("webview has no drawable size")
            Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                wv.draw(Canvas(bitmap))
            }
        } ?: return JSONObject().put("error", "screenshot failed")
        val bytes = ByteArrayOutputStream().use { out ->
            if (!shot.compress(Bitmap.CompressFormat.PNG, 90, out)) {
                return JSONObject().put("error", "screenshot encode failed")
            }
            out.toByteArray()
        }
        val file = File(context.filesDir, "browserdiag_shot.png")
        runCatching { file.writeBytes(bytes) }
        return JSONObject()
            .put("path", file.absolutePath)
            .put("bytes", bytes.size)
            .put("mimeType", "image/png")
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun perf(): JSONObject {
        return evalObject("JSON.stringify((function(){var nav=performance.getEntriesByType('navigation')[0];var res=performance.getEntriesByType('resource');var total=res.reduce(function(s,e){return s+(e.transferSize||0)},0);var slow=res.filter(function(e){return e.duration>2000}).sort(function(a,b){return b.duration-a.duration}).slice(0,10).map(function(e){return {name:e.name.slice(0,120),dur:Math.round(e.duration)}});return {url:location.href,timing:nav?{ttfb:Math.round(nav.responseStart-nav.requestStart),domContentLoaded:Math.round(nav.domContentLoadedEventEnd-nav.startTime),load:Math.round(nav.loadEventEnd-nav.startTime),protocol:nav.nextHopProtocol}:null,resources:{count:res.length,totalBytes:total},slowResources:slow}})())")
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

    // ==================== v3.x 增强 API ====================

    /** 列出所有标签页（mcp-chrome: get_windows_and_tabs / chrome_switch_tab） */
    private fun tabs(): JSONObject {
        return onMainResult {
            val tbs = getTabs() ?: return@onMainResult JSONObject().put("error", "tabs not ready")
            val arr = JSONArray()
            tbs.all.forEach { t ->
                arr.put(JSONObject()
                    .put("id", t.id)
                    .put("title", t.title.ifEmpty { t.url })
                    .put("url", t.url)
                    .put("active", t.visible))
            }
            JSONObject().put("count", arr.length()).put("tabs", arr)
        } ?: JSONObject().put("error", "tabs timeout")
    }

    /** 提取页面正文文本（mcp-chrome: chrome_get_web_content） */
    private fun text(): JSONObject {
        return evalObject(
            "(function(){var a=document.querySelector('article')||document.body;var t=(a?a.innerText:'').replace(/\\n{3,}/g,'\\n\\n');return JSON.stringify({title:document.title,url:location.href,text:t.slice(0,20000),length:t.length})})()"
        )
    }

    /** 查找可点击元素（mcp-chrome: chrome_get_interactive_elements） */
    private fun interactive(): JSONObject {
        val arr = evalArray(
            "(function(){var els=Array.from(document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[onclick],[tabindex]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0}).slice(0,60);return JSON.stringify(els.map(function(e,i){function sel(n){if(!n||n===document.body)return '';if(n.id)return '#'+n.id;var p=n.parentElement;var s=p?Array.from(p.children).indexOf(n)+1:1;return sel(p)+'>'+n.tagName.toLowerCase()+':nth-child('+s+')'}return{tag:e.tagName.toLowerCase(),text:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,80),href:e.getAttribute('href')||'',type:e.getAttribute('type')||'',selector:sel(e).slice(0,200)}}))})()"
        )
        return JSONObject().put("count", arr.length()).put("elements", arr)
    }

    /** 搜索浏览历史（mcp-chrome: chrome_history） */
    private fun history(params: JSONObject): JSONObject {
        val s = getSettings()
        val keyword = params.optString("keyword").lowercase()
        val limit = params.optInt("limit", 20).coerceIn(1, 100)
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
                val url = validatedHttpUrl(params.optString("url"))?.toString()
                if (url == null) JSONObject().put("error", "valid http(s) url required")
                else { s.addBookmark(name, url); JSONObject().put("added", true).put("name", name).put("url", url) }
            }
            "delete" -> {
                val url = params.optString("url")
                if (url.isEmpty()) JSONObject().put("error", "url required")
                else {
                    s.removeBookmark(url)
                    JSONObject().put("deleted", true).put("url", url)
                }
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
        val url = validatedHttpUrl(urlStr)
            ?: return JSONObject().put("error", "valid http(s) url required")
        var conn: HttpURLConnection? = null
        return try {
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            val timeout = params.optInt("timeoutMs", 10000).coerceIn(1000, 30000)
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.setRequestProperty("User-Agent", "BrowserDiag/3.2")
            conn.setRequestProperty("Accept", "*/*")
            // 附加请求头（JSON 对象）
            val headers = params.optJSONObject("headers")
            if (headers != null) {
                val it = headers.keys()
                while (it.hasNext()) { val k = it.next(); conn.setRequestProperty(k, headers.optString(k)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val (bodyBytes, truncated) = stream?.use { it.readCapped(MAX_HTTP_RESPONSE_BYTES) }
                ?: (ByteArray(0) to false)
            val body = String(bodyBytes, Charsets.UTF_8)
            val respHeaders = JSONObject()
            conn.headerFields.forEach { (k, v) -> if (k != null) respHeaders.put(k, v.firstOrNull() ?: "") }
            JSONObject()
                .put("status", code)
                .put("url", url.toString())
                .put("bodyBytes", bodyBytes.size)
                .put("truncated", truncated)
                .put("body", body)
                .put("headers", respHeaders)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.toString())
        } finally {
            conn?.disconnect()
        }
    }

    /** 网络日志（含响应状态，mcp-chrome: chrome_network_capture） */
    private fun netlog(): JSONObject {
        val arr = evalArray("JSON.stringify((window.__bdNet||[]).slice(-200))")
        return JSONObject().put("count", arr.length()).put("logs", arr)
    }

    private fun close(): JSONObject {
        val hadPage = onMainResult {
            val wv = getWebView() ?: return@onMainResult false
            wv.stopLoading()
            wv.loadUrl("about:blank")
            true
        } ?: false
        return JSONObject().put("closed", true).put("hadPage", hadPage)
    }

    private fun validatedHttpUrl(raw: String): URL? = runCatching {
        URL(raw.trim()).takeIf {
            (it.protocol.equals("http", true) || it.protocol.equals("https", true)) &&
                it.host.isNotBlank()
        }
    }.getOrNull()

    companion object {
        private const val MAX_REQUEST_BYTES = 256 * 1024
        private const val MAX_HTTP_RESPONSE_BYTES = 512 * 1024
        private const val MAX_SOURCE_CHARS = 500_000
    }
}

private fun java.io.InputStream.readExactly(expectedBytes: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(expectedBytes, 8192))
    val buffer = ByteArray(8192)
    var remaining = expectedBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) throw IllegalArgumentException("incomplete request body")
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}

private fun java.io.InputStream.readCapped(maxBytes: Int): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) return out.toByteArray() to false
        out.write(buffer, 0, read)
        remaining -= read
    }
    val truncated = read() >= 0
    return out.toByteArray() to truncated
}
