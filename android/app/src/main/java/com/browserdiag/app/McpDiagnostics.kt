package com.browserdiag.app

data class McpDiagnosticSnapshot(
    val serverDiscoverCount: Long,
    val initializeCount: Long,
    val toolsListCount: Long,
    val toolsCallCount: Long,
    val lastToolsReturned: Int,
    val rejectionCount: Long,
    val lastMethod: String,
    val lastProtocol: String,
    val lastTransport: String,
    val lastClient: String,
    val lastIssue: String,
    val lastAt: Long,
) {
    fun summary(): String =
        "discover $serverDiscoverCount · initialize $initializeCount · tools/list $toolsListCount · tools $lastToolsReturned · call $toolsCallCount"
}

/** 仅保留协议级计数/客户端名称，不记录 Token、参数、URL 或页面内容。 */
object McpDiagnostics {
    private val lock = Any()
    private var serverDiscoverCount = 0L
    private var initializeCount = 0L
    private var toolsListCount = 0L
    private var toolsCallCount = 0L
    private var lastToolsReturned = 0
    private var rejectionCount = 0L
    private var lastMethod = "尚无 MCP 请求"
    private var lastProtocol = "-"
    private var lastTransport = "-"
    private var lastClient = "-"
    private var lastIssue = "-"
    private var lastAt = 0L

    fun recordTransport(transport: String, method: String, protocol: String) = synchronized(lock) {
        lastTransport = transport.take(40)
        lastMethod = method.take(80)
        lastProtocol = protocol.take(40)
        lastAt = System.currentTimeMillis()
    }

    fun recordMessage(method: String, protocol: String, clientName: String) = synchronized(lock) {
        when (method) {
            "server/discover" -> serverDiscoverCount++
            "initialize" -> initializeCount++
            "tools/list" -> toolsListCount++
            "tools/call" -> toolsCallCount++
        }
        lastMethod = method.take(80)
        lastProtocol = protocol.take(40)
        if (clientName.isNotBlank()) lastClient = clientName.take(120)
        lastAt = System.currentTimeMillis()
    }

    fun recordToolsReturned(count: Int) = synchronized(lock) {
        lastToolsReturned = count.coerceAtLeast(0)
        lastAt = System.currentTimeMillis()
    }

    fun recordReject(reason: String) = synchronized(lock) {
        rejectionCount++
        lastIssue = reason.replace('\n', ' ').replace('\r', ' ').take(180)
        lastAt = System.currentTimeMillis()
    }

    fun snapshot(): McpDiagnosticSnapshot = synchronized(lock) {
        McpDiagnosticSnapshot(
            serverDiscoverCount,
            initializeCount,
            toolsListCount,
            toolsCallCount,
            lastToolsReturned,
            rejectionCount,
            lastMethod,
            lastProtocol,
            lastTransport,
            lastClient,
            lastIssue,
            lastAt,
        )
    }
}
