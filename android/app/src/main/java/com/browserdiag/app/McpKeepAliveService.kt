package com.browserdiag.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 保持 MCP 在切换到 AI 客户端或锁屏后继续可访问。
 * Foreground Service 提升进程优先级；有界续期 WakeLock + WifiLock 避免 CPU/Wi‑Fi 过早休眠。
 */
class McpKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var endpointHint = "MCP 正在启动…"

    private val renewWakeLock = object : Runnable {
        override fun run() {
            acquireWakeLock()
            handler.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).backgroundMcpEnabled = false
            if (!McpRuntime.hasUi()) McpServerHost.stop()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = Settings(this)
        if (!settings.lanApiEnabled || !settings.backgroundMcpEnabled) {
            if (!McpRuntime.hasUi()) McpServerHost.stop()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        endpointHint = intent?.getStringExtra(EXTRA_ENDPOINT)?.takeIf { it.isNotBlank() }
            ?: endpointHint
        // Android 要求 startForegroundService 后尽快进入 foreground，先展示启动态再做 socket 恢复。
        startForegroundCompat(buildNotification(endpointHint))

        val port = McpServerHost.ensureStarted(applicationContext)
        if (port == null) {
            updateNotification("MCP 启动失败 · 端口 8788-8791 被占用")
            releaseLocks()
            return START_STICKY
        }
        if (endpointHint == "MCP 正在启动…") endpointHint = "局域网 MCP · 端口 $port"
        updateNotification(endpointHint)
        acquireLocks()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireLocks() {
        acquireWakeLock()
        handler.removeCallbacks(renewWakeLock)
        handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_MS)
        if (wifiLock?.isHeld != true) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:mcp-wifi",
            )?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: run {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mcp-cpu").apply {
                setReferenceCounted(false)
            }.also { wakeLock = it }
        }
        runCatching {
            if (lock.isHeld) lock.release()
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MCP 后台连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "BrowserDiag 在后台保持 MCP/AI 客户端连接"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(detail: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, McpKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_network)
            .setContentTitle("BrowserDiag MCP 后台运行中")
            .setContentText("$detail · 切换到 AI 客户端仍保持服务")
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_close, "停止后台", stopPendingIntent)
            .build()
    }

    private fun updateNotification(detail: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(detail))
    }

    companion object {
        private const val CHANNEL_ID = "browserdiag_mcp_background"
        private const val NOTIFICATION_ID = 8788
        private const val ACTION_START = "com.browserdiag.app.action.START_MCP_BACKGROUND"
        private const val ACTION_STOP = "com.browserdiag.app.action.STOP_MCP_BACKGROUND"
        private const val EXTRA_ENDPOINT = "endpoint"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 45 * 60 * 1000L

        fun start(context: Context, endpoint: String) {
            val intent = Intent(context, McpKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENDPOINT, endpoint)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpKeepAliveService::class.java))
        }
    }
}
