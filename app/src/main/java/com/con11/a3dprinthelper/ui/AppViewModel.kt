package com.con11.a3dprinthelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.con11.a3dprinthelper.camera.CapturedFrame
import com.con11.a3dprinthelper.camera.CameraFrameSource
import com.con11.a3dprinthelper.data.AppSettings
import com.con11.a3dprinthelper.data.SettingField
import com.con11.a3dprinthelper.data.SettingsSchema
import com.con11.a3dprinthelper.monitor.MonitorController
import com.con11.a3dprinthelper.network.AnalysisResult
import kotlinx.coroutines.flow.StateFlow

data class MonitorUiState(
    val isRunning: Boolean = false,
    val isAnalyzing: Boolean = false,
    val nextCaptureAtMillis: Long? = null,
    val lastAnalysisAtMillis: Long? = null,
    val lastResult: AnalysisResult? = null,
    val statusMessage: String = "等待开始巡检",
    val errorMessage: String? = null,
    val torchEnabled: Boolean = false,
    val screenOff: Boolean = false,
    val screenSaverFallbackActive: Boolean = false,
    val webUrl: String = "",
    val cameraSource: String = "none",
    val lastFrameAtMillis: Long? = null,
    val cameraError: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = MonitorController.get(application)

    val settings: StateFlow<AppSettings> = controller.settings
    val uiState: StateFlow<MonitorUiState> = controller.uiState

    fun setLatestFrameProvider(provider: () -> CapturedFrame?) {
        controller.setLatestFrameProvider(provider)
    }

    fun setLatestLumaProvider(provider: () -> Double?) {
        controller.setLatestLumaProvider(provider)
    }

    fun registerFrameSource(source: CameraFrameSource) = controller.registerFrameSource(source)

    fun unregisterFrameSource(source: CameraFrameSource) = controller.unregisterFrameSource(source)

    fun saveSettings(settings: AppSettings) = controller.saveSettings(settings)

    fun currentSettings(): AppSettings = controller.currentSettings()

    fun settingsSchema(): SettingsSchema = controller.settingsSchema()

    fun updateSettingValue(current: AppSettings, field: SettingField, value: Any?): AppSettings =
        controller.updateSettingValue(current, field, value)

    fun settingValue(current: AppSettings, field: SettingField): Any =
        controller.settingValue(current, field)

    fun currentUiState(): MonitorUiState = controller.currentUiState()

    fun startMonitoring() = controller.startMonitoring()

    fun stopMonitoring() = controller.stopMonitoring()

    fun analyzeNow() = controller.analyzeNow()

    fun clearError() = controller.clearError()

    fun testBarkPush() = controller.testBarkPush()

    fun checkShizukuPermissionOnStartup() = controller.checkShizukuPermissionOnStartup()

    fun toggleTorch() = controller.toggleTorch()

    fun turnScreenOff() = controller.turnScreenOff()

    fun triggerAutoScreenOff() = controller.triggerAutoScreenOff()

    fun turnScreenOn() = controller.turnScreenOn()

    fun setForegroundVisible(visible: Boolean) = controller.setForegroundVisible(visible)

    fun syncService() = controller.syncService()

    fun syncWebServer(forceRestart: Boolean = false) = controller.syncWebServer(forceRestart)
}
