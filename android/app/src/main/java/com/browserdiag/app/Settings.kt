package com.browserdiag.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

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
    val urlPattern: String,   // 可保存多条 @match / @include，以换行分隔
    val code: String,
    val excludePattern: String = "",
    val sourceUrl: String = "",
    val namespace: String = "",
    val version: String = "",
    val description: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("urlPattern", urlPattern)
        .put("code", code)
        .put("excludePattern", excludePattern)
        .put("sourceUrl", sourceUrl)
        .put("namespace", namespace)
        .put("version", version)
        .put("description", description)

    companion object {
        fun fromJson(o: JSONObject): Userscript = Userscript(
            id = o.optString("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            urlPattern = o.optString("urlPattern", "*"),
            code = o.optString("code"),
            excludePattern = o.optString("excludePattern"),
            sourceUrl = o.optString("sourceUrl"),
            namespace = o.optString("namespace"),
            version = o.optString("version"),
            description = o.optString("description")
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
        list.add(0, name.take(512) to url.take(4096))
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
        scripts.take(50).forEach { script ->
            arr.put(
                script.copy(
                    name = script.name.take(120),
                    urlPattern = script.urlPattern.take(4096),
                    code = script.code.take(200_000),
                    excludePattern = script.excludePattern.take(4096),
                    sourceUrl = script.sourceUrl.take(4096),
                    namespace = script.namespace.take(200),
                    version = script.version.take(80),
                    description = script.description.take(500)
                ).toJson()
            )
        }
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
        list.add(0, url.take(4096) to title.take(512))
        if (list.size > 50) list.subList(50, list.size).clear()
        saveHistory(list)
    }

    fun removeHistory(url: String) {
        saveHistory(getHistory().filter { it.first != url })
    }

    private fun saveHistory(list: List<Pair<String, String>>) {
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
        set(v) = prefs.edit()
            .putString("screen_orientation", v.takeIf { it in setOf("auto", "portrait", "landscape") } ?: "auto")
            .apply()

    // ---------- 允许调试网页（WebView 远程调试） ----------
    var debugWeb: Boolean
        get() = prefs.getBoolean("debug_web", false)
        set(v) = prefs.edit().putBoolean("debug_web", v).apply()

    // ---------- 诊断 API 安全 ----------
    var lanApiEnabled: Boolean
        get() = prefs.getBoolean("lan_api_enabled", false)
        set(v) = prefs.edit().putBoolean("lan_api_enabled", v).apply()

    val apiToken: String
        get() {
            prefs.getString("api_token", null)?.takeIf { it.length >= 24 }?.let { return it }
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val token = Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            prefs.edit().putString("api_token", token).apply()
            return token
        }

    // ---------- 工具中心常用入口（完整功能分类不受此配置影响） ----------
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
