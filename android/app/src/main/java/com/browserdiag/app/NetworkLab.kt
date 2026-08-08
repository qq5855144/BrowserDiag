package com.browserdiag.app

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.ArrayDeque

/** BrowserDiag 网络实验室支持的规则动作。 */
enum class NetworkRuleAction(
    val key: String,
    val label: String,
    val description: String
) {
    BLOCK("block", "阻止请求", "阻止命中的请求；页面资源返回阻止响应，fetch/XHR 会被中止"),
    REWRITE_URL("rewrite_url", "URL 重写", "将命中的 URL 映射到另一个 HTTP(S) 地址"),
    SET_REQUEST_HEADERS("request_headers", "请求头注入", "新增或覆盖请求 Header"),
    REPLACE_REQUEST_BODY("request_body", "请求 Body 替换", "替换 fetch/XHR 的文本请求体"),
    MOCK_RESPONSE("mock", "Mock 响应", "不访问真实服务器，直接返回自定义状态、Header 与内容"),
    REPLACE_RESPONSE_BODY("response_body", "响应内容替换", "对文本/JSON/JS/CSS/HTML 响应执行字符串或正则替换"),
    SET_RESPONSE_HEADERS("response_headers", "响应头重写", "新增或覆盖响应 Header"),
    INJECT_JS("inject_js", "JavaScript 注入", "在匹配网页 document-start 阶段执行 JavaScript"),
    INJECT_CSS("inject_css", "CSS 注入", "向匹配网页注入 CSS 样式"),
    DELAY("delay", "请求延迟", "在网络请求发送前增加可控延迟，适合弱网调试");

    companion object {
        fun fromKey(value: String?): NetworkRuleAction? =
            entries.firstOrNull { it.key == value?.lowercase() }
    }
}

/**
 * 一条网络规则。urlPattern 默认使用 * 通配符；以 regex: 开头或 /.../ 包围时使用正则。
 * value / replacement 的含义由 action 决定，避免为每个动作维护稀疏字段集合。
 */
data class NetworkRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val urlPattern: String = "*",
    val methods: String = "*",
    val action: NetworkRuleAction,
    val value: String = "",
    val replacement: String = "",
    val headers: Map<String, String> = emptyMap(),
    val statusCode: Int = 200,
    val mimeType: String = "text/plain; charset=utf-8",
    val delayMs: Int = 0
) {
    fun toJson(): JSONObject {
        val headerJson = JSONObject()
        headers.forEach { (name, value) -> headerJson.put(name, value) }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("enabled", enabled)
            .put("priority", priority)
            .put("urlPattern", urlPattern)
            .put("methods", methods)
            .put("action", action.key)
            .put("value", value)
            .put("replacement", replacement)
            .put("headers", headerJson)
            .put("statusCode", statusCode)
            .put("mimeType", mimeType)
            .put("delayMs", delayMs)
    }

    companion object {
        fun fromJson(json: JSONObject): NetworkRule? {
            val action = NetworkRuleAction.fromKey(json.optString("action")) ?: return null
            val id = json.optString("id").trim()
            if (id.isEmpty()) return null
            val headers = linkedMapOf<String, String>()
            json.optJSONObject("headers")?.let { objectHeaders ->
                val keys = objectHeaders.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    headers[key] = objectHeaders.optString(key)
                }
            }
            return NetworkRule(
                id = id,
                name = json.optString("name", action.label),
                enabled = json.optBoolean("enabled", true),
                priority = json.optInt("priority", 100),
                urlPattern = json.optString("urlPattern", "*"),
                methods = json.optString("methods", "*"),
                action = action,
                value = json.optString("value"),
                replacement = json.optString("replacement"),
                headers = headers,
                statusCode = json.optInt("statusCode", 200),
                mimeType = json.optString("mimeType", "text/plain; charset=utf-8"),
                delayMs = json.optInt("delayMs", 0)
            )
        }
    }
}

data class NetworkRuleHit(
    val ts: Long,
    val ruleId: String,
    val ruleName: String,
    val action: String,
    val url: String,
    val phase: String,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ts", ts)
        .put("ruleId", ruleId)
        .put("ruleName", ruleName)
        .put("action", action)
        .put("url", url)
        .put("phase", phase)
        .put("detail", detail)
}

