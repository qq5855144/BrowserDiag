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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android 内嵌 MCP Streamable HTTP 服务 + 兼容 HTTP Bridge。
 * 默认仅监听 loopback；远程连接默认要求随机 MCP Token。
 */
class DiagServer(
    private val listenHost: String,
    port: Int,
    private val context: Context,
    private val getWebView: () -> WebView?,
    private val getConsoleLogs: () -> List<JSONObject>,
    private val getSettings: () -> Settings,
    private val getTabs: () -> Tabs?,
    private val getNetworkRuleStore: () -> NetworkRuleStore,
    private val onNetworkRulesChanged: () -> Unit,
    private val apiToken: String,
) : NanoHTTPD(listenHost, port) {

    override fun serve(session: IHTTPSession): Response {
        val mcpEndpoint = isMcpEndpoint(session.uri)
        val legacySseEndpoint = isLegacySseEndpoint(session.uri)
        val legacyMessageEndpoint = isLegacyMessageEndpoint(session.uri)
        val mcpTransportRoute = mcpEndpoint || legacySseEndpoint || legacyMessageEndpoint
        if (session.method == Method.OPTIONS) {
            if (mcpTransportRoute && !isTrustedMcpOrigin(session)) {
                return mcpErrorResponse(session, Response.Status.FORBIDDEN, -32000, "Origin not allowed")
            }
            val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            if (mcpTransportRoute) addMcpHeaders(r, session) else addCorsHeaders(r)
            return r
        }
        if (legacySseEndpoint && session.method == Method.POST) return serveLegacySseMessage(session)
        if (legacySseEndpoint || isLegacySseFallbackRequest(session)) return serveLegacySse(session)
        if (legacyMessageEndpoint) return serveLegacySseMessage(session)
        if (mcpEndpoint) return serveMcp(session)
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
        val result = executeTool(tool, params)
        return jsonResponse(Response.Status.OK, result)
    }

    private fun tools() = arrayOf(
        "BrowserDiag",
        "browser_open", "browser_state", "browser_console", "browser_network",
        "browser_eval", "browser_screenshot", "browser_perf", "browser_report",
        "browser_source", "browser_tabs", "browser_text", "browser_interactive",
        "browser_history", "browser_bookmarks", "browser_http", "browser_netlog",
        "browser_network_rules", "browser_close"
    )

    private fun executeTool(tool: String, params: JSONObject): JSONObject = try {
        when (tool) {
            "BrowserDiag" -> browserDiagFacade(params)
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
            "browser_network_rules" -> networkRuleTool(params)
            "browser_close" -> close()
            else -> JSONObject().put("error", "unknown tool: $tool")
        }
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.toString())
    }

    // ==================== Native MCP Streamable HTTP ====================

    private fun isMcpEndpoint(uri: String): Boolean {
        val path = uri.substringBefore('?').trimEnd('/')
        return path.isEmpty() || path == "/mcp"
    }

    private fun isLegacySseEndpoint(uri: String): Boolean =
        uri.substringBefore('?').trimEnd('/').let { it == "/sse" || it == "/mcp/sse" }

    private fun isLegacyMessageEndpoint(uri: String): Boolean {
        val path = uri.substringBefore('?').trimEnd('/')
        return path == "/message" || path == "/messages" ||
            path == "/mcp/message" || path == "/mcp/messages"
    }

    /**
     * 旧 MCP 客户端通常只拿到一个 URL：GET，且没有 MCP-Protocol-Version。
     * Streamable HTTP 客户端会以 POST 开场（2026 直接请求/发现，2025 initialize）；因此根 URL 的 GET 可无歧义回退 SSE。
     * 一些移动客户端错误地发送 Accept: */*，这里也兼容；2026 客户端会携带版本 Header，不会误入 SSE。
     */
    private fun isLegacySseFallbackRequest(session: IHTTPSession): Boolean =
        isMcpEndpoint(session.uri) &&
            session.method == Method.GET &&
            header(session, "MCP-Protocol-Version").isNullOrBlank()

    // ==================== MCP 2024-11-05 HTTP + SSE compatibility ====================

    private data class LegacySseSession(
        val id: String,
        val stream: LegacySseInputStream,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var protocolVersion: String = MCP_SSE_VERSION,
    )

    private val legacySseSessions = ConcurrentHashMap<String, LegacySseSession>()

    private fun serveLegacySse(session: IHTTPSession): Response {
        if (!isTrustedMcpOrigin(session)) {
            return mcpErrorResponse(session, Response.Status.FORBIDDEN, -32000, "Origin not allowed")
        }
        if (!isMcpAuthorized(session)) {
            return mcpErrorResponse(session, Response.Status.UNAUTHORIZED, -32001, "MCP authentication required").apply {
                addHeader("WWW-Authenticate", "Bearer realm=\"BrowserDiag MCP\"")
            }
        }
        if (session.method != Method.GET) {
            return mcpErrorResponse(session, Response.Status.METHOD_NOT_ALLOWED, -32000, "Legacy SSE endpoint accepts GET").apply {
                addHeader("Allow", "GET, OPTIONS")
            }
        }

        while (legacySseSessions.size >= MAX_LEGACY_SSE_SESSIONS) {
            val oldest = legacySseSessions.values.minByOrNull { it.createdAt } ?: break
            legacySseSessions.remove(oldest.id)
            runCatching { oldest.stream.close() }
        }

        val id = UUID.randomUUID().toString()
        val stream = LegacySseInputStream { legacySseSessions.remove(id) }
        val state = LegacySseSession(id, stream)
        legacySseSessions[id] = state
        // 与官方 v1 SDK 的 SSEServerTransport 约定一致：先告诉客户端后续 POST 地址。
        stream.send("endpoint", "/messages?sessionId=$id")

        McpDiagnostics.recordTransport("sse", "GET ${session.uri}", MCP_SSE_VERSION)
        return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", stream).also { response ->
            addMcpHeaders(response, session)
            response.addHeader("Connection", "keep-alive")
        }
    }

    private fun serveLegacySseMessage(session: IHTTPSession): Response {
        if (!isTrustedMcpOrigin(session)) {
            return mcpErrorResponse(session, Response.Status.FORBIDDEN, -32000, "Origin not allowed")
        }
        if (!isMcpAuthorized(session)) {
            return mcpErrorResponse(session, Response.Status.UNAUTHORIZED, -32001, "MCP authentication required")
        }
        if (session.method != Method.POST) {
            return mcpErrorResponse(session, Response.Status.METHOD_NOT_ALLOWED, -32000, "Legacy SSE message endpoint accepts POST").apply {
                addHeader("Allow", "POST, OPTIONS")
            }
        }
        val sessionId = session.parameters["sessionId"]?.firstOrNull()?.trim().orEmpty()
        val state = legacySseSessions[sessionId]
            ?: return mcpErrorResponse(session, Response.Status.NOT_FOUND, -32000, "Unknown SSE session")
        val payload = try {
            parseMcpPayload(session)
        } catch (e: IllegalArgumentException) {
            return mcpErrorResponse(session, Response.Status.BAD_REQUEST, -32700, e.message ?: "Parse error")
        }

        fun dispatch(message: JSONObject) {
            if (message.optString("method") == "initialize") {
                state.protocolVersion = selectLegacyProtocol(message.optJSONObject("params"))
            }
            val response = handleMcpMessage(message, session, state.protocolVersion)
            if (response != null) state.stream.send("message", response.toString())
        }

        when (payload) {
            is JSONObject -> dispatch(payload)
            is JSONArray -> for (index in 0 until payload.length()) {
                payload.optJSONObject(index)?.let(::dispatch)
            }
            else -> return mcpErrorResponse(session, Response.Status.BAD_REQUEST, -32600, "Invalid Request")
        }
        return newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain; charset=utf-8", "Accepted").also {
            addMcpHeaders(it, session)
        }
    }

    private fun serveMcp(session: IHTTPSession): Response {
        if (!isTrustedMcpOrigin(session)) {
            return mcpErrorResponse(session, Response.Status.FORBIDDEN, -32000, "Origin not allowed")
        }
        if (!isMcpAuthorized(session)) {
            return mcpErrorResponse(session, Response.Status.UNAUTHORIZED, -32001, "MCP authentication required").apply {
                addHeader("WWW-Authenticate", "Bearer realm=\"BrowserDiag MCP\"")
            }
        }
        if (session.method == Method.GET) {
            return mcpErrorResponse(
                session,
                Response.Status.METHOD_NOT_ALLOWED,
                -32000,
                "This MCP endpoint uses request-scoped Streamable HTTP; send JSON-RPC with POST"
            ).apply { addHeader("Allow", "POST, OPTIONS") }
        }
        if (session.method != Method.POST) {
            return mcpErrorResponse(session, Response.Status.METHOD_NOT_ALLOWED, -32000, "MCP endpoint accepts POST").apply {
                addHeader("Allow", "POST, OPTIONS")
            }
        }
        McpDiagnostics.recordTransport(
            "streamable-http",
            "POST ${session.uri}",
            header(session, "MCP-Protocol-Version") ?: MCP_HTTP_FALLBACK_VERSION,
        )

        val payload = try {
            parseMcpPayload(session)
        } catch (e: IllegalArgumentException) {
            return mcpErrorResponse(session, Response.Status.BAD_REQUEST, -32700, e.message ?: "Parse error")
        }

        return when (payload) {
            is JSONObject -> {
                validateModernRequest(payload, session)?.let { error ->
                    return mcpJsonResponse(session, Response.Status.BAD_REQUEST, error.toString())
                }
                val modern = isModernRequest(payload, session)
                val method = payload.optString("method").trim()
                if (modern && method.isNotEmpty() && method !in MODERN_MCP_METHODS) {
                    McpDiagnostics.recordReject("2026 method not supported: $method")
                    return mcpJsonResponse(
                        session,
                        Response.Status.NOT_FOUND,
                        jsonRpcError(payload.opt("id") ?: JSONObject.NULL, -32601, "Method not found: $method").toString()
                    )
                }
                val response = handleMcpMessage(payload, session)
                if (response == null) mcpAcceptedResponse(session)
                else mcpJsonResponse(session, Response.Status.OK, response.toString())
            }
            is JSONArray -> {
                if (payload.length() == 0) {
                    mcpErrorResponse(session, Response.Status.BAD_REQUEST, -32600, "Invalid Request")
                } else {
                    val responses = JSONArray()
                    for (index in 0 until payload.length()) {
                        val message = payload.optJSONObject(index)
                        if (message == null) {
                            responses.put(jsonRpcError(JSONObject.NULL, -32600, "Invalid Request"))
                        } else {
                            handleMcpMessage(message, session)?.let { responses.put(it) }
                        }
                    }
                    if (responses.length() == 0) mcpAcceptedResponse(session)
                    else mcpJsonResponse(session, Response.Status.OK, responses.toString())
                }
            }
            else -> mcpErrorResponse(session, Response.Status.BAD_REQUEST, -32600, "Invalid Request")
        }
    }

    /** 2026-07-28 是无状态协议：版本、客户端信息与能力都随每个请求放在 params._meta。 */
    private fun requestMeta(message: JSONObject): JSONObject? =
        message.optJSONObject("params")?.optJSONObject("_meta")

    private fun modernBodyProtocol(message: JSONObject): String? =
        requestMeta(message)?.optString(META_PROTOCOL_VERSION)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isModernRequest(message: JSONObject, session: IHTTPSession): Boolean {
        val meta = requestMeta(message)
        val bodyVersion = modernBodyProtocol(message)
        val headerVersion = header(session, "MCP-Protocol-Version")?.trim().orEmpty()
        return bodyVersion == MCP_CURRENT_VERSION || headerVersion == MCP_CURRENT_VERSION ||
            meta?.has(META_CLIENT_CAPABILITIES) == true ||
            (bodyVersion != null && bodyVersion !in LEGACY_MCP_VERSIONS) ||
            (headerVersion.isNotEmpty() && headerVersion !in LEGACY_MCP_VERSIONS)
    }

    /**
     * 对 modern 请求校验会影响路由安全的 Header/Body 一致性；缺少 2026 必需元数据时返回可识别错误。
     * Mcp-Method/Mcp-Name 缺失暂时宽容，以兼容刚升级协议但尚未镜像 Header 的移动客户端。
     */
    private fun validateModernRequest(message: JSONObject, session: IHTTPSession): JSONObject? {
        if (!isModernRequest(message, session)) return null
        val id = message.opt("id") ?: JSONObject.NULL
        val meta = requestMeta(message)
        val bodyVersion = modernBodyProtocol(message)
        val headerVersion = header(session, "MCP-Protocol-Version")?.trim().orEmpty()
        if (meta == null || bodyVersion == null || !meta.has(META_CLIENT_CAPABILITIES)) {
            McpDiagnostics.recordReject("2026 request missing params._meta protocolVersion/clientCapabilities")
            return jsonRpcError(id, -32602, "2026 request requires protocolVersion and clientCapabilities in params._meta")
        }
        if (headerVersion.isEmpty()) {
            // 为非完全合规的移动端保留互操作性；body 元数据仍提供确定的版本来源。
        } else if (headerVersion != bodyVersion) {
            McpDiagnostics.recordReject("MCP-Protocol-Version header/body mismatch")
            return jsonRpcError(id, -32020, "Header mismatch: MCP-Protocol-Version does not match params._meta")
        }
        if (bodyVersion != MCP_CURRENT_VERSION) {
            McpDiagnostics.recordReject("unsupported modern protocol: $bodyVersion")
            return jsonRpcError(
                id,
                -32022,
                "Unsupported protocol version",
                JSONObject()
                    .put("supported", JSONArray(MCP_SUPPORTED_VERSIONS))
                    .put("requested", bodyVersion)
            )
        }
        val bodyMethod = message.optString("method").trim()
        header(session, "Mcp-Method")?.takeIf { it.isNotBlank() }?.let { routedMethod ->
            if (routedMethod != bodyMethod) {
                McpDiagnostics.recordReject("Mcp-Method header/body mismatch")
                return jsonRpcError(id, -32020, "Header mismatch: Mcp-Method does not match request method")
            }
        }
        if (bodyMethod == "tools/call") {
            val bodyName = message.optJSONObject("params")?.optString("name").orEmpty()
            header(session, "Mcp-Name")?.takeIf { it.isNotBlank() }?.let { routedName ->
                if (routedName != bodyName) {
                    McpDiagnostics.recordReject("Mcp-Name header/body mismatch")
                    return jsonRpcError(id, -32020, "Header mismatch: Mcp-Name does not match params.name")
                }
            }
        }
        return null
    }

    private fun parseMcpPayload(session: IHTTPSession): Any {
        val declaredLength = header(session, "Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_REQUEST_BYTES) {
            throw IllegalArgumentException("request body too large")
        }
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid MCP request body")
        }
        val raw = files["postData"].orEmpty()
        if (raw.isBlank()) throw IllegalArgumentException("empty MCP request body")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw IllegalArgumentException("request body too large")
        }
        return try {
            JSONTokener(raw).nextValue()
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid JSON-RPC payload")
        }
    }

    private fun handleMcpMessage(
        message: JSONObject,
        session: IHTTPSession,
        protocolVersionOverride: String? = null,
    ): JSONObject? {
        val hasId = message.has("id")
        val id = if (hasId) message.opt("id") ?: JSONObject.NULL else JSONObject.NULL
        if (message.optString("jsonrpc") != "2.0") {
            return if (hasId) jsonRpcError(id, -32600, "Invalid Request: jsonrpc must be 2.0") else null
        }
        val method = message.optString("method").trim()
        if (method.isEmpty()) return if (hasId) jsonRpcError(id, -32600, "Invalid Request: method required") else null
        val protocolVersion = protocolVersionOverride ?: run {
            if (method == "initialize") selectLegacyProtocol(message.optJSONObject("params"))
            else modernBodyProtocol(message)
                ?: header(session, "MCP-Protocol-Version")?.takeIf { it.isNotBlank() }
                ?: MCP_HTTP_FALLBACK_VERSION
        }
        val clientName = if (method == "initialize") {
            message.optJSONObject("params")?.optJSONObject("clientInfo")?.optString("name").orEmpty()
        } else {
            requestMeta(message)?.optJSONObject(META_CLIENT_INFO)?.optString("name").orEmpty()
        }
        McpDiagnostics.recordMessage(method, protocolVersion, clientName)

        // 2026-07-28 把路由信息镜像到 Header；有 Header 时严格核对，缺失时为旧客户端保持兼容。
        header(session, "Mcp-Method")?.takeIf { it.isNotBlank() }?.let { routedMethod ->
            if (routedMethod != method) return if (hasId) jsonRpcError(id, -32600, "Mcp-Method header mismatch") else null
        }
        if (method == "tools/call") {
            val bodyName = message.optJSONObject("params")?.optString("name").orEmpty()
            header(session, "Mcp-Name")?.takeIf { it.isNotBlank() }?.let { routedName ->
                if (routedName != bodyName) return if (hasId) jsonRpcError(id, -32602, "Mcp-Name header mismatch") else null
            }
        }

        if (!hasId) {
            // MCP 的 initialized/cancelled 等通知无需响应；其它无 id 消息也按 JSON-RPC notification 接受。
            return null
        }
        val result = when (method) {
            "initialize" -> legacyInitializeResult(message.optJSONObject("params"))
            "server/discover" -> discoverResult()
            "ping" -> if (protocolVersion == MCP_CURRENT_VERSION) {
                return jsonRpcError(id, -32601, "Method not found: ping")
            } else JSONObject()
            "tools/list" -> listToolsResult(protocolVersion)
            "tools/call" -> {
                val params = message.optJSONObject("params")
                    ?: return jsonRpcError(id, -32602, "tools/call params must be an object")
                val name = params.optString("name").trim()
                if (name !in tools()) return jsonRpcError(id, -32602, "Unknown tool: $name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val toolResult = executeTool(name, args)
                JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", toolResult.toString())))
                    .put("isError", toolResult.has("error"))
                    .also { result ->
                        // 旧 SDK 对未知结果字段可能使用严格 schema；只发该协议年代定义过的字段。
                        if (supportsStructuredToolResult(protocolVersion)) {
                            result.put("structuredContent", toolResult)
                        }
                        if (protocolVersion == MCP_CURRENT_VERSION) {
                            result.put("resultType", "complete")
                            addModernServerMeta(result)
                        }
                    }
            }
            else -> return jsonRpcError(id, -32601, "Method not found: $method")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private fun legacyInitializeResult(params: JSONObject?): JSONObject {
        val selected = selectLegacyProtocol(params)
        return JSONObject()
            .put("protocolVersion", selected)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", "BrowserDiag")
                    .put("title", "BrowserDiag Android MCP")
                    .put("version", APP_VERSION)
            )
            .put("instructions", MCP_INSTRUCTIONS)
    }

    private fun selectLegacyProtocol(params: JSONObject?): String {
        val requested = params?.optString("protocolVersion").orEmpty()
        return if (requested in LEGACY_MCP_VERSIONS) requested else MCP_LEGACY_VERSION
    }

    private fun discoverResult(): JSONObject = JSONObject()
        .put("resultType", "complete")
        .put("supportedVersions", JSONArray(MCP_SUPPORTED_VERSIONS))
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put(
            "_meta",
            JSONObject().put(
                "io.modelcontextprotocol/serverInfo",
                JSONObject().put("name", "BrowserDiag").put("title", "BrowserDiag Android MCP").put("version", APP_VERSION)
            )
        )
        .put("instructions", MCP_INSTRUCTIONS)
        .put("ttlMs", 300_000)
        .put("cacheScope", "private")

    private fun listToolsResult(protocolVersion: String): JSONObject {
        val definitions = mcpToolDefinitions(protocolVersion)
        McpDiagnostics.recordToolsReturned(definitions.length())
        return JSONObject()
            .put("tools", definitions)
            .also { result ->
            // 2024/2025 客户端收到官方版本对应的最小 ListToolsResult，避免严格 schema 丢弃工具。
            if (protocolVersion == MCP_CURRENT_VERSION) {
                result.put("resultType", "complete")
                result.put("ttlMs", 300_000)
                result.put("cacheScope", "private")
                addModernServerMeta(result)
            }
        }
    }

    private fun addModernServerMeta(result: JSONObject): JSONObject = result.put(
        "_meta",
        JSONObject().put(
            META_SERVER_INFO,
            JSONObject().put("name", "BrowserDiag").put("title", "BrowserDiag Android MCP").put("version", APP_VERSION)
        )
    )

    private fun mcpToolDefinitions(protocolVersion: String): JSONArray {
        fun stringProperty(description: String, values: List<String>? = null): JSONObject =
            JSONObject().put("type", "string").put("description", description).also { schema ->
                values?.let { schema.put("enum", JSONArray(it)) }
            }
        fun integerProperty(description: String, minimum: Int? = null, maximum: Int? = null): JSONObject =
            JSONObject().put("type", "integer").put("description", description).also { schema ->
                minimum?.let { schema.put("minimum", it) }
                maximum?.let { schema.put("maximum", it) }
            }
        fun booleanProperty(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)
        fun objectProperty(description: String): JSONObject =
            JSONObject().put("type", "object").put("description", description).put("additionalProperties", true)
        fun tool(
            name: String,
            title: String,
            description: String,
            properties: JSONObject = JSONObject(),
            required: List<String> = emptyList()
        ): JSONObject {
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false)
            if (required.isNotEmpty()) schema.put("required", JSONArray(required))
            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", schema)
                .also { definition ->
                    if (supportsToolTitle(protocolVersion)) definition.put("title", title)
                }
        }
        fun props(vararg pairs: Pair<String, JSONObject>): JSONObject = JSONObject().also { out ->
            pairs.forEach { (key, value) -> out.put(key, value) }
        }

        return JSONArray()
            .put(tool(
                "BrowserDiag",
                "BrowserDiag 统一入口",
                "BrowserDiag MCP 的兼容总入口。action=capabilities 可确认工具包已注入；也可代理调用任一 browser_* 原子工具。优先直接调用具体工具。",
                props(
                    "action" to stringProperty(
                        "capabilities 或要代理调用的 BrowserDiag 原子工具名",
                        listOf("capabilities") + tools().filter { it != "BrowserDiag" }
                    ),
                    "arguments" to objectProperty("代理工具的参数对象")
                )
            ))
            .put(tool("browser_state", "浏览器状态", "获取当前页面标题、URL、加载状态与错误统计"))
            .put(tool(
                "browser_open", "打开网页", "在当前标签打开 HTTP(S) 页面并等待加载",
                props(
                    "url" to stringProperty("要打开的 HTTP(S) URL"),
                    "waitMs" to integerProperty("加载后等待毫秒数", 1000, 15000)
                ), listOf("url")
            ))
            .put(tool(
                "browser_console", "Console 日志", "读取当前浏览会话的 Console 日志",
                props(
                    "type" to stringProperty("可选日志级别过滤"),
                    "limit" to integerProperty("最多返回条数", 1, 200)
                )
            ))
            .put(tool("browser_network", "网络诊断", "读取 fetch/XHR 网络记录与失败请求"))
            .put(tool(
                "browser_eval", "执行 JavaScript", "在当前页面执行 JavaScript 表达式",
                props("expression" to stringProperty("JavaScript 表达式")), listOf("expression")
            ))
            .put(tool("browser_screenshot", "页面截图", "截取当前 WebView 为 PNG 并返回 base64"))
            .put(tool("browser_perf", "性能指标", "读取 Navigation/Resource Timing 性能摘要"))
            .put(tool("browser_report", "诊断报告", "汇总 Console、Network 与性能问题"))
            .put(tool("browser_source", "页面源码", "获取当前页面 HTML 源码（有大小上限）"))
            .put(tool("browser_tabs", "标签页", "列出 BrowserDiag 当前所有标签页"))
            .put(tool("browser_text", "页面正文", "提取当前页面可读正文文本"))
            .put(tool("browser_interactive", "可交互元素", "列出当前页面可见的链接、按钮与输入控件"))
            .put(tool(
                "browser_history", "浏览历史", "搜索最近浏览历史",
                props(
                    "keyword" to stringProperty("标题或 URL 关键词"),
                    "limit" to integerProperty("最多返回条数", 1, 100)
                )
            ))
            .put(tool(
                "browser_bookmarks", "书签", "搜索、添加或删除书签",
                props(
                    "action" to stringProperty("操作", listOf("search", "add", "delete")),
                    "name" to stringProperty("书签名称"),
                    "title" to stringProperty("书签标题别名"),
                    "url" to stringProperty("HTTP(S) URL"),
                    "keyword" to stringProperty("搜索关键词")
                )
            ))
            .put(tool(
                "browser_http", "HTTP 请求", "从 Android 侧执行受大小限制的 HTTP GET",
                props(
                    "url" to stringProperty("HTTP(S) URL"),
                    "timeoutMs" to integerProperty("超时毫秒数", 1000, 30000),
                    "headers" to objectProperty("附加请求 Header")
                ), listOf("url")
            ))
            .put(tool("browser_netlog", "网络日志", "读取当前页面最近的详细网络日志"))
            .put(tool(
                "browser_network_rules", "网络实验室规则", "查询、创建、更新、启停或删除网络重写规则和命中记录",
                props(
                    "operation" to stringProperty("操作", listOf("list", "hits", "add", "update", "enable", "delete", "clear_hits")),
                    "op" to stringProperty("operation 的兼容别名"),
                    "id" to stringProperty("规则 ID"),
                    "enabled" to booleanProperty("启用或停用"),
                    "limit" to integerProperty("命中记录上限", 1, 200),
                    "rule" to objectProperty("NetworkRule 对象")
                )
            ))
            .put(tool("browser_close", "关闭当前页面", "停止加载并把当前 WebView 重置为 about:blank"))
    }

    private fun browserDiagFacade(params: JSONObject): JSONObject {
        val action = params.optString("action", "capabilities").trim().ifEmpty { "capabilities" }
        if (action == "capabilities") {
            return JSONObject()
                .put("name", "BrowserDiag")
                .put("version", APP_VERSION)
                .put("mcpProtocolVersions", JSONArray(MCP_SUPPORTED_VERSIONS))
                .put("tools", JSONArray(tools().filter { it != "BrowserDiag" }))
                .put("toolCount", tools().size)
        }
        if (action == "BrowserDiag" || action !in tools()) {
            return JSONObject()
                .put("error", "unknown BrowserDiag action: $action")
                .put("actions", JSONArray(listOf("capabilities") + tools().filter { it != "BrowserDiag" }))
        }
        return executeTool(action, params.optJSONObject("arguments") ?: JSONObject())
    }

    private fun supportsToolTitle(protocolVersion: String): Boolean =
        protocolVersion == MCP_CURRENT_VERSION ||
            protocolVersion == MCP_LEGACY_VERSION ||
            protocolVersion == "2025-06-18"

    private fun supportsStructuredToolResult(protocolVersion: String): Boolean =
        protocolVersion == MCP_CURRENT_VERSION ||
            protocolVersion == MCP_LEGACY_VERSION ||
            protocolVersion == "2025-06-18"

    private fun jsonRpcError(id: Any?, code: Int, message: String, data: JSONObject? = null): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put(
            "error",
            JSONObject().put("code", code).put("message", message).also { error ->
                data?.let { error.put("data", it) }
            }
        )

    private fun mcpAcceptedResponse(session: IHTTPSession): Response =
        newFixedLengthResponse(Response.Status.ACCEPTED, "application/json; charset=utf-8", "").also {
            addMcpHeaders(it, session)
        }

    private fun mcpErrorResponse(
        session: IHTTPSession,
        status: Response.Status,
        code: Int,
        message: String
    ): Response {
        McpDiagnostics.recordReject("HTTP $status: $message")
        return mcpJsonResponse(session, status, jsonRpcError(JSONObject.NULL, code, message).toString())
    }

    private fun mcpJsonResponse(session: IHTTPSession, status: Response.Status, json: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", json).also {
            addMcpHeaders(it, session)
        }

    private fun addMcpHeaders(response: Response, session: IHTTPSession) {
        val origin = header(session, "Origin")
        if (!origin.isNullOrBlank() && isTrustedOriginValue(origin)) {
            response.addHeader("Access-Control-Allow-Origin", origin)
            response.addHeader("Vary", "Origin")
        }
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-BrowserDiag-Token, MCP-Protocol-Version, Mcp-Method, Mcp-Name, Mcp-Session-Id, Access-Control-Request-Private-Network"
        )
        response.addHeader(
            "Access-Control-Expose-Headers",
            "MCP-Protocol-Version, Mcp-Session-Id"
        )
        header(session, "MCP-Protocol-Version")
            ?.takeIf { it.isNotBlank() }
            ?.let { response.addHeader("MCP-Protocol-Version", it) }
        if (header(session, "Access-Control-Request-Private-Network").equals("true", ignoreCase = true) &&
            !origin.isNullOrBlank() && isTrustedOriginValue(origin)
        ) {
            response.addHeader("Access-Control-Allow-Private-Network", "true")
        }
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun isMcpAuthorized(session: IHTTPSession): Boolean {
        if (isAuthorized(session)) return true
        val settings = getSettings()
        return settings.lanApiEnabled && settings.mcpUrlOnlyCompatibility
    }

    private fun isTrustedMcpOrigin(session: IHTTPSession): Boolean {
        val origin = header(session, "Origin") ?: return true
        // file:// / sandbox WebView 会发 Origin: null；只有携带有效 Token 时才允许，避免放宽 DNS rebinding 防线。
        if (origin.equals("null", ignoreCase = true)) return isAuthorized(session)
        return isTrustedOriginValue(origin)
    }

    /** 只接受 localhost、可信本地 App scheme 或字面私网 IP Origin，避免 DNS rebinding 域名借浏览器访问本地 MCP。 */
    private fun isTrustedOriginValue(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return false
        if (scheme in LOCAL_APP_ORIGIN_SCHEMES) {
            return host == "localhost" || host == "127.0.0.1" || host == "::1"
        }
        if (scheme != "http" && scheme != "https") return false
        if (host == "localhost") return true
        val ipv6 = host.removePrefix("[").removeSuffix("]")
        if (ipv6.contains(':')) {
            return ipv6 == "::1" || ipv6.startsWith("fc") || ipv6.startsWith("fd") || ipv6.startsWith("fe80:")
        }
        val ipv4 = host.split('.').map { it.toIntOrNull() }
        if (ipv4.size == 4 && ipv4.all { it != null && it in 0..255 }) {
            val octets = ipv4.map { it!! }
            return octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 169 && octets[1] == 254)
        }
        return false
    }

    private fun header(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

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
            conn.setRequestProperty("User-Agent", "BrowserDiag/3.8")
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

    /**
     * 网络实验室规则管理。operation: list | hits | add | update | enable | delete | clear_hits。
     * add/update 建议把规则放入 {"rule": {...}}，避免 operation 与规则 action 混淆。
     */
    private fun networkRuleTool(params: JSONObject): JSONObject {
        val store = getNetworkRuleStore()
        val operation = params.optString("operation", params.optString("op", "list"))
            .trim().lowercase()
        return when (operation) {
            "list" -> {
                val rules = JSONArray()
                store.allRules().forEach { rules.put(it.toJson()) }
                JSONObject()
                    .put("count", rules.length())
                    .put("enabled", store.allRules().count { it.enabled })
                    .put("rules", rules)
                    .put("operations", JSONArray(listOf("list", "hits", "add", "update", "enable", "delete", "clear_hits")))
            }
            "hits" -> {
                val limit = params.optInt("limit", 100).coerceIn(1, 200)
                val nativeHits = store.recentHits(limit)
                val jsHits = evalArray("JSON.stringify((window.__bdRuleHits||[]).slice(-$limit).reverse())")
                val merged = mutableListOf<JSONObject>()
                for (index in 0 until nativeHits.length()) nativeHits.optJSONObject(index)?.let { merged += it }
                for (index in 0 until jsHits.length()) jsHits.optJSONObject(index)?.let { merged += it }
                val hits = JSONArray()
                merged.sortedByDescending { it.optLong("ts") }.take(limit).forEach { hits.put(it) }
                JSONObject()
                    .put("count", hits.length())
                    .put("hits", hits)
                    .put("nativeCount", nativeHits.length())
                    .put("pageJsCount", jsHits.length())
            }
            "add" -> {
                val payload = params.optJSONObject("rule") ?: params
                val rule = networkRuleFromPayload(payload, null)
                    ?: return JSONObject().put("error", "valid rule.action required")
                val saved = runCatching { store.upsert(rule) }.getOrElse { error ->
                    return JSONObject().put("error", error.message ?: "invalid network rule")
                }
                onNetworkRulesChanged()
                JSONObject().put("added", true).put("rule", saved.toJson())
            }
            "update" -> {
                val payload = params.optJSONObject("rule") ?: params
                val id = payload.optString("id", params.optString("id")).trim()
                if (id.isEmpty()) return JSONObject().put("error", "rule id required")
                val existing = store.findRule(id)
                    ?: return JSONObject().put("error", "network rule not found")
                val rule = networkRuleFromPayload(payload, existing)?.copy(id = id)
                    ?: return JSONObject().put("error", "valid rule.action required")
                val saved = runCatching { store.upsert(rule) }.getOrElse { error ->
                    return JSONObject().put("error", error.message ?: "invalid network rule")
                }
                onNetworkRulesChanged()
                JSONObject().put("updated", true).put("rule", saved.toJson())
            }
            "enable" -> {
                val id = params.optString("id").trim()
                if (id.isEmpty()) return JSONObject().put("error", "rule id required")
                val enabled = params.optBoolean("enabled", true)
                val saved = store.setEnabled(id, enabled)
                    ?: return JSONObject().put("error", "network rule not found")
                onNetworkRulesChanged()
                JSONObject().put("updated", true).put("rule", saved.toJson())
            }
            "delete" -> {
                val id = params.optString("id").trim()
                if (id.isEmpty()) return JSONObject().put("error", "rule id required")
                val deleted = store.remove(id)
                if (deleted) onNetworkRulesChanged()
                JSONObject().put("deleted", deleted).put("id", id)
            }
            "clear_hits" -> {
                store.clearHits()
                evalJs("window.__bdRuleHits=[];true", timeoutSec = 3)
                JSONObject().put("cleared", true)
            }
            else -> JSONObject()
                .put("error", "unsupported operation: $operation")
                .put("operations", JSONArray(listOf("list", "hits", "add", "update", "enable", "delete", "clear_hits")))
        }
    }

    private fun networkRuleFromPayload(payload: JSONObject, existing: NetworkRule?): NetworkRule? {
        val actionKey = if (payload.has("action")) payload.optString("action") else existing?.action?.key
        val action = NetworkRuleAction.fromKey(actionKey) ?: return null
        val headers = if (payload.has("headers")) {
            when (val raw = payload.opt("headers")) {
                is JSONObject -> buildMap {
                    val keys = raw.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, raw.optString(key))
                    }
                }
                is String -> parseNetworkHeaders(raw)
                else -> emptyMap()
            }
        } else existing?.headers ?: emptyMap()
        val id = payload.optString("id", existing?.id ?: "nr_${System.currentTimeMillis()}")
        return NetworkRule(
            id = id,
            name = payload.optString("name", existing?.name ?: action.label),
            enabled = if (payload.has("enabled")) payload.optBoolean("enabled") else existing?.enabled ?: true,
            priority = if (payload.has("priority")) payload.optInt("priority") else existing?.priority ?: 100,
            urlPattern = payload.optString("urlPattern", existing?.urlPattern ?: "*"),
            methods = payload.optString("methods", existing?.methods ?: "*"),
            action = action,
            value = payload.optString("value", existing?.value ?: ""),
            replacement = payload.optString("replacement", existing?.replacement ?: ""),
            headers = headers,
            statusCode = if (payload.has("statusCode")) payload.optInt("statusCode") else existing?.statusCode ?: 200,
            mimeType = payload.optString("mimeType", existing?.mimeType ?: "text/plain; charset=utf-8"),
            delayMs = if (payload.has("delayMs")) payload.optInt("delayMs") else existing?.delayMs ?: 0
        )
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
        private const val APP_VERSION = "3.8.0"
        private const val MCP_CURRENT_VERSION = "2026-07-28"
        private const val MCP_LEGACY_VERSION = "2025-11-25"
        private const val MCP_HTTP_FALLBACK_VERSION = "2025-03-26"
        private const val MCP_SSE_VERSION = "2024-11-05"
        private const val MAX_LEGACY_SSE_SESSIONS = 8
        private const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        private const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
        private const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
        private const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
        private const val MCP_INSTRUCTIONS =
            "BrowserDiag controls and diagnoses the Android WebView. The BrowserDiag tool is a compatibility facade; prefer browser_state/browser_open and other browser_* atomic tools. Network rewrite rules are managed by browser_network_rules."
        private val MODERN_MCP_METHODS = setOf("server/discover", "tools/list", "tools/call")
        private val LOCAL_APP_ORIGIN_SCHEMES = setOf("app", "capacitor", "ionic", "tauri")
        private val MCP_SUPPORTED_VERSIONS = listOf(
            MCP_CURRENT_VERSION,
            MCP_LEGACY_VERSION,
            "2025-06-18",
            MCP_HTTP_FALLBACK_VERSION
        )
        private val LEGACY_MCP_VERSIONS = setOf(
            MCP_LEGACY_VERSION,
            "2025-06-18",
            MCP_HTTP_FALLBACK_VERSION,
            MCP_SSE_VERSION
        )
    }
}

/** 阻塞式 SSE 输入流：JSON-RPC 响应入队，空闲时发送注释心跳，NanoHTTPD 负责 chunked 输出。 */
private class LegacySseInputStream(
    private val onClosed: () -> Unit,
) : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    @Volatile private var closed = false
    private var current = ByteArray(0)
    private var offset = 0

    fun send(event: String, data: String): Boolean {
        if (closed) return false
        val frame = "event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8)
        return queue.offer(frame)
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!closed) {
            if (offset < current.size) {
                val count = minOf(len, current.size - offset)
                System.arraycopy(current, offset, buffer, off, count)
                offset += count
                return count
            }
            val next = try {
                queue.poll(20, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                close()
                return -1
            }
            current = next ?: ": BrowserDiag MCP keepalive\n\n".toByteArray(Charsets.UTF_8)
            offset = 0
        }
        return -1
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.offer(ByteArray(0))
        onClosed()
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
