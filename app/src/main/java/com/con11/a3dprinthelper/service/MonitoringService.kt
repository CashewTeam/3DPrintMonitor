package com.con11.a3dprinthelper.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.con11.a3dprinthelper.R
import com.con11.a3dprinthelper.camera.Camera2BackgroundFrameSource
import com.con11.a3dprinthelper.camera.CameraSourceName
import com.con11.a3dprinthelper.monitor.MonitorController

class MonitoringService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: MonitorController
    private lateinit var backgroundFrameSource: Camera2BackgroundFrameSource
    private var wakeLock: PowerManager.WakeLock? = null
    private var backgroundCameraStarted = false
    private var retryScheduled = false

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
                backgroundCameraStarted = started
                retryScheduled = false
                controller.reportCameraError(CameraSourceName.Background, error)
                if (!started && error != null) {
                    scheduleBackgroundCameraRetry()
                }
            }
        )
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startAsForeground()
        acquireWakeLock()
        syncBackgroundCameraBinding()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        acquireWakeLock()
        syncBackgroundCameraBinding()
        controller.syncWebServer()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        controller.unregisterFrameSource(backgroundFrameSource)
        backgroundFrameSource.stop()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncBackgroundCameraBinding() {
        if (controller.isForegroundVisible()) {
            stopBackgroundCamera()
            return
        }
        controller.registerFrameSource(backgroundFrameSource)
        startBackgroundCamera()
    }

    private fun startBackgroundCamera() {
        if (backgroundCameraStarted) return
        backgroundFrameSource.start()
    }

    private fun stopBackgroundCamera() {
        backgroundFrameSource.stop()
        backgroundCameraStarted = false
        retryScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        controller.unregisterFrameSource(backgroundFrameSource)
    }

    private fun scheduleBackgroundCameraRetry() {
        if (retryScheduled || controller.isForegroundVisible() || !controller.currentSettings().keepAliveServiceEnabled) return
        retryScheduled = true
        mainHandler.postDelayed(
            {
                retryScheduled = false
                syncBackgroundCameraBinding()
            },
            BACKGROUND_CAMERA_RETRY_DELAY_MS
        )
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "3DPrintHelper:Monitoring").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("3DPrintHelper 后台保活运行中")
            .setContentText("保持后台相机、Web 控制和息屏恢复能力。")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "巡视保活", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
            .build()

    private companion object {
        const val CHANNEL_ID = "monitoring"
        const val NOTIFICATION_ID = 3001
        const val BACKGROUND_CAMERA_RETRY_DELAY_MS = 3_000L
    }
}