/**
 * 线程安全的网络规则仓库 + WebView 原生资源拦截器。
 * shouldInterceptRequest 在非 UI 线程调用，因此热路径只读取不可变 volatile snapshot。
 */
class NetworkRuleStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("browserdiag_settings", Context.MODE_PRIVATE)
    @Volatile
    private var snapshot: List<NetworkRule> = loadRules()
    private val hits = ArrayDeque<NetworkRuleHit>()

    fun allRules(): List<NetworkRule> = snapshot.toList()

    fun enabledRules(): List<NetworkRule> = snapshot.filter { it.enabled }

    fun findRule(id: String): NetworkRule? = snapshot.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(rule: NetworkRule): NetworkRule {
        networkRuleValidationError(rule)?.let { throw IllegalArgumentException(it) }
        val clean = sanitizeRule(rule)
        val updated = snapshot.toMutableList()
        val index = updated.indexOfFirst { it.id == clean.id }
        if (index >= 0) updated[index] = clean else updated.add(0, clean)
        snapshot = updated.take(MAX_RULES).sortedByDescending { it.priority }
        persist()
        return clean
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val updated = snapshot.filterNot { it.id == id }
        if (updated.size == snapshot.size) return false
        snapshot = updated
        persist()
        return true
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): NetworkRule? {
        val current = snapshot.firstOrNull { it.id == id } ?: return null
        return upsert(current.copy(enabled = enabled))
    }

    @Synchronized
    fun clearRules() {
        snapshot = emptyList()
        persist()
    }

    fun rulesJsonForJs(): String {
        val array = JSONArray()
        snapshot.asSequence()
            .filter { it.enabled }
            .filter { it.action != NetworkRuleAction.INJECT_JS && it.action != NetworkRuleAction.INJECT_CSS }
            .forEach { array.put(it.toJson()) }
        return array.toString()
    }

    fun recentHits(limit: Int = 100): JSONArray {
        val out = JSONArray()
        synchronized(hits) {
            hits.toList().takeLast(limit.coerceIn(1, MAX_HITS)).reversed().forEach { out.put(it.toJson()) }
        }
        return out
    }

    fun clearHits() {
        synchronized(hits) { hits.clear() }
    }

    /**
     * 原生层覆盖 document/script/css/image/font/media 与 GET/HEAD fetch/XHR。
     * POST Body WebResourceRequest 不提供，因此非 GET/HEAD 的 Body/响应改写交给 JS Hook。
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val originalUrl = request.url?.toString().orEmpty()
        if (!isHttpUrl(originalUrl)) return null
        val method = request.method.orEmpty().uppercase().ifBlank { "GET" }
        val matched = snapshot.filter { rule ->
            rule.enabled && matchesNetworkRulePattern(rule.urlPattern, originalUrl) &&
                matchesNetworkRuleMethod(rule.methods, method)
        }
        if (matched.isEmpty()) return null

        val delayRules = matched.filter { it.action == NetworkRuleAction.DELAY && it.delayMs > 0 }
        if (delayRules.isNotEmpty()) {
            val totalDelay = delayRules.sumOf { it.delayMs.toLong() }.coerceAtMost(MAX_DELAY_MS.toLong()).toInt()
            delayRules.forEach { recordHit(it, originalUrl, "request", "延迟 ${it.delayMs} ms") }
            if (totalDelay > 0) runCatching { Thread.sleep(totalDelay.toLong()) }
        }

        matched.firstOrNull {
            it.action == NetworkRuleAction.BLOCK || it.action == NetworkRuleAction.MOCK_RESPONSE
        }?.let { rule ->
            if (rule.action == NetworkRuleAction.BLOCK) {
                recordHit(rule, originalUrl, "request", "已阻止")
                return syntheticResponse(
                    status = 403,
                    mime = "text/plain; charset=utf-8",
                    headers = mapOf("Cache-Control" to "no-store", "X-BrowserDiag-Rule" to rule.id),
                    body = if (method == "HEAD") "" else "Blocked by BrowserDiag network rule: ${rule.name}"
                )
            }
            recordHit(rule, originalUrl, "response", "Mock ${normalizedStatus(rule.statusCode)}")
            return syntheticResponse(
                status = normalizedStatus(rule.statusCode),
                mime = rule.mimeType,
                headers = rule.headers + mapOf("Cache-Control" to "no-store", "X-BrowserDiag-Rule" to rule.id),
                body = if (method == "HEAD" || rule.statusCode == 204 || rule.statusCode == 205) "" else rule.value
            )
        }

        if (method != "GET" && method != "HEAD") return null

        var outboundUrl = originalUrl
        val outboundHeaders = linkedMapOf<String, String>()
        request.requestHeaders.orEmpty().forEach { (key, value) -> outboundHeaders[key] = value }
        var needsProxy = false

        matched.filter { it.action == NetworkRuleAction.REWRITE_URL }.forEach { rule ->
            val rewritten = rewriteNetworkUrl(rule.urlPattern, outboundUrl, rule.value)
            if (rewritten != outboundUrl && isHttpUrl(rewritten)) {
                recordHit(rule, originalUrl, "request", "URL → ${rewritten.take(220)}")
                outboundUrl = rewritten
                needsProxy = true
            }
        }
        val effectiveMatched = snapshot.filter { rule ->
            rule.enabled && matchesNetworkRuleMethod(rule.methods, method) &&
                (matchesNetworkRulePattern(rule.urlPattern, originalUrl) ||
                    matchesNetworkRulePattern(rule.urlPattern, outboundUrl))
        }
        val requestHeaderOverrides = linkedSetOf<String>()
        effectiveMatched.filter { it.action == NetworkRuleAction.SET_REQUEST_HEADERS }.forEach { rule ->
            if (rule.headers.isNotEmpty()) {
                rule.headers.forEach { (key, value) ->
                    if (requestHeaderOverrides.add(key.lowercase())) outboundHeaders[key] = value
                }
                recordHit(rule, originalUrl, "request", "Header ${rule.headers.keys.joinToString(", ").take(180)}")
                needsProxy = true
            }
        }
        if (!sameHttpOrigin(originalUrl, outboundUrl)) {
            // 跨 Origin URL 重写默认不携带原请求凭据；只有显式 Header 规则可重新添加。
            setOf("cookie", "authorization", "proxy-authorization").forEach { sensitive ->
                if (sensitive !in requestHeaderOverrides) {
                    outboundHeaders.keys.toList()
                        .filter { it.equals(sensitive, true) }
                        .forEach { outboundHeaders.remove(it) }
                }
            }
        }
        val responseRules = effectiveMatched.filter {
            it.action == NetworkRuleAction.REPLACE_RESPONSE_BODY ||
                it.action == NetworkRuleAction.SET_RESPONSE_HEADERS
        }
        if (responseRules.isNotEmpty()) needsProxy = true
        if (!needsProxy) return null

        return proxyRequest(
            originalUrl = originalUrl,
            outboundUrl = outboundUrl,
            method = method,
            headers = outboundHeaders,
            responseRules = responseRules
        )
    }

    private fun proxyRequest(
        originalUrl: String,
        outboundUrl: String,
        method: String,
        headers: Map<String, String>,
        responseRules: List<NetworkRule>
    ): WebResourceResponse? {
        var connection: HttpURLConnection? = null
        var handedStreamToWebView = false
        try {
            val conn = URL(outboundUrl).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            headers.forEach { (key, value) ->
                if (key.lowercase() !in REQUEST_HEADER_DENYLIST) {
                    runCatching { conn.setRequestProperty(key, value) }
                }
            }
            if (headers.keys.none { it.equals("User-Agent", true) }) {
                conn.setRequestProperty("User-Agent", "BrowserDiag/3.7")
            }
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (headers.keys.none { it.equals("Cookie", true) }) {
                runCatching { CookieManager.getInstance().getCookie(outboundUrl) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { conn.setRequestProperty("Cookie", it) }
            }

            val status = conn.responseCode
            if (status in 300..399 || status !in 100..599) return null
            val reason = statusReason(status)
            val finalUrl = conn.url?.toString().orEmpty().ifBlank { outboundUrl }
            persistResponseCookies(finalUrl, conn)

            val responseHeaders = linkedMapOf<String, String>()
            conn.headerFields.orEmpty().forEach { (key, values) ->
                if (key != null && key.lowercase() !in RESPONSE_HEADER_DENYLIST) {
                    values?.firstOrNull()?.let { responseHeaders[key] = it }
                }
            }
            val responseHeaderOverrides = linkedSetOf<String>()
            responseRules.filter { it.action == NetworkRuleAction.SET_RESPONSE_HEADERS }.forEach { rule ->
                rule.headers.forEach { (key, value) ->
                    val normalizedKey = key.lowercase()
                    if (
                        normalizedKey !in RESPONSE_HEADER_DENYLIST &&
                        responseHeaderOverrides.add(normalizedKey)
                    ) responseHeaders[key] = value
                }
                if (rule.headers.isNotEmpty()) {
                    recordHit(rule, originalUrl, "response", "Header ${rule.headers.keys.joinToString(", ").take(180)}")
                }
            }

            val contentType = conn.contentType.orEmpty()
            val mime = contentType.substringBefore(';').trim().ifBlank {
                java.net.URLConnection.guessContentTypeFromName(finalUrl) ?: "application/octet-stream"
            }
            val charset = extractCharset(contentType, mime)
            val rawStream = when {
                method == "HEAD" || status == 204 || status == 205 -> null
                status >= 400 -> conn.errorStream
                else -> conn.inputStream
            }
            if (rawStream == null) {
                conn.disconnect()
                connection = null
                return WebResourceResponse(
                    mime,
                    charset?.name(),
                    status,
                    reason,
                    responseHeaders,
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            val bodyRules = responseRules.filter { it.action == NetworkRuleAction.REPLACE_RESPONSE_BODY }
            if (bodyRules.isEmpty() || !isTextMime(mime) || conn.contentLengthLong > MAX_REWRITE_BODY_BYTES.toLong()) {
                handedStreamToWebView = true
                return WebResourceResponse(
                    mime,
                    charset?.name(),
                    status,
                    reason,
                    responseHeaders,
                    DisconnectingInputStream(rawStream, conn)
                )
            }

            val prefix = rawStream.readPrefix(MAX_REWRITE_BODY_BYTES + 1)
            if (prefix.size > MAX_REWRITE_BODY_BYTES) {
                val fullStream = SequenceInputStream(ByteArrayInputStream(prefix), rawStream)
                handedStreamToWebView = true
                return WebResourceResponse(
                    mime,
                    charset?.name(),
                    status,
                    reason,
                    responseHeaders,
                    DisconnectingInputStream(fullStream, conn)
                )
            }

            rawStream.close()
            val usedCharset = charset ?: Charsets.UTF_8
            var text = String(prefix, usedCharset)
            bodyRules.forEach { rule ->
                val changed = replaceNetworkText(text, rule.value, rule.replacement)
                recordHit(
                    rule,
                    originalUrl,
                    "response",
                    if (changed != text) "响应内容已替换" else "响应匹配，未找到替换内容"
                )
                text = changed
            }
            val modified = text.toByteArray(usedCharset)
            responseHeaders.keys.toList().forEach { key ->
                if (key.equals("Content-Length", true) || key.equals("Content-Encoding", true)) {
                    responseHeaders.remove(key)
                }
            }
            responseHeaders["Content-Length"] = modified.size.toString()
            conn.disconnect()
            connection = null
            return WebResourceResponse(
                mime,
                usedCharset.name(),
                status,
                reason,
                responseHeaders,
                ByteArrayInputStream(modified)
            )
        } catch (_: Exception) {
            return null
        } finally {
            if (!handedStreamToWebView) connection?.disconnect()
        }
    }

    private fun syntheticResponse(
        status: Int,
        mime: String,
        headers: Map<String, String>,
        body: String
    ): WebResourceResponse {
        val type = mime.substringBefore(';').trim().ifBlank { "text/plain" }
        val charset = extractCharset(mime, type) ?: Charsets.UTF_8
        val bytes = body.take(MAX_MOCK_BODY_CHARS).toByteArray(charset)
        val cleanHeaders = linkedMapOf<String, String>()
        headers.forEach { (key, value) ->
            if (key.lowercase() !in RESPONSE_HEADER_DENYLIST) cleanHeaders[key] = value
        }
        cleanHeaders["Content-Length"] = bytes.size.toString()
        return WebResourceResponse(
            type,
            charset.name(),
            status,
            statusReason(status),
            cleanHeaders,
            ByteArrayInputStream(bytes)
        )
    }

    private fun persistResponseCookies(url: String, conn: HttpURLConnection) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        conn.headerFields.orEmpty().forEach { (key, values) ->
            if (key?.equals("Set-Cookie", true) == true) {
                values.orEmpty().forEach { cookie -> runCatching { manager.setCookie(url, cookie) } }
            }
        }
    }

    private fun recordHit(rule: NetworkRule, url: String, phase: String, detail: String) {
        synchronized(hits) {
            hits.addLast(
                NetworkRuleHit(
                    ts = System.currentTimeMillis(),
                    ruleId = rule.id,
                    ruleName = rule.name,
                    action = rule.action.key,
                    url = url.take(4096),
                    phase = phase,
                    detail = detail.take(300)
                )
            )
            while (hits.size > MAX_HITS) hits.removeFirst()
        }
    }

    private fun loadRules(): List<NetworkRule> {
        val raw = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    NetworkRule.fromJson(array.optJSONObject(index) ?: continue)?.let { add(sanitizeRule(it)) }
                    if (size >= MAX_RULES) break
                }
            }.sortedByDescending { it.priority }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val array = JSONArray()
        snapshot.take(MAX_RULES).forEach { array.put(it.toJson()) }
        prefs.edit().putString(PREF_KEY, array.toString()).apply()
    }

    private fun sanitizeRule(rule: NetworkRule): NetworkRule {
        val headers = linkedMapOf<String, String>()
        rule.headers.entries.take(MAX_HEADERS).forEach { (rawName, rawValue) ->
            val name = rawName.trim().take(120)
            if (HEADER_NAME.matches(name)) headers[name] = rawValue.replace("\r", "").replace("\n", "").take(4096)
        }
        return rule.copy(
            id = rule.id.trim().ifBlank { "nr_${System.currentTimeMillis()}" }.take(80),
            name = rule.name.trim().ifBlank { rule.action.label }.take(100),
            urlPattern = rule.urlPattern.trim().ifBlank { "*" }.take(MAX_PATTERN_CHARS),
            methods = rule.methods.trim().ifBlank { "*" }.take(120),
            value = rule.value.take(MAX_VALUE_CHARS),
            replacement = rule.replacement.take(MAX_VALUE_CHARS),
            headers = headers,
            statusCode = normalizedStatus(rule.statusCode),
            mimeType = rule.mimeType.trim().ifBlank { "text/plain; charset=utf-8" }.take(160),
            delayMs = rule.delayMs.coerceIn(0, MAX_DELAY_MS),
            priority = rule.priority.coerceIn(0, 1000)
        )
    }

    companion object {
        private const val PREF_KEY = "network_rules_v1"
        private const val MAX_RULES = 40
        private const val MAX_HITS = 200
        private const val MAX_HEADERS = 32
        private const val MAX_PATTERN_CHARS = 1024
        private const val MAX_VALUE_CHARS = 100_000
        private const val MAX_MOCK_BODY_CHARS = 250_000
        private const val MAX_REWRITE_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_DELAY_MS = 10_000
        private const val REQUEST_TIMEOUT_MS = 15_000
        private val HEADER_NAME = Regex("^[!#\\u0024%&'*+.^_`|~0-9A-Za-z-]+\\z")
        private val REQUEST_HEADER_DENYLIST = setOf(
            "host", "content-length", "connection", "transfer-encoding", "accept-encoding",
            "if-none-match", "if-modified-since", "if-match", "if-unmodified-since", "if-range"
        )
        private val RESPONSE_HEADER_DENYLIST = setOf(
            "connection", "transfer-encoding", "content-encoding"
        )
    }
}

/** 通配符 / regex: / /regex/ 三种 URL 匹配方式。 */
internal fun matchesNetworkRulePattern(pattern: String, url: String): Boolean {
    val raw = pattern.trim()
    if (raw.isEmpty() || raw == "*" || raw == "<all_urls>") return true
    val regexText = when {
        raw.startsWith("regex:", true) -> raw.substring(6)
        raw.length > 2 && raw.startsWith('/') && raw.endsWith('/') -> raw.substring(1, raw.length - 1)
        else -> null
    }
    if (regexText != null) {
        if (regexText.length > 512) return false
        return runCatching { Regex(regexText).containsMatchIn(url) }.getOrDefault(false)
    }
    val escaped = buildString {
        raw.forEach { ch ->
            if (ch == '*') append(".*") else append(Regex.escape(ch.toString()))
        }
    }
    return runCatching { Regex("^" + escaped + "\\z", RegexOption.IGNORE_CASE).matches(url) }.getOrDefault(false)
}

