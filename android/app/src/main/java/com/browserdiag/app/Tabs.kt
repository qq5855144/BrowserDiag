package com.browserdiag.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView

/**
 * 轻量多标签管理：最多 MAX_TABS 个 WebView 实例，仅显示当前标签。
 * 每个标签独立保留页面状态（前进/后退栈、滚动位置）。
 */
class Tabs(private val context: Context, val maxTabs: Int = DEFAULT_MAX_TABS) {

    data class Tab(val id: Int, var title: String = "", var url: String = "") {
        lateinit var webView: WebView
        var visible: Boolean = false
    }

    private val tabs = ArrayList<Tab>()
    private var nextId = 1
    private var currentIndex = -1

    val size: Int get() = tabs.size
    val current: Tab? get() = if (currentIndex in tabs.indices) tabs[currentIndex] else null
    val all: List<Tab> get() = tabs.toList()

    /** 创建新标签（挂载到 parent 容器）。达到上限时返回 null，绝不静默销毁旧标签。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(container: ViewGroup, url: String): Tab? {
        if (tabs.size >= maxTabs) return null
        val tab = Tab(id = nextId++, url = url)
        tab.webView = WebView(context)
        tab.webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        tab.webView.visibility = android.view.View.GONE
        container.addView(tab.webView)
        tabs.add(tab)
        return tab
    }

    /** 切换到指定标签 */
    fun switchTo(id: Int): Tab? {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return null
        current?.let {
            if (it.visible) {
                it.webView.visibility = android.view.View.GONE
                it.visible = false
            }
        }
        currentIndex = idx
        val t = tabs[idx]
        t.webView.visibility = android.view.View.VISIBLE
        t.visible = true
        return t
    }

    /** 销毁标签 */
    fun destroy(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        val wasCurrent = idx == currentIndex
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(idx)
        if (wasCurrent) {
            currentIndex = -1
            if (tabs.isNotEmpty()) {
                switchTo(tabs[minOf(idx, tabs.lastIndex)].id)
            }
        } else if (currentIndex > idx) {
            currentIndex--
        }
    }

    fun destroyAll() {
        tabs.forEach { tab ->
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        currentIndex = -1
    }

    fun get(id: Int): Tab? = tabs.firstOrNull { it.id == id }

    companion object {
        /** WebView 标签较占内存，8 个兼顾多任务与 Android 设备稳定性。 */
        const val DEFAULT_MAX_TABS = 8
    }
}
