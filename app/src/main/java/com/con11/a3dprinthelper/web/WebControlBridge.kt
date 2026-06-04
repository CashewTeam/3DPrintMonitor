package com.con11.a3dprinthelper.web

import com.con11.a3dprinthelper.camera.CapturedFrame
import com.con11.a3dprinthelper.data.AppSettings
import com.con11.a3dprinthelper.ui.MonitorUiState
import org.json.JSONObject

class WebControlBridge(
    private val getSettings: () -> AppSettings,
    private val getSettingsSchemaJson: () -> String,
    private val encodeSettings: (Boolean) -> JSONObject,
    private val decodeSettings: (JSONObject) -> AppSettings,
    private val getUiState: () -> MonitorUiState,
    private val getFrame: () -> CapturedFrame?,
    private val startMonitoring: () -> Unit,
    private val stopMonitoring: () -> Unit,
    private val analyzeNow: () -> Unit,
    private val toggleTorch: () -> Unit,
    private val turnScreenOff: () -> Unit,
    private val turnScreenOn: () -> Unit,
    private val saveSettings: (AppSettings) -> Unit,
    private val testBark: () -> Unit
) {
    fun settings(): AppSettings = getSettings()

    fun settingsSchemaJson(): String = getSettingsSchemaJson()

    fun settingsJson(maskSecrets: Boolean): JSONObject = encodeSettings(maskSecrets)

    fun decodeSettings(values: JSONObject): AppSettings = decodeSettings.invoke(values)

    fun uiState(): MonitorUiState = getUiState()

    fun latestFrame(): CapturedFrame? = getFrame()

    fun control(action: String) {
        when (action) {
            "start" -> startMonitoring()
            "stop" -> stopMonitoring()
            "analyze_now" -> analyzeNow()
            "toggle_torch" -> toggleTorch()
            "screen_off" -> turnScreenOff()
            "screen_on" -> turnScreenOn()
            "test_bark" -> testBark()
        }
    }

    fun updateSettings(settings: AppSettings) {
        saveSettings(settings)
    }
}