internal fun matchesNetworkRuleMethod(methods: String, method: String): Boolean {
    val allowed = methods.trim()
    if (allowed.isEmpty() || allowed == "*") return true
    return allowed.split(',', ' ', '|')
        .asSequence()
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .any { it == method.uppercase() }
}

/** regex URL 重写支持 $1 捕获组；通配符规则使用固定目标并支持 {url}/{host}/{path}/{query}。 */
internal fun rewriteNetworkUrl(pattern: String, originalUrl: String, replacement: String): String {
    if (replacement.isBlank()) return originalUrl
    val rawPattern = pattern.trim()
    val regexText = when {
        rawPattern.startsWith("regex:", true) -> rawPattern.substring(6)
        rawPattern.length > 2 && rawPattern.startsWith('/') && rawPattern.endsWith('/') ->
            rawPattern.substring(1, rawPattern.length - 1)
        else -> null
    }
    if (regexText != null) {
        if (regexText.length > 512) return originalUrl
        return runCatching { Regex(regexText).replaceFirst(originalUrl, replacement) }.getOrDefault(originalUrl)
    }
    val parsed = runCatching { URL(originalUrl) }.getOrNull()
    return replacement
        .replace("{url}", originalUrl)
        .replace("{host}", parsed?.host.orEmpty())
        .replace("{path}", parsed?.path.orEmpty())
        .replace("{query}", parsed?.query.orEmpty())
}

