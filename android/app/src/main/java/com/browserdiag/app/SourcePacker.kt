package com.browserdiag.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 网页源码递归打包：HTML + 页面信息 + 控制台/网络日志 + 页面实际资源 + 资源依赖。
 * CSS、JS module、source map 与 iframe 会继续解析依赖；抓取过程有数量/深度/大小/耗时上限。
 */
object SourcePacker {
    private const val MAX_RESOURCE_COUNT = 160
    private const val MAX_RECURSION_DEPTH = 4
    private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_PARSE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_RESOURCE_BYTES = 64L * 1024 * 1024
    private const val MAX_CRAWL_MILLIS = 120_000L
    private const val FETCH_TIMEOUT_MILLIS = 8_000
    private val PARSEABLE_RESOURCE_KINDS = setOf("html", "css", "javascript", "source-map", "json")
    private val RESOURCE_LINK_RELS = setOf("stylesheet", "icon", "preload", "modulepreload", "manifest")

    private data class ResourceTask(
        val url: String,
        val zipPath: String,
        val depth: Int,
        val referer: String
    )

    private data class DownloadedResource(
        val bytes: ByteArray,
        val mime: String,
        val finalUrl: String
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BrowserDiag-SourcePacker").apply { isDaemon = true }
    }

    /**
     * 打包网页源码。HTML DOM 与 Resource Timing 作为入口，随后递归解析文本资源依赖。
     * @param onProgress 后台抓取进度；调用方负责切回 UI 线程。
     * @param onDone 完成回调：(成功?, 保存路径与摘要/错误信息)
     */
    fun pack(
        context: Context,
        url: String,
        title: String,
        html: String,
        consoleJson: String,
        networkJson: String,
        observedResourcesJson: String,
        ua: String,
        onProgress: (String) -> Unit = {},
        onDone: (Boolean, String) -> Unit
    ) {
        executor.execute {
            var zipFile: File? = null
            try {
                val startedAt = System.currentTimeMillis()
                val tempFile = File(context.cacheDir, "browserdiag_source_${System.currentTimeMillis()}.zip")
                zipFile = tempFile
                val queue = ArrayDeque<ResourceTask>()
                val urlToPath = LinkedHashMap<String, String>()
                val usedPaths = linkedSetOf<String>()
                val resources = JSONArray()
                val failures = JSONArray()
                val limitReasons = linkedSetOf<String>()
                var truncated = false
                var processed = 0
                var saved = 0
                var totalResourceBytes = 0L

                fun schedule(candidate: String, depth: Int, referer: String) {
                    val normalized = normalizedHttpUrl(candidate) ?: return
                    if (urlToPath.containsKey(normalized)) return
                    if (depth > MAX_RECURSION_DEPTH) {
                        truncated = true
                        limitReasons += "递归深度达到 $MAX_RECURSION_DEPTH 层"
                        return
                    }
                    if (urlToPath.size >= MAX_RESOURCE_COUNT) {
                        truncated = true
                        limitReasons += "资源数量达到 $MAX_RESOURCE_COUNT 个"
                        return
                    }
                    val zipPath = resourceZipPath(normalized, usedPaths)
                    urlToPath[normalized] = zipPath
                    queue.addLast(ResourceTask(normalized, zipPath, depth, referer))
                }

                runCatching { onProgress("正在分析页面源码与已加载资源…") }
                val observedResourceSeeds = extractObservedResourceUrls(observedResourcesJson)
                extractHtmlReferences(html, url).forEach { schedule(it, 1, url) }
                var observedResourcesQueued = false

                ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                    writeZipEntry(zos, "index.html", html.toByteArray(Charsets.UTF_8))
                    writeZipEntry(zos, "console.json", consoleJson.toByteArray(Charsets.UTF_8))
                    writeZipEntry(zos, "network.json", networkJson.toByteArray(Charsets.UTF_8))

                    while (queue.isNotEmpty() || !observedResourcesQueued) {
                        // 优先完整沿着源码依赖图递归，再补 Resource Timing 中的动态/懒加载资源，
                        // 避免动态资源先占满数量配额，导致 CSS/JS/source map 的源码依赖被挤掉。
                        if (queue.isEmpty() && !observedResourcesQueued) {
                            observedResourcesQueued = true
                            observedResourceSeeds.forEach { schedule(it, 1, url) }
                            if (queue.isEmpty()) break
                        }
                        if (System.currentTimeMillis() - startedAt >= MAX_CRAWL_MILLIS) {
                            truncated = true
                            limitReasons += "递归抓取达到 ${MAX_CRAWL_MILLIS / 1000} 秒耗时上限"
                            break
                        }
                        val task = queue.removeFirst()
                        processed++
                        runCatching {
                            onProgress("正在递归打包源码… $processed/${urlToPath.size} · 已保存 $saved")
                        }

                        val downloaded = try {
                            downloadResource(task.url, task.referer, ua)
                        } catch (e: Exception) {
                            failures.put(
                                JSONObject()
                                    .put("url", task.url)
                                    .put("path", task.zipPath)
                                    .put("depth", task.depth)
                                    .put("error", (e.message ?: e.javaClass.simpleName).take(300))
                            )
                            continue
                        }

                        if (totalResourceBytes + downloaded.bytes.size > MAX_TOTAL_RESOURCE_BYTES) {
                            truncated = true
                            limitReasons += "资源总大小达到 ${MAX_TOTAL_RESOURCE_BYTES / (1024 * 1024)} MB 上限"
                            failures.put(
                                JSONObject()
                                    .put("url", task.url)
                                    .put("path", task.zipPath)
                                    .put("depth", task.depth)
                                    .put("error", "超过归档总大小上限，停止继续抓取")
                            )
                            break
                        }

                        writeZipEntry(zos, task.zipPath, downloaded.bytes)
                        saved++
                        totalResourceBytes += downloaded.bytes.size
                        val kind = resourceKind(downloaded.mime, downloaded.finalUrl)
                        resources.put(
                            JSONObject()
                                .put("url", task.url)
                                .put("finalUrl", downloaded.finalUrl)
                                .put("path", task.zipPath)
                                .put("mime", downloaded.mime)
                                .put("kind", kind)
                                .put("bytes", downloaded.bytes.size)
                                .put("depth", task.depth)
                        )

                        if (downloaded.bytes.size <= MAX_PARSE_BYTES && kind in PARSEABLE_RESOURCE_KINDS) {
                            val text = decodeText(downloaded.bytes, downloaded.mime)
                            val dependencies = extractNestedReferences(text, downloaded.finalUrl, kind)
                            if (dependencies.isNotEmpty() && task.depth >= MAX_RECURSION_DEPTH) {
                                truncated = true
                                limitReasons += "递归深度达到 $MAX_RECURSION_DEPTH 层"
                            } else {
                                dependencies.forEach { dependency ->
                                    schedule(dependency, task.depth + 1, downloaded.finalUrl)
                                }
                            }
                        }
                    }

                    runCatching { onProgress("正在生成递归资源清单…") }
                    val reasonsJson = JSONArray()
                    limitReasons.forEach { reasonsJson.put(it) }
                    val manifest = JSONObject()
                        .put("formatVersion", 2)
                        .put("recursive", true)
                        .put("resources", resources)
                        .put("failures", failures)
                    writeZipEntry(
                        zos,
                        "resources-manifest.json",
                        manifest.toString(2).toByteArray(Charsets.UTF_8)
                    )

                    val meta = JSONObject()
                        .put("url", url)
                        .put("title", title)
                        .put("savedAt", System.currentTimeMillis())
                        .put("userAgent", ua)
                        .put(
                            "archive",
                            JSONObject()
                                .put("mode", "recursive-resources")
                                .put("discoveredResources", urlToPath.size)
                                .put("processedResources", processed)
                                .put("savedResources", saved)
                                .put("failedResources", failures.length())
                                .put("totalResourceBytes", totalResourceBytes)
                                .put("maxDepth", MAX_RECURSION_DEPTH)
                                .put("truncated", truncated)
                                .put("limitReasons", reasonsJson)
                                .put("elapsedMs", System.currentTimeMillis() - startedAt)
                        )
                    writeZipEntry(zos, "meta.json", meta.toString(2).toByteArray(Charsets.UTF_8))
                }

                val displayName = "browserdiag_source_${System.currentTimeMillis()}.zip"
                val savedPath = saveToDownloads(context, tempFile, displayName)
                val suffix = if (truncated) " · 已达到安全上限" else ""
                onDone(
                    true,
                    "$savedPath · 递归资源 $saved 个 · 失败 ${failures.length()} 个$suffix"
                )
            } catch (e: Exception) {
                onDone(false, e.message ?: e.toString())
            } finally {
                zipFile?.delete()
            }
        }
    }

    /** HTML 入口：DOM 资源、srcset、inline style 与 inline module 均纳入递归图。 */
    private fun extractHtmlReferences(html: String, pageUrl: String): List<String> {
        val out = linkedSetOf<String>()
        val baseTag = Regex("""<base\b[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
        val documentBase = baseTag
            ?.let { extractAttribute(it, "href") }
            ?.let { resolveHttpUrl(pageUrl, it) }
            ?: pageUrl

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            resolveHttpUrl(documentBase, raw)?.let { out.add(it) }
        }

        val tagRegex = Regex(
            """<(script|img|link|source|video|audio|iframe|embed|object)\b[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        tagRegex.findAll(html).forEach { match ->
            val tagName = match.groupValues[1].lowercase()
            val tag = match.value
            when (tagName) {
                "script" -> add(extractAttribute(tag, "src"))
                "img" -> {
                    add(extractAttribute(tag, "src"))
                    extractSrcset(extractAttribute(tag, "srcset")).forEach { add(it) }
                }
                "link" -> {
                    val rels = extractAttribute(tag, "rel").orEmpty()
                        .lowercase()
                        .split(Regex("""\s+"""))
                        .filter { it.isNotBlank() }
                    if (rels.any { it in RESOURCE_LINK_RELS }) add(extractAttribute(tag, "href"))
                }
                "source" -> {
                    add(extractAttribute(tag, "src"))
                    extractSrcset(extractAttribute(tag, "srcset")).forEach { add(it) }
                }
                "video" -> {
                    add(extractAttribute(tag, "src"))
                    add(extractAttribute(tag, "poster"))
                }
                "audio", "iframe", "embed" -> add(extractAttribute(tag, "src"))
                "object" -> add(extractAttribute(tag, "data"))
            }
        }

        Regex("""<style\b[^>]*>([\s\S]*?)</style\s*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { style ->
                extractCssReferences(style.groupValues[1], documentBase).forEach { out.add(it) }
            }
        Regex("""\bstyle\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { style ->
                extractCssReferences(style.groupValues[2], documentBase).forEach { out.add(it) }
            }
        Regex("""<script\b[^>]*>([\s\S]*?)</script\s*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { script ->
                extractJsReferences(script.groupValues[1], documentBase).forEach { out.add(it) }
            }
        return out.toList()
    }

    /** Performance Resource Timing 记录可以补齐懒加载、动态注入和 CSS 字体等 DOM 看不到的资源。 */
    private fun extractObservedResourceUrls(rawJson: String): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            val arr = JSONArray(rawJson)
            for (index in 0 until arr.length()) {
                normalizedHttpUrl(arr.optString(index))?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private fun extractNestedReferences(text: String, baseUrl: String, kind: String): List<String> = when (kind) {
        "html" -> extractHtmlReferences(text, baseUrl)
        "css" -> extractCssReferences(text, baseUrl)
        "javascript" -> extractJsReferences(text, baseUrl)
        "source-map" -> extractSourceMapReferences(text, baseUrl)
        "json" -> if (text.contains("\"sources\"")) extractSourceMapReferences(text, baseUrl) else emptyList()
        else -> emptyList()
    }

    private fun extractCssReferences(css: String, baseUrl: String): List<String> {
        val out = linkedSetOf<String>()
        fun add(raw: String) {
            resolveHttpUrl(baseUrl, raw)?.let { out.add(it) }
        }
        Regex("""url\(\s*(["']?)([^"')]+)\1\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(css)
            .forEach { add(it.groupValues[2]) }
        Regex("""@import\s+(?:url\(\s*)?(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)
            .findAll(css)
            .forEach { add(it.groupValues[2]) }
        extractSourceMapComment(css)?.let { add(it) }
        return out.toList()
    }

    private fun extractJsReferences(js: String, baseUrl: String): List<String> {
        val out = linkedSetOf<String>()
        fun addModule(raw: String) {
            val value = raw.trim()
            if (
                value.startsWith(".") || value.startsWith("/") || value.startsWith("//") ||
                value.startsWith("http://", true) || value.startsWith("https://", true)
            ) {
                resolveHttpUrl(baseUrl, value)?.let { out.add(it) }
            }
        }
        Regex("""(?m)^\s*(?:import|export)\s+(?:[^;\n]*?\s+from\s+)?["']([^"']+)["']""")
            .findAll(js)
            .forEach { addModule(it.groupValues[1]) }
        Regex("""import\s*\(\s*["']([^"']+)["']\s*\)""")
            .findAll(js)
            .forEach { addModule(it.groupValues[1]) }
        Regex("""new\s+URL\s*\(\s*["']([^"']+)["']\s*,\s*import\.meta\.url\s*\)""")
            .findAll(js)
            .forEach { addModule(it.groupValues[1]) }
        extractSourceMapComment(js)?.let { raw ->
            resolveHttpUrl(baseUrl, raw)?.let { out.add(it) }
        }
        return out.toList()
    }

    /** source map 中的 sources 继续抓取，可把 TS/源码模块一并归档；sourcesContent 本身已保存在 map 内。 */
    private fun extractSourceMapReferences(text: String, baseUrl: String): List<String> {
        val map = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val sources = map.optJSONArray("sources") ?: return emptyList()
        val sourceRoot = map.optString("sourceRoot").trim()
        val out = linkedSetOf<String>()
        for (index in 0 until sources.length()) {
            val source = sources.optString(index).trim()
            if (source.isBlank() || source.startsWith("data:", true) || source.startsWith("webpack:", true)) continue
            val candidate = if (
                source.startsWith("http://", true) || source.startsWith("https://", true) ||
                source.startsWith("//") || sourceRoot.isBlank()
            ) {
                source
            } else {
                sourceRoot.trimEnd('/') + "/" + source.trimStart('/')
            }
            resolveHttpUrl(baseUrl, candidate)?.let { out.add(it) }
        }
        return out.toList()
    }

    private fun extractSourceMapComment(text: String): String? =
        Regex("""[#@]\s*sourceMappingURL\s*=\s*([^\s*]+)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('"', '\'')

    private fun extractSrcset(value: String?): List<String> {
        val raw = value.orEmpty()
        // data: URI 自身可能含逗号，不能按普通 srcset 规则安全切分；这类内容也无需网络归档。
        if (raw.isBlank() || raw.contains("data:", ignoreCase = true)) return emptyList()
        return raw.split(',').mapNotNull { candidate ->
            candidate.trim().split(Regex("""\s+"""), limit = 2).firstOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    private fun extractAttribute(tag: String, name: String): String? =
        Regex(
            """\b${Regex.escape(name)}\s*=\s*(["'])(.*?)\1""",
            RegexOption.IGNORE_CASE
        ).find(tag)?.groupValues?.getOrNull(2)

    private fun resolveHttpUrl(baseUrl: String, raw: String): String? {
        val value = raw.trim()
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&#38;", "&")
        if (
            value.isBlank() || value.startsWith("#") || value.startsWith("data:", true) ||
            value.startsWith("blob:", true) || value.startsWith("javascript:", true) ||
            value.startsWith("about:", true)
        ) return null
        return runCatching { normalizedHttpUrl(URL(URL(baseUrl), value).toString()) }.getOrNull()
    }

    /** 去掉 fragment，保留 query；同一个真实 HTTP 资源只排队一次。 */
    private fun normalizedHttpUrl(raw: String): String? = runCatching {
        val parsed = URL(raw.trim())
        val protocol = parsed.protocol.lowercase()
        if (protocol !in setOf("http", "https") || parsed.host.isBlank()) return@runCatching null
        URL(protocol, parsed.host, parsed.port, parsed.file.ifBlank { "/" }).toString()
    }.getOrNull()

    private fun downloadResource(urlStr: String, referer: String, ua: String): DownloadedResource {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = FETCH_TIMEOUT_MILLIS
        conn.readTimeout = FETCH_TIMEOUT_MILLIS
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            ua.ifBlank { "Mozilla/5.0 (Linux; Android) BrowserDiag/3.8" }
        )
        conn.setRequestProperty("Accept", "*/*")
        if (normalizedHttpUrl(referer) != null) conn.setRequestProperty("Referer", referer.take(4096))
        runCatching { CookieManager.getInstance().getCookie(urlStr) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { conn.setRequestProperty("Cookie", it) }

        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            if (conn.contentLengthLong > MAX_RESOURCE_BYTES.toLong()) {
                throw IOException("单个资源超过 ${MAX_RESOURCE_BYTES / (1024 * 1024)} MB")
            }
            val bytes = conn.inputStream.use { input ->
                val initialSize = conn.contentLengthLong
                    .takeIf { it in 1L..MAX_RESOURCE_BYTES.toLong() }
                    ?.toInt()
                    ?: 32 * 1024
                val out = ByteArrayOutputStream(initialSize)
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_RESOURCE_BYTES) {
                        throw IOException("单个资源超过 ${MAX_RESOURCE_BYTES / (1024 * 1024)} MB")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            DownloadedResource(
                bytes = bytes,
                mime = conn.contentType.orEmpty().take(200),
                finalUrl = normalizedHttpUrl(conn.url.toString()) ?: urlStr
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun resourceKind(mime: String, url: String): String {
        val type = mime.substringBefore(';').trim().lowercase()
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return when {
            type == "text/html" || type == "application/xhtml+xml" || path.endsWith(".html") || path.endsWith(".htm") -> "html"
            type == "text/css" || path.endsWith(".css") -> "css"
            type.contains("javascript") || type == "application/ecmascript" || path.endsWith(".js") || path.endsWith(".mjs") -> "javascript"
            path.endsWith(".map") -> "source-map"
            type.contains("json") -> "json"
            type.startsWith("image/") -> "image"
            type.startsWith("font/") || path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> "font"
            type.startsWith("audio/") || type.startsWith("video/") -> "media"
            else -> "binary"
        }
    }

    private fun decodeText(bytes: ByteArray, mime: String): String {
        val charsetName = Regex(
            """charset\s*=\s*["']?([A-Za-z0-9._-]+)""",
            RegexOption.IGNORE_CASE
        ).find(mime)?.groupValues?.getOrNull(1)
        val charset = charsetName
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /** 资源按 host + 原 URL 路径归档；同路径不同 query 冲突时追加短哈希。 */
    private fun resourceZipPath(urlStr: String, usedPaths: MutableSet<String>): String {
        val url = URL(urlStr)
        val host = sanitizePathSegment(url.host).ifBlank { "unknown-host" }
        val segments = url.path
            .split('/')
            .filter { it.isNotBlank() }
            .map { sanitizePathSegment(it) }
            .toMutableList()
        if (segments.isEmpty() || url.path.endsWith('/')) segments += "index"
        var candidate = "resources/$host/${segments.joinToString("/")}".take(240)
        if (candidate.length >= 240) {
            val ext = segments.lastOrNull()?.substringAfterLast('.', "")
                ?.takeIf { it.length in 1..8 }
                ?.let { ".$it" }
                .orEmpty()
            candidate = "resources/$host/_long/${shortHash(urlStr)}$ext"
        }
        if (usedPaths.add(candidate)) return candidate

        val base = candidate
        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) shortHash(urlStr) else "${shortHash(urlStr)}_$attempt"
            val unique = appendPathSuffix(base, suffix)
            if (usedPaths.add(unique)) return unique
            attempt++
        }
    }

    private fun sanitizePathSegment(value: String): String {
        val clean = buildString {
            value.take(72).forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch in setOf('.', '_', '-', '@')) ch else '_')
            }
        }.trim('.').ifBlank { "resource" }
        return if (clean == "." || clean == "..") "resource" else clean
    }

    private fun shortHash(value: String): String = Integer.toHexString(value.hashCode())

    private fun appendPathSuffix(path: String, suffix: String): String {
        val slash = path.lastIndexOf('/')
        val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$dir${stem}__$suffix$ext"
    }

    private fun writeZipEntry(zos: ZipOutputStream, path: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(bytes)
        zos.closeEntry()
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
