package com.browserdiag.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView

/**
 * 轻量多标签管理：最多 MAX_TABS 个 WebView 实例，仅显示当前标签。
 * 每个标签独立保留页面状态（前进/后退栈、滚动位置）。
 */
class Tabs(private val context: Context, private val maxTabs: Int = 5) {

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

    /** 创建新标签（挂载到 parent 容器），返回 Tab */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(container: ViewGroup, url: String): Tab {
        // 超过上限：移除最旧的标签
        while (tabs.size >= maxTabs) {
            destroy(tabs.first().id)
        }
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
        tabs[currentIndex]?.let { if (it.visible) { it.webView.visibility = android.view.View.GONE; it.visible = false } }
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
            currentIndex = if (tabs.isEmpty()) -1 else 0
            if (currentIndex >= 0) switchTo(tabs[currentIndex].id)
        } else if (currentIndex > idx) {
            currentIndex--
        }
    }

    fun destroyAll() {
        tabs.toList().forEach { destroy(it.id) }
    }

    fun get(id: Int): Tab? = tabs.firstOrNull { it.id == id }
}