/** find 以 regex: 开头时执行正则替换，否则执行字面字符串替换。 */
internal fun replaceNetworkText(text: String, find: String, replacement: String): String {
    if (find.isEmpty()) return text
    if (find.startsWith("regex:", true)) {
        val pattern = find.substring(6)
        if (pattern.isEmpty() || pattern.length > 512) return text
        return runCatching { Regex(pattern).replace(text, replacement) }.getOrDefault(text)
    }
    return text.replace(find, replacement)
}

internal fun parseNetworkHeaders(text: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    text.lineSequence().take(32).forEach { line ->
        val name = line.substringBefore(':').trim()
        val value = line.substringAfter(':', "").trim()
        if (name.isNotEmpty() && value.isNotEmpty() &&
            Regex("^[!#\\u0024%&'*+.^_`|~0-9A-Za-z-]+\\z").matches(name)
        ) {
            headers[name.take(120)] = value.replace("\r", "").replace("\n", "").take(4096)
        }
    }
    return headers
}

/** 保存和 MCP 写入共用的规则校验，避免“规则存在但永远不会命中”的静默失败。 */
internal fun networkRuleValidationError(rule: NetworkRule): String? {
    if (rule.id.length > 80) return "规则 ID 过长"
    if (rule.name.trim().isEmpty()) return "规则名称不能为空"
    if (rule.name.length > 100) return "规则名称不能超过 100 个字符"
    if (rule.priority !in 0..1000) return "优先级必须在 0-1000 之间"
    val pattern = rule.urlPattern.trim().ifEmpty { "*" }
    if (pattern.length > 1024) return "URL 匹配规则过长"
    val regexText = when {
        pattern.startsWith("regex:", true) -> pattern.substring(6)
        pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/') ->
            pattern.substring(1, pattern.length - 1)
        else -> null
    }
    if (regexText != null) {
        if (regexText.isEmpty()) return "正则表达式不能为空"
        if (regexText.length > 512) return "正则表达式不能超过 512 个字符"
        if (runCatching { Regex(regexText) }.isFailure) return "URL 正则表达式无效"
    }
    val methods = rule.methods.trim()
    if (methods.isNotEmpty() && methods != "*") {
        val invalid = methods.split(',', ' ', '|').filter { it.isNotBlank() }
            .firstOrNull { !Regex("^[A-Za-z]+\\z").matches(it) }
        if (invalid != null) return "请求方法格式无效：$invalid"
    }
    if (rule.value.length > 100_000 || rule.replacement.length > 100_000) {
        return "规则内容超过 100,000 字符上限"
    }
    val invalidHeader = rule.headers.entries.firstOrNull { (name, value) ->
        !Regex("^[!#\\u0024%&'*+.^_`|~0-9A-Za-z-]+\\z").matches(name.trim()) ||
            value.contains('\r') || value.contains('\n')
    }
    if (invalidHeader != null) return "Header 格式无效：${invalidHeader.key}"
    val restrictedRequestHeaders = setOf(
        "host", "content-length", "connection", "transfer-encoding", "accept-encoding",
        "if-none-match", "if-modified-since", "if-match", "if-unmodified-since", "if-range"
    )
    val restrictedResponseHeaders = setOf("connection", "transfer-encoding", "content-encoding")
    return when (rule.action) {
        NetworkRuleAction.REWRITE_URL -> if (rule.value.isBlank()) "URL 重写目标不能为空" else null
        NetworkRuleAction.SET_REQUEST_HEADERS -> when {
            rule.headers.isEmpty() -> "请至少填写一个有效 Header"
            rule.headers.keys.any { it.lowercase() in restrictedRequestHeaders } -> "包含 WebView 不允许重写的请求 Header"
            else -> null
        }
        NetworkRuleAction.SET_RESPONSE_HEADERS -> when {
            rule.headers.isEmpty() -> "请至少填写一个有效 Header"
            rule.headers.keys.any { it.lowercase() in restrictedResponseHeaders } -> "包含 WebView 不允许重写的响应 Header"
            else -> null
        }
        NetworkRuleAction.REPLACE_REQUEST_BODY,
        NetworkRuleAction.REPLACE_RESPONSE_BODY -> {
            if (rule.value.isEmpty()) "查找内容不能为空"
            else if (rule.value.startsWith("regex:", true)) {
                val contentPattern = rule.value.substring(6)
                if (contentPattern.isEmpty() || contentPattern.length > 512 ||
                    runCatching { Regex(contentPattern) }.isFailure
                ) "内容替换正则无效" else null
            } else null
        }
        NetworkRuleAction.MOCK_RESPONSE -> when {
            rule.statusCode !in 200..299 && rule.statusCode !in 400..599 ->
                "Mock 状态码仅支持 2xx / 4xx / 5xx；请用 URL 重写处理跳转"
            rule.value.length > 250_000 -> "Mock 响应体超过 250,000 字符上限"
            rule.statusCode in setOf(204, 205) && rule.value.isNotEmpty() -> "204/205 Mock 不能包含响应 Body"
            rule.mimeType.isBlank() -> "Mock MIME 类型不能为空"
            rule.headers.keys.any { it.lowercase() in restrictedResponseHeaders } -> "Mock 包含不安全的传输 Header"
            else -> null
        }
        NetworkRuleAction.INJECT_JS,
        NetworkRuleAction.INJECT_CSS -> if (rule.value.isBlank()) "注入内容不能为空" else null
        NetworkRuleAction.DELAY -> if (rule.delayMs !in 1..10_000) "延迟必须在 1-10000 ms 之间" else null
        NetworkRuleAction.BLOCK -> null
    }
}

