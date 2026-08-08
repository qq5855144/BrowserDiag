package com.browserdiag.app

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/**
 * 进程级 MCP 运行时。
 *
 * DiagServer 不再由 Activity 自己持有，因此切到后台、任务卡片被移除或 Activity 因内存回收
 * 被销毁时，Foreground Service 仍可以维持 MCP transport。Activity 存活时把当前 WebView/Tabs
 * 绑定到这里；Activity 消失后，协议握手和无需页面的工具仍然可用，页面类工具会安全返回无 WebView。
 */
object McpRuntime {
    private data class UiBindings(
        val owner: Any,
        val getWebView: () -> WebView?,
        val getConsoleLogs: () -> List<JSONObject>,
        val getTabs: () -> Tabs?,
        val getNetworkRuleStore: () -> NetworkRuleStore,
        val onNetworkRulesChanged: () -> Unit,
    )

    @Volatile
    private var bindings: UiBindings? = null

    fun attach(
        owner: Any,
        getWebView: () -> WebView?,
        getConsoleLogs: () -> List<JSONObject>,
        getTabs: () -> Tabs?,
        getNetworkRuleStore: () -> NetworkRuleStore,
        onNetworkRulesChanged: () -> Unit,
    ) {
        bindings = UiBindings(
            owner,
            getWebView,
            getConsoleLogs,
            getTabs,
            getNetworkRuleStore,
            onNetworkRulesChanged,
        )
    }

    fun detach(owner: Any) {
        if (bindings?.owner === owner) bindings = null
    }

    fun hasUi(): Boolean = bindings != null
    fun webView(): WebView? = bindings?.getWebView?.invoke()
    fun consoleLogs(): List<JSONObject> = bindings?.getConsoleLogs?.invoke().orEmpty()
    fun tabs(): Tabs? = bindings?.getTabs?.invoke()
    fun networkRuleStore(): NetworkRuleStore? = bindings?.getNetworkRuleStore?.invoke()
    fun notifyNetworkRulesChanged() = bindings?.onNetworkRulesChanged?.invoke() ?: Unit
}

/** 单进程唯一的 MCP Server Host，Activity 与前台 Service 共同复用。 */
object McpServerHost {
    @Volatile
    private var server: DiagServer? = null
    @Volatile
    private var boundHost: String? = null
    @Volatile
    var port: Int = 8788
        private set

    val isRunning: Boolean
        get() = server != null

    @Synchronized
    fun ensureStarted(context: Context): Int? {
        val app = context.applicationContext
        val settings = Settings(app)
        val desiredHost = if (settings.lanApiEnabled) "0.0.0.0" else "127.0.0.1"
        if (server != null && boundHost == desiredHost) return port
        stopLocked()

        var candidate = 8788
        while (candidate < 8792) {
            try {
                val diag = DiagServer(
                    desiredHost,
                    candidate,
                    app,
                    { McpRuntime.webView() },
                    { McpRuntime.consoleLogs() },
                    { settings },
                    { McpRuntime.tabs() },
                    { McpRuntime.networkRuleStore() ?: NetworkRuleStore(app) },
                    { McpRuntime.notifyNetworkRulesChanged() },
                    settings.apiToken,
                )
                diag.start(5000, true)
                server = diag
                boundHost = desiredHost
                port = candidate
                return candidate
            } catch (_: Exception) {
                candidate++
            }
        }
        return null
    }

    @Synchronized
    fun restart(context: Context): Int? {
        stopLocked()
        return ensureStarted(context)
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        runCatching { server?.stop() }
        server = null
        boundHost = null
    }
}
