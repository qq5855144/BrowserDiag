package com.browserdiag.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 搜索引擎定义 */
enum class SearchEngine(val label: String, val searchUrl: String, val homeUrl: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
    BAIDU("百度", "https://www.baidu.com/s?wd=%s", "https://www.baidu.com"),
    SOGOU("搜狗", "https://www.sogou.com/web?query=%s", "https://www.sogou.com"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com");

    companion object {
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name == name } ?: GOOGLE
    }
}

/** UA 模式定义 */
enum class UaMode(val label: String) {
    ANDROID("Android 默认"),
    DESKTOP("桌面 Chrome"),
    IPHONE("iPhone Safari");

    fun uaString(defaultUa: String): String = when (this) {
        ANDROID -> defaultUa
        DESKTOP -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        IPHONE -> "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }

    companion object {
        fun fromName(name: String?): UaMode =
            entries.firstOrNull { it.name == name } ?: ANDROID
    }
}

/** 油猴脚本（用户脚本） */
data class Userscript(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val urlPattern: String,   // 匹配规则：* 通配子串，如 *youtube.com* 或 *
    val code: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("urlPattern", urlPattern)
        .put("code", code)

    companion object {
        fun fromJson(o: JSONObject): Userscript = Userscript(
            id = o.optString("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            urlPattern = o.optString("urlPattern", "*"),
            code = o.optString("code")
        )
    }
}

/**
 * 应用设置与数据存储（SharedPreferences）。
 * 覆盖：搜索引擎、UA 模式、油猴脚本列表、历史记录、最近搜索。
 */
class Settings(private val ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("browserdiag_settings", Context.MODE_PRIVATE)

    // ---------- 深色主题 ----------
    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(v) = prefs.edit().putBoolean("dark_mode", v).apply()

    // ---------- 书签 ----------
    fun getBookmarks(): List<Pair<String, String>> { // name, url
        val raw = prefs.getString("bookmarks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("name") to o.optString("url")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addBookmark(name: String, url: String) {
        val list = getBookmarks().toMutableList()
        list.removeAll { it.second == url }
        list.add(0, name to url)
        if (list.size > 100) list.subList(100, list.size).clear()
        saveBookmarks(list)
    }

    fun removeBookmark(url: String) {
        saveBookmarks(getBookmarks().filter { it.second != url })
    }

    private fun saveBookmarks(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (n, u) -> arr.put(JSONObject().put("name", n).put("url", u)) }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    // ---------- 搜索引擎 ----------
    var engine: SearchEngine
        get() = SearchEngine.fromName(prefs.getString("engine", null))
        set(v) = prefs.edit().putString("engine", v.name).apply()

    // ---------- UA ----------
    var uaMode: UaMode
        get() = UaMode.fromName(prefs.getString("ua_mode", null))
        set(v) = prefs.edit().putString("ua_mode", v.name).apply()

    // ---------- 油猴脚本 ----------
    fun getScripts(): List<Userscript> {
        val raw = prefs.getString("userscripts", null) ?: return defaultScripts()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Userscript.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            defaultScripts()
        }
    }

    fun saveScripts(scripts: List<Userscript>) {
        val arr = JSONArray()
        scripts.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("userscripts", arr.toString()).apply()
    }

    /** 首次使用：内置一个示例脚本 */
    private fun defaultScripts(): List<Userscript> {
        val example = Userscript(
            id = "example_dark_bg",
            name = "示例：页面深色背景",
            enabled = false,
            urlPattern = "*",
            code = "(function(){\n" +
                "  'use strict';\n" +
                "  // 示例油猴脚本：页面加载后修改背景色\n" +
                "  function apply(){ document.body && (document.body.style.background='#f0f4ff'); }\n" +
                "  if (document.readyState === 'loading') {\n" +
                "    document.addEventListener('DOMContentLoaded', apply);\n" +
                "  } else apply();\n" +
                "})();"
        )
        saveScripts(listOf(example))
        return listOf(example)
    }

    // ---------- 历史记录 ----------
    fun getHistory(): List<Pair<String, String>> { // url, title
        val raw = prefs.getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("url") to o.optString("title")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(url: String, title: String) {
        if (url.startsWith("about:") || url.isEmpty()) return
        val list = getHistory().toMutableList()
        list.removeAll { it.first == url }
        list.add(0, url to title)
        if (list.size > 50) list.subList(50, list.size).clear()
        val arr = JSONArray()
        list.forEach { (u, t) -> arr.put(JSONObject().put("url", u).put("title", t)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove("history").apply()

    // ---------- 广告拦截 ----------
    var adBlock: Boolean
        get() = prefs.getBoolean("adblock", false)
        set(v) = prefs.edit().putBoolean("adblock", v).apply()

    // ---------- 字体缩放（%） ----------
    var fontScale: Int
        get() = prefs.getInt("font_scale", 100)
        set(v) = prefs.edit().putInt("font_scale", v.coerceIn(50, 200)).apply()

    // ---------- 屏幕方向（auto/portrait/landscape） ----------
    var screenOrientation: String
        get() = prefs.getString("screen_orientation", "auto") ?: "auto"
        set(v) = prefs.edit().putString("screen_orientation", v).apply()

    // ---------- 允许调试网页（WebView 远程调试） ----------
    var debugWeb: Boolean
        get() = prefs.getBoolean("debug_web", false)
        set(v) = prefs.edit().putBoolean("debug_web", v).apply()

    // ---------- 定制菜单（菜单项显隐） ----------
    fun getMenuConfig(): Map<String, Boolean> {
        val raw = prefs.getString("menu_config", null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                o.optString("id") to o.optBoolean("enabled", true)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setMenuConfig(cfg: Map<String, Boolean>) {
        val arr = JSONArray()
        cfg.forEach { (id, enabled) -> arr.put(JSONObject().put("id", id).put("enabled", enabled)) }
        prefs.edit().putString("menu_config", arr.toString()).apply()
    }
}