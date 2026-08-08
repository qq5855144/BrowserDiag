package com.browserdiag.app

data class McpDiagnosticSnapshot(
    val initializeCount: Long,
    val toolsListCount: Long,
    val toolsCallCount: Long,
    val lastMethod: String,
    val lastProtocol: String,
    val lastTransport: String,
    val lastClient: String,
    val lastAt: Long,
) {
    fun summary(): String =
        "initialize $initializeCount · tools/list $toolsListCount · tools/call $toolsCallCount"
}

/** 仅保留协议级计数/客户端名称，不记录 Token、参数、URL 或页面内容。 */
object McpDiagnostics {
    private val lock = Any()
    private var initializeCount = 0L
    private var toolsListCount = 0L
    private var toolsCallCount = 0L
    private var lastMethod = "尚无 MCP 请求"
    private var lastProtocol = "-"
    private var lastTransport = "-"
    private var lastClient = "-"
    private var lastAt = 0L

    fun recordTransport(transport: String, method: String, protocol: String) = synchronized(lock) {
        lastTransport = transport.take(40)
        lastMethod = method.take(80)
        lastProtocol = protocol.take(40)
        lastAt = System.currentTimeMillis()
    }

    fun recordMessage(method: String, protocol: String, clientName: String) = synchronized(lock) {
        when (method) {
            "initialize" -> initializeCount++
            "tools/list" -> toolsListCount++
            "tools/call" -> toolsCallCount++
        }
        lastMethod = method.take(80)
        lastProtocol = protocol.take(40)
        if (clientName.isNotBlank()) lastClient = clientName.take(120)
        lastAt = System.currentTimeMillis()
    }

    fun snapshot(): McpDiagnosticSnapshot = synchronized(lock) {
        McpDiagnosticSnapshot(
            initializeCount,
            toolsListCount,
            toolsCallCount,
            lastMethod,
            lastProtocol,
            lastTransport,
            lastClient,
            lastAt,
        )
    }
}
