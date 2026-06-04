package com.con11.a3dprinthelper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.con11.a3dprinthelper.monitor.MonitorController
import com.con11.a3dprinthelper.service.MonitoringService
import kotlin.system.exitProcess

class ExitAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MonitorController.get(applicationContext).temporarilyStopKeepAliveService()
        NotificationManagerCompat.from(this).cancel(MonitoringService.NOTIFICATION_ID)
        stopService(Intent(this, MonitoringService::class.java))
        finishAndRemoveTask()

        Handler(Looper.getMainLooper()).postDelayed(
            {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            },
            PROCESS_EXIT_DELAY_MS
        )
    }

    private companion object {
        const val PROCESS_EXIT_DELAY_MS = 250L
    }
}
