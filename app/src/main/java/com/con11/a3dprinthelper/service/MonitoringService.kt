package com.con11.a3dprinthelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.con11.a3dprinthelper.ExitAppActivity
import com.con11.a3dprinthelper.R
import com.con11.a3dprinthelper.camera.Camera2BackgroundFrameSource
import com.con11.a3dprinthelper.camera.CameraSourceName
import com.con11.a3dprinthelper.monitor.MonitorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MonitoringService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: MonitorController
    private lateinit var backgroundFrameSource: Camera2BackgroundFrameSource
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var backgroundCameraStarted = false
    private var backgroundCameraStartedAtMillis = 0L
    private var backgroundCameraStartRequestedAtMillis = 0L
    private var retryScheduled = false
    private val startBackgroundCameraRunnable = Runnable {
        if (!controller.isForegroundVisible()) {
            controller.registerFrameSource(backgroundFrameSource)
            startBackgroundCamera()
        }
    }
    private val retryBackgroundCameraRunnable = Runnable {
        retryScheduled = false
        syncBackgroundCameraBinding()
    }
    private val backgroundCameraWatchdog = object : Runnable {
        override fun run() {
            if (!controller.isForegroundVisible()) {
                val now = System.currentTimeMillis()
                if (backgroundCameraStarted) {
                    val latestFrameAt = backgroundFrameSource.lastFrameAtMillis()
                    val referenceTime = maxOf(latestFrameAt, backgroundCameraStartedAtMillis)
                    if (now - referenceTime >= BACKGROUND_FRAME_STALE_TIMEOUT_MS) {
                        restartBackgroundCamera("后台相机超过 ${BACKGROUND_FRAME_STALE_TIMEOUT_MS / 1_000} 秒未产生新画面，正在重启")
                        return
                    }
                } else if (
                    backgroundCameraStartRequestedAtMillis > 0L &&
                    now - backgroundCameraStartRequestedAtMillis >= BACKGROUND_CAMERA_START_TIMEOUT_MS
                ) {
                    restartBackgroundCamera("后台相机启动超时，正在重试")
                    return
                }
            }
            mainHandler.postDelayed(this, BACKGROUND_CAMERA_WATCHDOG_INTERVAL_MS)
        }
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        controller = MonitorController.get(applicationContext)
        backgroundFrameSource = Camera2BackgroundFrameSource(
            context = applicationContext,
            jpegQualityProvider = { controller.currentSettings().jpegQuality },
            torchEnabledProvider = { controller.currentUiState().torchEnabled },
            onState = { started, error ->
                mainHandler.post {
                    backgroundCameraStarted = started
                    if (started) {
                        backgroundCameraStartedAtMillis = System.currentTimeMillis()
                        backgroundCameraStartRequestedAtMillis = 0L
                        retryScheduled = false
                        mainHandler.removeCallbacks(retryBackgroundCameraRunnable)
                        scheduleBackgroundCameraWatchdog()
                    } else if (error != null) {
                        backgroundCameraStartRequestedAtMillis = 0L
                    }
                    if (started || error != null) {
                        controller.reportCameraError(CameraSourceName.Background, error)
                    }
                    if (!started && error != null) {
                        scheduleBackgroundCameraRetry()
                    }
                }
            }
        )
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        controller.reportKeepAliveServiceRunning(true)
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            controller.reportNotificationPermissionMissing()
        }
        startAsForeground()
        observeNotificationState()
        acquireWakeLock()
        acquireWifiLock()
        syncBackgroundCameraBinding()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (controller.isKeepAliveTemporarilyStopped()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        acquireWakeLock()
        acquireWifiLock()
        syncBackgroundCameraBinding()
        controller.syncWebServer()
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        controller.unregisterFrameSource(backgroundFrameSource)
        backgroundFrameSource.stop()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        releaseLocks()
        controller.reportKeepAliveServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncBackgroundCameraBinding() {
        if (controller.isForegroundVisible()) {
            stopBackgroundCamera()
            return
        }
        controller.registerFrameSource(backgroundFrameSource)
        mainHandler.removeCallbacks(startBackgroundCameraRunnable)
        mainHandler.postDelayed(startBackgroundCameraRunnable, CAMERA_HANDOFF_DELAY_MS)
    }

    private fun startBackgroundCamera() {
        if (backgroundCameraStarted) return
        if (backgroundCameraStartRequestedAtMillis == 0L) {
            backgroundCameraStartRequestedAtMillis = System.currentTimeMillis()
        }
        backgroundFrameSource.start()
        scheduleBackgroundCameraWatchdog()
    }

    private fun stopBackgroundCamera() {
        backgroundFrameSource.stop()
        backgroundCameraStarted = false
        backgroundCameraStartedAtMillis = 0L
        backgroundCameraStartRequestedAtMillis = 0L
        retryScheduled = false
        mainHandler.removeCallbacks(startBackgroundCameraRunnable)
        mainHandler.removeCallbacks(retryBackgroundCameraRunnable)
        mainHandler.removeCallbacks(backgroundCameraWatchdog)
        controller.unregisterFrameSource(backgroundFrameSource)
    }

    private fun scheduleBackgroundCameraRetry() {
        if (
            retryScheduled ||
            controller.isForegroundVisible() ||
            controller.isKeepAliveTemporarilyStopped() ||
            !controller.currentSettings().keepAliveServiceEnabled
        ) return
        retryScheduled = true
        mainHandler.removeCallbacks(retryBackgroundCameraRunnable)
        mainHandler.postDelayed(retryBackgroundCameraRunnable, BACKGROUND_CAMERA_RETRY_DELAY_MS)
    }

    private fun scheduleBackgroundCameraWatchdog() {
        mainHandler.removeCallbacks(backgroundCameraWatchdog)
        mainHandler.postDelayed(backgroundCameraWatchdog, BACKGROUND_CAMERA_WATCHDOG_INTERVAL_MS)
    }

    private fun restartBackgroundCamera(reason: String) {
        if (controller.isForegroundVisible()) return
        controller.reportCameraError(CameraSourceName.Background, reason)
        mainHandler.removeCallbacks(backgroundCameraWatchdog)
        backgroundFrameSource.stop()
        backgroundCameraStarted = false
        backgroundCameraStartedAtMillis = 0L
        backgroundCameraStartRequestedAtMillis = 0L
        retryScheduled = false
        mainHandler.removeCallbacks(startBackgroundCameraRunnable)
        mainHandler.postDelayed(startBackgroundCameraRunnable, BACKGROUND_CAMERA_RESTART_DELAY_MS)
    }

    private fun startAsForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
    }

    private fun observeNotificationState() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            controller.uiState
                .map {
                    KeepAliveNotificationState(
                        monitoring = it.isRunning,
                        cameraSource = it.cameraSource,
                        webUrl = it.webUrl.ifBlank { controller.webUrl() },
                        cameraError = it.cameraError
                    )
                }
                .distinctUntilChanged()
                .collect {
                    val manager = getSystemService(NotificationManager::class.java)
                    runCatching { manager.notify(NOTIFICATION_ID, buildNotification(it)) }
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "3DPrintHelper:Monitoring").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "3DPrintHelper:WebMonitor").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "巡视保活", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(
        state: KeepAliveNotificationState = KeepAliveNotificationState(
            monitoring = controller.currentUiState().isRunning,
            cameraSource = controller.currentUiState().cameraSource,
            webUrl = controller.currentUiState().webUrl.ifBlank { controller.webUrl() },
            cameraError = controller.currentUiState().cameraError
        )
    ): Notification {
        val monitoringText = if (state.monitoring) "巡检运行中" else "巡检已暂停"
        val cameraText = when (state.cameraSource) {
            "foreground" -> "前台相机"
            "background" -> "后台相机"
            else -> "相机等待中"
        }
        val summary = "$monitoringText · $cameraText"
        val detail = buildString {
            append(summary)
            append("\nWeb：")
            append(state.webUrl)
            state.cameraError?.takeIf { it.isNotBlank() }?.let {
                append("\n相机：")
                append(it)
            }
            append("\n点击此通知将完全关闭 App")
        }
        val exitAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_EXIT_APP,
            Intent(this, ExitAppActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            pendingIntentFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("3DPrintHelper 后台保活运行中")
            .setContentText("点击通知将完全关闭 App")
            .setSubText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(exitAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val CHANNEL_ID = "monitoring"
        const val NOTIFICATION_ID = 3001
        private const val REQUEST_EXIT_APP = 3002
        const val CAMERA_HANDOFF_DELAY_MS = 700L
        const val BACKGROUND_CAMERA_RETRY_DELAY_MS = 3_000L
        const val BACKGROUND_CAMERA_RESTART_DELAY_MS = 1_000L
        const val BACKGROUND_CAMERA_WATCHDOG_INTERVAL_MS = 5_000L
        const val BACKGROUND_CAMERA_START_TIMEOUT_MS = 12_000L
        const val BACKGROUND_FRAME_STALE_TIMEOUT_MS = 12_000L
    }

    private data class KeepAliveNotificationState(
        val monitoring: Boolean,
        val cameraSource: String,
        val webUrl: String,
        val cameraError: String?
    )
}