private fun isHttpUrl(value: String): Boolean = runCatching {
    val url = URL(value)
    (url.protocol.equals("http", true) || url.protocol.equals("https", true)) && url.host.isNotBlank()
}.getOrDefault(false)

private fun sameHttpOrigin(first: String, second: String): Boolean = runCatching {
    val a = URL(first)
    val b = URL(second)
    fun port(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort
    a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) && port(a) == port(b)
}.getOrDefault(false)

private fun normalizedStatus(status: Int): Int =
    if (status in 200..299 || status in 400..599) status else 200

private fun statusReason(status: Int): String = when (status) {
    in 100..199 -> "Informational"
    in 200..299 -> "OK"
    in 400..499 -> "Client Error"
    in 500..599 -> "Server Error"
    else -> "OK"
}

private fun extractCharset(contentType: String, mime: String): Charset? {
    val name = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
        .find(contentType)?.groupValues?.getOrNull(1)
    name?.let { runCatching { Charset.forName(it) }.getOrNull() }?.let { return it }
    return if (isTextMime(mime)) Charsets.UTF_8 else null
}

private fun isTextMime(mime: String): Boolean {
    val type = mime.lowercase()
    return type.startsWith("text/") || type.contains("json") || type.contains("javascript") ||
        type.contains("xml") || type.contains("svg") || type.contains("x-www-form-urlencoded")
}

private fun InputStream.readPrefix(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total))
        if (read < 0) break
        if (read == 0) continue
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

private class DisconnectingInputStream(
    delegate: InputStream,
    private val connection: HttpURLConnection
) : FilterInputStream(delegate) {
    override fun close() {
        try {
            super.close()
        } finally {
            connection.disconnect()
        }
    }
}
