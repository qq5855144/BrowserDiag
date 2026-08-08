package com.browserdiag.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.WeakHashMap

/**
 * BrowserDiag 3.8：Chrome 风格底部导航浏览器 + dual-era MCP + 网络实验室。
 * 功能：多标签 / 搜索引擎切换 / UA 切换 / 深色主题 / 油猴脚本 / 书签 / 保存页面 / 分享 /
 * 页面查找 / 翻译 / 媒体嗅探 / 页面资源 / 源码 zip / 语音播报 / 二维码 / 添加到桌面 / 历史。
 * 平时可作普通浏览器使用，也可作为诊断后端供其它 AI 工具通过 HTTP 调用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusBar: TextView
    private lateinit var settings: Settings
    private lateinit var networkRules: NetworkRuleStore
    private lateinit var tabs: Tabs
    private lateinit var webContainer: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var addressCard: MaterialCardView
    private lateinit var siteInfoButton: ImageButton
    private lateinit var pageActionButton: ImageButton
    private lateinit var tabCountButton: TextView
    private lateinit var pageProgress: ProgressBar
    private lateinit var backButton: LinearLayout
    private lateinit var forwardButton: LinearLayout
    private lateinit var tabsNavButton: LinearLayout
    private val consoleLogs = mutableListOf<JSONObject>()
    private var serverPort = 8788
    private var tts: TextToSpeech? = null
    private var isDark = false
    private var isFullscreen = false
    private var userscriptInstallBusy = false
    private var mainMenuDialog: Dialog? = null
    private var lastExitBackPressedAt = 0L
    private val networkScriptHandlers = WeakHashMap<WebView, MutableList<ScriptHandler>>()

    // Chrome / Material 3 风格调色板。手动主题与系统组件主题保持同步。
    private fun surfaceColor() = if (isDark) 0xFF202124.toInt() else 0xFFF8FAFD.toInt()
    private fun barColor() = if (isDark) 0xFF292A2D.toInt() else 0xFFF1F3F4.toInt()
    private fun fieldColor() = if (isDark) 0xFF303134.toInt() else 0xFFE9EEF6.toInt()
    private fun tonalColor() = if (isDark) 0xFF3C4043.toInt() else 0xFFE8EAED.toInt()
    private fun textColor() = if (isDark) 0xFFE8EAED.toInt() else 0xFF1F1F1F.toInt()
    private fun secondaryTextColor() = if (isDark) 0xFFBDC1C6.toInt() else 0xFF5F6368.toInt()
    private fun dividerColor() = if (isDark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
    private fun accentColor() = if (isDark) 0xFF8AB4F8.toInt() else 0xFF0B57D0.toInt()
    private fun accentContainerColor() = if (isDark) 0xFF233A5A.toInt() else 0xFFD3E3FD.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        networkRules = NetworkRuleStore(this)
        tabs = Tabs(this)
        McpRuntime.attach(
            owner = this,
            getWebView = { tabs.current?.webView },
            getConsoleLogs = { synchronized(consoleLogs) { consoleLogs.toList() } },
            getTabs = { tabs },
            getNetworkRuleStore = { networkRules },
            onNetworkRulesChanged = { runOnUiThread { refreshNetworkRuleHooks() } },
        )
        isDark = settings.darkMode
        theme.applyStyle(
            if (isDark) R.style.ThemeOverlay_BrowserDiag_Dark else R.style.ThemeOverlay_BrowserDiag_Light,
            true
        )
        WebView.setWebContentsDebuggingEnabled(settings.debugWeb)
        installServiceWorkerNetworkRules()
        applySavedOrientation()
        buildUi()
        installBackNavigation()
        newTab(initialUrlFromIntent(intent) ?: settings.engine.homeUrl)
        startServer()
        syncMcpKeepAliveService()
        applyTheme()
    }

    /**
     * 系统返回键/返回手势使用浏览器语义，而不是直接交给 Activity 退出。
     * 只有单标签已停在主页且用户在短时间内再次返回时才真正退出。
     */
    private fun installBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBrowserBack()
            }
        })
    }

    // ==================== 崩溃捕获 ====================
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val text = "BrowserDiag crash @ ${System.currentTimeMillis()}\n$sw"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "browserdiag_crash.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(text.toByteArray()) } }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, "browserdiag_crash.txt").writeText(text)
                }
            } catch (ex: Exception) {
            }
            try {
                File(filesDir, "crash.log").writeText(text)
            } catch (ex: Exception) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(2)
            }
        }
    }

    // ==================== UI 构建 ====================
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ---- 顶部：Omnibox + 标签计数；全局工具统一从底栏进入 ----
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 6.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 64.dp()
            )
        }
        addressCard = MaterialCardView(this).apply {
            radius = 24.dp().toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(0, 48.dp(), 1f).apply {
                marginEnd = 4.dp()
            }
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp(), 0, 2.dp(), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        siteInfoButton = iconBtn(R.drawable.ic_search, 20, "站点信息") {
            if (urlInput.hasFocus()) urlInput.requestFocus() else showSiteInfo()
        }
        addressRow.addView(siteInfoButton)
        urlInput = EditText(this).apply {
            hint = "搜索或输入网址"
            textSize = 15f
            background = null
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setPadding(2.dp(), 0, 2.dp(), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(urlInput.text.toString()); true } else false
            }
            setOnFocusChangeListener { _, focused ->
                val actualUrl = currentWeb()?.url.orEmpty()
                if (actualUrl.isNotBlank()) {
                    setText(if (focused) actualUrl else compactUrl(actualUrl))
                    if (focused) selectAll()
                }
                updateChromeControls()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        addressRow.addView(urlInput)
        pageActionButton = iconBtn(R.drawable.ic_refresh, 20, "刷新或停止") { reloadOrStop() }
        addressRow.addView(pageActionButton)
        addressCard.addView(addressRow)
        topBar.addView(addressCard)

        tabCountButton = TextView(this).apply {
            text = "1"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            contentDescription = "标签页"
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp()).apply {
                marginStart = 2.dp()
                marginEnd = 2.dp()
            }
            setOnClickListener { showTabsDialog() }
        }
        topBar.addView(tabCountButton)
        root.addView(topBar)

        pageProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2.dp())
        }
        root.addView(pageProgress)

        // ---- 页面查找栏：独立浮层式工具条 ----
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 4.dp(), 6.dp(), 4.dp())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 52.dp()
            ).apply {
                marginStart = 10.dp()
                marginEnd = 10.dp()
                bottomMargin = 4.dp()
            }
        }
        findInput = EditText(this).apply {
            hint = "查找内容"
            textSize = 14f
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    findNext(true)
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        findBar.addView(findInput)
        findBar.addView(iconBtn(R.drawable.ic_back, 18, "上一个匹配项") { findNext(false) })
        findBar.addView(iconBtn(R.drawable.ic_forward, 18, "下一个匹配项") { findNext(true) })
        findBar.addView(iconBtn(R.drawable.ic_close, 18, "关闭查找") { hideFindBar() })
        root.addView(findBar)

        // ---- WebView 容器 ----
        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(webContainer)

        // ---- BrowserDiag 状态条：和网页内容分离，不占用 Omnibox ----
        statusBar = TextView(this).apply {
            text = "正在启动诊断服务…"
            textSize = 10.5f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 0, 14.dp(), 0)
            setSingleLine(true)
            isClickable = true
            isFocusable = true
            contentDescription = "诊断状态，点击打开开发者工具"
            setOnClickListener { devTools() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28.dp())
        }
        root.addView(statusBar)

        // ---- 底部主导航：浏览历史 / 主页 / 标签 / 全局工具始终一触可达 ----
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4.dp(), 5.dp(), 4.dp(), 5.dp())
            elevation = 8.dp().toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 68.dp()
            )
        }
        backButton = navBtn(R.drawable.ic_back, "后退") {
            currentWeb()?.let { if (it.canGoBack()) it.goBack() }
        }
        forwardButton = navBtn(R.drawable.ic_forward, "前进") {
            currentWeb()?.let { if (it.canGoForward()) it.goForward() }
        }
        bottomBar.addView(backButton)
        bottomBar.addView(forwardButton)
        bottomBar.addView(navBtn(R.drawable.ic_home, "主页") { goHome() })
        tabsNavButton = navBtn(R.drawable.ic_tab, "标签") { showTabsDialog() }
        bottomBar.addView(tabsNavButton)
        bottomBar.addView(navBtn(R.drawable.ic_tools, "工具", emphasized = true) { showMainMenu() })
        root.addView(bottomBar)

        setContentView(root)
        root.requestFocus()
    }

    private fun iconBtn(
        drawableRes: Int,
        sizeDp: Int = 24,
        description: String? = null,
        action: () -> Unit
    ): ImageButton {
        val dp = (sizeDp * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            val ripple = android.util.TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            } else {
                setBackgroundColor(Color.TRANSPARENT)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = description
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp + 12, dp + 12)
            setOnClickListener { action() }
        }
    }

    private fun navBtn(
        drawableRes: Int,
        label: String,
        emphasized: Boolean = false,
        action: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = label
        isClickable = true
        isFocusable = true
        tag = if (emphasized) "nav-emphasized" else "nav-normal"
        layoutParams = LinearLayout.LayoutParams(0, 58.dp(), 1f).apply {
            marginStart = 2.dp()
            marginEnd = 2.dp()
        }
        setPadding(2.dp(), 4.dp(), 2.dp(), 3.dp())
        val ripple = android.util.TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        } else {
            setBackgroundColor(Color.TRANSPARENT)
        }
        setOnClickListener { action() }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(drawableRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(24.dp(), 24.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dp() }
        })
        styleNavItem(this, emphasized)
    }

    private fun styleNavItem(item: LinearLayout, emphasized: Boolean = item.tag == "nav-emphasized") {
        val color = if (emphasized) accentColor() else textColor()
        for (i in 0 until item.childCount) {
            when (val child = item.getChildAt(i)) {
                is ImageView -> child.setColorFilter(color)
                is TextView -> {
                    child.setTextColor(color)
                    child.setTypeface(
                        null,
                        if (emphasized) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                    )
                }
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp.dp().toFloat()
            strokeColor?.let { setStroke(1.dp(), it) }
        }

    private fun tabCountBackground(): GradientDrawable =
        roundedBackground(Color.TRANSPARENT, 11, secondaryTextColor())

    /** 应用 Chrome/Material 视觉系统，并同步系统状态栏的明暗图标。 */
    private fun applyTheme() {
        theme.applyStyle(
            if (isDark) R.style.ThemeOverlay_BrowserDiag_Dark else R.style.ThemeOverlay_BrowserDiag_Light,
            true
        )
        window.statusBarColor = surfaceColor()
        window.navigationBarColor = barColor()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        topBar.setBackgroundColor(surfaceColor())
        bottomBar.setBackgroundColor(barColor())
        addressCard.setCardBackgroundColor(fieldColor())
        addressCard.strokeColor = dividerColor()
        statusBar.setBackgroundColor(if (isDark) 0xFF252629.toInt() else 0xFFF1F3F4.toInt())
        statusBar.setTextColor(secondaryTextColor())
        urlInput.setTextColor(textColor())
        urlInput.setHintTextColor(secondaryTextColor())
        findBar.background = roundedBackground(fieldColor(), 16, dividerColor())
        findInput.setTextColor(textColor())
        findInput.setHintTextColor(secondaryTextColor())
        tabCountButton.setTextColor(textColor())
        tabCountButton.background = tabCountBackground()
        pageProgress.progressTintList = ColorStateList.valueOf(accentColor())
        pageProgress.progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)

        val tint = textColor()
        val barChildren = ArrayList<View>()
        barChildren.addAll(topBar.children())
        barChildren.addAll(bottomBar.children())
        barChildren.addAll(findBar.children())
        barChildren.forEach { v ->
            if (v is ImageButton) v.setColorFilter(tint)
        }
        bottomBar.children().filterIsInstance<LinearLayout>().forEach { styleNavItem(it) }
        siteInfoButton.setColorFilter(secondaryTextColor())
        pageActionButton.setColorFilter(secondaryTextColor())
        updateChromeControls()
        statusBar.text = buildStatusText()
    }

    private fun LinearLayout.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }

    private fun buildStatusText(): String {
        val errs = synchronized(consoleLogs) { consoleLogs.filter { it.optString("type") == "error" }.size }
        val scope = if (settings.lanApiEnabled) "局域网" else "本机"
        val mcp = if (!McpServerHost.isRunning) "MCP 接口未启动" else "MCP $scope:${serverPort}"
        val background = if (settings.lanApiEnabled && settings.backgroundMcpEnabled) " · 后台保活" else ""
        return "$mcp$background  ·  Console ${consoleLogs.size}/$errs  ·  点击诊断"
    }

    private fun reloadOrStop() {
        val wv = currentWeb() ?: return
        if (pageProgress.visibility == View.VISIBLE && wv.progress in 0..99) {
            wv.stopLoading()
            pageProgress.visibility = View.GONE
            pageProgress.progress = 0
            pageActionButton.setImageResource(R.drawable.ic_refresh)
            pageActionButton.contentDescription = "刷新页面"
            return
        } else {
            wv.reload()
        }
        updateChromeControls()
    }

    private fun updateChromeControls() {
        if (!::tabCountButton.isInitialized) return
        val wv = currentWeb()
        val currentUrl = wv?.url.orEmpty()
        val currentProgress = wv?.progress ?: 100
        pageProgress.progress = currentProgress
        if (!isFullscreen) {
            pageProgress.visibility = if (currentProgress in 1..99) View.VISIBLE else View.GONE
        }
        val loading = wv != null && !isFullscreen && currentProgress in 1..99
        tabCountButton.text = tabs.size.toString()
        tabsNavButton.contentDescription = "标签页，共 ${tabs.size} 个"
        backButton.isEnabled = wv?.canGoBack() == true
        forwardButton.isEnabled = wv?.canGoForward() == true
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
        pageActionButton.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        pageActionButton.contentDescription = if (loading) "停止加载" else "刷新页面"
        val siteIcon = when {
            urlInput.hasFocus() -> R.drawable.ic_search
            currentUrl.startsWith("https://", true) -> R.drawable.ic_lock
            currentUrl.isNotBlank() -> R.drawable.ic_info
            else -> R.drawable.ic_search
        }
        siteInfoButton.setImageResource(siteIcon)
    }

    /** Chrome 风格底部功能面板：统一标题、动作区、滚动内容和深浅色。 */
    private fun showBrowserSheet(
        title: String,
        subtitle: String = "",
        headerActionLabel: String? = null,
        headerAction: ((BottomSheetDialog) -> Unit)? = null,
        buildContent: (LinearLayout, BottomSheetDialog) -> Unit
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 18.dp())
            background = roundedBackground(surfaceColor(), 28)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 2.dp(), 2.dp(), 8.dp())
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTextColor(textColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(secondaryTextColor())
                    maxLines = 1
                })
            }
        }
        header.addView(heading)
        if (headerActionLabel != null && headerAction != null) {
            header.addView(TextView(this).apply {
                text = headerActionLabel
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(accentColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = roundedBackground(accentContainerColor(), 18)
                setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
                isClickable = true
                setOnClickListener { headerAction(dialog) }
            })
        }
        header.addView(iconBtn(R.drawable.ic_close, 20, "关闭") { dialog.dismiss() }.apply {
            setColorFilter(secondaryTextColor())
        })
        root.addView(header)

        val maxSheetContentHeight = minOf(540.dp(), (resources.displayMetrics.heightPixels * 0.66f).toInt())
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2.dp(), 0, 6.dp())
        }
        buildContent(content, dialog)
        val estimatedContentHeight = maxOf(190.dp(), content.childCount * 72.dp())
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(maxSheetContentHeight, estimatedContentHeight)
        )
        scroll.addView(content)
        root.addView(scroll)
        dialog.setContentView(root)
        dialog.show()
        return dialog
    }

    private fun sectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(accentColor())
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(14.dp(), 18.dp(), 14.dp(), 6.dp())
    }

    private fun quickAction(iconRes: Int, label: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = 76.dp()
            background = roundedBackground(accentContainerColor(), 18)
            isClickable = true
            isFocusable = true
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 3.dp()
                marginEnd = 3.dp()
            }
            setOnClickListener { action() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(accentColor())
                layoutParams = LinearLayout.LayoutParams(24.dp(), 24.dp()).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11.5f
                setTextColor(textColor())
                gravity = Gravity.CENTER
                setPadding(0, 7.dp(), 0, 0)
            })
        }

    private fun panelRow(
        iconRes: Int,
        title: String,
        subtitle: String = "",
        trailingIcon: Int? = null,
        selected: Boolean = false,
        onTrailing: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 16.dp().toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp()
            strokeColor = if (selected) accentColor() else dividerColor()
            setCardBackgroundColor(if (selected) accentContainerColor() else fieldColor())
            isClickable = onClick != null
            isFocusable = onClick != null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 4.dp()
                marginEnd = 4.dp()
                topMargin = 4.dp()
                bottomMargin = 4.dp()
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 64.dp()
            setPadding(14.dp(), 8.dp(), 8.dp(), 8.dp())
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (selected) accentColor() else secondaryTextColor())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp()).apply { marginEnd = 14.dp() }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14.5f
                setTextColor(textColor())
                maxLines = 1
            })
            if (subtitle.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(secondaryTextColor())
                    maxLines = 2
                })
            }
        })
        if (trailingIcon != null && onTrailing != null) {
            row.addView(iconBtn(trailingIcon, 20, "更多操作") { onTrailing() }.apply {
                setColorFilter(secondaryTextColor())
            })
        }
        card.addView(row)
        return card
    }

    private fun emptyPanel(iconRes: Int, title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp(), 42.dp(), 24.dp(), 42.dp())
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(secondaryTextColor())
                layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp()).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(textColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 12.dp(), 0, 4.dp())
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(secondaryTextColor())
                gravity = Gravity.CENTER
            })
        }

    private fun displayHost(url: String): String = runCatching {
        Uri.parse(url).host?.removePrefix("www.")
    }.getOrNull().orEmpty().ifEmpty { url.take(64) }

    private fun compactUrl(url: String): String = when {
        url.startsWith("https://www.", true) -> url.substring(12)
        url.startsWith("http://www.", true) -> url.substring(11)
        url.startsWith("https://", true) -> url.substring(8)
        url.startsWith("http://", true) -> url.substring(7)
        else -> url
    }

    private fun setOmniboxUrl(url: String) {
        if (!::urlInput.isInitialized || urlInput.hasFocus()) return
        urlInput.setText(compactUrl(url))
    }

    private fun showSiteInfo() {
        val url = currentWeb()?.url.orEmpty()
        if (url.isBlank()) return
        val secure = url.startsWith("https://", true)
        showBrowserSheet("站点信息", displayHost(url)) { content, dialog ->
            content.addView(panelRow(
                if (secure) R.drawable.ic_lock else R.drawable.ic_info,
                if (secure) "连接安全" else "连接未加密",
                if (secure) "此页面使用 HTTPS 加密连接" else "HTTP 页面不会加密传输内容"
            ))
            content.addView(panelRow(R.drawable.ic_link, "当前地址", url.take(180), onClick = {
                copyText(url)
                dialog.dismiss()
            }))
            content.addView(panelRow(R.drawable.ic_tools, "页面诊断", "查看 MCP 接口、Console 与当前标签状态", onClick = {
                dialog.dismiss()
                devTools()
            }))
        }
    }

    private fun initialUrlFromIntent(source: Intent?): String? {
        if (source?.action != Intent.ACTION_VIEW) return null
        val uri = source.data ?: return null
        return uri.toString().takeIf {
            uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
        }
    }

    private fun applySavedOrientation() {
        requestedOrientation = when (settings.screenOrientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialUrlFromIntent(intent)?.let { target ->
            currentWeb()?.loadUrl(target) ?: newTab(target)
        }
    }

    // ==================== 标签管理 ====================
    private fun currentWeb(): WebView? = tabs.current?.webView

    private fun newTab(url: String) {
        if (tabs.size >= tabs.maxTabs) {
            toast("标签已达上限（${tabs.maxTabs} 个），请先关闭不需要的标签")
            return
        }
        val tab = tabs.create(webContainer, url) ?: run {
            toast("暂时无法创建新标签")
            return
        }
        configureWebView(tab.webView)
        tabs.switchTo(tab.id)
        tab.webView.loadUrl(url)
        setOmniboxUrl(url)
        updateChromeControls()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = settings.uaMode.uaString(WebSettings.getDefaultUserAgent(this@MainActivity))
            textZoom = settings.fontScale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        // 网络实验室 Hook + 油猴脚本（document-start）
        installNetworkRuleHooks(wv)
        settings.getScripts().filter { it.enabled }.forEach { s ->
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    wrapUserscriptForRuntime(s),
                    patternToOriginRules(s.urlPattern)
                )
            } catch (e: Exception) {
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tabs.current?.webView !== view) return
                pageProgress.progress = newProgress
                pageProgress.visibility = if (!isFullscreen && newProgress in 1..99) View.VISIBLE else View.GONE
                updateChromeControls()
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val entry = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("type", msg.messageLevel().name.lowercase())
                    .put("text", msg.message())
                    .put("url", wv.url.orEmpty())
                    .put("source", msg.sourceId().orEmpty())
                    .put("line", msg.lineNumber())
                synchronized(consoleLogs) {
                    consoleLogs.add(entry)
                    if (consoleLogs.size > 500) consoleLogs.removeAt(0)
                }
                runOnUiThread { statusBar.text = buildStatusText() }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val tab = tabs.all.firstOrNull { it.webView === view } ?: return
                tab.title = title.orEmpty()
                if (tabs.current === tab) {
                    setOmniboxUrl(tab.url)
                    updateChromeControls()
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val tab = tabs.all.firstOrNull { it.webView === view }
                if (tab != null) {
                    tab.url = url.orEmpty()
                    tab.title = view?.title.orEmpty().ifEmpty { tab.title }
                    settings.addHistory(tab.url, tab.title)
                }
                if (tab != null && tabs.current === tab) {
                    setOmniboxUrl(url.orEmpty())
                    updateChromeControls()
                }
                // 旧版 WebView 不支持 document-start 时仍提供降级能力；现代 WebView 在导航前已注入。
                if (view != null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(buildNetworkHookJs()) { }
                    networkRules.enabledRules()
                        .filter { it.action == NetworkRuleAction.INJECT_JS || it.action == NetworkRuleAction.INJECT_CSS }
                        .forEach { rule -> view.evaluateJavascript(wrapNetworkInjectionRule(rule)) { } }
                }
                runOnUiThread { statusBar.text = buildStatusText() }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val scriptUri = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (scriptUri != null && isUserscriptUri(scriptUri)) {
                    view?.stopLoading()
                    val previousUrl = tabs.all.firstOrNull { it.webView === view }?.url.orEmpty()
                    if (view?.canGoBack() == true) {
                        view.goBack()
                    } else if (previousUrl.isNotBlank() && previousUrl != scriptUri.toString()) {
                        view?.loadUrl(previousUrl)
                    }
                    requestUserscriptInstall(scriptUri.toString())
                    return
                }
                if (tabs.current?.webView === view) {
                    setOmniboxUrl(url.orEmpty())
                    updateChromeControls()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (
                    request.isForMainFrame &&
                    (scheme == "http" || scheme == "https") &&
                    isUserscriptUri(uri)
                ) {
                    requestUserscriptInstall(uri.toString())
                    return true
                }
                if (scheme == "http" || scheme == "https" || scheme == "about") return false
                if (scheme in setOf("mailto", "tel", "sms", "geo")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                        toast("没有可处理该链接的应用")
                    }
                }
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                val entry = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("type", "error")
                    .put("text", "page error [$errorCode] $description: $failingUrl")
                synchronized(consoleLogs) {
                    consoleLogs.add(entry)
                    if (consoleLogs.size > 500) consoleLogs.removeAt(0)
                }
                runOnUiThread { statusBar.text = buildStatusText() }
            }

            // 广告拦截：命中规则则返回空响应
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.let { networkRequest ->
                    networkRules.intercept(networkRequest)?.let { return it }
                }
                if (settings.adBlock && request?.url != null) {
                    val host = request.url.host?.lowercase() ?: ""
                    if (host.isNotEmpty() && AD_BLOCK_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (isUserscriptUri(Uri.parse(url))) {
                requestUserscriptInstall(url)
                return@DownloadListener
            }
            enqueueDownload(
                url = url,
                mimeType = mimeType.orEmpty(),
                contentDisposition = contentDisposition.orEmpty(),
                userAgentOverride = userAgent.orEmpty()
            )
        })
    }

    /** 每个 WebView 独立注册 document-start Hook；规则更新时移除旧脚本并原位刷新。 */
    private fun installNetworkRuleHooks(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        networkScriptHandlers.remove(wv)?.forEach { handler -> runCatching { handler.remove() } }
        val handlers = mutableListOf<ScriptHandler>()
        runCatching {
            handlers += WebViewCompat.addDocumentStartJavaScript(wv, buildNetworkHookJs(), setOf("*"))
        }
        networkRules.enabledRules()
            .filter { it.action == NetworkRuleAction.INJECT_JS || it.action == NetworkRuleAction.INJECT_CSS }
            .forEach { rule ->
                runCatching {
                    handlers += WebViewCompat.addDocumentStartJavaScript(
                        wv,
                        wrapNetworkInjectionRule(rule),
                        setOf("*")
                    )
                }
            }
        if (handlers.isNotEmpty()) networkScriptHandlers[wv] = handlers
    }

    private fun releaseNetworkRuleHooks(wv: WebView) {
        networkScriptHandlers.remove(wv)?.forEach { handler -> runCatching { handler.remove() } }
    }

    /** Service Worker 请求不一定经过某个页面的 WebViewClient，单独接入同一套原生规则。 */
    private fun installServiceWorkerNetworkRules() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    networkRules.intercept(request)?.let { return it }
                    if (settings.adBlock) {
                        val host = request.url.host?.lowercase().orEmpty()
                        if (host.isNotEmpty() && AD_BLOCK_HOSTS.any { host == it || host.endsWith(".$it") }) {
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                    return null
                }
            })
        }
    }

    private fun buildNetworkHookJs(): String {
        val rulesLiteral = JSONObject.quote(networkRules.rulesJsonForJs())
        return NETWORK_HOOK_JS.replace("__BD_NETWORK_RULES_JSON__", rulesLiteral)
    }

    /**
     * 原生规则快照立即生效；当前页面的 fetch/XHR 同步更新，document-start JS/CSS 注入从下次导航生效。
     */
    private fun refreshNetworkRuleHooks() {
        val rulesLiteral = JSONObject.quote(networkRules.rulesJsonForJs())
        val cssRules = networkRules.enabledRules().filter { it.action == NetworkRuleAction.INJECT_CSS }
        tabs.all.forEach { tab ->
            installNetworkRuleHooks(tab.webView)
            tab.webView.evaluateJavascript(
                "window.__bdSetNetworkRules&&window.__bdSetNetworkRules(JSON.parse($rulesLiteral));true"
            ) { }
            // CSS 是可逆注入：当前页先清掉 NetworkLab 样式，再按最新启用规则重放，保证停用/删除立即生效。
            tab.webView.evaluateJavascript(
                "document.querySelectorAll('style[data-browserdiag-rule]').forEach(function(e){e.remove()});true"
            ) { }
            cssRules.forEach { rule -> tab.webView.evaluateJavascript(wrapNetworkInjectionRule(rule)) { } }
        }
    }

    private fun wrapNetworkInjectionRule(rule: NetworkRule): String {
        val pattern = JSONObject.quote(rule.urlPattern)
        val ruleId = JSONObject.quote(rule.id)
        val ruleName = JSONObject.quote(rule.name)
        val action = JSONObject.quote(rule.action.key)
        val body = when (rule.action) {
            NetworkRuleAction.INJECT_JS -> rule.value
            NetworkRuleAction.INJECT_CSS -> {
                val css = JSONObject.quote(rule.value)
                """
                (function(){
                  var applyCss=function(){
                    var root=document.head||document.documentElement;
                    if(!root)return false;
                    var old=null;
                    document.querySelectorAll('style[data-browserdiag-rule]').forEach(function(e){
                      if(e.getAttribute('data-browserdiag-rule')===$ruleId)old=e;
                    });
                    if(old)old.remove();
                    var style=document.createElement('style');
                    style.setAttribute('data-browserdiag-rule',$ruleId);
                    style.textContent=$css;
                    root.appendChild(style);
                    return true;
                  };
                  if(!applyCss())document.addEventListener('DOMContentLoaded',applyCss,{once:true});
                })();
                """.trimIndent()
            }
            else -> ""
        }
        return buildString {
            append(
                """
                (function(){
                  var p=$pattern;
                  var u=String(location.href);
                  function matchPattern(pattern,url){
                    if(!pattern||pattern==='*'||pattern==='<all_urls>')return true;
                    try{
                      var low=pattern.toLowerCase();
                      if(low.indexOf('regex:')===0)return new RegExp(pattern.slice(6)).test(url);
                      if(pattern.length>2&&pattern.charAt(0)==='/'&&pattern.charAt(pattern.length-1)==='/')return new RegExp(pattern.slice(1,-1)).test(url);
                      var special="\\.^+?()[]{}|"+String.fromCharCode(36);
                      var re='';
                      for(var i=0;i<pattern.length;i++){
                        var ch=pattern.charAt(i);
                        if(ch==='*')re+='.*';else{if(special.indexOf(ch)>=0)re+='\\';re+=ch;}
                      }
                      return new RegExp('^'+re+String.fromCharCode(36),'i').test(url);
                    }catch(_){return false;}
                  }
                  if(!matchPattern(p,u))return;
                  try{
                """.trimIndent()
            )
            append('\n')
            append(body)
            append('\n')
            append(
                """
                    if(window.__bdRecordRuleHit)window.__bdRecordRuleHit({ruleId:$ruleId,ruleName:$ruleName,action:$action,url:u,phase:'inject',detail:'document-start'});
                  }catch(e){console.error('[BrowserDiag NetworkLab]',$ruleName,e);}
                })();
                """.trimIndent()
            )
        }
    }

    private fun showTabsDialog() {
        val all = tabs.all
        showBrowserSheet(
            title = "标签页",
            subtitle = "${all.size}/${tabs.maxTabs} · 点击切换，右侧 × 关闭单个标签",
            headerActionLabel = "＋ 新标签",
            headerAction = { dialog ->
                newTab(settings.engine.homeUrl)
                dialog.dismiss()
            }
        ) { content, dialog ->
            if (all.isNotEmpty()) {
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(1.dp(), 2.dp(), 1.dp(), 8.dp())
                }
                actions.addView(quickAction(R.drawable.ic_close, "关闭当前") {
                    val currentId = tabs.current?.id ?: return@quickAction
                    closeTabAndKeepBrowser(currentId)
                    dialog.dismiss()
                })
                actions.addView(quickAction(R.drawable.ic_delete, "关闭全部") {
                    confirmCloseAllTabs(dialog)
                })
                content.addView(actions)
            }
            if (all.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_tab, "还没有标签页", "新建一个标签开始浏览"))
            }
            all.forEach { tab ->
                val isCurrent = tabs.current?.id == tab.id
                val title = tab.title.ifEmpty { tab.url }.ifEmpty { "新标签" }
                val subtitle = displayHost(tab.url).ifEmpty { "空白标签" }
                content.addView(panelRow(
                    iconRes = if (isCurrent) R.drawable.ic_tab else R.drawable.ic_link,
                    title = title.take(64),
                    subtitle = subtitle,
                    trailingIcon = R.drawable.ic_close,
                    selected = isCurrent,
                    onTrailing = {
                        closeTabAndKeepBrowser(tab.id)
                        dialog.dismiss()
                    },
                    onClick = {
                        tabs.switchTo(tab.id)
                        setOmniboxUrl(tab.url)
                        updateChromeControls()
                        statusBar.text = buildStatusText()
                        dialog.dismiss()
                    }
                ))
            }
        }
    }

    private fun closeTabAndKeepBrowser(tabId: Int) {
        tabs.get(tabId)?.webView?.let(::releaseNetworkRuleHooks)
        tabs.destroy(tabId)
        if (tabs.size == 0) {
            newTab(settings.engine.homeUrl)
        } else {
            tabs.current?.let { setOmniboxUrl(it.url) }
            updateChromeControls()
            statusBar.text = buildStatusText()
        }
    }

    private fun confirmCloseAllTabs(parentSheet: BottomSheetDialog) {
        AlertDialog.Builder(this)
            .setTitle("关闭全部标签页？")
            .setMessage("将关闭当前 ${tabs.size} 个标签页，并打开一个新的主页标签。")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭全部") { _, _ ->
                parentSheet.dismiss()
                tabs.all.forEach { releaseNetworkRuleHooks(it.webView) }
                tabs.destroyAll()
                newTab(settings.engine.homeUrl)
                toast("已关闭全部标签页")
            }
            .show()
    }

    // ==================== 导航 ====================
    private fun navigate(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val engine = settings.engine
        val wv = currentWeb() ?: return
        when {
            q.startsWith("http://") || q.startsWith("https://") -> wv.loadUrl(q)
            q.startsWith("about:") -> wv.loadUrl(q)
            looksLikeWebAddress(q) ->
                wv.loadUrl("https://$q")
            else -> wv.loadUrl(engine.searchUrl.format(URLEncoder.encode(q, "UTF-8")))
        }
        clearFocusAndKeyboard(urlInput)
    }

    /** 识别 example.com/path、IP:port、localhost 等常见地址，避免被错误送去搜索。 */
    private fun looksLikeWebAddress(value: String): Boolean {
        if (value.any { it.isWhitespace() }) return false
        val parsed = runCatching { Uri.parse("https://$value") }.getOrNull() ?: return false
        val host = parsed.host?.trim('[', ']')?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost") return true
        if (Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""").matches(host)) {
            return host.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 }
        }
        return host.contains('.') && host.split('.').all { label ->
            label.isNotBlank() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun goHome() {
        currentWeb()?.loadUrl(settings.engine.homeUrl)
        setOmniboxUrl(settings.engine.homeUrl)
    }

    // ==================== 工具中心：稳定入口 + 快捷动作 + 永久可达的分类功能 ====================
    private fun toolCategories(): List<ToolCategory> = listOf(
        ToolCategory(
            "页面与内容",
            R.drawable.ic_widget,
            "查找、保存、分享、翻译与页面操作",
            listOf(
                MenuItem("find", R.drawable.ic_find, "页面查找", "在当前网页中查找文字") { showFindBar() },
                MenuItem("save", R.drawable.ic_save, "保存页面", "将当前页面保存到设备") { savePage() },
                MenuItem("share", R.drawable.ic_share, "分享页面", "通过系统分享当前地址") { sharePage() },
                MenuItem("translate", R.drawable.ic_translate, "翻译本页", "使用翻译服务打开当前页面") { translatePage() },
                MenuItem("widget", R.drawable.ic_widget, "添加到桌面", "创建当前网站快捷方式") { addToHome() },
                MenuItem("fullscreen", R.drawable.ic_launch, "全屏模式", "隐藏浏览器工具栏，专注页面内容") { toggleFullscreen() },
                MenuItem("qr", R.drawable.ic_qr, "页面二维码", "将当前地址生成二维码") { showQr() },
                MenuItem("tts", R.drawable.ic_mic, "语音播报", "朗读页面主要文本内容") { speakPage() }
            )
        ),
        ToolCategory(
            "浏览数据",
            R.drawable.ic_history,
            "${settings.getBookmarks().size} 书签 · ${settings.getHistory().size} 历史记录",
            listOf(
                MenuItem("bookmark", R.drawable.ic_bookmark, "书签", "收藏和管理常用页面") { showBookmarks() },
                MenuItem("history", R.drawable.ic_history, "历史记录", "${settings.getHistory().size} 条最近访问记录") { showHistory() },
                MenuItem("downloads", R.drawable.ic_download, "下载内容", "查看下载进度、状态与已完成文件") { showDownloads() }
            )
        ),
        ToolCategory(
            "诊断与开发",
            R.drawable.ic_tools,
            "媒体、资源、源码、网络与 Console",
            listOf(
                MenuItem("sniff", R.drawable.ic_movie, "媒体嗅探", "智能识别视频、音频、HLS/DASH 清单并折叠分片") { sniffMedia() },
                MenuItem("resources", R.drawable.ic_folder, "页面资源", "分类识别图片、脚本、样式、字体与媒体") { pageResources() },
                MenuItem("source", R.drawable.ic_code, "递归源码归档", "递归打包 HTML、CSS/JS、图片、字体、source map 与诊断日志") { downloadSourceZip() },
                MenuItem("networklab", R.drawable.ic_network, "网络实验室", "请求重写、Mock、Header/Body 修改与 JS/CSS 注入") { showNetworkLab() },
                MenuItem("netlog", R.drawable.ic_network, "网络日志", "查看请求状态、类型、大小与耗时") { showNetLog() },
                MenuItem("devtools", R.drawable.ic_tools, "开发者工具", "MCP 接口、Console 与页面状态") { devTools() }
            )
        ),
        ToolCategory(
            "浏览设置",
            R.drawable.ic_settings,
            "${settings.engine.label} · ${settings.uaMode.label}",
            listOf(
                MenuItem("dark", R.drawable.ic_dark, "深色主题", if (isDark) "已开启" else "已关闭") { toggleDark() },
                MenuItem("engine", R.drawable.ic_search, "搜索引擎", settings.engine.label) { showEnginePicker() },
                MenuItem("ua", R.drawable.ic_phone, "User-Agent", settings.uaMode.label) { showUaPicker() },
                MenuItem("userscript", R.drawable.ic_extension, "用户脚本", "${settings.getScripts().size} 个脚本 · 支持网页 .user.js 安装") { showScriptManager() },
                MenuItem("font", R.drawable.ic_font, "网页字体", "${settings.fontScale}%") { showFontScale() },
                MenuItem("orientation", R.drawable.ic_rotate, "屏幕方向", orientationLabel()) { showOrientation() },
                MenuItem("adblock", R.drawable.ic_shield, "广告拦截", if (settings.adBlock) "已开启" else "已关闭") { toggleAdBlock() },
                MenuItem("debugweb", R.drawable.ic_code, "WebView 调试", if (settings.debugWeb) "已开启" else "已关闭") { toggleDebugWeb() }
            )
        ),
        ToolCategory(
            "BrowserDiag",
            R.drawable.ic_info,
            if (settings.lanApiEnabled) "MCP 接口已允许局域网访问" else "MCP 接口仅限本机",
            listOf(
                MenuItem("lanapi", R.drawable.ic_link, "局域网 MCP 接口", if (settings.lanApiEnabled) "已开启 · Streamable HTTP · Token 认证" else "已关闭 · 仅本机") { toggleLanApi() },
                MenuItem(
                    "mcpbackground",
                    R.drawable.ic_network,
                    "后台 MCP 保活",
                    when {
                        !settings.lanApiEnabled -> "开启局域网 MCP 后生效"
                        settings.backgroundMcpEnabled -> "已开启 · 前台服务 + Wi-Fi/CPU 保活"
                        else -> "已关闭 · 切换应用后可能断开"
                    }
                ) { toggleBackgroundMcp() },
                MenuItem(
                    "mcpbattery",
                    R.drawable.ic_settings,
                    "系统后台权限",
                    if (isBatteryOptimizationIgnored()) "电池优化已放宽" else "建议设为不限制/不优化 · 厂商系统可能需要"
                ) { openBackgroundPowerSettings() },
                MenuItem(
                    "mcpcompat",
                    R.drawable.ic_shield,
                    "MCP URL-only 兼容",
                    if (settings.mcpUrlOnlyCompatibility) "已开启 · 仅限可信局域网" else "已关闭 · 推荐 Token 模式"
                ) { toggleMcpUrlOnlyCompatibility() },
                MenuItem("menuconfig", R.drawable.ic_settings, "常用工具设置", "选择工具中心的常用快捷入口") { showMenuConfig() },
                MenuItem("about", R.drawable.ic_info, "关于 BrowserDiag", "v3.8.0 · MCP ${serverPort}") { showAbout() }
            )
        )
    )

    private fun addToolShortcutGrid(
        content: LinearLayout,
        items: List<MenuItem>,
        sheet: BottomSheetDialog
    ) {
        items.chunked(4).forEach { chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(1.dp(), 2.dp(), 1.dp(), 4.dp())
            }
            chunk.forEach { item ->
                row.addView(quickAction(item.icon, item.label) {
                    sheet.dismiss()
                    item.action()
                })
            }
            repeat(4 - chunk.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1.dp(), 1f).apply {
                        marginStart = 3.dp()
                        marginEnd = 3.dp()
                    }
                })
            }
            content.addView(row)
        }
    }

    private fun showToolGroup(category: ToolCategory) {
        showBrowserSheet(
            title = category.title,
            subtitle = category.subtitle,
            headerActionLabel = "工具中心",
            headerAction = { dialog ->
                dialog.dismiss()
                showMainMenu()
            }
        ) { content, sheet ->
            category.items.forEach { item ->
                content.addView(panelRow(item.icon, item.label, item.subtitle, onClick = {
                    sheet.dismiss()
                    item.action()
                }))
            }
        }
    }

    private fun showAllTools() {
        val categories = toolCategories()
        showBrowserSheet(
            title = "全部工具",
            subtitle = "${categories.sumOf { it.items.size }} 个功能 · 不受快捷设置影响",
            headerActionLabel = "工具中心",
            headerAction = { dialog ->
                dialog.dismiss()
                showMainMenu()
            }
        ) { content, sheet ->
            categories.forEach { category ->
                content.addView(sectionTitle(category.title))
                category.items.forEach { item ->
                    content.addView(panelRow(item.icon, item.label, item.subtitle, onClick = {
                        sheet.dismiss()
                        item.action()
                    }))
                }
            }
        }
    }

    private fun showMainMenu() {
        mainMenuDialog?.dismiss()
        val categories = toolCategories()
        val allItems = categories.flatMap { it.items }
        val cfg = settings.getMenuConfig()
        val fixedPageActions = setOf("find", "save", "share")
        val defaultFavorites = listOf("history", "downloads", "networklab", "netlog", "devtools", "userscript", "dark", "ua")
        val favoriteIds = if (cfg.isEmpty()) {
            defaultFavorites
        } else {
            allItems.filter { cfg[it.id] == true && it.id !in fixedPageActions }.map { it.id }.take(8)
        }
        val favorites = favoriteIds.mapNotNull { id -> allItems.firstOrNull { it.id == id } }
        val currentTitle = tabs.current?.title.orEmpty().ifEmpty { displayHost(currentWeb()?.url.orEmpty()) }

        val dialog = showBrowserSheet("工具中心", currentTitle.take(72)) { content, sheet ->
            content.addView(sectionTitle("当前页面"))
            val pageQuick = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(1.dp(), 2.dp(), 1.dp(), 6.dp())
            }
            pageQuick.addView(quickAction(R.drawable.ic_bookmark, "收藏当前") {
                sheet.dismiss(); addCurrentToBookmarks()
            })
            pageQuick.addView(quickAction(R.drawable.ic_find, "查找") {
                sheet.dismiss(); showFindBar()
            })
            pageQuick.addView(quickAction(R.drawable.ic_share, "分享") {
                sheet.dismiss(); sharePage()
            })
            pageQuick.addView(quickAction(R.drawable.ic_save, "保存") {
                sheet.dismiss(); savePage()
            })
            content.addView(pageQuick)

            content.addView(sectionTitle("常用工具"))
            if (favorites.isEmpty()) {
                content.addView(panelRow(
                    R.drawable.ic_settings,
                    "尚未配置常用工具",
                    "点击选择最多 8 个快捷入口",
                    onClick = {
                        sheet.dismiss()
                        showMenuConfig()
                    }
                ))
            } else {
                addToolShortcutGrid(content, favorites, sheet)
            }

            content.addView(sectionTitle("全部功能分类"))
            categories.forEach { category ->
                content.addView(panelRow(
                    category.icon,
                    category.title,
                    "${category.items.size} 个功能 · ${category.subtitle}",
                    trailingIcon = R.drawable.ic_forward,
                    onTrailing = {
                        sheet.dismiss()
                        showToolGroup(category)
                    },
                    onClick = {
                        sheet.dismiss()
                        showToolGroup(category)
                    }
                ))
            }
            content.addView(panelRow(
                R.drawable.ic_tools,
                "全部工具一览",
                "一次查看所有功能；任何功能都不会因快捷设置而消失",
                trailingIcon = R.drawable.ic_forward,
                onTrailing = {
                    sheet.dismiss()
                    showAllTools()
                },
                onClick = {
                    sheet.dismiss()
                    showAllTools()
                }
            ))
        }
        mainMenuDialog = dialog
        dialog.setOnDismissListener {
            if (mainMenuDialog === dialog) mainMenuDialog = null
        }
    }

    private class ToolCategory(
        val title: String,
        val icon: Int,
        val subtitle: String,
        val items: List<MenuItem>
    )

    private class MenuItem(
        val id: String,
        val icon: Int,
        val label: String,
        val subtitle: String = "",
        val action: () -> Unit
    )

    // ==================== 书签 ====================
    private fun showBookmarks() {
        val bookmarks = settings.getBookmarks()
        showBrowserSheet(
            title = "书签",
            subtitle = if (bookmarks.isEmpty()) "保存常用网页，随时快速访问" else "${bookmarks.size} 个已收藏页面",
            headerActionLabel = "＋ 收藏当前页",
            headerAction = { dialog ->
                addCurrentToBookmarks()
                dialog.dismiss()
            }
        ) { content, dialog ->
            if (bookmarks.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_bookmark, "还没有书签", "打开网页后点击“收藏当前页”即可保存"))
            }
            bookmarks.forEach { (name, url) ->
                content.addView(panelRow(
                    iconRes = R.drawable.ic_bookmark,
                    title = name.ifEmpty { displayHost(url) }.take(72),
                    subtitle = displayHost(url),
                    trailingIcon = R.drawable.ic_delete,
                    onTrailing = {
                        settings.removeBookmark(url)
                        toast("书签已删除")
                        dialog.dismiss()
                        showBookmarks()
                    },
                    onClick = {
                        currentWeb()?.loadUrl(url)
                        dialog.dismiss()
                    }
                ))
            }
        }
    }

    private fun addCurrentToBookmarks() {
        val url = currentWeb()?.url ?: return
        if (url.isEmpty()) { toast("当前无页面"); return }
        val name = tabs.current?.title.orEmpty().ifEmpty { url }
        settings.addBookmark(name, url)
        toast("已收藏：$name")
    }

    // ==================== 保存页面 / 分享 / 查找 / 翻译 ====================
    private fun savePage() {
        if (!ensureLegacyDownloadsPermission()) return
        val wv = currentWeb() ?: return
        val url = wv.url ?: return
        if (url.isEmpty() || url == "about:blank") { toast("当前没有可保存的页面"); return }
        statusBar.text = "正在保存页面…"
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            val name = "page_${System.currentTimeMillis()}.html"
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(html.toByteArray()) } }
                    runOnUiThread { statusBar.text = "页面已保存 ✅ $name"; toast("页面已保存到下载目录") }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, name).writeText(html)
                    runOnUiThread { statusBar.text = "页面已保存 ✅ $name"; toast("页面已保存到下载目录") }
                }
            } catch (e: Exception) {
                runOnUiThread { statusBar.text = "保存失败 ❌ ${e.message}"; toast("保存失败：${e.message}") }
            }
        }
    }

    private fun sharePage() {
        val url = currentWeb()?.url ?: return
        val title = tabs.current?.title.orEmpty().ifEmpty { url }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        startActivity(Intent.createChooser(send, "分享页面"))
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        clearFocusAndKeyboard(findInput)
        currentWeb()?.clearMatches()
    }

    private fun clearFocusAndKeyboard(view: View) {
        view.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun findNext(forward: Boolean) {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        currentWeb()?.let { wv ->
            wv.findAllAsync(q)
            wv.findNext(forward)
        }
    }

    private fun translatePage() {
        val url = currentWeb()?.url ?: return
        val tUrl = "https://translate.google.com/translate?u=" + URLEncoder.encode(url, "UTF-8")
        currentWeb()?.loadUrl(tUrl)
        setOmniboxUrl(tUrl)
    }

    // ==================== 媒体嗅探 / 页面资源 ====================
    private data class MediaCandidate(
        val url: String,
        val kind: String,
        val source: String,
        val mime: String,
        val bytes: Long,
        val duration: Double,
        val width: Int,
        val height: Int,
        val status: Int
    )

    private fun sniffMedia() {
        val wv = currentWeb() ?: return
        statusBar.text = "正在嗅探媒体资源…"
        wv.evaluateJavascript(MEDIA_SNIFF_JS) { raw ->
            val candidates = try {
                val arr = decodeJsArray(raw)
                (0 until arr.length()).mapNotNull { index ->
                    val item = arr.optJSONObject(index) ?: return@mapNotNull null
                    val url = item.optString("url")
                    if (url.isBlank()) return@mapNotNull null
                    MediaCandidate(
                        url = url,
                        kind = item.optString("kind", "media"),
                        source = item.optString("source"),
                        mime = item.optString("mime"),
                        bytes = item.optLong("bytes", 0L),
                        duration = item.optDouble("duration", 0.0),
                        width = item.optInt("width", 0),
                        height = item.optInt("height", 0),
                        status = item.optInt("status", 0)
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                showMediaSniffResults(candidates)
            }
        }
    }

    private fun showMediaSniffResults(candidates: List<MediaCandidate>) {
        val segments = candidates.count { it.kind == "segment" }
        val visible = candidates.filter { it.kind != "segment" }
        val videos = visible.count { it.kind == "video" }
        val audios = visible.count { it.kind == "audio" }
        val playlists = visible.count { it.kind == "playlist" }
        statusBar.text = if (visible.isEmpty()) "未发现可直接使用的媒体资源" else "发现 ${visible.size} 个媒体资源"
        val summary = buildList {
            if (videos > 0) add("视频 $videos")
            if (audios > 0) add("音频 $audios")
            if (playlists > 0) add("流媒体清单 $playlists")
            if (segments > 0) add("已折叠分片 $segments")
        }.joinToString(" · ").ifBlank { "DOM + Performance + 网络请求智能识别" }

        showBrowserSheet(
            title = "媒体嗅探",
            subtitle = summary,
            headerActionLabel = "重新扫描",
            headerAction = { dialog ->
                dialog.dismiss()
                sniffMedia()
            }
        ) { content, _ ->
            content.addView(panelRow(
                R.drawable.ic_info,
                "智能识别",
                "已合并页面媒体、Performance 与 XHR/fetch，并自动去重；播放媒体后重新扫描可获得更多结果。"
            ))
            if (visible.isEmpty()) {
                content.addView(emptyPanel(
                    R.drawable.ic_movie,
                    "暂未发现可直接使用的媒体",
                    if (segments > 0) "已检测到 $segments 个媒体分片并自动折叠；继续播放后重扫以寻找 M3U8/MPD 清单。"
                    else "先播放页面中的视频或音频，再点击“重新扫描”。"
                ))
                return@showBrowserSheet
            }
            visible.forEach { media ->
                val blobUrl = media.url.startsWith("blob:", true)
                val title = mediaDisplayTitle(media)
                val subtitle = mediaDisplaySubtitle(media)
                content.addView(panelRow(
                    iconRes = when (media.kind) {
                        "audio" -> R.drawable.ic_mic
                        "playlist" -> R.drawable.ic_network
                        else -> R.drawable.ic_movie
                    },
                    title = title,
                    subtitle = subtitle,
                    trailingIcon = if (blobUrl) null else R.drawable.ic_download,
                    onTrailing = if (blobUrl) null else ({ enqueueMediaDownload(media) }),
                    onClick = { showMediaActions(media) }
                ))
            }
        }
    }

    private fun mediaDisplayTitle(media: MediaCandidate): String {
        val kind = when (media.kind) {
            "video" -> "视频"
            "audio" -> "音频"
            "playlist" -> "流媒体清单"
            else -> "媒体"
        }
        val format = mediaFormat(media)
        val file = runCatching {
            Uri.parse(media.url).lastPathSegment.orEmpty().substringBefore('?').takeLast(54)
        }.getOrDefault("")
        return listOf(kind, format, file).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(90)
    }

    private fun mediaDisplaySubtitle(media: MediaCandidate): String = buildList {
        if (media.url.startsWith("blob:", true)) add("页面 Blob 临时流")
        else displayHost(media.url).takeIf { it.isNotBlank() }?.let { add(it) }
        media.source.takeIf { it.isNotBlank() }?.let { add(it) }
        if (media.width > 0 && media.height > 0) add("${media.width}×${media.height}")
        if (media.duration > 0 && media.duration.isFinite()) add(formatDuration(media.duration))
        if (media.bytes > 0) add(formatBytes(media.bytes))
        if (media.status > 0) add("HTTP ${media.status}")
        media.mime.takeIf { it.isNotBlank() }?.let { add(it.substringBefore(';')) }
    }.joinToString(" · ").take(220)

    private fun mediaFormat(media: MediaCandidate): String {
        val path = runCatching { Uri.parse(media.url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val extension = path.substringAfterLast('.', "").takeIf { it.length in 2..6 }
        if (extension != null) return extension.uppercase(Locale.ROOT)
        val subtype = media.mime.substringAfter('/', "").substringBefore(';').substringBefore('+')
        return subtype.take(12).uppercase(Locale.ROOT)
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, secs)
        else "%d:%02d".format(Locale.ROOT, minutes, secs)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.ROOT, bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun showMediaActions(media: MediaCandidate) {
        val blobUrl = media.url.startsWith("blob:", true)
        val actions = mutableListOf<String>()
        if (!blobUrl) {
            actions += "新标签预览"
            actions += "使用系统应用打开"
            actions += "下载资源"
        }
        actions += "复制链接"
        actions += "复制媒体信息"
        AlertDialog.Builder(this)
            .setTitle(mediaDisplayTitle(media))
            .setItems(actions.toTypedArray()) { dialog, which ->
                when (actions[which]) {
                    "新标签预览" -> newTab(media.url)
                    "使用系统应用打开" -> try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(media.url)))
                    } catch (_: Exception) {
                        toast("没有可处理该媒体的应用")
                    }
                    "下载资源" -> enqueueMediaDownload(media)
                    "复制链接" -> copyText(media.url)
                    "复制媒体信息" -> copyText("${mediaDisplayTitle(media)}\n${mediaDisplaySubtitle(media)}\n${media.url}")
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun enqueueMediaDownload(media: MediaCandidate) {
        if (media.url.startsWith("blob:", true)) {
            toast("Blob 临时地址不能直接下载，请寻找对应的流媒体清单")
            return
        }
        val suggestedName = runCatching { Uri.parse(media.url).lastPathSegment.orEmpty() }.getOrDefault("")
        enqueueDownload(
            url = media.url,
            mimeType = media.mime,
            suggestedName = suggestedName,
            successMessage = if (media.kind == "playlist") "已加入流媒体清单下载" else "已加入下载队列"
        )
    }

    /**
     * 所有网页下载统一携带当前浏览上下文，避免登录态、来源校验或 UA 校验导致下载失败。
     * DownloadManager 负责最终存储与系统通知，BrowserDiag 只传递安全的请求头与显示信息。
     */
    private fun enqueueDownload(
        url: String,
        mimeType: String = "",
        contentDisposition: String = "",
        suggestedName: String = "",
        userAgentOverride: String = "",
        successMessage: String = "已加入下载队列"
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (uri == null || scheme !in setOf("http", "https")) {
            toast("该地址不能直接交给系统下载")
            return
        }
        try {
            val cleanMime = mimeType.substringBefore(';').trim()
            val request = DownloadManager.Request(uri)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (cleanMime.isNotBlank()) request.setMimeType(cleanMime)

            val userAgent = userAgentOverride.ifBlank { currentWeb()?.settings?.userAgentString.orEmpty() }
            val referer = currentWeb()?.url.orEmpty()
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
            if (userAgent.isNotBlank()) request.addRequestHeader("User-Agent", userAgent)
            if (referer.startsWith("http://") || referer.startsWith("https://")) {
                request.addRequestHeader("Referer", referer)
            }
            if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)

            val guessedName = suggestedName.ifBlank {
                URLUtil.guessFileName(
                    url,
                    contentDisposition.takeIf { it.isNotBlank() },
                    cleanMime.takeIf { it.isNotBlank() }
                )
            }
            val safeTitle = guessedName
                .replace(Regex("""[\\/\u0000-\u001F]"""), "_")
                .trim()
                .take(120)
            request.setTitle(safeTitle.ifBlank { displayHost(url) })
            request.setDescription("来自 ${displayHost(url)}")
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(successMessage)
        } catch (e: Exception) {
            toast("下载失败：${e.message}")
        }
    }

    private data class PageResource(
        val url: String,
        val kind: String,
        val source: String,
        val mime: String,
        val bytes: Long
    )

    private fun pageResources() {
        val wv = currentWeb() ?: return toast("当前没有可分析的页面")
        wv.evaluateJavascript(PAGE_RESOURCES_JS) { raw ->
            val arr = decodeJsArray(raw)
            val resources = buildList {
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (url.isBlank()) continue
                    add(
                        PageResource(
                            url = url,
                            kind = item.optString("kind").ifBlank { "other" },
                            source = item.optString("source"),
                            mime = item.optString("mime"),
                            bytes = item.optLong("bytes").coerceAtLeast(0)
                        )
                    )
                }
            }
            runOnUiThread { showPageResources(resources) }
        }
    }

    private fun showPageResources(resources: List<PageResource>) {
        val counts = resources.groupingBy { it.kind }.eachCount()
        val summary = buildList {
            counts["image"]?.takeIf { it > 0 }?.let { add("图片 $it") }
            counts["script"]?.takeIf { it > 0 }?.let { add("脚本 $it") }
            counts["style"]?.takeIf { it > 0 }?.let { add("样式 $it") }
            counts["media"]?.takeIf { it > 0 }?.let { add("媒体 $it") }
            counts["font"]?.takeIf { it > 0 }?.let { add("字体 $it") }
        }.joinToString(" · ")
        showBrowserSheet(
            title = "页面资源",
            subtitle = if (resources.isEmpty()) "未识别到可用的外部资源" else "${resources.size} 个 · ${summary.ifBlank { "其他资源" }}",
            headerActionLabel = "重新扫描",
            headerAction = { dialog ->
                dialog.dismiss()
                pageResources()
            }
        ) { content, _ ->
            if (resources.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_folder, "未发现页面资源", "滚动或操作页面后重新扫描，可发现懒加载资源"))
                return@showBrowserSheet
            }
            content.addView(panelRow(
                R.drawable.ic_info,
                "资源已自动分类与去重",
                "结合 DOM 与 Performance 信息显示类型、来源、大小和 MIME；点击资源可打开、下载或复制"
            ))
            resources.forEach { resource ->
                content.addView(panelRow(
                    iconRes = resourceIcon(resource.kind),
                    title = resourceDisplayTitle(resource),
                    subtitle = resourceDisplaySubtitle(resource),
                    onClick = { showResourceActions(resource) }
                ))
            }
        }
    }

    private fun resourceIcon(kind: String): Int = when (kind) {
        "image" -> R.drawable.ic_image
        "script" -> R.drawable.ic_code
        "style", "font" -> R.drawable.ic_font
        "media" -> R.drawable.ic_movie
        else -> R.drawable.ic_link
    }

    private fun resourceKindLabel(kind: String): String = when (kind) {
        "image" -> "图片"
        "script" -> "脚本"
        "style" -> "样式"
        "font" -> "字体"
        "media" -> "媒体"
        else -> "资源"
    }

    private fun resourceDisplayTitle(resource: PageResource): String {
        val name = runCatching { Uri.parse(resource.url).lastPathSegment.orEmpty() }
            .getOrDefault("")
            .substringBefore('?')
            .take(52)
            .ifBlank { displayHost(resource.url) }
        val format = resourceFormat(resource)
        return buildList {
            add(resourceKindLabel(resource.kind))
            if (format.isNotBlank()) add(format)
            add(name)
        }.joinToString(" · ")
    }

    private fun resourceDisplaySubtitle(resource: PageResource): String = buildList {
        add(displayHost(resource.url))
        if (resource.source.isNotBlank()) add(resource.source)
        if (resource.bytes > 0) add(formatBytes(resource.bytes))
        if (resource.mime.isNotBlank()) add(resource.mime.substringBefore(';').take(48))
    }.joinToString(" · ")

    private fun resourceFormat(resource: PageResource): String {
        val path = runCatching { Uri.parse(resource.url).path.orEmpty() }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").takeIf { it.length in 1..6 }
        if (ext != null) return ext.uppercase(Locale.ROOT)
        return resource.mime.substringAfter('/', "").substringBefore(';').substringBefore('+')
            .take(10).uppercase(Locale.ROOT)
    }

    private fun showResourceActions(resource: PageResource) {
        val downloadable = resource.url.startsWith("http://", true) || resource.url.startsWith("https://", true)
        val actions = mutableListOf<String>()
        if (downloadable) {
            actions += "新标签打开"
            actions += "下载资源"
        }
        actions += "复制链接"
        actions += "复制资源信息"
        AlertDialog.Builder(this)
            .setTitle(resourceDisplayTitle(resource))
            .setItems(actions.toTypedArray()) { dialog, which ->
                when (actions[which]) {
                    "新标签打开" -> newTab(resource.url)
                    "下载资源" -> enqueueDownload(
                        url = resource.url,
                        mimeType = resource.mime,
                        suggestedName = runCatching { Uri.parse(resource.url).lastPathSegment.orEmpty() }.getOrDefault("")
                    )
                    "复制链接" -> copyText(resource.url)
                    "复制资源信息" -> copyText("${resourceDisplayTitle(resource)}\n${resourceDisplaySubtitle(resource)}\n${resource.url}")
                }
                dialog.dismiss()
            }
            .show()
    }

    // ==================== 语音播报 ====================
    private fun speakPage() {
        val wv = currentWeb() ?: return
        wv.evaluateJavascript("document.body?document.body.innerText.slice(0,4000):''") { raw ->
            val text = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            if (text.isBlank()) {
                runOnUiThread { toast("页面没有可朗读的文本") }
                return@evaluateJavascript
            }
            runOnUiThread {
                if (tts == null) {
                    tts = TextToSpeech(this) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.language = Locale.CHINA
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bd_speak")
                            toast("开始朗读（再次点击停止）")
                        }
                    }
                } else {
                    if (tts!!.isSpeaking) {
                        tts!!.stop()
                        toast("已停止朗读")
                    } else {
                        tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bd_speak")
                        toast("开始朗读（再次点击停止）")
                    }
                }
            }
        }
    }

    // ==================== 二维码 ====================
    private fun showQr() {
        val url = currentWeb()?.url ?: run { toast("当前无页面"); return }
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (y in 0 until size) for (x in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                setPadding(40, 24, 40, 0)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(img)
                addView(TextView(this@MainActivity).apply {
                    text = url.take(60)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(16, 10, 16, 20)
                })
            }
            AlertDialog.Builder(this)
                .setTitle("页面二维码")
                .setView(col)
                .setPositiveButton("复制链接") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", url))
                    toast("已复制链接")
                }
                .setNegativeButton("关闭", null)
                .show()
        } catch (e: Exception) {
            toast("二维码生成失败：${e.message}")
        }
    }

    // ==================== 添加到桌面 ====================
    private fun addToHome() {
        val url = currentWeb()?.url ?: return
        val title = tabs.current?.title.orEmpty().ifEmpty { url }
        val id = "bd_${url.hashCode()}"
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val info = ShortcutInfoCompat.Builder(this, id)
            .setIntent(intent)
            .setShortLabel(title.take(10))
            .setLongLabel(title)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .build()
        if (ShortcutManagerCompat.requestPinShortcut(this, info, null)) {
            toast("已请求添加到桌面")
        } else {
            toast("设备不支持固定快捷方式")
        }
    }

    // ==================== 开发者工具 ====================
    private fun devTools() {
        val api = apiBaseUrl()
        val mcp = mcpEndpointUrl()
        val token = settings.apiToken
        val mcpDiag = McpDiagnostics.snapshot()
        val wv = currentWeb()
        val errors = synchronized(consoleLogs) { consoleLogs.count { it.optString("type") == "error" } }
        showBrowserSheet("开发者工具", "BrowserDiag 页面诊断控制台") { content, dialog ->
            content.addView(sectionTitle("连接"))
            content.addView(panelRow(
                R.drawable.ic_network,
                "MCP Streamable HTTP",
                "$mcp · 根地址同样兼容 · ${if (settings.lanApiEnabled) "局域网 + 本机" else "仅本机"}",
                onClick = { copyText(mcp) }
            ))
            content.addView(panelRow(
                R.drawable.ic_lock,
                "MCP Token",
                "${token.take(6)}…${token.takeLast(4)} · 推荐 Authorization: Bearer · 点击复制",
                onClick = { copyText(token) }
            ))
            content.addView(panelRow(
                R.drawable.ic_network,
                "后台 MCP 保活",
                when {
                    !settings.lanApiEnabled -> "当前仅本机；开启局域网 MCP 后可后台持续连接"
                    settings.backgroundMcpEnabled -> "运行中 · 前台服务 + CPU/Wi-Fi 保活 · 点击关闭"
                    else -> "已关闭 · 点击开启，切换到 AI 客户端后保持连接"
                },
                selected = settings.lanApiEnabled && settings.backgroundMcpEnabled,
                onClick = {
                    dialog.dismiss()
                    toggleBackgroundMcp()
                }
            ))
            content.addView(panelRow(
                R.drawable.ic_shield,
                "只填写 URL 的客户端",
                if (settings.mcpUrlOnlyCompatibility) {
                    "兼容模式已开启：无需 Token 即可连接 MCP · 点击关闭"
                } else {
                    "默认要求 Token；若客户端不能配置 Header，可点击开启可信局域网兼容"
                },
                selected = settings.mcpUrlOnlyCompatibility,
                onClick = {
                    dialog.dismiss()
                    toggleMcpUrlOnlyCompatibility()
                }
            ))
            content.addView(panelRow(
                R.drawable.ic_link,
                "兼容 HTTP Bridge",
                "$api/api/browser_* · 保留给旧集成使用 · 始终要求 Token",
                onClick = { copyText("$api/api/") }
            ))
            content.addView(panelRow(
                R.drawable.ic_link,
                "Legacy MCP SSE",
                "$api/sse · 兼容 2024-11-05/旧版移动 AI 客户端 · 点击复制",
                onClick = { copyText("$api/sse") }
            ))
            content.addView(panelRow(
                R.drawable.ic_info,
                "MCP 工具发现诊断",
                "${mcpDiag.summary()} · 最后 ${mcpDiag.lastTransport}/${mcpDiag.lastProtocol}",
                onClick = {
                    dialog.dismiss()
                    showMcpDiagnostics()
                }
            ))
            content.addView(sectionTitle("当前页面"))
            content.addView(panelRow(
                R.drawable.ic_tab,
                tabs.current?.title.orEmpty().ifEmpty { "当前标签" }.take(72),
                wv?.url.orEmpty().take(140)
            ))
            content.addView(panelRow(
                R.drawable.ic_code,
                "Console",
                "${consoleLogs.size} 条日志 · $errors 条错误",
                onClick = {
                    dialog.dismiss()
                    showConsoleLog()
                }
            ))
            content.addView(panelRow(
                R.drawable.ic_network,
                "Network",
                "查看当前页面最近的请求、状态码与资源地址",
                onClick = {
                    dialog.dismiss()
                    showNetLog()
                }
            ))
            content.addView(TextView(this).apply {
                text = "这里是真正的 dual-era MCP 服务：原生支持 2026-07-28 无状态 server/discover + 每请求 _meta，同时兼容 2025.x initialize 与 2024-11-05 HTTP+SSE。工具列表包含 BrowserDiag 统一兼容入口和 browser_* 原子工具。若客户端显示已连接但模型仍看不到工具，请查看上方“工具发现诊断”。推荐使用 Bearer Token，URL-only 仅在可信网络使用。"
                textSize = 12.5f
                setTextColor(secondaryTextColor())
                setPadding(14.dp(), 16.dp(), 14.dp(), 8.dp())
            })
        }
    }

    private fun showMcpDiagnostics() {
        val diag = McpDiagnostics.snapshot()
        val age = if (diag.lastAt <= 0L) "无" else {
            val seconds = ((System.currentTimeMillis() - diag.lastAt).coerceAtLeast(0L) / 1000L)
            if (seconds < 60) "${seconds} 秒前" else "${seconds / 60} 分钟前"
        }
        AlertDialog.Builder(this)
            .setTitle("MCP 工具发现诊断")
            .setMessage(
                "server/discover：${diag.serverDiscoverCount}\n" +
                    "initialize：${diag.initializeCount}\n" +
                    "tools/list：${diag.toolsListCount}\n" +
                    "最后返回工具：${diag.lastToolsReturned}\n" +
                    "tools/call：${diag.toolsCallCount}\n\n" +
                    "协议拒绝：${diag.rejectionCount}\n" +
                    "最后方法：${diag.lastMethod}\n" +
                    "Transport：${diag.lastTransport}\n" +
                    "协议：${diag.lastProtocol}\n" +
                    "客户端：${diag.lastClient}\n" +
                    "最后问题：${diag.lastIssue}\n" +
                    "时间：$age\n\n" +
                    "判读：2026 客户端通常会出现 server/discover，2025/更旧客户端会出现 initialize；tools/list=0 表示客户端没有请求 BrowserDiag 工具；tools/list>0 且“最后返回工具”>0，但模型仍看不到 BrowserDiag/browser_*，说明工具已经由服务端返回，问题位于 AI 客户端的工具注入或会话刷新层。"
            )
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制诊断") { _, _ ->
                copyText(
                    "BrowserDiag MCP: ${diag.summary()}, rejected=${diag.rejectionCount}, method=${diag.lastMethod}, " +
                        "transport=${diag.lastTransport}, protocol=${diag.lastProtocol}, client=${diag.lastClient}, issue=${diag.lastIssue}"
                )
            }
            .show()
    }

    private fun showConsoleLog() {
        val logs = synchronized(consoleLogs) { consoleLogs.takeLast(120).reversed() }
        val errors = logs.count { it.optString("type") == "error" }
        showBrowserSheet(
            "Console",
            "${logs.size} 条最近日志 · $errors 条错误",
            headerActionLabel = if (logs.isEmpty()) null else "清空",
            headerAction = if (logs.isEmpty()) null else { dialog ->
                synchronized(consoleLogs) { consoleLogs.clear() }
                statusBar.text = buildStatusText()
                dialog.dismiss()
                toast("Console 日志已清空")
            }
        ) { content, _ ->
            if (logs.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_code, "暂无 Console 日志", "页面脚本输出会显示在这里"))
            }
            logs.forEach { entry ->
                val type = entry.optString("type").ifEmpty { "log" }.uppercase(Locale.ROOT)
                val message = entry.optString("text")
                val host = displayHost(entry.optString("url"))
                val line = entry.optInt("line")
                content.addView(panelRow(
                    R.drawable.ic_code,
                    buildList {
                        add(type)
                        if (host.isNotBlank()) add(host)
                        if (line > 0) add("L$line")
                    }.joinToString(" · "),
                    message.take(220),
                    selected = type == "ERROR",
                    onClick = {
                        val source = entry.optString("source")
                        copyText(buildString {
                            append(message)
                            if (source.isNotBlank()) append("\n").append(source)
                            if (line > 0) append(":").append(line)
                        })
                    }
                ))
            }
        }
    }

    // ==================== 深色主题 / 引擎 / UA / 油猴 / 历史 / 关于 ====================
    private fun toggleDark() {
        isDark = !isDark
        settings.darkMode = isDark
        applyTheme()
        toast(if (isDark) "已切换到深色主题" else "已切换到浅色主题")
    }

    private fun showEnginePicker() {
        val engines = SearchEngine.entries
        val current = settings.engine
        showBrowserSheet("搜索引擎", "当前：${current.label}") { content, dialog ->
            engines.forEach { engine ->
                content.addView(panelRow(
                    R.drawable.ic_search,
                    engine.label,
                    displayHost(engine.searchUrl),
                    selected = engine == current,
                    onClick = {
                        settings.engine = engine
                        statusBar.text = buildStatusText()
                        dialog.dismiss()
                        toast("搜索引擎已切换为 ${engine.label}")
                    }
                ))
            }
        }
    }

    private fun showUaPicker() {
        val modes = UaMode.entries
        val current = settings.uaMode
        showBrowserSheet("User-Agent", "当前：${current.label}") { content, dialog ->
            modes.forEach { mode ->
                val description = when (mode) {
                    UaMode.ANDROID -> "使用系统 WebView 默认移动端标识"
                    UaMode.DESKTOP -> "模拟桌面 Chrome 页面布局"
                    UaMode.IPHONE -> "模拟 iPhone Safari 页面布局"
                }
                content.addView(panelRow(
                    R.drawable.ic_phone,
                    mode.label,
                    description,
                    selected = mode == current,
                    onClick = {
                        settings.uaMode = mode
                        val ua = mode.uaString(WebSettings.getDefaultUserAgent(this))
                        tabs.all.forEach { it.webView.settings.userAgentString = ua }
                        statusBar.text = buildStatusText()
                        dialog.dismiss()
                        toast("UA 已切换为 ${mode.label}，刷新标签页后生效")
                    }
                ))
            }
        }
    }

    private fun showScriptManager() {
        val scripts = settings.getScripts()
        showBrowserSheet(
            title = "用户脚本",
            subtitle = "${scripts.size} 个脚本 · 网页中的 .user.js 安装链接可直接识别",
            headerActionLabel = "＋ 手动",
            headerAction = { dialog ->
                dialog.dismiss()
                showAddScriptDialog()
            }
        ) { content, dialog ->
            content.addView(panelRow(
                iconRes = R.drawable.ic_link,
                title = "从脚本链接安装",
                subtitle = "粘贴标准 .user.js 地址；在脚本网站点击安装链接也会自动弹出确认",
                trailingIcon = R.drawable.ic_forward,
                onTrailing = {
                    dialog.dismiss()
                    showUserscriptUrlDialog()
                },
                onClick = {
                    dialog.dismiss()
                    showUserscriptUrlDialog()
                }
            ))
            if (scripts.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_extension, "暂无用户脚本", "添加脚本后可按 URL 规则自动注入页面"))
            }
            scripts.forEachIndexed { index, script ->
                val source = if (script.sourceUrl.isNotBlank()) {
                    displayHost(script.sourceUrl)
                } else {
                    script.urlPattern.lineSequence().firstOrNull().orEmpty().ifBlank { "手动脚本" }
                }
                val version = script.version.takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty()
                content.addView(panelRow(
                    iconRes = R.drawable.ic_extension,
                    title = script.name,
                    subtitle = "${if (script.enabled) "已启用" else "已停用"}$version · $source",
                    trailingIcon = R.drawable.ic_delete,
                    selected = script.enabled,
                    onTrailing = {
                        val updated = settings.getScripts().toMutableList()
                        if (index in updated.indices) updated.removeAt(index)
                        settings.saveScripts(updated)
                        toast("脚本已删除")
                        dialog.dismiss()
                        showScriptManager()
                    },
                    onClick = {
                        val updated = settings.getScripts().toMutableList()
                        if (index in updated.indices) {
                            updated[index] = updated[index].copy(enabled = !updated[index].enabled)
                            settings.saveScripts(updated)
                            toast("脚本已${if (updated[index].enabled) "启用" else "停用"}，新标签页后生效")
                        }
                        dialog.dismiss()
                        showScriptManager()
                    }
                ))
            }
        }
    }

    private data class UserscriptMetadata(
        val name: String,
        val namespace: String,
        val version: String,
        val description: String,
        val matches: List<String>,
        val excludes: List<String>,
        val grants: List<String>,
        val requires: List<String>
    )

    private data class FetchedUserscript(val source: String, val finalUrl: String)

    private fun isUserscriptUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        return uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".user.js")
    }

    private fun showUserscriptUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/script.user.js"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(18.dp(), 8.dp(), 18.dp(), 8.dp())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("从链接安装用户脚本")
            .setMessage("支持 HTTP(S) 标准 UserScript；下载后会先显示元数据和权限提醒。")
            .setView(input)
            .setPositiveButton("读取脚本", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                val scheme = uri?.scheme?.lowercase(Locale.ROOT)
                if (uri == null || (scheme != "http" && scheme != "https")) {
                    toast("请输入有效的 HTTP(S) 脚本链接")
                    return@setOnClickListener
                }
                dialog.dismiss()
                requestUserscriptInstall(url)
            }
        }
        dialog.show()
    }

    private fun requestUserscriptInstall(url: String) {
        if (userscriptInstallBusy) {
            toast("已有用户脚本正在读取，请稍候")
            return
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (uri == null || (scheme != "http" && scheme != "https")) {
            toast("仅支持 HTTP(S) 用户脚本链接")
            return
        }
        val userAgent = currentWeb()?.settings?.userAgentString
            ?: WebSettings.getDefaultUserAgent(this)
        val referer = currentWeb()?.url.orEmpty()
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        userscriptInstallBusy = true
        toast("正在读取用户脚本…")
        Thread {
            val result = runCatching {
                val fetched = fetchUserscriptSource(url, userAgent, cookie, referer)
                val metadata = parseUserscriptMetadata(fetched.source)
                    ?: throw IllegalArgumentException("未发现标准 ==UserScript== 元数据")
                fetched to metadata
            }
            runOnUiThread {
                userscriptInstallBusy = false
                result.onSuccess { (fetched, metadata) ->
                    showUserscriptInstallPreview(fetched, metadata)
                }.onFailure { error ->
                    toast("读取脚本失败：${error.message ?: "未知错误"}")
                }
            }
        }.start()
    }

    private fun fetchUserscriptSource(
        url: String,
        userAgent: String,
        cookie: String,
        referer: String
    ): FetchedUserscript {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/javascript, application/javascript, text/plain;q=0.9, */*;q=0.5")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            if (referer.startsWith("http://") || referer.startsWith("https://")) {
                setRequestProperty("Referer", referer)
            }
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalArgumentException("HTTP $responseCode")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 1_000_000L) {
                throw IllegalArgumentException("脚本文件超过 1 MB 安全上限")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > 1_000_000) {
                        throw IllegalArgumentException("脚本文件超过 1 MB 安全上限")
                    }
                    output.write(buffer, 0, read)
                }
            }
            val source = output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
            if (source.length > 200_000) {
                throw IllegalArgumentException("脚本代码超过 200,000 字符上限")
            }
            return FetchedUserscript(source, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUserscriptMetadata(source: String): UserscriptMetadata? {
        val start = source.indexOf("==UserScript==")
        val end = source.indexOf("==/UserScript==", start.coerceAtLeast(0))
        if (start < 0 || end <= start) return null
        val block = source.substring(start, end)
        val fields = LinkedHashMap<String, MutableList<String>>()
        Regex("""(?m)^\s*//\s*@([A-Za-z0-9:_-]+)\s*(.*?)\s*$""").findAll(block).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].trim()
            fields.getOrPut(key) { mutableListOf() }.add(value)
        }
        fun first(vararg keys: String): String {
            keys.forEach { key ->
                fields[key]?.firstOrNull { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
        val name = first("name:zh-cn", "name:zh", "name")
        if (name.isBlank()) return null
        val matches = ((fields["match"] ?: emptyList()) + (fields["include"] ?: emptyList()))
            .filter { it.isNotBlank() }.distinct().take(40)
        val excludes = ((fields["exclude-match"] ?: emptyList()) + (fields["exclude"] ?: emptyList()))
            .filter { it.isNotBlank() }.distinct().take(40)
        return UserscriptMetadata(
            name = name.take(120),
            namespace = first("namespace").take(200),
            version = first("version").take(80),
            description = first("description:zh-cn", "description:zh", "description").take(500),
            matches = matches.ifEmpty { listOf("*") },
            excludes = excludes,
            grants = (fields["grant"] ?: emptyList()).filter { it.isNotBlank() }.distinct(),
            requires = (fields["require"] ?: emptyList()).filter { it.isNotBlank() }.distinct()
        )
    }

    private fun showUserscriptInstallPreview(
        fetched: FetchedUserscript,
        metadata: UserscriptMetadata
    ) {
        val scripts = settings.getScripts().toMutableList()
        val existingIndex = scripts.indexOfFirst { installed ->
            (installed.sourceUrl.isNotBlank() && installed.sourceUrl == fetched.finalUrl) ||
                (metadata.namespace.isNotBlank() &&
                    installed.namespace == metadata.namespace &&
                    installed.name == metadata.name)
        }
        val updating = existingIndex >= 0
        val scopeText = metadata.matches.take(3).joinToString(" · ").let {
            if (metadata.matches.size > 3) "$it · +${metadata.matches.size - 3}" else it
        }
        showBrowserSheet(
            title = if (updating) "更新用户脚本" else "安装用户脚本",
            subtitle = displayHost(fetched.finalUrl),
            headerActionLabel = if (updating) "更新" else "安装",
            headerAction = { dialog ->
                val previous = scripts.getOrNull(existingIndex)
                val installed = Userscript(
                    id = previous?.id ?: "us_${System.currentTimeMillis()}",
                    name = metadata.name,
                    enabled = previous?.enabled ?: true,
                    urlPattern = metadata.matches.joinToString("\n"),
                    code = fetched.source,
                    excludePattern = metadata.excludes.joinToString("\n"),
                    sourceUrl = fetched.finalUrl,
                    namespace = metadata.namespace,
                    version = metadata.version,
                    description = metadata.description
                )
                if (existingIndex >= 0) {
                    scripts[existingIndex] = installed
                } else {
                    scripts.add(0, installed)
                }
                settings.saveScripts(scripts)
                toast(if (updating) "用户脚本已更新，新标签页后生效" else "用户脚本已安装，新标签页后生效")
                dialog.dismiss()
                showScriptManager()
            }
        ) { content, _ ->
            content.addView(panelRow(
                R.drawable.ic_extension,
                metadata.name,
                listOfNotNull(
                    metadata.version.takeIf { it.isNotBlank() }?.let { "版本 $it" },
                    metadata.namespace.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { "标准 UserScript" }
            ))
            if (metadata.description.isNotBlank()) {
                content.addView(panelRow(R.drawable.ic_info, "说明", metadata.description))
            }
            content.addView(panelRow(
                R.drawable.ic_link,
                "运行范围",
                "$scopeText${if (metadata.excludes.isNotEmpty()) " · 排除 ${metadata.excludes.size} 条" else ""}"
            ))
            content.addView(panelRow(
                R.drawable.ic_shield,
                "安装前确认来源",
                "脚本将在匹配网页中执行；仅安装你信任的来源。"
            ))
            if (fetched.finalUrl.startsWith("http://", true)) {
                content.addView(panelRow(
                    R.drawable.ic_shield,
                    "非 HTTPS 来源",
                    "该脚本通过明文 HTTP 下载，内容可能在传输途中被修改；建议优先使用 HTTPS 安装地址。"
                ))
            }
            if (metadata.requires.isNotEmpty() || metadata.grants.any { it.lowercase(Locale.ROOT) != "none" }) {
                content.addView(panelRow(
                    R.drawable.ic_info,
                    "兼容性提示",
                    "检测到 ${metadata.grants.size} 个 @grant / ${metadata.requires.size} 个 @require；部分 Tampermonkey 专用 API 或外部依赖可能需要后续兼容。"
                ))
            }
        }
    }

    private fun showAddScriptDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 8.dp(), 24.dp(), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "脚本名称"
            setSingleLine(true)
        }
        val patternInput = EditText(this).apply {
            hint = "匹配规则（* 全部，如 *youtube.com*）"
            setText("*")
            setSingleLine(true)
        }
        val codeInput = EditText(this).apply {
            hint = "脚本代码（IIFE 形式）"
            gravity = Gravity.TOP
            minLines = 8
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        container.addView(nameInput)
        container.addView(patternInput)
        container.addView(codeInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("添加油猴脚本")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                if (name.isEmpty() || code.isEmpty()) {
                    toast("名称和代码不能为空")
                    return@setOnClickListener
                }
                val list = settings.getScripts().toMutableList()
                list.add(
                    Userscript(
                        id = "us_${System.currentTimeMillis()}",
                        name = name,
                        enabled = true,
                        urlPattern = patternInput.text.toString().trim().ifEmpty { "*" },
                        code = code
                    )
                )
                settings.saveScripts(list)
                toast("脚本已添加，新标签页或下次启动后生效")
                dialog.dismiss()
                showScriptManager()
            }
        }
        dialog.show()
    }

    private fun showHistory() {
        val history = settings.getHistory()
        showBrowserSheet(
            title = "历史记录",
            subtitle = if (history.isEmpty()) "最近访问的网页会显示在这里" else "最近 ${history.size} 条访问记录",
            headerActionLabel = if (history.isEmpty()) null else "清空",
            headerAction = if (history.isEmpty()) null else { dialog ->
                confirmClearHistory(dialog)
            }
        ) { content, dialog ->
            if (history.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_history, "暂无历史记录", "你的浏览记录只保存在本机"))
            }
            history.forEach { (url, title) ->
                content.addView(panelRow(
                    R.drawable.ic_history,
                    title.ifEmpty { displayHost(url) }.take(72),
                    displayHost(url),
                    trailingIcon = R.drawable.ic_delete,
                    onTrailing = {
                        settings.removeHistory(url)
                        toast("已删除这条历史记录")
                        dialog.dismiss()
                        showHistory()
                    },
                    onClick = {
                        currentWeb()?.loadUrl(url)
                        dialog.dismiss()
                    }
                ))
            }
        }
    }

    private fun confirmClearHistory(parentSheet: BottomSheetDialog) {
        AlertDialog.Builder(this)
            .setTitle("清空浏览历史？")
            .setMessage("将删除本机保存的 ${settings.getHistory().size} 条访问记录，此操作不会删除书签或下载内容。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                settings.clearHistory()
                parentSheet.dismiss()
                toast("浏览历史已清空")
            }
            .show()
    }

    private fun showAbout() {
        val api = apiBaseUrl()
        val token = settings.apiToken
        val ua = currentWeb()?.settings?.userAgentString ?: ""
        showBrowserSheet("BrowserDiag", "安全浏览 + 页面诊断 · v3.8.0") { content, _ ->
            content.addView(panelRow(R.drawable.ic_info, "BrowserDiag 3.8.0", "Android 浏览器、网络实验室与 dual-era MCP 服务"))
            content.addView(panelRow(R.drawable.ic_network, "MCP Streamable HTTP", "${mcpEndpointUrl()} · Token 认证", onClick = {
                copyText(mcpEndpointUrl())
            }))
            content.addView(panelRow(R.drawable.ic_lock, "MCP Token", "${token.take(6)}…${token.takeLast(4)} · 点击复制", onClick = {
                copyText(token)
            }))
            content.addView(panelRow(R.drawable.ic_phone, "当前 User-Agent", ua.take(150)))
            content.addView(TextView(this).apply {
                text = "多标签浏览、搜索引擎、UA、用户脚本、书签、源码归档、媒体嗅探、网络日志、二维码、语音与远程诊断能力均在本机统一提供。"
                textSize = 12.5f
                setTextColor(secondaryTextColor())
                setPadding(14.dp(), 16.dp(), 14.dp(), 8.dp())
            })
        }
    }

    // ==================== 递归源码 ZIP ====================
    private fun downloadSourceZip() {
        if (!ensureLegacyDownloadsPermission()) return
        val wv = currentWeb() ?: return
        val currentUrl = wv.url ?: ""
        if (currentUrl.isEmpty() || currentUrl == "about:blank") {
            toast("当前没有可打包的页面")
            return
        }
        statusBar.text = "正在获取网页源码…"
        wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            val html = try {
                val v = org.json.JSONTokener(raw ?: "\"\"").nextValue()
                v as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            val consoleJson = synchronized(consoleLogs) { JSONArray(consoleLogs.toList()).toString() }
            wv.evaluateJavascript("JSON.stringify(window.__bdNet||[])") { netRaw ->
                val netJson = decodeJsArray(netRaw).toString()
                wv.evaluateJavascript(
                    "JSON.stringify(Array.from(new Set((performance.getEntriesByType('resource')||[]).filter(function(e){var t=(e.initiatorType||'').toLowerCase();return t!=='fetch'&&t!=='xmlhttprequest'&&t!=='beacon';}).map(function(e){return e.name;}).filter(Boolean))).slice(0,240))"
                ) { resourcesRaw ->
                    val observedResourcesJson = decodeJsArray(resourcesRaw).toString()
                    SourcePacker.pack(
                        context = this,
                        url = currentUrl,
                        title = tabs.current?.title.orEmpty(),
                        html = html,
                        consoleJson = consoleJson,
                        networkJson = netJson,
                        observedResourcesJson = observedResourcesJson,
                        ua = wv.settings.userAgentString,
                        onProgress = { progress ->
                            runOnUiThread { statusBar.text = progress }
                        }
                    ) { ok, msg ->
                        runOnUiThread {
                            statusBar.text = if (ok) "源码归档完成 ✅" else "打包失败 ❌ $msg"
                            toast(if (ok) "递归源码 ZIP 已保存：$msg" else "打包失败：$msg")
                        }
                    }
                }
            }
        }
    }

    // ==================== 油猴规则 / 服务器 ====================
    private fun ensureLegacyDownloadsPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            LEGACY_STORAGE_PERMISSION_REQUEST
        )
        toast("请允许存储权限后重试")
        return false
    }

    private fun scriptPatternList(value: String): List<String> =
        value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /**
     * WebView document-start API 只能按 origin 限制，先尽量收窄域名；
     * 具体路径、@include 与 @exclude 再由运行时 wrapper 精确判断。
     */
    private fun patternToOriginRules(pattern: String): Set<String> {
        val patterns = scriptPatternList(pattern)
        if (patterns.isEmpty() || patterns.any { it == "*" || it == "<all_urls>" }) return setOf("*")
        val origins = linkedSetOf<String>()
        for (raw in patterns) {
            if (raw.startsWith("/") && raw.endsWith("/")) return setOf("*")
            val marker = raw.indexOf("://")
            if (marker > 0) {
                val schemeToken = raw.substring(0, marker).lowercase(Locale.ROOT)
                val authority = raw.substring(marker + 3).substringBefore('/').substringBefore('?').substringBefore('#')
                if (authority.isBlank() || authority == "*") return setOf("*")
                val schemes = when (schemeToken) {
                    "*" -> listOf("http", "https")
                    "http", "https" -> listOf(schemeToken)
                    else -> emptyList()
                }
                if (schemes.isEmpty()) continue
                val host = authority.substringBefore(':').lowercase(Locale.ROOT)
                if (host.isBlank() || host == "*") return setOf("*")
                schemes.forEach { scheme -> origins.add("$scheme://$host") }
                continue
            }

            val host = Regex("""([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")
                .find(raw)?.value?.lowercase(Locale.ROOT)
                ?: return setOf("*")
            origins.add("https://$host")
            origins.add("http://$host")
            origins.add("https://*.$host")
            origins.add("http://*.$host")
        }
        return if (origins.isEmpty()) setOf("*") else origins
    }

    private fun wrapUserscriptForRuntime(script: Userscript): String {
        val includes = JSONArray(scriptPatternList(script.urlPattern).ifEmpty { listOf("*") }).toString()
        val excludes = JSONArray(scriptPatternList(script.excludePattern)).toString()
        val label = JSONObject.quote(script.name)
        return buildString {
            append(
                """
                (function(){
                  var __bdIncludes = $includes;
                  var __bdExcludes = $excludes;
                  function __bdMatch(pattern, url) {
                    if (!pattern || pattern === '*' || pattern === '<all_urls>') return true;
                    if (pattern.length > 2 && pattern.charAt(0) === '/' && pattern.charAt(pattern.length - 1) === '/') {
                      try { return new RegExp(pattern.slice(1, -1)).test(url); } catch (_) { return false; }
                    }
                    var special = "\\.^+?()[]{}|" + String.fromCharCode(36);
                    var re = '';
                    for (var i = 0; i < pattern.length; i++) {
                      var ch = pattern.charAt(i);
                      if (ch === '*') re += '.*';
                      else {
                        if (special.indexOf(ch) >= 0) re += '\\';
                        re += ch;
                      }
                    }
                    try { return new RegExp('^' + re + String.fromCharCode(36)).test(url); } catch (_) { return false; }
                  }
                  var __bdUrl = String(location.href);
                  if (!__bdIncludes.some(function(p){ return __bdMatch(p, __bdUrl); })) return;
                  if (__bdExcludes.some(function(p){ return __bdMatch(p, __bdUrl); })) return;
                  try {
                """.trimIndent()
            )
            append('\n')
            append(script.code)
            append('\n')
            append(
                """
                  } catch (e) {
                    console.error('[BrowserDiag UserScript]', $label, e);
                  }
                })();
                """.trimIndent()
            )
        }
    }

    // ==================== v3.x 浏览器增强 ====================

    /** 全屏模式：隐藏顶栏/状态栏/底栏，沉浸式浏览 */
    private fun toggleFullscreen() {
        setFullscreen(!isFullscreen)
        toast(if (isFullscreen) "已进入全屏（按返回键退出）" else "已退出全屏")
    }

    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        if (!enabled) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            statusBar.visibility = View.VISIBLE
            pageProgress.visibility = if ((currentWeb()?.progress ?: 100) in 1..99) View.VISIBLE else View.GONE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            applyTheme()
        } else {
            if (findBar.visibility == View.VISIBLE) hideFindBar()
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            statusBar.visibility = View.GONE
            pageProgress.visibility = View.GONE
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** 网络日志面板：把 XHR / fetch 请求转换为可读的状态、类型、大小与耗时。 */
    private fun showNetLog() {
        val wv = currentWeb() ?: return toast("无页面")
        wv.evaluateJavascript("JSON.stringify((window.__bdNet||[]).slice(-140).reverse())") { raw ->
            val arr = decodeJsArray(raw)
            var successCount = 0
            var errorCount = 0
            var totalBytes = 0L
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: continue
                val status = item.optInt("status")
                if (status in 200..399) successCount++ else if (status == 0 || status >= 400) errorCount++
                totalBytes += item.optLong("bytes").coerceAtLeast(0)
            }
            val summary = buildList {
                add("${arr.length()} 条")
                if (successCount > 0) add("成功 $successCount")
                if (errorCount > 0) add("异常 $errorCount")
                if (totalBytes > 0) add(formatBytes(totalBytes))
            }.joinToString(" · ")
            showBrowserSheet(
                title = "网络日志",
                subtitle = if (arr.length() == 0) "XHR / Fetch 请求诊断" else summary,
                headerActionLabel = if (arr.length() == 0) null else "清空",
                headerAction = if (arr.length() == 0) null else { dialog ->
                    wv.evaluateJavascript("window.__bdNet=[];true") { }
                    dialog.dismiss()
                    toast("网络日志已清空")
                }
            ) { content, _ ->
                if (arr.length() == 0) {
                    content.addView(emptyPanel(R.drawable.ic_network, "暂无网络记录", "刷新或浏览页面后再查看请求"))
                    return@showBrowserSheet
                }
                for (idx in 0 until arr.length()) {
                    val o = arr.optJSONObject(idx) ?: continue
                    val status = o.optInt("status")
                    content.addView(panelRow(
                        iconRes = if (status == 0 || status >= 400) R.drawable.ic_info else R.drawable.ic_network,
                        title = networkEntryTitle(o),
                        subtitle = networkEntrySubtitle(o),
                        selected = status == 0 || status >= 400,
                        onClick = { showNetworkEntryActions(o) }
                    ))
                }
            }
        }
    }

    private fun networkEntryTitle(entry: JSONObject): String {
        val method = entry.optString("method").ifEmpty { "GET" }.uppercase(Locale.ROOT)
        val status = entry.optInt("status")
        val type = entry.optString("type").ifEmpty { "request" }.uppercase(Locale.ROOT)
        val host = displayHost(entry.optString("url"))
        return "$method ${if (status > 0) status else "ERR"} · $type · $host"
    }

    private fun networkEntrySubtitle(entry: JSONObject): String = buildList {
        val url = entry.optString("url")
        val path = runCatching { Uri.parse(url).encodedPath.orEmpty() }.getOrDefault("")
        if (path.isNotBlank() && path != "/") add(path.take(72))
        val mime = entry.optString("mime").substringBefore(';')
        if (mime.isNotBlank()) add(mime.take(42))
        val bytes = entry.optLong("bytes")
        if (bytes > 0) add(formatBytes(bytes))
        val duration = entry.optDouble("duration")
        if (duration > 0) add("${duration.toLong()} ms")
        val matchedRules = entry.optJSONArray("rules")?.length() ?: 0
        if (matchedRules > 0) add("规则 $matchedRules")
        if (isEmpty()) add(compactUrl(url).take(120))
    }.joinToString(" · ")

    private fun showNetworkEntryActions(entry: JSONObject) {
        val url = entry.optString("url")
        val webUrl = url.startsWith("http://", true) || url.startsWith("https://", true)
        val actions = mutableListOf<String>()
        if (webUrl) actions += "新标签打开"
        if (webUrl) actions += "从此请求创建规则"
        actions += "复制 URL"
        actions += "复制请求信息"
        AlertDialog.Builder(this)
            .setTitle(networkEntryTitle(entry))
            .setItems(actions.toTypedArray()) { dialog, which ->
                when (actions[which]) {
                    "新标签打开" -> newTab(url)
                    "从此请求创建规则" -> showNetworkRuleTypePicker(
                        defaultPattern = url,
                        defaultMethod = entry.optString("method", "GET").uppercase(Locale.ROOT)
                    )
                    "复制 URL" -> copyText(url)
                    "复制请求信息" -> copyText(
                        "${networkEntryTitle(entry)}\n${networkEntrySubtitle(entry)}\n$url"
                    )
                }
                dialog.dismiss()
            }
            .show()
    }

    // ==================== 网络实验室 ====================

    /** Chrome DevTools 风格的规则中心：规则列表、启停、命中记录与编辑统一从这里进入。 */
    private fun showNetworkLab() {
        val rules = networkRules.allRules()
        val enabled = rules.count { it.enabled }
        val hitCount = networkRules.recentHits(200).length()
        showBrowserSheet(
            title = "网络实验室",
            subtitle = "${rules.size} 条规则 · $enabled 条启用 · 原生 + document-start 双层引擎",
            headerActionLabel = "＋ 规则",
            headerAction = { dialog ->
                dialog.dismiss()
                showNetworkRuleTypePicker()
            }
        ) { content, dialog ->
            content.addView(panelRow(
                R.drawable.ic_shield,
                "无证书网络调试",
                "不使用 HTTPS MITM；支持静态资源拦截、fetch/XHR Body、Mock、响应替换及页面注入。"
            ))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(1.dp(), 5.dp(), 1.dp(), 7.dp())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(quickAction(R.drawable.ic_history, "命中记录 $hitCount") {
                    dialog.dismiss()
                    showNetworkRuleHits()
                })
                addView(quickAction(R.drawable.ic_refresh, "重新应用") {
                    refreshNetworkRuleHooks()
                    toast("网络规则已重新应用")
                })
            }
            content.addView(actions)
            content.addView(sectionTitle("规则 · 高优先级先执行"))
            if (rules.isEmpty()) {
                content.addView(emptyPanel(
                    R.drawable.ic_network,
                    "还没有网络规则",
                    "创建规则后可重写请求、Mock API、替换响应，或按页面地址注入 JS/CSS。"
                ))
                return@showBrowserSheet
            }
            rules.forEach { rule ->
                val scope = if (rule.action == NetworkRuleAction.INJECT_JS || rule.action == NetworkRuleAction.INJECT_CSS) {
                    rule.urlPattern
                } else {
                    "${rule.methods.ifBlank { "*" }} · ${rule.urlPattern}"
                }
                content.addView(panelRow(
                    iconRes = networkRuleIcon(rule.action),
                    title = rule.name,
                    subtitle = "${if (rule.enabled) "已启用" else "已停用"} · ${rule.action.label} · P${rule.priority} · ${scope.take(96)}",
                    trailingIcon = R.drawable.ic_forward,
                    selected = rule.enabled,
                    onTrailing = {
                        dialog.dismiss()
                        showNetworkRuleActions(rule)
                    },
                    onClick = {
                        dialog.dismiss()
                        showNetworkRuleActions(rule)
                    }
                ))
            }
        }
    }

    private fun networkRuleIcon(action: NetworkRuleAction): Int = when (action) {
        NetworkRuleAction.BLOCK -> R.drawable.ic_stop
        NetworkRuleAction.REWRITE_URL -> R.drawable.ic_arrow
        NetworkRuleAction.SET_REQUEST_HEADERS,
        NetworkRuleAction.SET_RESPONSE_HEADERS -> R.drawable.ic_network
        NetworkRuleAction.REPLACE_REQUEST_BODY,
        NetworkRuleAction.REPLACE_RESPONSE_BODY,
        NetworkRuleAction.INJECT_JS,
        NetworkRuleAction.INJECT_CSS -> R.drawable.ic_code
        NetworkRuleAction.MOCK_RESPONSE -> R.drawable.ic_play
        NetworkRuleAction.DELAY -> R.drawable.ic_history
    }

    private fun showNetworkRuleTypePicker(defaultPattern: String? = null, defaultMethod: String? = null) {
        showBrowserSheet(
            title = "创建网络规则",
            subtitle = if (defaultPattern.isNullOrBlank()) "选择要执行的动作" else "已带入当前请求 · ${displayHost(defaultPattern)}"
        ) { content, dialog ->
            NetworkRuleAction.entries.forEach { action ->
                content.addView(panelRow(
                    iconRes = networkRuleIcon(action),
                    title = action.label,
                    subtitle = action.description,
                    trailingIcon = R.drawable.ic_forward,
                    onTrailing = {
                        dialog.dismiss()
                        showNetworkRuleEditor(null, action, defaultPattern, defaultMethod)
                    },
                    onClick = {
                        dialog.dismiss()
                        showNetworkRuleEditor(null, action, defaultPattern, defaultMethod)
                    }
                ))
            }
        }
    }

    private fun showNetworkRuleActions(rule: NetworkRule) {
        val actions = arrayOf(
            if (rule.enabled) "停用规则" else "启用规则",
            "编辑规则",
            "复制为停用规则",
            "删除规则"
        )
        AlertDialog.Builder(this)
            .setTitle(rule.name)
            .setMessage("${rule.action.label} · P${rule.priority}\n${rule.methods} · ${rule.urlPattern}")
            .setItems(actions) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        networkRules.setEnabled(rule.id, !rule.enabled)
                        refreshNetworkRuleHooks()
                        if (rule.enabled && rule.action == NetworkRuleAction.INJECT_CSS) {
                            removeInjectedNetworkCss(rule.id)
                        }
                        toast(if (rule.enabled) "规则已停用" else "规则已启用")
                        showNetworkLab()
                    }
                    1 -> showNetworkRuleEditor(rule, rule.action)
                    2 -> {
                        val copy = rule.copy(
                            id = "nr_${System.currentTimeMillis()}",
                            name = "${rule.name} · 副本".take(100),
                            enabled = false
                        )
                        networkRules.upsert(copy)
                        refreshNetworkRuleHooks()
                        toast("已复制为停用规则")
                        showNetworkLab()
                    }
                    3 -> confirmDeleteNetworkRule(rule)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteNetworkRule(rule: NetworkRule) {
        AlertDialog.Builder(this)
            .setTitle("删除网络规则？")
            .setMessage("将永久删除“${rule.name}”。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                networkRules.remove(rule.id)
                refreshNetworkRuleHooks()
                if (rule.action == NetworkRuleAction.INJECT_CSS) removeInjectedNetworkCss(rule.id)
                toast("规则已删除")
                showNetworkLab()
            }
            .show()
    }

    private fun removeInjectedNetworkCss(ruleId: String) {
        val id = JSONObject.quote(ruleId)
        tabs.all.forEach { tab ->
            tab.webView.evaluateJavascript(
                "(function(){var id=$id;document.querySelectorAll('style[data-browserdiag-rule]').forEach(function(e){if(e.getAttribute('data-browserdiag-rule')===id)e.remove()});return true})()"
            ) { }
        }
    }

    private fun showNetworkRuleEditor(
        existing: NetworkRule?,
        action: NetworkRuleAction,
        defaultPattern: String? = null,
        defaultMethod: String? = null
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 4.dp(), 20.dp(), 12.dp())
        }
        fun addField(
            label: String,
            hint: String,
            value: String,
            lines: Int = 1,
            numeric: Boolean = false
        ): EditText {
            container.addView(sectionTitle(label))
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                textSize = 13.5f
                setTextColor(textColor())
                setHintTextColor(secondaryTextColor())
                if (lines > 1) {
                    minLines = lines
                    maxLines = maxOf(lines, 12)
                    gravity = Gravity.TOP or Gravity.START
                    typeface = android.graphics.Typeface.MONOSPACE
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    setSingleLine(true)
                    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
                container.addView(this)
            }
        }

        val nameInput = addField("规则名称", action.label, existing?.name ?: action.label)
        val patternInput = addField(
            "URL 匹配",
            "*://api.example.com/* 或 regex:https://…",
            existing?.urlPattern ?: defaultPattern ?: "*"
        )
        val methodInput = if (action != NetworkRuleAction.INJECT_JS && action != NetworkRuleAction.INJECT_CSS) {
            addField("请求方法", "* 或 GET,POST", existing?.methods ?: defaultMethod ?: "*")
        } else null
        val priorityInput = addField("优先级", "0-1000，数值越大越先执行", (existing?.priority ?: 100).toString(), numeric = true)

        var valueInput: EditText? = null
        var replacementInput: EditText? = null
        var headersInput: EditText? = null
        var statusInput: EditText? = null
        var mimeInput: EditText? = null
        var delayInput: EditText? = null
        when (action) {
            NetworkRuleAction.BLOCK -> Unit
            NetworkRuleAction.REWRITE_URL -> {
                valueInput = addField(
                    "目标 URL",
                    "https://host{path}?{query}；regex 模式可用 \$1",
                    existing?.value.orEmpty(),
                    2
                )
            }
            NetworkRuleAction.SET_REQUEST_HEADERS,
            NetworkRuleAction.SET_RESPONSE_HEADERS -> {
                headersInput = addField(
                    "Header（每行一个）",
                    "X-Debug: true\nAuthorization: Bearer …",
                    existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty(),
                    4
                )
            }
            NetworkRuleAction.REPLACE_REQUEST_BODY,
            NetworkRuleAction.REPLACE_RESPONSE_BODY -> {
                valueInput = addField(
                    "查找内容",
                    "普通文本；正则使用 regex:pattern",
                    existing?.value.orEmpty(),
                    3
                )
                replacementInput = addField("替换为", "支持正则捕获组 \$1", existing?.replacement.orEmpty(), 3)
            }
            NetworkRuleAction.MOCK_RESPONSE -> {
                statusInput = addField("HTTP 状态码", "200 / 404 / 500", (existing?.statusCode ?: 200).toString(), numeric = true)
                mimeInput = addField("Content-Type", "application/json; charset=utf-8", existing?.mimeType ?: "application/json; charset=utf-8")
                headersInput = addField(
                    "响应 Header（可选）",
                    "Cache-Control: no-store",
                    existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty(),
                    3
                )
                valueInput = addField("响应 Body", "{\"ok\":true}", existing?.value.orEmpty(), 7)
            }
            NetworkRuleAction.INJECT_JS -> {
                valueInput = addField(
                    "JavaScript",
                    "console.log('BrowserDiag injected');",
                    existing?.value.orEmpty(),
                    9
                )
            }
            NetworkRuleAction.INJECT_CSS -> {
                valueInput = addField("CSS", "body { outline: 2px solid #0B57D0; }", existing?.value.orEmpty(), 8)
            }
            NetworkRuleAction.DELAY -> {
                delayInput = addField("延迟毫秒", "1-10000", (existing?.delayMs ?: 1000).toString(), numeric = true)
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "${action.label}规则" else "编辑 · ${action.label}")
            .setMessage(action.description + if (action == NetworkRuleAction.INJECT_JS) "。仅注入你信任的代码。" else "")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val headers = parseNetworkHeaders(headersInput?.text?.toString().orEmpty())
                val candidate = NetworkRule(
                    id = existing?.id ?: "nr_${System.currentTimeMillis()}",
                    name = nameInput.text.toString().trim(),
                    enabled = existing?.enabled ?: true,
                    priority = priorityInput.text.toString().toIntOrNull() ?: -1,
                    urlPattern = patternInput.text.toString().trim().ifEmpty { "*" },
                    methods = methodInput?.text?.toString()?.trim().orEmpty().ifEmpty { "*" },
                    action = action,
                    value = valueInput?.text?.toString().orEmpty(),
                    replacement = replacementInput?.text?.toString().orEmpty(),
                    headers = headers,
                    statusCode = statusInput?.text?.toString()?.toIntOrNull() ?: (existing?.statusCode ?: 200),
                    mimeType = mimeInput?.text?.toString()?.trim().orEmpty().ifEmpty { existing?.mimeType ?: "text/plain; charset=utf-8" },
                    delayMs = delayInput?.text?.toString()?.toIntOrNull() ?: 0
                )
                val error = networkRuleValidationError(candidate)
                if (error != null) {
                    toast(error)
                    return@setOnClickListener
                }
                val saved = runCatching { networkRules.upsert(candidate) }.getOrElse { failure ->
                    toast("保存失败：${failure.message ?: "无效规则"}")
                    return@setOnClickListener
                }
                refreshNetworkRuleHooks()
                if (saved.action == NetworkRuleAction.INJECT_JS || saved.action == NetworkRuleAction.INJECT_CSS) {
                    currentWeb()?.evaluateJavascript(wrapNetworkInjectionRule(saved)) { }
                }
                dialog.dismiss()
                toast(if (existing == null) "网络规则已创建" else "网络规则已更新")
                showNetworkLab()
            }
        }
        dialog.show()
    }

    private fun showNetworkRuleHits() {
        val nativeHits = networkRules.recentHits(120)
        val wv = currentWeb()
        if (wv == null) {
            renderNetworkRuleHits(nativeHits, JSONArray())
            return
        }
        wv.evaluateJavascript("JSON.stringify((window.__bdRuleHits||[]).slice(-120).reverse())") { raw ->
            renderNetworkRuleHits(nativeHits, decodeJsArray(raw))
        }
    }

    private fun renderNetworkRuleHits(nativeHits: JSONArray, jsHits: JSONArray) {
        val merged = mutableListOf<JSONObject>()
        for (index in 0 until nativeHits.length()) nativeHits.optJSONObject(index)?.let { merged += it }
        for (index in 0 until jsHits.length()) jsHits.optJSONObject(index)?.let { merged += it }
        val hits = merged.sortedByDescending { it.optLong("ts") }.take(180)
        showBrowserSheet(
            title = "规则命中记录",
            subtitle = "${hits.size} 条 · 原生资源 + 当前页 fetch/XHR/注入",
            headerActionLabel = if (hits.isEmpty()) null else "清空",
            headerAction = if (hits.isEmpty()) null else { dialog ->
                networkRules.clearHits()
                tabs.all.forEach { tab -> tab.webView.evaluateJavascript("window.__bdRuleHits=[];true") { } }
                dialog.dismiss()
                toast("规则命中记录已清空")
            }
        ) { content, _ ->
            if (hits.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_history, "暂无规则命中", "访问匹配页面或触发请求后，这里会显示规则、阶段和结果。"))
                return@showBrowserSheet
            }
            hits.forEach { hit ->
                val action = NetworkRuleAction.fromKey(hit.optString("action"))
                val url = hit.optString("url")
                val phase = when (hit.optString("phase")) {
                    "request" -> "请求"
                    "response" -> "响应"
                    "inject" -> "注入"
                    else -> hit.optString("phase", "JS")
                }
                val detail = hit.optString("detail").ifBlank { displayHost(url) }
                content.addView(panelRow(
                    iconRes = action?.let(::networkRuleIcon) ?: R.drawable.ic_network,
                    title = hit.optString("ruleName").ifBlank { action?.label ?: "网络规则" },
                    subtitle = "${relativeRuleHitTime(hit.optLong("ts"))} · $phase · ${displayHost(url)}\n${detail.take(120)}"
                ))
            }
        }
    }

    private fun relativeRuleHitTime(ts: Long): String {
        val seconds = ((System.currentTimeMillis() - ts).coerceAtLeast(0) / 1000)
        return when {
            ts <= 0 -> "刚刚"
            seconds < 5 -> "刚刚"
            seconds < 60 -> "${seconds} 秒前"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            else -> "${seconds / 3600} 小时前"
        }
    }

    /** WebView 会把 JavaScript 字符串结果再编码一层；兼容 JSON.stringify(...) 的双层返回值。 */
    private fun decodeJsArray(raw: String?): JSONArray {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return JSONArray()
        return try {
            when (val first = org.json.JSONTokener(raw).nextValue()) {
                is JSONArray -> first
                is String -> runCatching { JSONArray(first) }.getOrDefault(JSONArray())
                else -> JSONArray()
            }
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private data class DownloadEntry(
        val id: Long,
        val title: String,
        val sourceUrl: String,
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val reason: Int
    )

    /** 下载管理：显示实时状态/进度；完成项可直接打开，其余跳转系统下载管理继续处理。 */
    private fun showDownloads() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query()
        q.setFilterByStatus(
            DownloadManager.STATUS_SUCCESSFUL or DownloadManager.STATUS_PAUSED or
                DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or
                DownloadManager.STATUS_FAILED
        )
        val cursor: Cursor? = try { dm.query(q) } catch (e: Exception) { null }
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            showBrowserSheet(
                "下载内容",
                "系统 DownloadManager",
                headerActionLabel = "系统下载",
                headerAction = { openSystemDownloads() }
            ) { content, _ ->
                content.addView(emptyPanel(R.drawable.ic_download, "暂无下载记录", "从网页下载的文件会显示在这里"))
            }
            return
        }
        val entries = mutableListOf<DownloadEntry>()
        do {
            entries += DownloadEntry(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty(),
                sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).orEmpty(),
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            )
        } while (cursor.moveToNext())
        cursor.close()
        val complete = entries.count { it.status == DownloadManager.STATUS_SUCCESSFUL }
        val active = entries.count { it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING }
        val failed = entries.count { it.status == DownloadManager.STATUS_FAILED }
        val summary = buildList {
            add("${entries.size} 个任务")
            if (complete > 0) add("完成 $complete")
            if (active > 0) add("进行中 $active")
            if (failed > 0) add("失败 $failed")
        }.joinToString(" · ")
        showBrowserSheet(
            "下载内容",
            summary,
            headerActionLabel = "系统下载",
            headerAction = { openSystemDownloads() }
        ) { content, dialog ->
            entries.forEach { entry ->
                content.addView(panelRow(
                    iconRes = R.drawable.ic_download,
                    title = entry.title.ifBlank { displayHost(entry.sourceUrl) }.take(80),
                    subtitle = downloadEntrySubtitle(entry),
                    selected = entry.status == DownloadManager.STATUS_FAILED,
                    onClick = {
                        if (entry.status == DownloadManager.STATUS_SUCCESSFUL) {
                            openDownloadedFile(dm, entry.id, dialog)
                        } else {
                            openSystemDownloads()
                        }
                    }
                ))
            }
        }
    }

    private fun downloadEntrySubtitle(entry: DownloadEntry): String = buildList {
        val state = when (entry.status) {
            DownloadManager.STATUS_SUCCESSFUL -> "已完成"
            DownloadManager.STATUS_FAILED -> "下载失败${if (entry.reason > 0) " (${entry.reason})" else ""}"
            DownloadManager.STATUS_PAUSED -> "已暂停"
            DownloadManager.STATUS_RUNNING -> if (entry.totalBytes > 0) {
                val percent = (entry.downloadedBytes * 100 / entry.totalBytes).coerceIn(0, 100)
                "下载中 $percent%"
            } else "下载中"
            else -> "等待下载"
        }
        add(state)
        if (entry.downloadedBytes > 0) {
            add(
                if (entry.totalBytes > 0) "${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)}"
                else formatBytes(entry.downloadedBytes)
            )
        } else if (entry.totalBytes > 0) {
            add(formatBytes(entry.totalBytes))
        }
        displayHost(entry.sourceUrl).takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")

    private fun openDownloadedFile(dm: DownloadManager, id: Long, parent: BottomSheetDialog) {
        val uri = dm.getUriForDownloadedFile(id) ?: return toast("下载文件已不存在")
        try {
            val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            parent.dismiss()
        } catch (_: Exception) {
            toast("没有可打开该文件的应用")
        }
    }

    private fun openSystemDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            toast("系统下载管理器不可用")
        }
    }

    /** 广告拦截开关：持久化 + 刷新当前页生效 */
    private fun toggleAdBlock() {
        settings.adBlock = !settings.adBlock
        val on = settings.adBlock
        toast(if (on) "广告拦截已开启" else "广告拦截已关闭")
        currentWeb()?.reload()
    }

    /** 允许调试网页（WebView 远程调试，chrome://inspect 可见） */
    private fun toggleDebugWeb() {
        settings.debugWeb = !settings.debugWeb
        WebView.setWebContentsDebuggingEnabled(settings.debugWeb)
        toast(if (settings.debugWeb) "已开启 WebView 远程调试（chrome://inspect 可连接）" else "已关闭远程调试")
    }

    /** MCP 默认仅本机访问；显式开启后才监听局域网地址，默认仍要求 Token。 */
    private fun toggleLanApi() {
        settings.lanApiEnabled = !settings.lanApiEnabled
        if (!settings.lanApiEnabled) settings.mcpUrlOnlyCompatibility = false
        restartServer()
        if (settings.lanApiEnabled && settings.backgroundMcpEnabled) ensureMcpNotificationPermission()
        toast(
            if (settings.lanApiEnabled) {
                if (settings.backgroundMcpEnabled) "局域网 MCP 已开启 · 后台保活运行中"
                else "局域网 MCP 已开启（后台保活已关闭）"
            }
            else "局域网 MCP 接口已关闭，仅本机可访问"
        )
    }

    private fun toggleBackgroundMcp() {
        if (!settings.lanApiEnabled) {
            toast("请先开启“局域网 MCP 接口”")
            return
        }
        settings.backgroundMcpEnabled = !settings.backgroundMcpEnabled
        if (settings.backgroundMcpEnabled) ensureMcpNotificationPermission()
        syncMcpKeepAliveService()
        statusBar.text = buildStatusText()
        toast(
            if (settings.backgroundMcpEnabled) "后台 MCP 保活已开启 · 可切换到 AI 客户端"
            else "后台 MCP 保活已关闭"
        )
    }

    private fun ensureMcpNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                MCP_NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBackgroundPowerSettings() {
        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }.onFailure { toast("无法打开系统后台设置") }
        }
    }

    private fun syncMcpKeepAliveService() {
        if (settings.lanApiEnabled && settings.backgroundMcpEnabled && McpServerHost.isRunning) {
            runCatching { McpKeepAliveService.start(this, mcpEndpointUrl()) }
                .onFailure { statusBar.text = "MCP 已启动，但后台服务启动失败：${it.message}" }
        } else {
            McpKeepAliveService.stop(this)
        }
    }

    private fun toggleMcpUrlOnlyCompatibility() {
        if (!settings.lanApiEnabled) {
            toast("请先开启“局域网 MCP 接口”")
            return
        }
        if (settings.mcpUrlOnlyCompatibility) {
            settings.mcpUrlOnlyCompatibility = false
            toast("URL-only 兼容已关闭；MCP 恢复 Token 认证")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启 URL-only MCP 兼容？")
            .setMessage(
                "开启后，只需要服务器 URL 的 AI 客户端即可连接，但同一局域网中的其它设备也可能调用 BrowserDiag 的页面控制、JavaScript 执行和网络改写能力。\n\n仅在你信任的家庭/开发网络中开启；公共 Wi-Fi 请保持关闭。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("仅在可信网络开启") { _, _ ->
                settings.mcpUrlOnlyCompatibility = true
                toast("URL-only MCP 兼容已开启")
            }
            .show()
    }

    /** 字体大小调节（50%-200%，textZoom 持久化） */
    private fun showFontScale() {
        val options = arrayOf("50%", "75%", "100%", "125%", "150%", "175%", "200%")
        val values = intArrayOf(50, 75, 100, 125, 150, 175, 200)
        val current = settings.fontScale
        showBrowserSheet("网页字体", "当前缩放 ${current}%") { content, dialog ->
            values.forEachIndexed { index, value ->
                content.addView(panelRow(
                    R.drawable.ic_font,
                    options[index],
                    when {
                        value < 100 -> "缩小网页文字"
                        value == 100 -> "网站默认比例"
                        else -> "放大网页文字"
                    },
                    selected = value == current,
                    onClick = {
                        settings.fontScale = value
                        tabs.all.forEach { it.webView.settings.textZoom = value }
                        dialog.dismiss()
                        toast("网页字体已调整为 $value%")
                    }
                ))
            }
        }
    }

    private fun orientationLabel(): String = when (settings.screenOrientation) {
        "portrait" -> "竖屏"
        "landscape" -> "横屏"
        else -> "自动"
    }

    /** 屏幕方向：自动 / 竖屏 / 横屏 */
    private fun showOrientation() {
        val modes = listOf(
            Triple("auto", "自动旋转", "跟随设备方向"),
            Triple("portrait", "竖屏锁定", "始终使用纵向浏览布局"),
            Triple("landscape", "横屏锁定", "始终使用横向浏览布局")
        )
        val current = settings.screenOrientation
        showBrowserSheet("屏幕方向", "当前：${orientationLabel()}") { content, dialog ->
            modes.forEach { (value, label, description) ->
                content.addView(panelRow(
                    R.drawable.ic_rotate,
                    label,
                    description,
                    selected = value == current,
                    onClick = {
                        settings.screenOrientation = value
                        requestedOrientation = when (value) {
                            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        dialog.dismiss()
                        toast("屏幕方向：$label")
                    }
                ))
            }
        }
    }

    /** 常用工具：只控制工具中心快捷入口，分类中的完整功能永远保留。 */
    private fun showMenuConfig() {
        val categories = toolCategories()
        val allItems = categories.flatMap { it.items }
        val fixedPageActions = setOf("find", "save", "share")
        val configurable = allItems.filter {
            it.id != "menuconfig" && it.id != "about" && it.id !in fixedPageActions
        }
        val defaults = listOf("history", "downloads", "networklab", "netlog", "devtools", "userscript", "dark", "ua")
        val stored = settings.getMenuConfig()
        val selected = linkedSetOf<String>().apply {
            if (stored.isEmpty()) {
                addAll(defaults)
            } else {
                addAll(configurable.filter { stored[it.id] == true }.map { it.id }.take(8))
            }
        }
        showBrowserSheet(
            title = "常用工具",
            subtitle = "最多 8 个快捷入口；所有功能始终保留在分类中",
            headerActionLabel = "保存",
            headerAction = { dialog ->
                settings.setMenuConfig(allItems.associate { it.id to (it.id in selected) })
                toast("常用工具已更新")
                dialog.dismiss()
            }
        ) { content, _ ->
            val counter = TextView(this).apply {
                text = "已选择 ${selected.size}/8"
                textSize = 13f
                setTextColor(accentColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(14.dp(), 6.dp(), 14.dp(), 4.dp())
            }
            content.addView(counter)
            categories.forEach { category ->
                val categoryItems = category.items.filter { it in configurable }
                if (categoryItems.isEmpty()) return@forEach
                content.addView(sectionTitle(category.title))
                categoryItems.forEach { item ->
                    content.addView(MaterialSwitch(this).apply {
                        text = item.label
                        textSize = 14.5f
                        setTextColor(textColor())
                        isChecked = item.id in selected
                        setPadding(14.dp(), 7.dp(), 14.dp(), 7.dp())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setOnCheckedChangeListener { button, checked ->
                            if (checked) {
                                if (selected.size >= 8 && item.id !in selected) {
                                    button.isChecked = false
                                    toast("常用工具最多选择 8 个")
                                } else {
                                    selected.add(item.id)
                                }
                            } else {
                                selected.remove(item.id)
                            }
                            counter.text = "已选择 ${selected.size}/8"
                        }
                    })
                }
            }
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("browserdiag", text))
        toast("已复制")
    }

    private fun restartServer() {
        val port = McpServerHost.restart(applicationContext)
        if (port == null) {
            statusBar.text = "MCP 接口启动失败（端口 8788-8791 均被占用）"
        } else {
            serverPort = port
            statusBar.text = buildStatusText()
        }
        syncMcpKeepAliveService()
    }

    private fun startServer() {
        val port = McpServerHost.ensureStarted(applicationContext)
        if (port == null) {
            statusBar.text = "MCP 接口启动失败（端口 8788-8791 均被占用）"
        } else {
            serverPort = port
            statusBar.text = buildStatusText()
        }
    }

    private fun apiBaseUrl(): String {
        val host = if (settings.lanApiEnabled) localIp() else "127.0.0.1"
        return "http://$host:$serverPort"
    }

    private fun mcpEndpointUrl(): String = "${apiBaseUrl()}/mcp"

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("127.") == false }
                ?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        McpRuntime.detach(this)
        if (!settings.lanApiEnabled || !settings.backgroundMcpEnabled) {
            McpServerHost.stop()
            McpKeepAliveService.stop(this)
        }
        tts?.stop()
        tts?.shutdown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { ServiceWorkerController.getInstance().setServiceWorkerClient(null) }
        }
        tabs.all.forEach { releaseNetworkRuleHooks(it.webView) }
        tabs.destroyAll()
        super.onDestroy()
    }

    private fun handleBrowserBack() {
        if (isFullscreen) {
            lastExitBackPressedAt = 0L
            setFullscreen(false)
            return
        }
        if (findBar.visibility == View.VISIBLE) {
            lastExitBackPressedAt = 0L
            hideFindBar()
            return
        }
        if (urlInput.hasFocus()) {
            lastExitBackPressedAt = 0L
            clearFocusAndKeyboard(urlInput)
            setOmniboxUrl(currentWeb()?.url.orEmpty())
            updateChromeControls()
            return
        }
        val wv = currentWeb()
        if (wv != null && wv.canGoBack()) {
            lastExitBackPressedAt = 0L
            wv.goBack()
            return
        }
        if (tabs.size > 1) {
            lastExitBackPressedAt = 0L
            val currentId = tabs.current?.id
            if (currentId != null) {
                closeTabAndKeepBrowser(currentId)
                toast("已关闭当前标签页")
            }
            return
        }
        if (wv != null && !isConfiguredHomePage(wv.url.orEmpty())) {
            lastExitBackPressedAt = 0L
            goHome()
            toast("已返回主页")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastExitBackPressedAt <= BACK_TO_EXIT_INTERVAL_MS) {
            finish()
        } else {
            lastExitBackPressedAt = now
            toast("再按一次返回退出 BrowserDiag")
        }
    }

    private fun isConfiguredHomePage(url: String): Boolean {
        val current = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val home = runCatching { Uri.parse(settings.engine.homeUrl) }.getOrNull() ?: return false
        if (!current.scheme.equals(home.scheme, true) || !current.host.equals(home.host, true)) return false
        return current.path.orEmpty().trimEnd('/') == home.path.orEmpty().trimEnd('/')
    }

    companion object {
        private const val LEGACY_STORAGE_PERMISSION_REQUEST = 4101
        private const val MCP_NOTIFICATION_PERMISSION_REQUEST = 4102
        private const val BACK_TO_EXIT_INTERVAL_MS = 2_000L

        /** 广告拦截域名黑名单（子域名自动匹配） */
        private val AD_BLOCK_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adsystem.com", "criteo.com",
            "taboola.com", "outbrain.com", "amazon-adsystem.com", "pubmatic.com",
            "rubiconproject.com", "moatads.com", "scorecardresearch.com",
            "advertising.com", "adsrvr.org", "quantserve.com", "lijit.com",
            "openx.net", "casalemedia.com", "smartadserver.com", "adroll.com",
            "revcontent.com", "popads.net", "propellerads.com", "adsterra.com"
        )

        /** DOM + Resource Timing 双来源资源盘点，避免“页面资源”退化成无法辨认的 URL 列表。 */
        private val PAGE_RESOURCES_JS = """
            (function(){
              var map = Object.create(null);
              var priority = {media:6, script:5, style:4, image:3, font:2, other:1};
              function absoluteUrl(value) {
                if (!value) return '';
                var raw = String(value);
                if (raw.indexOf('data:') === 0) return '';
                try { return new URL(raw, document.baseURI).href; } catch (_) { return raw; }
              }
              function extensionOf(url) {
                var clean = String(url).split('#')[0].split('?')[0].toLowerCase();
                var index = clean.lastIndexOf('.');
                return index >= 0 ? clean.slice(index + 1) : '';
              }
              function guessKind(url, mime, initiator) {
                var ext = extensionOf(url);
                var type = String(mime || '').toLowerCase();
                var init = String(initiator || '').toLowerCase();
                if (type.indexOf('image/') === 0 || ['png','jpg','jpeg','gif','webp','avif','svg','ico','bmp'].indexOf(ext) >= 0 || init === 'img') return 'image';
                if (type.indexOf('javascript') >= 0 || ['js','mjs','cjs'].indexOf(ext) >= 0 || init === 'script') return 'script';
                if (type.indexOf('font/') === 0 || ['woff','woff2','ttf','otf','eot'].indexOf(ext) >= 0) return 'font';
                if (type.indexOf('text/css') >= 0 || ext === 'css' || init === 'css') return 'style';
                if (type.indexOf('video/') === 0 || type.indexOf('audio/') === 0 || ['mp4','webm','m3u8','mpd','mp3','m4a','aac','ogg','wav','flac'].indexOf(ext) >= 0 || init === 'video' || init === 'audio') return 'media';
                return 'other';
              }
              function add(url, kind, source, mime, bytes) {
                var resolved = absoluteUrl(url);
                if (!resolved) return;
                var detected = kind || guessKind(resolved, mime, source);
                var item = map[resolved];
                if (!item) {
                  map[resolved] = {
                    url: resolved,
                    kind: detected,
                    source: source || '',
                    mime: mime || '',
                    bytes: Number(bytes) || 0
                  };
                  return;
                }
                if ((priority[detected] || 0) > (priority[item.kind] || 0)) item.kind = detected;
                if (source && item.source.split(' · ').indexOf(source) < 0) item.source += (item.source ? ' · ' : '') + source;
                if (!item.mime && mime) item.mime = mime;
                item.bytes = Math.max(item.bytes || 0, Number(bytes) || 0);
              }

              document.querySelectorAll('img[src],script[src],link[href],source[src],video[src],audio[src]').forEach(function(el){
                var tag = el.tagName.toLowerCase();
                var url = el.currentSrc || el.src || el.href || '';
                var kind = '';
                var source = 'DOM ' + tag;
                if (tag === 'img') kind = 'image';
                else if (tag === 'script') kind = 'script';
                else if (tag === 'video' || tag === 'audio' || tag === 'source') kind = 'media';
                else if (tag === 'link') {
                  var rel = String(el.rel || '').toLowerCase();
                  var asType = String(el.as || '').toLowerCase();
                  if (rel.indexOf('stylesheet') >= 0 || asType === 'style') kind = 'style';
                  else if (rel.indexOf('icon') >= 0 || asType === 'image') kind = 'image';
                  else if (asType === 'font') kind = 'font';
                  else if (asType === 'script') kind = 'script';
                }
                add(url, kind, source, el.type || '', 0);
              });

              try {
                performance.getEntriesByType('resource').forEach(function(entry){
                  add(
                    entry.name,
                    guessKind(entry.name, '', entry.initiatorType),
                    'Performance ' + (entry.initiatorType || 'resource'),
                    '',
                    entry.encodedBodySize || entry.transferSize || 0
                  );
                });
              } catch (_) {}

              var out = Object.keys(map).map(function(key){ return map[key]; });
              out.sort(function(a,b){
                var kindDiff = (priority[b.kind] || 0) - (priority[a.kind] || 0);
                return kindDiff || ((b.bytes || 0) - (a.bytes || 0));
              });
              return JSON.stringify(out.slice(0, 140));
            })()
        """.trimIndent()

        private val MEDIA_SNIFF_JS = """
            (function(){
              var map = Object.create(null);
              var priority = {playlist:5, video:4, audio:4, media:3, segment:1};
              function absoluteUrl(value) {
                if (!value) return '';
                var raw = String(value);
                if (raw.indexOf('data:') === 0) return '';
                try { return new URL(raw, document.baseURI).href; } catch (_) { return raw; }
              }
              function extensionOf(url) {
                var clean = String(url).split('#')[0].split('?')[0].toLowerCase();
                var index = clean.lastIndexOf('.');
                return index >= 0 ? clean.slice(index + 1) : '';
              }
              function guessKind(url, mime, initiator) {
                var ext = extensionOf(url);
                var type = String(mime || '').toLowerCase();
                var init = String(initiator || '').toLowerCase();
                var decoded = String(url || '').toLowerCase();
                try { decoded = decodeURIComponent(decoded); } catch (_) {}
                if (ext === 'm3u8' || ext === 'mpd' || type.indexOf('mpegurl') >= 0 || type.indexOf('dash+xml') >= 0) return 'playlist';
                if (ext === 'ts' || ext === 'm2ts' || ext === 'm4s' || ext === 'cmfv' || ext === 'cmfa') return 'segment';
                if (['mp4','webm','mkv','mov','m4v','flv','3gp','ogv','mpeg','mpg'].indexOf(ext) >= 0 || type.indexOf('video/') === 0) return 'video';
                if (['mp3','m4a','aac','ogg','oga','opus','wav','flac'].indexOf(ext) >= 0 || type.indexOf('audio/') === 0) return 'audio';
                if (decoded.indexOf('mime=video/') >= 0 || decoded.indexOf('type=video/') >= 0) return 'video';
                if (decoded.indexOf('mime=audio/') >= 0 || decoded.indexOf('type=audio/') >= 0) return 'audio';
                if (decoded.indexOf('m3u8') >= 0 || decoded.indexOf('application/dash+xml') >= 0) return 'playlist';
                if (init === 'video') return 'video';
                if (init === 'audio') return 'audio';
                if (init === 'media') return 'media';
                return '';
              }
              function add(url, source, kind, mime, bytes, duration, width, height, status) {
                var resolved = absoluteUrl(url);
                if (!resolved) return;
                var detected = kind || guessKind(resolved, mime, '');
                if (!detected) return;
                var item = map[resolved];
                if (!item) {
                  item = map[resolved] = {
                    url: resolved,
                    kind: detected,
                    source: source || '',
                    mime: mime || '',
                    bytes: Number(bytes) || 0,
                    duration: Number(duration) || 0,
                    width: Number(width) || 0,
                    height: Number(height) || 0,
                    status: Number(status) || 0
                  };
                  return;
                }
                if ((priority[detected] || 0) > (priority[item.kind] || 0)) item.kind = detected;
                if (source && item.source.split(' · ').indexOf(source) < 0) item.source += (item.source ? ' · ' : '') + source;
                if (!item.mime && mime) item.mime = mime;
                item.bytes = Math.max(item.bytes || 0, Number(bytes) || 0);
                item.duration = Math.max(item.duration || 0, Number(duration) || 0);
                item.width = Math.max(item.width || 0, Number(width) || 0);
                item.height = Math.max(item.height || 0, Number(height) || 0);
                if (!item.status && status) item.status = Number(status) || 0;
              }

              document.querySelectorAll('video,audio').forEach(function(media){
                var tag = media.tagName.toLowerCase();
                var kind = tag === 'audio' ? 'audio' : 'video';
                add(
                  media.currentSrc || media.src,
                  '页面 ' + tag,
                  kind,
                  media.getAttribute('type') || '',
                  0,
                  media.duration,
                  media.videoWidth,
                  media.videoHeight,
                  0
                );
                media.querySelectorAll('source').forEach(function(source){
                  add(source.src, 'source 标签', kind, source.type || '', 0, media.duration, media.videoWidth, media.videoHeight, 0);
                });
              });

              try {
                performance.getEntriesByType('resource').forEach(function(entry){
                  var kind = guessKind(entry.name, '', entry.initiatorType);
                  if (kind) {
                    add(
                      entry.name,
                      'Performance ' + (entry.initiatorType || 'resource'),
                      kind,
                      '',
                      entry.encodedBodySize || entry.transferSize || 0,
                      0,
                      0,
                      0,
                      0
                    );
                  }
                });
              } catch (_) {}

              (window.__bdNet || []).forEach(function(entry){
                var kind = guessKind(entry.url, entry.mime, entry.type);
                if (kind) {
                  add(
                    entry.url,
                    String(entry.type || 'network').toUpperCase(),
                    kind,
                    entry.mime || '',
                    entry.bytes || 0,
                    0,
                    0,
                    0,
                    entry.status || 0
                  );
                }
              });

              var out = Object.keys(map).map(function(key){ return map[key]; });
              out.sort(function(a,b){ return (priority[b.kind] || 0) - (priority[a.kind] || 0); });
              return JSON.stringify(out.slice(0, 160));
            })()
        """.trimIndent()

        private val NETWORK_HOOK_JS = """
            (function(){
              var initialRules=[];
              try{initialRules=JSON.parse(__BD_NETWORK_RULES_JSON__);}catch(_){}
              if(window.__bdHooked){
                if(window.__bdSetNetworkRules)window.__bdSetNetworkRules(initialRules);
                return;
              }
              window.__bdHooked=true;
              var rules=Array.isArray(initialRules)?initialRules:[];
              window.__bdNetworkRules=rules;
              window.__bdNet=[];
              window.__bdRuleHits=[];

              function recordRuleHit(hit){
                if(!hit)return;
                var item={
                  ts:Date.now(),
                  ruleId:String(hit.ruleId||'').slice(0,80),
                  ruleName:String(hit.ruleName||'').slice(0,120),
                  action:String(hit.action||'').slice(0,40),
                  url:String(hit.url||'').slice(0,4096),
                  phase:String(hit.phase||'js').slice(0,40),
                  detail:String(hit.detail||'').slice(0,300)
                };
                window.__bdRuleHits.push(item);
                if(window.__bdRuleHits.length>200)window.__bdRuleHits.shift();
              }
              window.__bdRecordRuleHit=recordRuleHit;
              window.__bdSetNetworkRules=function(next){
                rules=Array.isArray(next)?next:[];
                window.__bdNetworkRules=rules;
              };

              function record(u,m,s,t,mime,bytes,duration,matchedRules){
                window.__bdNet.push({
                  url:String(u).slice(0,4096),
                  method:m,
                  status:s,
                  type:t,
                  mime:String(mime || '').slice(0,160),
                  bytes:Number(bytes) || 0,
                  duration:Math.max(0,Math.round(Number(duration) || 0)),
                  rules:Array.isArray(matchedRules)?matchedRules.slice(0,12):[],
                  ts:Date.now()
                });
                if(window.__bdNet.length>500)window.__bdNet.shift();
              }
              function urlOf(value){
                if(typeof value==='string')return value;
                if(value&&value.url)return value.url;
                if(value&&value.href)return value.href;
                return String(value||'');
              }
              function absoluteUrl(value){
                try{return new URL(urlOf(value),location.href).href;}catch(_){return urlOf(value);}
              }
              function matchPattern(pattern,url){
                if(!pattern||pattern==='*'||pattern==='<all_urls>')return true;
                try{
                  var low=String(pattern).toLowerCase();
                  if(low.indexOf('regex:')===0)return new RegExp(String(pattern).slice(6)).test(url);
                  if(pattern.length>2&&pattern.charAt(0)==='/'&&pattern.charAt(pattern.length-1)==='/'){
                    return new RegExp(pattern.slice(1,-1)).test(url);
                  }
                  var special="\\.^+?()[]{}|"+String.fromCharCode(36);
                  var out='';
                  for(var i=0;i<pattern.length;i++){
                    var ch=pattern.charAt(i);
                    if(ch==='*')out+='.*';else{if(special.indexOf(ch)>=0)out+='\\';out+=ch;}
                  }
                  return new RegExp('^'+out+String.fromCharCode(36),'i').test(url);
                }catch(_){return false;}
              }
              function matchMethod(rule,method){
                var raw=String(rule.methods||'*').trim();
                if(!raw||raw==='*')return true;
                return raw.split(/[ ,|]+/).some(function(v){return String(v).toUpperCase()===method;});
              }
              function matching(action,url,method){
                return rules.filter(function(rule){
                  return rule&&rule.enabled!==false&&rule.action===action&&
                    matchMethod(rule,method)&&matchPattern(String(rule.urlPattern||'*'),url);
                });
              }
              function matchingEither(action,first,second,method){
                var out=[];
                var seen=Object.create(null);
                [first,second].forEach(function(url){
                  if(!url)return;
                  matching(action,url,method).forEach(function(rule){
                    var key=String(rule.id||rule.name||out.length);
                    if(!seen[key]){seen[key]=true;out.push(rule);}
                  });
                });
                return out;
              }
              function firstTerminal(url,method){
                for(var i=0;i<rules.length;i++){
                  var rule=rules[i];
                  if(!rule||rule.enabled===false)continue;
                  if(rule.action!=='block'&&rule.action!=='mock')continue;
                  if(matchMethod(rule,method)&&matchPattern(String(rule.urlPattern||'*'),url))return rule;
                }
                return null;
              }
              function remember(applied,rule,url,phase,detail){
                var label=String(rule.name||rule.id||rule.action||'规则');
                if(applied.indexOf(label)<0)applied.push(label);
                recordRuleHit({
                  ruleId:rule.id,ruleName:label,action:rule.action,url:url,phase:phase,detail:detail
                });
              }
              function replaceText(text,rule){
                var find=String(rule.value||'');
                if(!find)return String(text);
                var replacement=String(rule.replacement||'');
                try{
                  if(find.toLowerCase().indexOf('regex:')===0){
                    return String(text).replace(new RegExp(find.slice(6),'g'),replacement);
                  }
                }catch(_){return String(text);}
                return String(text).split(find).join(replacement);
              }
              function rewriteUrl(url,rule){
                var replacement=String(rule.value||'');
                if(!replacement)return url;
                var pattern=String(rule.urlPattern||'*');
                try{
                  if(pattern.toLowerCase().indexOf('regex:')===0){
                    return url.replace(new RegExp(pattern.slice(6)),replacement);
                  }
                  if(pattern.length>2&&pattern.charAt(0)==='/'&&pattern.charAt(pattern.length-1)==='/'){
                    return url.replace(new RegExp(pattern.slice(1,-1)),replacement);
                  }
                  var parsed=new URL(url,location.href);
                  return replacement
                    .split('{url}').join(url)
                    .split('{host}').join(parsed.host)
                    .split('{path}').join(parsed.pathname)
                    .split('{query}').join(parsed.search?parsed.search.slice(1):'');
                }catch(_){return url;}
              }
              function headerEntries(rule){
                var headers=rule&&rule.headers&&typeof rule.headers==='object'?rule.headers:{};
                return Object.keys(headers).map(function(name){return [name,String(headers[name])];});
              }
              function requestHeaderRules(first,second,method){
                return matchingEither('request_headers',first,second,method);
              }
              function responseHeaderRules(first,second,method){
                return matchingEither('response_headers',first,second,method);
              }
              function copyResponseMetadata(target,source){
                try{Object.defineProperty(target,'url',{value:source.url,configurable:true});}catch(_){}
                try{Object.defineProperty(target,'redirected',{value:source.redirected,configurable:true});}catch(_){}
                try{Object.defineProperty(target,'type',{value:source.type,configurable:true});}catch(_){}
                return target;
              }

              var nativeFetch=window.fetch;
              if(typeof nativeFetch==='function'){
                window.fetch=function(input,init){
                  init=init||{};
                  var originalUrl=absoluteUrl(input);
                  var method=String(init.method||(input&&input.method)||'GET').toUpperCase();
                  var start=performance.now();
                  var applied=[];
                  var terminal=firstTerminal(originalUrl,method);
                  if(terminal&&terminal.action==='block'){
                    remember(applied,terminal,originalUrl,'request','fetch 已阻止');
                    record(originalUrl,method,0,'fetch','',0,performance.now()-start,applied);
                    return Promise.reject(new TypeError('Blocked by BrowserDiag network rule: '+String(terminal.name||terminal.id||'')));
                  }
                  if(terminal&&terminal.action==='mock'){
                    var mockHeaders=new Headers();
                    headerEntries(terminal).forEach(function(pair){try{mockHeaders.set(pair[0],pair[1]);}catch(_){}});
                    if(!mockHeaders.has('content-type'))mockHeaders.set('content-type',String(terminal.mimeType||'text/plain; charset=utf-8'));
                    var mockStatus=Number(terminal.statusCode)||200;
                    if(!((mockStatus>=200&&mockStatus<=299)||(mockStatus>=400&&mockStatus<=599)))mockStatus=200;
                    var mockBody=(method==='HEAD'||mockStatus===204||mockStatus===205)?null:String(terminal.value||'');
                    var mocked=new Response(mockBody,{status:mockStatus,headers:mockHeaders});
                    try{Object.defineProperty(mocked,'url',{value:originalUrl,configurable:true});}catch(_){}
                    remember(applied,terminal,originalUrl,'response','fetch Mock '+mockStatus);
                    record(originalUrl,method,mockStatus,'fetch',mockHeaders.get('content-type')||'',mockBody===null?0:mockBody.length,performance.now()-start,applied);
                    return Promise.resolve(mocked);
                  }

                  var request;
                  try{request=new Request(input,init);}catch(error){return nativeFetch.apply(this,arguments);}
                  var currentUrl=request.url||originalUrl;
                  matching('rewrite_url',originalUrl,method).forEach(function(rule){
                    var next=rewriteUrl(currentUrl,rule);
                    if(next&&next!==currentUrl){
                      remember(applied,rule,originalUrl,'request','URL → '+next.slice(0,220));
                      currentUrl=next;
                    }
                  });
                  var crossOriginRewrite=false;
                  try{crossOriginRewrite=new URL(originalUrl,location.href).origin!==new URL(currentUrl,location.href).origin;}catch(_){}
                  try{if(currentUrl!==request.url)request=new Request(currentUrl,request);}catch(_){}
                  if(crossOriginRewrite){
                    try{
                      var safeHeaders=new Headers(request.headers);
                      safeHeaders.delete('authorization');
                      safeHeaders.delete('proxy-authorization');
                      request=new Request(request,{headers:safeHeaders});
                    }catch(_){}
                  }

                  var reqHeaderRules=requestHeaderRules(originalUrl,currentUrl,method);
                  if(reqHeaderRules.length){
                    try{
                      var requestHeaders=new Headers(request.headers);
                      var setNames=Object.create(null);
                      reqHeaderRules.forEach(function(rule){
                        var changed=false;
                        headerEntries(rule).forEach(function(pair){
                          var key=pair[0].toLowerCase();
                          if(setNames[key])return;
                          try{requestHeaders.set(pair[0],pair[1]);setNames[key]=true;changed=true;}catch(_){}
                        });
                        if(changed)remember(applied,rule,originalUrl,'request','请求 Header 已修改');
                      });
                      request=new Request(request,{headers:requestHeaders});
                    }catch(_){}
                  }

                  var bodyRules=matchingEither('request_body',originalUrl,currentUrl,method);
                  var prepared=Promise.resolve(request);
                  if(bodyRules.length&&method!=='GET'&&method!=='HEAD'){
                    prepared=request.clone().text().then(function(body){
                      var nextBody=body;
                      bodyRules.forEach(function(rule){
                        var changed=replaceText(nextBody,rule);
                        remember(applied,rule,originalUrl,'request',changed!==nextBody?'请求 Body 已替换':'请求 Body 未找到替换内容');
                        nextBody=changed;
                      });
                      if(nextBody===body)return request;
                      var options={
                        method:request.method,headers:request.headers,body:nextBody,mode:request.mode,
                        credentials:request.credentials,cache:request.cache,redirect:request.redirect,
                        referrer:request.referrer,referrerPolicy:request.referrerPolicy,
                        integrity:request.integrity,keepalive:request.keepalive
                      };
                      if(request.signal)options.signal=request.signal;
                      return new Request(request.url,options);
                    }).catch(function(){return request;});
                  }

                  return prepared.then(function(outbound){
                    return nativeFetch.call(window,outbound);
                  }).then(function(response){
                    var responseUrl=response.url||currentUrl||originalUrl;
                    var bodyRewriteRules=matchingEither('response_body',originalUrl,responseUrl,method);
                    var responseHeadersRules=responseHeaderRules(originalUrl,responseUrl,method);
                    var contentType=response.headers.get('content-type')||'';
                    var isText=/^(text\/)|json|javascript|xml|svg|x-www-form-urlencoded/i.test(contentType);

                    function finish(result,bodyBytes){
                      record(result.url||responseUrl,method,result.status,'fetch',result.headers.get('content-type')||contentType,bodyBytes||result.headers.get('content-length')||0,performance.now()-start,applied);
                      return result;
                    }
                    function rewrittenHeaders(){
                      var headers=new Headers(response.headers);
                      var setNames=Object.create(null);
                      responseHeadersRules.forEach(function(rule){
                        var changed=false;
                        headerEntries(rule).forEach(function(pair){
                          var key=pair[0].toLowerCase();
                          if(setNames[key])return;
                          try{headers.set(pair[0],pair[1]);setNames[key]=true;changed=true;}catch(_){}
                        });
                        if(changed)remember(applied,rule,responseUrl,'response','响应 Header 已修改');
                      });
                      return headers;
                    }

                    if(bodyRewriteRules.length&&isText){
                      return response.clone().text().then(function(body){
                        var nextBody=body;
                        bodyRewriteRules.forEach(function(rule){
                          var changed=replaceText(nextBody,rule);
                          remember(applied,rule,responseUrl,'response',changed!==nextBody?'响应内容已替换':'响应内容未找到替换内容');
                          nextBody=changed;
                        });
                        var headers=rewrittenHeaders();
                        headers.delete('content-length');
                        var result=copyResponseMetadata(new Response(nextBody,{status:response.status,statusText:response.statusText,headers:headers}),response);
                        return finish(result,nextBody.length);
                      }).catch(function(){return finish(response,0);});
                    }
                    if(responseHeadersRules.length){
                      try{
                        var result=copyResponseMetadata(new Response(response.body,{status:response.status,statusText:response.statusText,headers:rewrittenHeaders()}),response);
                        return finish(result,0);
                      }catch(_){}
                    }
                    return finish(response,0);
                  }).catch(function(error){
                    record(currentUrl||originalUrl,method,0,'fetch','',0,performance.now()-start,applied);
                    throw error;
                  });
                };
              }

              var XHR=window.XMLHttpRequest;
              if(XHR&&XHR.prototype){
                var xp=XHR.prototype;
                var nativeOpen=xp.open;
                var nativeSend=xp.send;
                var nativeSetHeader=xp.setRequestHeader;
                var nativeGetHeader=xp.getResponseHeader;
                var nativeGetAllHeaders=xp.getAllResponseHeaders;
                var responseTextDescriptor=Object.getOwnPropertyDescriptor(xp,'responseText');
                var responseDescriptor=Object.getOwnPropertyDescriptor(xp,'response');

                function xhrRuleOnce(xhr,rule,phase,detail){
                  xhr.__bdRuleSeen=xhr.__bdRuleSeen||Object.create(null);
                  var key=String(rule.id||rule.name||rule.action)+'|'+phase;
                  if(xhr.__bdRuleSeen[key])return;
                  xhr.__bdRuleSeen[key]=true;
                  xhr.__bdAppliedRules=xhr.__bdAppliedRules||[];
                  remember(xhr.__bdAppliedRules,rule,xhr.__bdOriginalUrl||xhr.__bdUrl||'',phase,detail);
                }
                function xhrHeaderOverride(xhr,name,action){
                  var method=String(xhr.__bdMethod||'GET').toUpperCase();
                  var list=matchingEither(action,xhr.__bdOriginalUrl||'',xhr.__bdUrl||'',method);
                  var wanted=String(name||'').toLowerCase();
                  for(var i=0;i<list.length;i++){
                    var entries=headerEntries(list[i]);
                    for(var j=0;j<entries.length;j++){
                      if(entries[j][0].toLowerCase()===wanted)return {rule:list[i],value:entries[j][1]};
                    }
                  }
                  return null;
                }
                function applyXhrResponseText(xhr,text){
                  var method=String(xhr.__bdMethod||'GET').toUpperCase();
                  var url=xhr.__bdOriginalUrl||xhr.__bdUrl||'';
                  var responseUrl='';
                  try{responseUrl=xhr.responseURL||xhr.__bdUrl||'';}catch(_){}
                  var out=String(text);
                  matchingEither('response_body',url,responseUrl,method).forEach(function(rule){
                    var changed=replaceText(out,rule);
                    xhrRuleOnce(xhr,rule,'response',changed!==out?'XHR 响应内容已替换':'XHR 响应未找到替换内容');
                    out=changed;
                  });
                  return out;
                }

                xp.open=function(method,url){
                  var args=Array.prototype.slice.call(arguments);
                  var original=absoluteUrl(url);
                  var upper=String(method||'GET').toUpperCase();
                  var current=original;
                  this.__bdOriginalUrl=original;
                  this.__bdMethod=upper;
                  this.__bdAppliedRules=[];
                  this.__bdRuleSeen=Object.create(null);
                  this.__bdRuleHeadersSet=Object.create(null);
                  matching('rewrite_url',original,upper).forEach(function(rule){
                    var next=rewriteUrl(current,rule);
                    if(next&&next!==current){
                      current=next;
                      xhrRuleOnce(this,rule,'request','XHR URL → '+next.slice(0,220));
                    }
                  },this);
                  this.__bdUrl=current;
                  try{this.__bdCrossOriginRewrite=new URL(original,location.href).origin!==new URL(current,location.href).origin;}catch(_){this.__bdCrossOriginRewrite=false;}
                  args[1]=current;
                  return nativeOpen.apply(this,args);
                };

                xp.setRequestHeader=function(name,value){
                  this.__bdRuleHeadersSet=this.__bdRuleHeadersSet||Object.create(null);
                  var override=xhrHeaderOverride(this,name,'request_headers');
                  if(override){
                    var key=String(name).toLowerCase();
                    if(!this.__bdRuleHeadersSet[key]){
                      this.__bdRuleHeadersSet[key]=true;
                      xhrRuleOnce(this,override.rule,'request','XHR 请求 Header 已覆盖');
                      return nativeSetHeader.call(this,name,override.value);
                    }
                    return;
                  }
                  var lowerName=String(name||'').toLowerCase();
                  if(this.__bdCrossOriginRewrite&&(lowerName==='authorization'||lowerName==='proxy-authorization'))return;
                  return nativeSetHeader.apply(this,arguments);
                };

                xp.send=function(body){
                  var xhr=this;
                  var method=String(xhr.__bdMethod||'GET').toUpperCase();
                  var original=xhr.__bdOriginalUrl||xhr.__bdUrl||'';
                  var start=performance.now();
                  var terminal=firstTerminal(original,method);
                  if(terminal&&terminal.action==='block'){
                    xhrRuleOnce(xhr,terminal,'request','XHR 已阻止');
                    record(original,method,0,'xhr','',0,performance.now()-start,xhr.__bdAppliedRules);
                    try{xhr.abort();}catch(_){}
                    setTimeout(function(){
                      try{xhr.dispatchEvent(new ProgressEvent('error'));}catch(_){}
                      try{xhr.dispatchEvent(new ProgressEvent('loadend'));}catch(_){}
                    },0);
                    return;
                  }

                  var setNames=xhr.__bdRuleHeadersSet||Object.create(null);
                  requestHeaderRules(original,xhr.__bdUrl||original,method).forEach(function(rule){
                    var changed=false;
                    headerEntries(rule).forEach(function(pair){
                      var key=pair[0].toLowerCase();
                      if(setNames[key])return;
                      try{nativeSetHeader.call(xhr,pair[0],pair[1]);setNames[key]=true;changed=true;}catch(_){}
                    });
                    if(changed)xhrRuleOnce(xhr,rule,'request','XHR 请求 Header 已注入');
                  });

                  var outboundBody=body;
                  if(typeof body==='string'){
                    matchingEither('request_body',original,xhr.__bdUrl||original,method).forEach(function(rule){
                      var changed=replaceText(outboundBody,rule);
                      xhrRuleOnce(xhr,rule,'request',changed!==outboundBody?'XHR Body 已替换':'XHR Body 未找到替换内容');
                      outboundBody=changed;
                    });
                  }

                  var recorded=false;
                  function finish(){
                    if(recorded)return;
                    recorded=true;
                    var responseUrl='';
                    try{responseUrl=xhr.responseURL||xhr.__bdUrl||original;}catch(_){responseUrl=xhr.__bdUrl||original;}
                    matchingEither('response_body',original,responseUrl,method).forEach(function(rule){
                      if(xhr.__bdAppliedRules.indexOf(String(rule.name||rule.id||rule.action))<0)xhr.__bdAppliedRules.push(String(rule.name||rule.id||rule.action));
                    });
                    responseHeaderRules(original,responseUrl,method).forEach(function(rule){
                      if(xhr.__bdAppliedRules.indexOf(String(rule.name||rule.id||rule.action))<0)xhr.__bdAppliedRules.push(String(rule.name||rule.id||rule.action));
                    });
                    var mime='';var bytes=0;
                    try{mime=xhr.getResponseHeader('content-type')||'';}catch(_){}
                    try{bytes=xhr.getResponseHeader('content-length')||0;}catch(_){}
                    record(responseUrl,method,xhr.status,'xhr',mime,bytes,performance.now()-start,xhr.__bdAppliedRules);
                  }
                  xhr.addEventListener('load',finish);
                  xhr.addEventListener('error',finish);
                  xhr.addEventListener('abort',finish);
                  xhr.addEventListener('timeout',finish);
                  return nativeSend.call(xhr,outboundBody);
                };

                xp.getResponseHeader=function(name){
                  var override=xhrHeaderOverride(this,name,'response_headers');
                  if(override){
                    xhrRuleOnce(this,override.rule,'response','XHR 响应 Header 已覆盖');
                    return override.value;
                  }
                  return nativeGetHeader.apply(this,arguments);
                };
                xp.getAllResponseHeaders=function(){
                  var base=nativeGetAllHeaders.apply(this,arguments)||'';
                  var method=String(this.__bdMethod||'GET').toUpperCase();
                  var responseUrl='';
                  try{responseUrl=this.responseURL||this.__bdUrl||'';}catch(_){responseUrl=this.__bdUrl||'';}
                  var lines=[];var names=Object.create(null);
                  responseHeaderRules(this.__bdOriginalUrl||'',responseUrl,method).forEach(function(rule){
                    headerEntries(rule).forEach(function(pair){
                      var key=pair[0].toLowerCase();
                      if(names[key])return;
                      names[key]=true;lines.push(pair[0]+': '+pair[1]);
                    });
                  });
                  return base+(lines.length?(base&&base.slice(-2)!=='\r\n'?'\r\n':'')+lines.join('\r\n')+'\r\n':'');
                };

                if(responseTextDescriptor&&responseTextDescriptor.get&&responseTextDescriptor.configurable){
                  try{Object.defineProperty(xp,'responseText',{
                    configurable:true,enumerable:responseTextDescriptor.enumerable,
                    get:function(){return applyXhrResponseText(this,responseTextDescriptor.get.call(this));}
                  });}catch(_){}
                }
                if(responseDescriptor&&responseDescriptor.get&&responseDescriptor.configurable){
                  try{Object.defineProperty(xp,'response',{
                    configurable:true,enumerable:responseDescriptor.enumerable,
                    get:function(){
                      var raw=responseDescriptor.get.call(this);
                      var type=String(this.responseType||'');
                      if((type===''||type==='text')&&typeof raw==='string')return applyXhrResponseText(this,raw);
                      if(type==='json'&&raw!==null&&typeof raw==='object'){
                        try{
                          var text=JSON.stringify(raw);var changed=applyXhrResponseText(this,text);
                          return changed===text?raw:JSON.parse(changed);
                        }catch(_){return raw;}
                      }
                      return raw;
                    }
                  });}catch(_){}
                }
              }

              var NativeWebSocket=window.WebSocket;
              if(typeof NativeWebSocket==='function'){
                var WrappedWebSocket=function(url,protocols){
                  var original=absoluteUrl(url);
                  var method='CONNECT';
                  var terminal=firstTerminal(original,method);
                  if(terminal&&terminal.action==='block'){
                    var blocked=[];remember(blocked,terminal,original,'request','WebSocket 已阻止');
                    record(original,method,0,'websocket','',0,0,blocked);
                    throw new DOMException('Blocked by BrowserDiag network rule','SecurityError');
                  }
                  var current=original;var applied=[];
                  matching('rewrite_url',original,method).forEach(function(rule){
                    var next=rewriteUrl(current,rule);
                    if(next&&next!==current){current=next;remember(applied,rule,original,'request','WebSocket URL 已重写');}
                  });
                  var socket=protocols===undefined?new NativeWebSocket(current):new NativeWebSocket(current,protocols);
                  var start=performance.now();var done=false;
                  socket.addEventListener('open',function(){if(!done){done=true;record(current,method,101,'websocket','',0,performance.now()-start,applied);}});
                  socket.addEventListener('error',function(){if(!done){done=true;record(current,method,0,'websocket','',0,performance.now()-start,applied);}});
                  return socket;
                };
                WrappedWebSocket.prototype=NativeWebSocket.prototype;
                try{Object.setPrototypeOf(WrappedWebSocket,NativeWebSocket);}catch(_){}
                ['CONNECTING','OPEN','CLOSING','CLOSED'].forEach(function(key){try{WrappedWebSocket[key]=NativeWebSocket[key];}catch(_){}});
                window.WebSocket=WrappedWebSocket;
              }

              var NativeEventSource=window.EventSource;
              if(typeof NativeEventSource==='function'){
                var WrappedEventSource=function(url,config){
                  var original=absoluteUrl(url);var method='GET';
                  var terminal=firstTerminal(original,method);
                  if(terminal&&terminal.action==='block'){
                    var blocked=[];remember(blocked,terminal,original,'request','EventSource 已阻止');
                    record(original,method,0,'eventsource','text/event-stream',0,0,blocked);
                    throw new DOMException('Blocked by BrowserDiag network rule','SecurityError');
                  }
                  var current=original;var applied=[];
                  matching('rewrite_url',original,method).forEach(function(rule){
                    var next=rewriteUrl(current,rule);
                    if(next&&next!==current){current=next;remember(applied,rule,original,'request','EventSource URL 已重写');}
                  });
                  var source=new NativeEventSource(current,config);
                  var start=performance.now();var done=false;
                  source.addEventListener('open',function(){if(!done){done=true;record(current,method,200,'eventsource','text/event-stream',0,performance.now()-start,applied);}});
                  source.addEventListener('error',function(){if(!done){done=true;record(current,method,0,'eventsource','text/event-stream',0,performance.now()-start,applied);}});
                  return source;
                };
                WrappedEventSource.prototype=NativeEventSource.prototype;
                try{Object.setPrototypeOf(WrappedEventSource,NativeEventSource);}catch(_){}
                window.EventSource=WrappedEventSource;
              }
            })();
        """.trimIndent()
    }
}
