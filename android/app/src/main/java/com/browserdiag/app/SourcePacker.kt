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
    private const val MAX_ASSET_COUNT = 12
    private const val MAX_ASSET_BYTES = 5 * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BrowserDiag-SourcePacker").apply { isDaemon = true }
    }

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
        executor.execute {
            var zipFile: File? = null
            try {
                val tempFile = File(context.cacheDir, "browserdiag_source_${System.currentTimeMillis()}.zip")
                zipFile = tempFile
                ZipOutputStream(FileOutputStream(tempFile)).use { zos ->

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
                    val assets = extractAssetUrls(html, url)
                    var saved = 0
                    for ((name, assetUrl) in assets) {
                        if (saved >= MAX_ASSET_COUNT) break
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
                val savedPath = saveToDownloads(context, tempFile, displayName)
                onDone(true, savedPath)
            } catch (e: Exception) {
                onDone(false, e.message ?: e.toString())
            } finally {
                zipFile?.delete()
            }
        }
    }

    /** 从 HTML 提取可下载的静态资源（去重，支持绝对、协议相对和页面相对 URL）。 */
    private fun extractAssetUrls(html: String, pageUrl: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val base = runCatching { URL(pageUrl) }.getOrNull()

        fun resolve(raw: String): String? {
            if (raw.isBlank() || raw.startsWith("data:", true) || raw.startsWith("blob:", true)) return null
            val resolved = runCatching { if (base != null) URL(base, raw) else URL(raw) }.getOrNull()
                ?: return null
            if (resolved.protocol != "http" && resolved.protocol != "https") return null
            return resolved.toString()
        }

        fun attr(tag: String, name: String): String? =
            Regex("""\b${name}\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.getOrNull(1)

        Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val tag = match.value
            val rel = attr(tag, "rel") ?: return@forEach
            if (!rel.split(Regex("""\s+""")).any { it.equals("stylesheet", true) }) return@forEach
            val href = attr(tag, "href") ?: return@forEach
            resolve(href)?.let { resolved ->
                out["css_${resolved.hashCode().toUInt()}.css"] = resolved
            }
        }

        Regex("""<script\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val src = attr(match.value, "src") ?: return@forEach
            resolve(src)?.let { resolved ->
                out["js_${resolved.hashCode().toUInt()}.js"] = resolved
            }
        }
        return out.toList()
    }

    private fun download(urlStr: String, timeoutMs: Int): ByteArray? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) BrowserDiag/3.2")
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode in 200..299) {
                if (conn.contentLengthLong > MAX_ASSET_BYTES) return null
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_ASSET_BYTES) return null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
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
