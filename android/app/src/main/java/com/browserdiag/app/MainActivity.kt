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
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Locale

/**
 * BrowserDiag 3.2：Chrome 风格底部导航浏览器 + 带 Token 认证的内嵌 HTTP 诊断服务器。
 * 功能：多标签 / 搜索引擎切换 / UA 切换 / 深色主题 / 油猴脚本 / 书签 / 保存页面 / 分享 /
 * 页面查找 / 翻译 / 媒体嗅探 / 页面资源 / 源码 zip / 语音播报 / 二维码 / 添加到桌面 / 历史。
 * 平时可作普通浏览器使用，也可作为诊断后端供其它 AI 工具通过 HTTP 调用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusBar: TextView
    private lateinit var settings: Settings
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
    private var server: DiagServer? = null
    private var serverPort = 8788
    private var tts: TextToSpeech? = null
    private var isDark = false
    private var isFullscreen = false
    private var mainMenuDialog: Dialog? = null

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
        tabs = Tabs(this)
        isDark = settings.darkMode
        theme.applyStyle(
            if (isDark) R.style.ThemeOverlay_BrowserDiag_Dark else R.style.ThemeOverlay_BrowserDiag_Light,
            true
        )
        WebView.setWebContentsDebuggingEnabled(settings.debugWeb)
        applySavedOrientation()
        buildUi()
        newTab(initialUrlFromIntent(intent) ?: settings.engine.homeUrl)
        startServer()
        applyTheme()
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

        // ---- 顶部：Chrome 风格 Omnibox + 标签计数 + 更多菜单 ----
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
        topBar.addView(iconBtn(R.drawable.ic_menu, 24, "工具中心") { showMainMenu() })
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
        val api = if (server == null) "API 未启动" else "API $scope:${serverPort}"
        return "$api  ·  Console ${consoleLogs.size}/$errs  ·  点击诊断"
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
            content.addView(panelRow(R.drawable.ic_tools, "页面诊断", "查看 API、Console 与当前标签状态", onClick = {
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
        if (tabs.size >= 5) {
            toast("标签已达上限（5 个），请先关闭标签")
            return
        }
        val tab = tabs.create(webContainer, url)
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

        // 网络 hook + 油猴脚本（document-start）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, NETWORK_HOOK_JS, setOf("*"))
            } catch (e: Exception) {
            }
        }
        settings.getScripts().filter { it.enabled }.forEach { s ->
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, s.code, patternToOriginRules(s.urlPattern))
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
                runOnUiThread { statusBar.text = buildStatusText() }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (tabs.current?.webView === view) {
                    setOmniboxUrl(url.orEmpty())
                    updateChromeControls()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
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
                if (settings.adBlock && request?.url != null) {
                    val host = request.url.host?.lowercase() ?: ""
                    if (host.isNotEmpty() && AD_BLOCK_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        wv.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            try {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url))
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setMimeType(mimeType ?: "application/octet-stream")
                dm.enqueue(req)
                toast("已加入下载队列")
            } catch (e: Exception) {
                toast("下载失败: ${e.message}")
            }
        })
    }

    private fun showTabsDialog() {
        val all = tabs.all
        showBrowserSheet(
            title = "标签页",
            subtitle = "${all.size}/5 · 点击卡片切换标签",
            headerActionLabel = "＋ 新标签",
            headerAction = { dialog ->
                newTab(settings.engine.homeUrl)
                dialog.dismiss()
            }
        ) { content, dialog ->
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
                        tabs.destroy(tab.id)
                        if (tabs.size == 0) newTab(settings.engine.homeUrl)
                        tabs.current?.let { setOmniboxUrl(it.url) }
                        updateChromeControls()
                        statusBar.text = buildStatusText()
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

    // ==================== 导航 ====================
    private fun navigate(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        val engine = settings.engine
        val wv = currentWeb() ?: return
        when {
            q.startsWith("http://") || q.startsWith("https://") -> wv.loadUrl(q)
            q.startsWith("about:") -> wv.loadUrl(q)
            q.contains(".") && !q.contains(" ") && !q.contains("/") && !q.contains("?") ->
                wv.loadUrl("https://$q")
            else -> wv.loadUrl(engine.searchUrl.format(URLEncoder.encode(q, "UTF-8")))
        }
        urlInput.clearFocus()
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
                MenuItem("downloads", R.drawable.ic_download, "下载内容", "查看系统下载队列与已下载文件") { showDownloads() }
            )
        ),
        ToolCategory(
            "诊断与开发",
            R.drawable.ic_tools,
            "媒体、资源、源码、网络与 Console",
            listOf(
                MenuItem("sniff", R.drawable.ic_movie, "媒体嗅探", "识别 video / audio 与常见流媒体链接") { sniffMedia() },
                MenuItem("resources", R.drawable.ic_folder, "页面资源", "查看图片、脚本和样式资源") { pageResources() },
                MenuItem("source", R.drawable.ic_code, "源码归档", "导出 HTML、资源、Console 与网络日志") { downloadSourceZip() },
                MenuItem("netlog", R.drawable.ic_network, "网络日志", "查看当前页面最近的请求状态") { showNetLog() },
                MenuItem("devtools", R.drawable.ic_tools, "开发者工具", "诊断 API、Console 与页面状态") { devTools() }
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
                MenuItem("userscript", R.drawable.ic_extension, "用户脚本", "${settings.getScripts().size} 个脚本") { showScriptManager() },
                MenuItem("font", R.drawable.ic_font, "网页字体", "${settings.fontScale}%") { showFontScale() },
                MenuItem("orientation", R.drawable.ic_rotate, "屏幕方向", orientationLabel()) { showOrientation() },
                MenuItem("adblock", R.drawable.ic_shield, "广告拦截", if (settings.adBlock) "已开启" else "已关闭") { toggleAdBlock() },
                MenuItem("debugweb", R.drawable.ic_code, "WebView 调试", if (settings.debugWeb) "已开启" else "已关闭") { toggleDebugWeb() }
            )
        ),
        ToolCategory(
            "BrowserDiag",
            R.drawable.ic_info,
            if (settings.lanApiEnabled) "诊断 API 已允许局域网访问" else "诊断 API 仅限本机",
            listOf(
                MenuItem("lanapi", R.drawable.ic_link, "局域网诊断 API", if (settings.lanApiEnabled) "已开启 · Token 认证" else "已关闭 · 仅本机") { toggleLanApi() },
                MenuItem("menuconfig", R.drawable.ic_settings, "常用工具设置", "选择工具中心的常用快捷入口") { showMenuConfig() },
                MenuItem("about", R.drawable.ic_info, "关于 BrowserDiag", "v3.2.0 · API ${serverPort}") { showAbout() }
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
        val defaultFavorites = listOf("history", "downloads", "netlog", "devtools", "engine", "userscript", "dark", "ua")
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
        currentWeb()?.clearMatches()
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
    private fun sniffMedia() {
        val wv = currentWeb() ?: return
        statusBar.text = "正在嗅探媒体资源…"
        wv.evaluateJavascript(
            "(function(){var out=[];document.querySelectorAll('video,audio').forEach(function(m){if(m.src)out.push(m.src);m.querySelectorAll('source').forEach(function(s){if(s.src)out.push(s.src);});});if(window.__bdNet)window.__bdNet.forEach(function(n){if(/\\.(mp4|m3u8|mp3|webm|flv|m4a|ogg|aac)(\\?|$)/i.test(n.url))out.push(n.url);});return JSON.stringify(out);})()"
        ) { raw ->
            val list = try {
                val arr = decodeJsArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    statusBar.text = "未发现媒体资源"
                    showBrowserSheet("媒体嗅探", "扫描 video / audio 与常见媒体请求") { content, _ ->
                        content.addView(emptyPanel(R.drawable.ic_movie, "未发现媒体资源", "播放页面中的视频或音频后可再次扫描"))
                    }
                    return@runOnUiThread
                }
                statusBar.text = "发现 ${list.size} 个媒体资源"
                showUrlListDialog("媒体资源（点击下载）", list)
            }
        }
    }

    private fun pageResources() {
        val wv = currentWeb() ?: return
        wv.evaluateJavascript(
            "(function(){var out=[];document.querySelectorAll('img[src],script[src],link[href]').forEach(function(e){var u=e.src||e.href;if(u&&u.indexOf('data:')!==0)out.push(u);});return JSON.stringify(out.slice(0,60));})()"
        ) { raw ->
            val list = try {
                val arr = decodeJsArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    showBrowserSheet("页面资源", "图片、脚本与样式资源") { content, _ ->
                        content.addView(emptyPanel(R.drawable.ic_folder, "未发现页面资源", "当前页面没有可列出的外部资源"))
                    }
                } else {
                    showUrlListDialog("页面资源", list)
                }
            }
        }
    }

    private fun showUrlListDialog(title: String, urls: List<String>) {
        showBrowserSheet(title, "${urls.size} 个资源 · 点击选择操作") { content, sheet ->
            urls.forEach { url ->
                content.addView(panelRow(R.drawable.ic_link, displayHost(url), url.take(120), onClick = {
                AlertDialog.Builder(this)
                    .setTitle("操作")
                    .setItems(arrayOf("在当前标签打开", "下载资源", "复制链接")) { actionDialog, op ->
                        when (op) {
                            0 -> {
                                currentWeb()?.loadUrl(url)
                                sheet.dismiss()
                            }
                            1 -> {
                                try {
                                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val req = DownloadManager.Request(Uri.parse(url))
                                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    dm.enqueue(req)
                                    toast("已加入下载队列")
                                } catch (e: Exception) {
                                    toast("下载失败：${e.message}")
                                }
                            }
                            2 -> copyText(url)
                        }
                        actionDialog.dismiss()
                    }
                    .show()
                }))
            }
        }
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
        val token = settings.apiToken
        val wv = currentWeb()
        val errors = synchronized(consoleLogs) { consoleLogs.count { it.optString("type") == "error" } }
        showBrowserSheet("开发者工具", "BrowserDiag 页面诊断控制台") { content, dialog ->
            content.addView(sectionTitle("连接"))
            content.addView(panelRow(
                R.drawable.ic_network,
                "诊断 API",
                "$api · ${if (settings.lanApiEnabled) "局域网 + 本机" else "仅本机"}",
                onClick = { copyText(api) }
            ))
            content.addView(panelRow(
                R.drawable.ic_lock,
                "访问 Token",
                "${token.take(6)}…${token.takeLast(4)} · 点击复制完整 Token",
                onClick = { copyText(token) }
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
                text = "HTTP 调用必须携带 Authorization: Bearer <token>。支持页面状态、Console、Network、Eval、截图、性能、报告、源码等诊断能力。"
                textSize = 12.5f
                setTextColor(secondaryTextColor())
                setPadding(14.dp(), 16.dp(), 14.dp(), 8.dp())
            })
        }
    }

    private fun showConsoleLog() {
        val logs = synchronized(consoleLogs) { consoleLogs.takeLast(120).reversed() }
        val errors = logs.count { it.optString("type") == "error" }
        showBrowserSheet("Console", "${logs.size} 条最近日志 · $errors 条错误") { content, _ ->
            if (logs.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_code, "暂无 Console 日志", "页面脚本输出会显示在这里"))
            }
            logs.forEach { entry ->
                val type = entry.optString("type").ifEmpty { "log" }.uppercase(Locale.ROOT)
                val message = entry.optString("text")
                content.addView(panelRow(
                    R.drawable.ic_code,
                    type,
                    message.take(180),
                    selected = type == "ERROR",
                    onClick = { copyText(message) }
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
            subtitle = "${scripts.size} 个脚本 · 新标签页加载启用脚本",
            headerActionLabel = "＋ 添加",
            headerAction = { dialog ->
                dialog.dismiss()
                showAddScriptDialog()
            }
        ) { content, dialog ->
            if (scripts.isEmpty()) {
                content.addView(emptyPanel(R.drawable.ic_extension, "暂无用户脚本", "添加脚本后可按 URL 规则自动注入页面"))
            }
            scripts.forEachIndexed { index, script ->
                content.addView(panelRow(
                    iconRes = R.drawable.ic_extension,
                    title = script.name,
                    subtitle = "${if (script.enabled) "已启用" else "已停用"} · ${script.urlPattern}",
                    trailingIcon = R.drawable.ic_delete,
                    selected = script.enabled,
                    onTrailing = {
                        val updated = settings.getScripts().toMutableList()
                        if (index in updated.indices) updated.removeAt(index)
                        settings.saveScripts(updated)
                        toast("脚本已删除")
                        dialog.dismiss()
                    },
                    onClick = {
                        val updated = settings.getScripts().toMutableList()
                        if (index in updated.indices) {
                            updated[index] = updated[index].copy(enabled = !updated[index].enabled)
                            settings.saveScripts(updated)
                            toast("脚本已${if (updated[index].enabled) "启用" else "停用"}，新标签页后生效")
                        }
                        dialog.dismiss()
                    }
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
                settings.clearHistory()
                toast("历史已清空")
                dialog.dismiss()
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
                    onClick = {
                        currentWeb()?.loadUrl(url)
                        dialog.dismiss()
                    }
                ))
            }
        }
    }

    private fun showAbout() {
        val api = apiBaseUrl()
        val token = settings.apiToken
        val ua = currentWeb()?.settings?.userAgentString ?: ""
        showBrowserSheet("BrowserDiag", "安全浏览 + 页面诊断 · v3.2.0") { content, _ ->
            content.addView(panelRow(R.drawable.ic_info, "BrowserDiag 3.2.0", "Android 浏览器与诊断后端"))
            content.addView(panelRow(R.drawable.ic_network, "HTTP API", "$api · Token 认证", onClick = {
                copyText(api)
            }))
            content.addView(panelRow(R.drawable.ic_lock, "API Token", "${token.take(6)}…${token.takeLast(4)} · 点击复制", onClick = {
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

    // ==================== 源码 zip（保留 v2.1） ====================
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
                SourcePacker.pack(
                    context = this,
                    url = currentUrl,
                    title = tabs.current?.title.orEmpty(),
                    html = html,
                    consoleJson = consoleJson,
                    networkJson = netJson,
                    ua = wv.settings.userAgentString
                ) { ok, msg ->
                    runOnUiThread {
                        statusBar.text = if (ok) "源码已保存 ✅ $msg" else "打包失败 ❌ $msg"
                        toast(if (ok) "网页源码已保存：$msg" else "打包失败：$msg")
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

    private fun patternToOriginRules(pattern: String): Set<String> {
        val p = pattern.trim()
        if (p.isEmpty() || p == "*") return setOf("*")
        val host = p.trim('*').trim().lowercase()
            .removePrefix("https://").removePrefix("http://").trim('/')
        if (host.isEmpty()) return setOf("*")
        return buildSet {
            add("https://$host")
            add("http://$host")
            add("https://*.$host")
            add("http://*.$host")
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

    /** 网络日志面板：读取 window.__bdNet 展示请求列表 */
    private fun showNetLog() {
        val wv = currentWeb() ?: return toast("无页面")
        wv.evaluateJavascript("JSON.stringify((window.__bdNet||[]).slice(-100).reverse())") { raw ->
            val arr = decodeJsArray(raw)
            showBrowserSheet("网络日志", "当前页面最近 ${arr.length()} 条请求 · 点击复制 URL") { content, _ ->
                if (arr.length() == 0) {
                    content.addView(emptyPanel(R.drawable.ic_network, "暂无网络记录", "刷新或浏览页面后再查看请求"))
                }
                for (idx in 0 until arr.length()) {
                    val o = arr.optJSONObject(idx) ?: continue
                    val status = o.optInt("status")
                    val method = o.optString("method").ifEmpty { "GET" }
                    val state = when {
                        status in 200..399 -> "$method $status · 成功"
                        status == 0 -> "$method · 进行中或失败"
                        else -> "$method $status · 异常"
                    }
                    val url = o.optString("url")
                    content.addView(panelRow(R.drawable.ic_network, state, url.take(140), onClick = {
                        copyText(url)
                    }))
                }
            }
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

    /** 下载管理：列出系统 DownloadManager 的下载记录，点击打开 */
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
            showBrowserSheet("下载内容", "系统 DownloadManager") { content, _ ->
                content.addView(emptyPanel(R.drawable.ic_download, "暂无下载记录", "从网页下载的文件会显示在这里"))
            }
            return
        }
        val names = mutableListOf<String>()
        val ids = mutableListOf<Long>()
        do {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val state = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> "完成"
                DownloadManager.STATUS_FAILED -> "失败"
                DownloadManager.STATUS_PAUSED -> "暂停"
                DownloadManager.STATUS_RUNNING -> "下载中"
                else -> "等待"
            }
            val sizeText = if (size > 0) " · ${size / 1024}KB" else ""
            names.add("$state · $title$sizeText")
            ids.add(id)
        } while (cursor.moveToNext())
        cursor.close()
        showBrowserSheet("下载内容", "${names.size} 个下载任务") { content, dialog ->
            names.forEachIndexed { idx, label ->
                val split = label.split(" · ", limit = 2)
                val state = split.firstOrNull().orEmpty()
                val title = split.getOrNull(1).orEmpty().ifEmpty { label }
                content.addView(panelRow(R.drawable.ic_download, title, state, onClick = {
                    val uri = dm.getUriForDownloadedFile(ids[idx])
                    if (uri == null) {
                        toast("该下载尚不可打开")
                    } else {
                        try {
                            val mime = dm.getMimeTypeForDownloadedFile(ids[idx]) ?: "*/*"
                            startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                            dialog.dismiss()
                        } catch (_: ActivityNotFoundException) {
                            toast("无法打开该文件")
                        } catch (_: SecurityException) {
                            toast("无法打开该文件")
                        }
                    }
                }))
            }
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

    /** 默认仅本机访问；显式开启后才监听局域网地址，仍必须携带 API Token。 */
    private fun toggleLanApi() {
        settings.lanApiEnabled = !settings.lanApiEnabled
        restartServer()
        toast(
            if (settings.lanApiEnabled) "局域网 API 已开启（Token 认证仍然有效）"
            else "局域网 API 已关闭，仅本机可访问"
        )
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
        val defaults = listOf("history", "downloads", "netlog", "devtools", "engine", "userscript", "dark", "ua")
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
        server?.stop()
        server = null
        startServer()
    }

    private fun startServer() {
        val listenHost = if (settings.lanApiEnabled) "0.0.0.0" else "127.0.0.1"
        var port = 8788
        var started = false
        while (port < 8792) {
            try {
                val s = DiagServer(
                    listenHost,
                    port,
                    applicationContext,
                    { tabs.current?.webView },
                    { synchronized(consoleLogs) { consoleLogs.toList() } },
                    { settings },
                    { tabs },
                    settings.apiToken
                )
                s.start(5000, true)
                server = s
                serverPort = port
                started = true
                break
            } catch (e: Exception) {
                port++
            }
        }
        if (!started) {
            statusBar.text = "API 服务启动失败（端口 8788-8791 均被占用）"
        } else {
            statusBar.text = buildStatusText()
        }
    }

    private fun apiBaseUrl(): String {
        val host = if (settings.lanApiEnabled) localIp() else "127.0.0.1"
        return "http://$host:$serverPort"
    }

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
        server?.stop()
        tts?.stop()
        tts?.shutdown()
        tabs.destroyAll()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            setFullscreen(false)
            return
        }
        if (findBar.visibility == View.VISIBLE) {
            hideFindBar()
            return
        }
        val wv = currentWeb()
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    companion object {
        private const val LEGACY_STORAGE_PERMISSION_REQUEST = 4101

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

        private val NETWORK_HOOK_JS = """
            (function(){
              if (window.__bdHooked) return;
              window.__bdHooked = true;
              window.__bdNet = [];
              function record(u,m,s,t){ window.__bdNet.push({url:String(u).slice(0,300),method:m,status:s,type:t}); if(window.__bdNet.length>300) window.__bdNet.shift(); }
              function urlOf(u){ if(typeof u==='string')return u;if(u&&u.url)return u.url;if(u&&u.href)return u.href;return String(u); }
              var op = XMLHttpRequest.prototype.open;
              var sp = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(m,u){ this.__u=u; this.__m=m; return op.apply(this,arguments); };
              XMLHttpRequest.prototype.send = function(){
                this.addEventListener('load', function(){ record(this.__u,this.__m,this.status,'xhr'); });
                this.addEventListener('error', function(){ record(this.__u,this.__m,0,'xhr'); });
                return sp.apply(this,arguments);
              };
              var of = window.fetch;
              window.fetch = function(){
                var u = arguments[0];
                var init = arguments[1] || {};
                var method = String(init.method || (u && u.method) || 'GET').toUpperCase();
                return of.apply(this,arguments).then(function(r){ record(urlOf(u),method,r.status,'fetch'); return r; })
                  .catch(function(e){ record(urlOf(u),method,0,'fetch'); throw e; });
              };
            })();
        """.trimIndent()
    }
}
