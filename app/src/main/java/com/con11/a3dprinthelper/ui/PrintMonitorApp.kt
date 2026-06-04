package com.con11.a3dprinthelper.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.con11.a3dprinthelper.camera.CameraPreview
import com.con11.a3dprinthelper.camera.CameraFrameSource
import com.con11.a3dprinthelper.camera.CameraSourceName
import com.con11.a3dprinthelper.camera.CapturedFrame
import com.con11.a3dprinthelper.camera.LatestFrameAnalyzer
import com.con11.a3dprinthelper.data.AppSettings
import com.con11.a3dprinthelper.data.DEFAULT_PROMPT
import com.con11.a3dprinthelper.data.SettingControlType
import com.con11.a3dprinthelper.data.SettingField
import com.con11.a3dprinthelper.data.SettingsSchema
import com.con11.a3dprinthelper.network.PrintStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class AppPage {
    Preview,
    Settings
}

@Composable
fun PrintMonitorApp(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var page by remember { mutableStateOf(AppPage.Preview) }
    val analyzer = remember { LatestFrameAnalyzer { viewModel.settings.value.jpegQuality } }
    val foregroundFrameSource = remember(analyzer) {
        object : CameraFrameSource {
            override val name = CameraSourceName.Foreground
            override fun latestFrame(): CapturedFrame? = analyzer.latest()
            override fun latestAverageLuma(): Double? = analyzer.latestAverageLuma()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = context.findActivity()
    fun activateScreenSaver() = viewModel.turnScreenOff()

    fun wakeScreen() = viewModel.turnScreenOn()

    LaunchedEffect(analyzer) {
        viewModel.setLatestFrameProvider { analyzer.latest() }
        viewModel.setLatestLumaProvider { analyzer.latestAverageLuma() }
        viewModel.registerFrameSource(foregroundFrameSource)
    }

    LaunchedEffect(Unit) {
        viewModel.checkShizukuPermissionOnStartup()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setLatestFrameProvider { analyzer.latest() }
                    viewModel.setLatestLumaProvider { analyzer.latestAverageLuma() }
                    viewModel.registerFrameSource(foregroundFrameSource)
                    viewModel.setForegroundVisible(true)
                    viewModel.syncService()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.unregisterFrameSource(foregroundFrameSource)
                    viewModel.setForegroundVisible(false)
                    viewModel.syncService()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setForegroundVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.unregisterFrameSource(foregroundFrameSource)
            viewModel.setForegroundVisible(false)
            viewModel.syncService()
        }
    }

    LaunchedEffect(Unit) {
        VolumeWakeEvents.events.collect {
            wakeScreen()
        }
    }

    LaunchedEffect(uiState.isRunning, page, settings.autoScreenOffEnabled) {
        if (uiState.isRunning && page == AppPage.Preview && settings.autoScreenOffEnabled) {
            kotlinx.coroutines.delay(10_000)
            if (uiState.isRunning && page == AppPage.Preview && settings.autoScreenOffEnabled && !uiState.screenOff) {
                viewModel.triggerAutoScreenOff()
            }
        } else if (uiState.screenOff && uiState.screenSaverFallbackActive) {
            wakeScreen()
        }
    }

    DisposableEffect(activity, uiState.screenSaverFallbackActive, uiState.screenOff) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val hadKeepScreenOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val wakeLock = if ((uiState.screenSaverFallbackActive || uiState.screenOff) && activity != null) {
            val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "3DPrintHelper:ScreenSaver").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            null
        }
        if (uiState.screenSaverFallbackActive && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = 0f
            }
        }
        onDispose {
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = originalBrightness
                }
                if (!hadKeepScreenOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            when (page) {
                AppPage.Preview -> PreviewPage(
                    settings = settings,
                    uiState = uiState,
                    webUrl = uiState.webUrl.ifBlank { viewModel.currentSettings().let { "http://手机IP:${it.webPort}/" } },
                    analyzer = analyzer,
                    onStartStop = {
                        if (uiState.isRunning) {
                            wakeScreen()
                            viewModel.stopMonitoring()
                        } else {
                            viewModel.startMonitoring()
                        }
                    },
                    onAnalyzeNow = viewModel::analyzeNow,
                    onToggleTorch = viewModel::toggleTorch,
                    onOpenSettings = {
                        wakeScreen()
                        page = AppPage.Settings
                    },
                    onScreenSaver = { if (uiState.screenOff) wakeScreen() else activateScreenSaver() },
                    onCameraError = { viewModel.clearError() }
                )

                AppPage.Settings -> SettingsPage(
                    settings = settings,
                    schema = viewModel.settingsSchema(),
                    settingValue = viewModel::settingValue,
                    updateSettingValue = viewModel::updateSettingValue,
                    onBack = { page = AppPage.Preview },
                    onSave = {
                        viewModel.saveSettings(it)
                        page = AppPage.Preview
                    },
                    onTestBark = viewModel::testBarkPush
                )
            }
        }

        if (uiState.screenSaverFallbackActive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        wakeScreen()
                    }
            )
        }
    }
}

