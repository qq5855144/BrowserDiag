package com.browserdiag.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 网页源码打包：HTML + 页面信息 + 控制台日志 + 网络日志 + 尽力下载的静态资源，
 * 打包为 zip 保存到「下载」目录，便于后续功能开发与离线分析。
 */
object SourcePacker {

    /**
     * 打包网页源码。html 来自页面 outerHTML；console/network 由调用方提供。
     * @param onDone 完成回调：(成功?, 保存路径/错误信息)
     */
    fun pack(
        context: Context,
        url: String,
        title: String,
        html: String,
        consoleJson: String,
        networkJson: String,
        ua: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val zipFile = File(context.cacheDir, "browserdiag_source_${System.currentTimeMillis()}.zip")
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->

                    // 1. 页面 HTML
                    zos.putNextEntry(ZipEntry("index.html"))
                    zos.write(html.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 2. 页面信息
                    val meta = JSONObject()
                        .put("url", url)
                        .put("title", title)
                        .put("savedAt", System.currentTimeMillis())
                        .put("userAgent", ua)
                    zos.putNextEntry(ZipEntry("meta.json"))
                    zos.write(meta.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 3. 控制台日志
                    zos.putNextEntry(ZipEntry("console.json"))
                    zos.write(consoleJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 4. 网络日志
                    zos.putNextEntry(ZipEntry("network.json"))
                    zos.write(networkJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 5. 尽力下载页面内静态资源（css/js，最多 12 个）
                    val assets = extractAssetUrls(html)
                    var saved = 0
                    for ((name, assetUrl) in assets) {
                        if (saved >= 12) break
                        try {
                            val bytes = download(assetUrl, 8000)
                            if (bytes != null && bytes.isNotEmpty()) {
                                zos.putNextEntry(ZipEntry("assets/$name"))
                                zos.write(bytes)
                                zos.closeEntry()
                                saved++
                            }
                        } catch (e: Exception) {
                            // 忽略单个资源失败
                        }
                    }
                }

                // 保存到「下载」目录
                val displayName = "browserdiag_source_${System.currentTimeMillis()}.zip"
                val savedPath = saveToDownloads(context, zipFile, displayName)
                onDone(true, savedPath)
            } catch (e: Exception) {
                onDone(false, e.message ?: e.toString())
            }
        }
    }

    /** 从 HTML 提取可下载的静态资源（去重、仅同站相对路径或 http(s)） */
    private fun extractAssetUrls(html: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val css = Regex("""<link[^>]*rel=["']stylesheet["'][^>]*href=["']([^"']+)["']""")
            .findAll(html)
        for (m in css) {
            val href = m.groupValues[1]
            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")) {
                val name = "css_" + href.hashCode().toUInt() + ".css"
                out[name] = if (href.startsWith("//")) "https:$href" else href
            }
        }
        val js = Regex("""<script[^>]*src=["']([^"']+)["']""")
            .findAll(html)
        for (m in js) {
            val src = m.groupValues[1]
            if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("//")) {
                val name = "js_" + src.hashCode().toUInt() + ".js"
                out[name] = if (src.startsWith("//")) "https:$src" else src
            }
        }
        return out.toList()
    }

    private fun download(urlStr: String, timeoutMs: Int): ByteArray? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) BrowserDiag/2.1")
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun saveToDownloads(context: Context, file: File, displayName: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载条目")
            context.contentResolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            } ?: throw IllegalStateException("无法写入下载目录")
            "下载/$displayName"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dest = File(dir, displayName)
            file.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    /** URL 编码工具（保留给搜索等场景） */
    fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8")
}