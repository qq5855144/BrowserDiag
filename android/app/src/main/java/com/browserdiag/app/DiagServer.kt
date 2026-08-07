package com.browserdiag.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
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
        "browser_eval", "browser_screenshot", "browser_perf", "browser_report", "browser_close"
    )

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
}

private fun java.io.InputStream.readFully(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val read = read(bytes, offset, bytes.size - offset)
        if (read < 0) break
        offset += read
    }
}