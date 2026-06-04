package com.con11.a3dprinthelper.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.BatteryManager
import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.con11.a3dprinthelper.camera.CameraFrameSource
import com.con11.a3dprinthelper.camera.CameraSourceName
import com.con11.a3dprinthelper.camera.CapturedFrame
import com.con11.a3dprinthelper.data.AppSettings
import com.con11.a3dprinthelper.data.SettingField
import com.con11.a3dprinthelper.data.SettingsCodec
import com.con11.a3dprinthelper.data.SettingsRepository
import com.con11.a3dprinthelper.data.SettingsSchema
import com.con11.a3dprinthelper.data.SettingsSchemaRepository
import com.con11.a3dprinthelper.network.AnalysisResult
import com.con11.a3dprinthelper.network.BarkNotifier
import com.con11.a3dprinthelper.network.OpenAiVisionClient
import com.con11.a3dprinthelper.network.VisionStreamUpdate
import com.con11.a3dprinthelper.power.ShizukuScreenPowerController
import com.con11.a3dprinthelper.service.MonitoringService
import com.con11.a3dprinthelper.ui.AnalysisStage
import com.con11.a3dprinthelper.ui.MonitorUiState
import com.con11.a3dprinthelper.web.MonitorWebServer
import com.con11.a3dprinthelper.web.NetworkAddress
import com.con11.a3dprinthelper.web.BatteryStatus
import com.con11.a3dprinthelper.web.WifiStatus
import com.con11.a3dprinthelper.web.WebControlBridge
import java.net.BindException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class MonitorController private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val schemaRepository = SettingsSchemaRepository(appContext)
    private val settingsCodec = SettingsCodec(schemaRepository.schema)
    private val repository = SettingsRepository(appContext, settingsCodec)
    private val openAiClient = OpenAiVisionClient()
    private val barkNotifier = BarkNotifier()
    private val shizukuScreenPowerController = ShizukuScreenPowerController(appContext)
    private val mutableUiState = MutableStateFlow(MonitorUiState())
    private var latestFrameProvider: (() -> CapturedFrame?)? = null
    private var latestLumaProvider: (() -> Double?)? = null
    private var activeFrameSource: CameraFrameSource? = null
    private var lastGoodFrame: CapturedFrame? = null
    private var loopJob: Job? = null
    private var timedStopJob: Job? = null
    private var lastNotificationAtMillis = 0L
    private var lastNotificationKey = ""
    private var manualTorchOverride: Boolean? = null
    private var autoScreenOffTriggered = false
    private var webServer: MonitorWebServer? = null
    private var webServerPort: Int? = null
    private var foregroundVisible = false
    @Volatile private var keepAliveTemporarilyStopped = false
    private var webServerStarting = false
    private var webServerRetryJob: Job? = null

    val settings: StateFlow<AppSettings> = repository.settingsFlow.stateIn(
        scope,
        SharingStarted.Eagerly,
        AppSettings()
    )

    val uiState: StateFlow<MonitorUiState> = mutableUiState

    init {
        schemaRepository.errorMessage?.let { message ->
            mutableUiState.update { it.copy(errorMessage = message, statusMessage = "设置描述文件不可用，已使用安全默认值") }
        }
        scope.launch {
            settings.collect { currentSettings ->
                syncIdleTorch(currentSettings)
                syncService()
                syncWebServer()
            }
        }
    }

    fun setLatestFrameProvider(provider: (() -> CapturedFrame?)?) {
        latestFrameProvider = provider
    }

    fun setLatestLumaProvider(provider: (() -> Double?)?) {
        latestLumaProvider = provider
    }

    fun registerFrameSource(source: CameraFrameSource) {
        activeFrameSource = source
        mutableUiState.update {
            it.copy(
                cameraSource = source.name.apiName,
                cameraError = null
            )
        }
        refreshLastGoodFrame()
    }

    fun unregisterFrameSource(source: CameraFrameSource) {
        if (activeFrameSource === source || activeFrameSource?.name == source.name) {
            activeFrameSource = null
            mutableUiState.update { it.copy(cameraSource = "none") }
        }
    }

    fun reportCameraError(sourceName: CameraSourceName, message: String?) {
        mutableUiState.update {
            it.copy(
                cameraSource = sourceName.apiName,
                cameraError = message,
                errorMessage = message
            )
        }
    }

    fun latestWebFrame(): CapturedFrame? = refreshLastGoodFrame() ?: lastGoodFrame

    fun setForegroundVisible(visible: Boolean) {
        foregroundVisible = visible
        syncWebServer()
    }

    fun saveSettings(next: AppSettings) {
        scope.launch {
            syncIdleTorch(next)
            repository.save(next)
            mutableUiState.update { it.copy(statusMessage = "设置已保存", errorMessage = null) }
            delay(150)
            syncWebServer()
        }
    }

    fun currentSettings(): AppSettings = settings.value

    fun settingsSchema(): SettingsSchema = schemaRepository.schema

    fun settingsSchemaJson(): String = schemaRepository.schema.rawJson

    fun settingsJson(maskSecrets: Boolean): org.json.JSONObject =
        settingsCodec.toJson(currentSettings(), maskSecrets)

    fun defaultSettingsJson(): org.json.JSONObject = settingsCodec.defaultsJson()

    fun updateSettingsFromJson(values: org.json.JSONObject): AppSettings =
        settingsCodec.update(currentSettings(), values, preserveBlankSecrets = true)

    fun updateSettingValue(current: AppSettings, field: SettingField, value: Any?): AppSettings =
        settingsCodec.updateValue(current, field, value)

    fun settingValue(current: AppSettings, field: SettingField): Any =
        settingsCodec.getValue(current, field.key)

    fun currentUiState(): MonitorUiState = mutableUiState.value

    fun isForegroundVisible(): Boolean = foregroundVisible

    fun isKeepAliveTemporarilyStopped(): Boolean = keepAliveTemporarilyStopped

    fun reportKeepAliveServiceRunning(running: Boolean) {
        mutableUiState.update {
            it.copy(
                keepAliveServiceRunning = running,
                keepAliveTemporarilyStopped = keepAliveTemporarilyStopped
            )
        }
    }

    fun temporarilyStopKeepAliveService() {
        keepAliveTemporarilyStopped = true
        mutableUiState.update {
            it.copy(
                keepAliveServiceRunning = false,
                keepAliveTemporarilyStopped = true,
                statusMessage = "后台保活服务已临时停止"
            )
        }
        syncWebServer()
    }

    fun reportNotificationPermissionMissing() {
        mutableUiState.update {
            it.copy(errorMessage = "通知权限未授权，后台保活通知可能不可见")
        }
    }

    fun startMonitoring(durationMinutes: Int = 0) {
        if (loopJob?.isActive == true) return
        timedStopJob?.cancel()
        timedStopJob = null
        val normalizedDurationMinutes = durationMinutes.coerceIn(0, MAX_MONITORING_DURATION_MINUTES)
        val startedAtMillis = System.currentTimeMillis()
        val stopAtMillis = normalizedDurationMinutes.takeIf { it > 0 }
            ?.let { startedAtMillis + it * 60_000L }
        autoScreenOffTriggered = false
        mutableUiState.update {
            it.copy(
                isRunning = true,
                statusMessage = if (stopAtMillis == null) "巡检运行中" else "巡检运行中，已设置定时关闭",
                errorMessage = null,
                nextCaptureAtMillis = System.currentTimeMillis(),
                monitoringStartedAtMillis = startedAtMillis.takeIf { stopAtMillis != null },
                monitoringStopAtMillis = stopAtMillis
            )
        }
        if (stopAtMillis != null) {
            timedStopJob = scope.launch {
                delay((stopAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
                timedStopJob = null
                stopMonitoring("定时巡检已结束")
            }
        }
        syncService()
        syncWebServer()
        loopJob = scope.launch {
            while (true) {
                runAnalysis()
                val intervalMillis = settings.value.captureIntervalMinutes.coerceIn(1, 30) * 60_000L
                val nextAt = System.currentTimeMillis() + intervalMillis
                mutableUiState.update { it.copy(nextCaptureAtMillis = nextAt, statusMessage = "等待下一次巡检") }
                while (System.currentTimeMillis() < nextAt && mutableUiState.value.isRunning) {
                    delay(1_000)
                }
                if (!mutableUiState.value.isRunning) break
            }
        }
    }

    fun stopMonitoring() = stopMonitoring("巡检已暂停")

    private fun stopMonitoring(statusMessage: String) {
        timedStopJob?.cancel()
        timedStopJob = null
        loopJob?.cancel()
        loopJob = null
        if (autoScreenOffTriggered && mutableUiState.value.screenOff) {
            turnScreenOn()
        }
        autoScreenOffTriggered = false
        mutableUiState.update {
            it.copy(
                isRunning = false,
                isAnalyzing = false,
                torchEnabled = manualTorchOverride ?: false,
                nextCaptureAtMillis = null,
                monitoringStartedAtMillis = null,
                monitoringStopAtMillis = null,
                statusMessage = statusMessage
            )
        }
        syncService()
        syncWebServer()
    }

    fun analyzeNow() {
        scope.launch { runAnalysis() }
    }

    fun clearError() {
        mutableUiState.update { it.copy(errorMessage = null) }
    }

    fun testBarkPush() {
        scope.launch {
            runCatching {
                barkNotifier.send(settings.value, settings.value.barkTitleTemplate, "测试推送：3DPrintHelper 已连接 Bark")
            }.onSuccess {
                mutableUiState.update { it.copy(statusMessage = "Bark 测试推送已发送", errorMessage = null) }
            }.onFailure { error ->
                mutableUiState.update { it.copy(errorMessage = error.message ?: "Bark 测试推送失败") }
            }
        }
    }

    fun checkShizukuPermissionOnStartup() {
        scope.launch {
            val result = shizukuScreenPowerController.prepare()
            mutableUiState.update {
                if (result.success) {
                    it.copy(statusMessage = "Shizuku 已授权，息屏控制服务已就绪", errorMessage = null)
                } else {
                    it.copy(statusMessage = result.message ?: "Shizuku 未授权，息屏将使用黑屏兜底")
                }
            }
        }
    }

    fun toggleTorch() {
        val next = !(manualTorchOverride ?: mutableUiState.value.torchEnabled)
        manualTorchOverride = if (next) true else null
        activeFrameSource?.setTorchEnabled(next)
        mutableUiState.update {
            it.copy(
                torchEnabled = next,
                statusMessage = if (next) "闪光灯已开启" else "闪光灯已关闭，拍摄时允许自动补光",
                errorMessage = null
            )
        }
    }

    fun turnScreenOff() {
        scope.launch {
            val result = shizukuScreenPowerController.turnScreenOff()
            mutableUiState.update {
                if (result.success) {
                    it.copy(
                        screenOff = true,
                        screenSaverFallbackActive = false,
                        statusMessage = "屏幕已息屏",
                        errorMessage = null
                    )
                } else {
                    it.copy(
                        screenOff = true,
                        screenSaverFallbackActive = true,
                        statusMessage = "Shizuku 息屏失败，已使用黑屏兜底",
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    fun triggerAutoScreenOff() {
        autoScreenOffTriggered = true
        turnScreenOff()
    }

    fun turnScreenOn() {
        scope.launch {
            val shouldCallShizuku = mutableUiState.value.screenOff && !mutableUiState.value.screenSaverFallbackActive
            val result = if (shouldCallShizuku) shizukuScreenPowerController.turnScreenOn() else null
            autoScreenOffTriggered = false
            mutableUiState.update {
                it.copy(
                    screenOff = false,
                    screenSaverFallbackActive = false,
                    statusMessage = if (result?.success == false) "屏幕状态已恢复，Shizuku 点亮失败" else "屏幕已点亮",
                    errorMessage = result?.message
                )
            }
        }
    }

    fun webUrl(): String = NetworkAddress.localHttpUrl(settings.value.webPort.coerceIn(1024, 65535))

    fun syncService() {
        val shouldRunService = settings.value.keepAliveServiceEnabled && !keepAliveTemporarilyStopped
        val intent = Intent(appContext, MonitoringService::class.java)
        if (shouldRunService) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        } else {
            appContext.stopService(intent)
        }
    }

    fun syncWebServer(forceRestart: Boolean = false) {
        syncWebServerInternal(forceRestart = forceRestart, attempt = 0)
    }

    private fun syncWebServerInternal(forceRestart: Boolean = false, attempt: Int) {
        if (webServerStarting) return
        val port = settings.value.webPort.coerceIn(1024, 65535)
        val shouldRun = foregroundVisible || (settings.value.keepAliveServiceEnabled && !keepAliveTemporarilyStopped)
        if (!shouldRun) {
            stopWebServer()
            return
        }
        if (!forceRestart && webServer != null && webServerPort == port) return
        if (attempt == 0) {
            webServerRetryJob?.cancel()
            webServerRetryJob = null
        }
        if (webServer != null) {
            stopWebServer()
            if (forceRestart) {
                scope.launch {
                    delay(500)
                    syncWebServerInternal(forceRestart = false, attempt = 0)
                }
                return
            }
        }
        val bridge = WebControlBridge(
            getSettings = ::currentSettings,
            getSettingsSchemaJson = ::settingsSchemaJson,
            encodeSettings = ::settingsJson,
            getDefaultSettings = ::defaultSettingsJson,
            decodeSettings = ::updateSettingsFromJson,
            getUiState = ::currentUiState,
            getBatteryStatus = ::currentBatteryStatus,
            getWifiStatus = ::currentWifiStatus,
            getFrame = { latestWebFrame() },
            startMonitoring = ::startMonitoring,
            stopMonitoring = ::stopMonitoring,
            analyzeNow = ::analyzeNow,
            toggleTorch = ::toggleTorch,
            turnScreenOff = ::turnScreenOff,
            turnScreenOn = ::turnScreenOn,
            saveSettings = ::saveSettings,
            testBark = ::testBarkPush
        )
        webServerStarting = true
        runCatching {
            MonitorWebServer(bridge, port).also {
                it.start(5_000, false)
                webServer = it
                webServerPort = port
                webServerRetryJob?.cancel()
                webServerRetryJob = null
                mutableUiState.update { state -> state.copy(webUrl = NetworkAddress.localHttpUrl(port)) }
            }
        }.onFailure { error ->
            if (error.isAddressInUse() && attempt < WEB_START_MAX_RETRIES) {
                mutableUiState.update {
                    it.copy(
                        statusMessage = "Web 端口释放中，正在重试",
                        errorMessage = null,
                        webUrl = "Web 服务启动中：${NetworkAddress.localHttpUrl(port)}"
                    )
                }
                webServerRetryJob = scope.launch {
                    delay(500L * (attempt + 1))
                    syncWebServerInternal(forceRestart = false, attempt = attempt + 1)
                }
            } else {
                mutableUiState.update { it.copy(errorMessage = "Web 服务启动失败：${error.message}") }
            }
        }.also {
            webServerStarting = false
        }
    }

    private fun currentBatteryStatus(): BatteryStatus {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        return BatteryStatus(
            percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        )
    }

    @Suppress("DEPRECATION")
    private fun currentWifiStatus(): WifiStatus {
        val runtimePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(appContext, runtimePermission) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            return WifiStatus(connected = false, rssi = null, level = null, permissionGranted = false)
        }
        return runCatching {
            val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                    ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                    ?.transportInfo as? WifiInfo
            } else {
                wifiManager.connectionInfo
            }
            val rssi = info?.rssi?.takeUnless { it == INVALID_WIFI_RSSI }
            val connected = wifiManager.isWifiEnabled && info != null && rssi != null
            WifiStatus(
                connected = connected,
                rssi = if (connected) rssi else null,
                level = rssi?.takeIf { connected }?.let { WifiManager.calculateSignalLevel(it, WIFI_SIGNAL_LEVELS) },
                permissionGranted = true
            )
        }.getOrElse {
            WifiStatus(connected = false, rssi = null, level = null, permissionGranted = true)
        }
    }

    private fun stopWebServer() {
        webServerRetryJob?.cancel()
        webServerRetryJob = null
        webServer?.runCatchingClose()
        webServer = null
        webServerPort = null
        webServerStarting = false
    }

    private fun Throwable.isAddressInUse(): Boolean =
        this is BindException ||
            message?.contains("Address already in use", ignoreCase = true) == true ||
            cause?.isAddressInUse() == true

    private fun MonitorWebServer.runCatchingClose() {
        runCatching { closeAllConnections() }
        runCatching { stop() }
    }

    private suspend fun runAnalysis() {
        if (mutableUiState.value.isAnalyzing) return
        val currentSettings = settings.value
        val startedAt = System.currentTimeMillis()
        mutableUiState.update {
            it.copy(
                isAnalyzing = true,
                statusMessage = "正在准备分析",
                errorMessage = null,
                analysisStage = AnalysisStage.Preparing,
                analysisStartedAtMillis = startedAt,
                analysisFirstTokenAtMillis = null,
                analysisReceivedChars = 0
            )
        }
        val result = runCatching {
            val frame = captureFrameWithFlash(currentSettings)
            runCatching {
                openAiClient.analyze(frame.jpegBytes, currentSettings, ::onVisionStreamUpdate)
            }.recoverCatching { error ->
                if (mutableUiState.value.analysisReceivedChars > 0) throw error
                mutableUiState.update { state ->
                    state.copy(
                        analysisStage = AnalysisStage.Uploading,
                        statusMessage = "网络请求失败，正在复用本次照片重试"
                    )
                }
                delay(1_000)
                openAiClient.analyze(frame.jpegBytes, currentSettings, ::onVisionStreamUpdate)
            }.getOrThrow()
        }.onFailure {
            mutableUiState.update { state ->
                state.copy(
                    isAnalyzing = false,
                    torchEnabled = manualTorchOverride ?: false,
                    statusMessage = "分析失败",
                    errorMessage = it.message ?: "分析失败",
                    analysisStage = AnalysisStage.Failed
                )
            }
        }.getOrNull()

        if (result != null) {
            mutableUiState.update {
                it.copy(
                    isAnalyzing = false,
                    torchEnabled = manualTorchOverride ?: false,
                    lastAnalysisAtMillis = System.currentTimeMillis(),
                    lastResult = result,
                    statusMessage = result.summary,
                    errorMessage = null,
                    analysisStage = AnalysisStage.Completed
                )
            }
            maybeNotify(result, currentSettings)
        }
    }

    private suspend fun captureFrameWithFlash(settings: AppSettings): CapturedFrame {
        val luma = activeFrameSource?.latestAverageLuma() ?: latestLumaProvider?.invoke()
        val requestedTorch = manualTorchOverride ?: (settings.autoFlashEnabled && (luma ?: 255.0) < AUTO_FLASH_LUMA_THRESHOLD)
        val restoreTorch = manualTorchOverride ?: false
        val torchRequestedAtMillis = System.currentTimeMillis()
        val torchApplied = if (requestedTorch) requestTorchWithRetry(true) else false
        val useTorch = requestedTorch && torchApplied
        return try {
            mutableUiState.update {
                it.copy(
                    torchEnabled = useTorch,
                    statusMessage = when {
                        useTorch -> "补光已开启，正在等待曝光稳定"
                        requestedTorch -> "当前相机无法开启补光，正在取帧"
                        else -> "正在取帧"
                    },
                    analysisStage = if (useTorch) AnalysisStage.Lighting else AnalysisStage.Capturing
                )
            }
            if (useTorch) {
                delay(TORCH_EXPOSURE_SETTLE_MILLIS)
                waitForFrameAfter(torchRequestedAtMillis + TORCH_MIN_FRAME_AGE_MILLIS)
            }
            mutableUiState.update {
                it.copy(
                    statusMessage = "正在取帧并编码 JPEG",
                    analysisStage = AnalysisStage.Capturing
                )
            }
            val frame = withContext(Dispatchers.Default) {
                refreshLastGoodFrame()
            } ?: error("相机尚未提供可分析画面")
            mutableUiState.update {
                it.copy(
                    statusMessage = "拍照完成，正在上传",
                    analysisStage = AnalysisStage.Uploading
                )
            }
            frame
        } finally {
            if (useTorch || restoreTorch) {
                requestTorchWithRetry(restoreTorch)
            }
            mutableUiState.update { it.copy(torchEnabled = restoreTorch) }
            yield()
        }
    }

    private suspend fun requestTorchWithRetry(enabled: Boolean): Boolean {
        repeat(TORCH_REQUEST_ATTEMPTS) { attempt ->
            if (activeFrameSource?.setTorchEnabled(enabled) == true) return true
            if (attempt + 1 < TORCH_REQUEST_ATTEMPTS) delay(TORCH_REQUEST_RETRY_MILLIS)
        }
        return false
    }

    private suspend fun waitForFrameAfter(minTimestampMillis: Long) {
        val deadline = System.currentTimeMillis() + TORCH_FRESH_FRAME_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val timestamp = activeFrameSource?.latestFrameTimestampMillis()
            if (timestamp != null && timestamp >= minTimestampMillis) return
            delay(TORCH_FRAME_POLL_MILLIS)
        }
    }

    private fun onVisionStreamUpdate(update: VisionStreamUpdate) {
        mutableUiState.update { state ->
            when (update) {
                VisionStreamUpdate.Uploading -> state.copy(
                    analysisStage = AnalysisStage.Uploading,
                    statusMessage = "正在上传图片"
                )
                VisionStreamUpdate.WaitingForResponse -> state.copy(
                    analysisStage = AnalysisStage.WaitingForResponse,
                    statusMessage = "图片已发送，等待模型响应"
                )
                is VisionStreamUpdate.TextDelta -> {
                    state.copy(
                        analysisStage = AnalysisStage.Streaming,
                        statusMessage = "正在接收模型输出",
                        analysisFirstTokenAtMillis = state.analysisFirstTokenAtMillis ?: System.currentTimeMillis(),
                        analysisReceivedChars = update.receivedChars
                    )
                }
                VisionStreamUpdate.Parsing -> state.copy(
                    analysisStage = AnalysisStage.Parsing,
                    statusMessage = "模型输出完成，正在解析"
                )
                VisionStreamUpdate.FallingBackToNonStreaming -> state.copy(
                    analysisStage = AnalysisStage.WaitingForResponse,
                    statusMessage = "接口不支持流式输出，已切换兼容模式"
                )
            }
        }
    }

    private fun refreshLastGoodFrame(): CapturedFrame? {
        val frame = activeFrameSource?.latestFrame() ?: latestFrameProvider?.invoke()
        if (frame != null) {
            lastGoodFrame = frame
            mutableUiState.update {
                it.copy(
                    lastFrameAtMillis = frame.timestampMillis,
                    cameraSource = activeFrameSource?.name?.apiName ?: it.cameraSource,
                    cameraError = null
                )
            }
        }
        return frame
    }

    private fun syncIdleTorch(settings: AppSettings) {
        if (mutableUiState.value.isAnalyzing) return
        mutableUiState.update { it.copy(torchEnabled = manualTorchOverride ?: false) }
    }

    private suspend fun maybeNotify(result: AnalysisResult, settings: AppSettings) {
        if (!result.isAbnormal || settings.barkUrl.trim().length <= "https://api.day.app/".length) return
        val now = System.currentTimeMillis()
        val key = "${result.status}:${result.abnormalReasons.joinToString("|").ifBlank { result.summary }}"
        val cooldownMillis = settings.notificationCooldownMinutes.coerceAtLeast(1) * 60_000L
        if (key == lastNotificationKey && now - lastNotificationAtMillis < cooldownMillis) return

        val body = buildString {
            append(result.summary)
            if (result.abnormalReasons.isNotEmpty()) {
                append("\n")
                append(result.abnormalReasons.joinToString("；"))
            }
        }
        runCatching {
            barkNotifier.send(settings, settings.barkTitleTemplate.ifBlank { "3D 打印异常" }, body)
        }.onSuccess {
            lastNotificationKey = key
            lastNotificationAtMillis = now
        }.onFailure { error ->
            mutableUiState.update { it.copy(errorMessage = error.message ?: "Bark 推送失败") }
        }
    }

    companion object {
        private const val WEB_START_MAX_RETRIES = 8
        private const val WIFI_SIGNAL_LEVELS = 4
        private const val INVALID_WIFI_RSSI = -127
        private const val MAX_MONITORING_DURATION_MINUTES = 7 * 24 * 60
        private const val AUTO_FLASH_LUMA_THRESHOLD = 70.0
        private const val TORCH_EXPOSURE_SETTLE_MILLIS = 900L
        private const val TORCH_MIN_FRAME_AGE_MILLIS = 500L
        private const val TORCH_FRESH_FRAME_TIMEOUT_MILLIS = 1_500L
        private const val TORCH_FRAME_POLL_MILLIS = 50L
        private const val TORCH_REQUEST_ATTEMPTS = 15
        private const val TORCH_REQUEST_RETRY_MILLIS = 100L

        @Volatile private var instance: MonitorController? = null

        fun get(context: Context): MonitorController =
            instance ?: synchronized(this) {
                instance ?: MonitorController(context.applicationContext).also { instance = it }
            }
    }
}

private val CameraSourceName.apiName: String
    get() = when (this) {
        CameraSourceName.Foreground -> "foreground"
        CameraSourceName.Background -> "background"
    }