@Composable
private fun PreviewPage(
    settings: AppSettings,
    uiState: MonitorUiState,
    webUrl: String,
    analyzer: LatestFrameAnalyzer,
    onStartStop: () -> Unit,
    onAnalyzeNow: () -> Unit,
    onToggleTorch: () -> Unit,
    onOpenSettings: () -> Unit,
    onScreenSaver: () -> Unit,
    onCameraError: (Throwable) -> Unit
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(3f)
                .fillMaxHeight()
                .aspectRatio(4f / 3f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            CameraPreview(
                analyzer = analyzer,
                torchEnabled = uiState.torchEnabled,
                onCameraError = onCameraError,
                modifier = Modifier.fillMaxSize()
            )
            CameraStatusOverlay(
                settings = settings,
                uiState = uiState,
                webUrl = webUrl,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }

        ControlPanel(
            uiState = uiState,
            onStartStop = onStartStop,
            onAnalyzeNow = onAnalyzeNow,
            onToggleTorch = onToggleTorch,
            onOpenSettings = onOpenSettings,
            onScreenSaver = onScreenSaver,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun CameraStatusOverlay(
    settings: AppSettings,
    uiState: MonitorUiState,
    webUrl: String,
    modifier: Modifier = Modifier
) {
    val bg = when (uiState.lastResult?.status) {
        PrintStatus.Abnormal -> Color(0xD9B3261E)
        PrintStatus.Warning -> Color(0xD9B45F06)
        PrintStatus.Normal -> Color(0xD90F6B45)
        else -> Color(0xCC101418)
    }
    Column(
        modifier
            .width(430.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("3D 打印巡检", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (uiState.isRunning) "运行中" else "已暂停",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = countdownText(uiState.nextCaptureAtMillis),
                color = Color(0xFFE6EDF3),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${settings.captureIntervalMinutes} 分钟 · 自动闪光灯${if (settings.autoFlashEnabled) "开" else "关"}",
                color = Color(0xFFB8C3CC),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = uiState.lastResult?.summary ?: uiState.statusMessage,
            color = Color(0xFFE6EDF3),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OverlayMetric("最近", uiState.lastAnalysisAtMillis?.formatTime() ?: "尚未分析")
            OverlayMetric("模型", settings.openAiModel)
            OverlayMetric("补光", if (uiState.torchEnabled) "开" else "关")
        }
        Text(
            text = "Web $webUrl",
            color = Color(0xFF7DD3FC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
        uiState.lastResult?.let { result ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OverlayMetric("状态", result.status.name)
                OverlayMetric("置信度", "%.2f".format(result.confidence))
            }
            if (result.abnormalReasons.isNotEmpty()) {
                Text(
                    text = "异常原因：${result.abnormalReasons.joinToString("；")}",
                    color = Color(0xFFFFD1C9),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        uiState.errorMessage?.let {
            Text(
                text = it,
                color = Color(0xFFFFB4AB),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun OverlayMetric(label: String, value: String) {
    Text(
        text = "$label $value",
        color = Color(0xFFD6E1EA),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ControlPanel(
    uiState: MonitorUiState,
    onStartStop: () -> Unit,
    onAnalyzeNow: () -> Unit,
    onToggleTorch: () -> Unit,
    onOpenSettings: () -> Unit,
    onScreenSaver: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B2229))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = onStartStop, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.isRunning) "暂停巡检" else "开始巡检")
        }
        OutlinedButton(
            onClick = onAnalyzeNow,
            enabled = !uiState.isAnalyzing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isAnalyzing) "分析中..." else "立即分析")
        }
        OutlinedButton(onClick = onToggleTorch, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.torchEnabled) "关闭闪光灯" else "开启闪光灯")
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("设置")
        }
        OutlinedButton(onClick = onScreenSaver, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.screenOff) "点亮屏幕" else "息屏")
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SettingsPage(
    settings: AppSettings,
    schema: SettingsSchema,
    settingValue: (AppSettings, SettingField) -> Any,
    updateSettingValue: (AppSettings, SettingField, Any?) -> AppSettings,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onTestBark: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text("调整拍照、模型提示词和推送。", color = Color(0xFFB8C3CC))
            Button(
                onClick = { onSave(draft) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
            OutlinedButton(
                onClick = onBack,
                colors = darkOutlinedButtonColors(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("返回") }
            OutlinedButton(
                onClick = onTestBark,
                colors = darkOutlinedButtonColors(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("测试 Bark") }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            schema.sections.forEach { section ->
                SettingsSection(section.title) {
                    section.fields.forEach { field ->
                        SchemaSettingField(
                            field = field,
                            value = settingValue(draft, field),
                            onChange = { value -> draft = updateSettingValue(draft, field, value) },
                            onResetDefaultPrompt = { draft = updateSettingValue(draft, field, DEFAULT_PROMPT) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemaSettingField(
    field: SettingField,
    value: Any,
    onChange: (Any?) -> Unit,
    onResetDefaultPrompt: () -> Unit
) {
    when (field.type) {
        SettingControlType.Slider -> NumberSlider(
            label = field.label,
            value = value as Int,
            min = field.min ?: 0,
            max = field.max ?: 100,
            suffix = field.unit,
            onChange = onChange
        )
        SettingControlType.Number -> SchemaNumberField(field, value as Int, onChange)
        SettingControlType.Text -> SimpleField(field.label, value.toString(), onChange)
        SettingControlType.Secret -> SecretField(field.label, value.toString(), onChange)
        SettingControlType.Textarea -> {
            OutlinedTextField(
                value = value.toString(),
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().height((field.rows * 24).coerceAtLeast(120).dp),
                label = { Text(field.label) },
                colors = darkTextFieldColors()
            )
            if (field.resetAction == "defaultPrompt") {
                TextButton(onClick = onResetDefaultPrompt) { Text("恢复默认提示词") }
            }
        }
        SettingControlType.Enum -> EnumRadioRow(
            items = field.options,
            selected = value.toString(),
            label = { it.label },
            value = { it.value },
            onSelect = onChange
        )
        SettingControlType.Boolean -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = value as Boolean, onCheckedChange = onChange)
            Text(field.label, color = Color.White)
        }
    }
}

@Composable
private fun SchemaNumberField(field: SettingField, value: Int, onChange: (Any?) -> Unit) {
    var text by remember(field.key) { mutableStateOf(value.toString()) }
    NumberField(field.label, text) {
        text = it
        it.toIntOrNull()?.let(onChange)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2229)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberSlider(label: String, value: Int, min: Int, max: Int, suffix: String, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White)
            Text("$value$suffix", color = Color(0xFFB8C3CC))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF7DD3FC),
                activeTrackColor = Color(0xFF7DD3FC),
                inactiveTrackColor = Color(0xFF44515C)
            )
        )
    }
}

@Composable
private fun SimpleField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        colors = darkTextFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            onChange(text.filter { it.isDigit() })
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = darkTextFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        colors = darkTextFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun <T> EnumRadioRow(
    items: List<T>,
    selected: String,
    label: (T) -> String,
    value: (T) -> String,
    onSelect: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected == value(item),
                    onClick = { onSelect(value(item)) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color(0xFF7DD3FC),
                        unselectedColor = Color(0xFFB8C3CC)
                    )
                )
                Text(label(item), color = Color.White)
            }
        }
    }
}

@Composable
private fun darkOutlinedButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = Color(0xFFE6EDF3),
    disabledContentColor = Color(0xFF60717E)
)

@Composable
private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color(0xFF8A98A3),
    focusedLabelColor = Color(0xFF7DD3FC),
    unfocusedLabelColor = Color(0xFFB8C3CC),
    cursorColor = Color(0xFF7DD3FC),
    focusedBorderColor = Color(0xFF7DD3FC),
    unfocusedBorderColor = Color(0xFF60717E),
    focusedContainerColor = Color(0xFF101820),
    unfocusedContainerColor = Color(0xFF101820),
    disabledContainerColor = Color(0xFF101820)
)

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF91A2AF), maxLines = 1)
        Text(value, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
}

private fun countdownText(nextAtMillis: Long?): String {
    if (nextAtMillis == null) return "下次：--:--"
    val remaining = max(0L, nextAtMillis - System.currentTimeMillis())
    val minutes = remaining / 60_000
    val seconds = (remaining % 60_000) / 1_000
    return "下次：%02d:%02d".format(minutes, seconds)
}

private fun Long.formatTime(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(this))
