package com.con11.a3dprinthelper.camera

import android.annotation.SuppressLint
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@SuppressLint("RestrictedApi")
@Composable
fun CameraPreview(
    analyzer: LatestFrameAnalyzer,
    torchEnabled: Boolean,
    modifier: Modifier = Modifier,
    onCameraReady: (Camera) -> Unit = {},
    onCameraError: (Throwable) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val previewUseCaseRef = remember { mutableStateOf<Preview?>(null) }
    val analysisUseCaseRef = remember { mutableStateOf<ImageAnalysis?>(null) }
    val bindGeneration = remember { mutableStateOf(0) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            val provider = cameraProviderRef.value
            val preview = previewUseCaseRef.value
            val analysis = analysisUseCaseRef.value
            if (provider != null && preview != null && analysis != null) {
                provider.unbind(preview, analysis)
            }
            cameraExecutor.shutdown()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> bindGeneration.value += 1
                Lifecycle.Event.ON_PAUSE -> {
                    val provider = cameraProviderRef.value
                    val preview = previewUseCaseRef.value
                    val analysis = analysisUseCaseRef.value
                    if (provider != null && preview != null && analysis != null) {
                        provider.unbind(preview, analysis)
                    }
                    cameraRef.value = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(previewView, lifecycleOwner, analyzer, bindGeneration.value) {
        delay(FOREGROUND_CAMERA_HANDOFF_DELAY_MS)
        val cameraProvider = runCatching {
            withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
        }.getOrElse {
            onCameraError(it)
            return@LaunchedEffect
        }
        var lastError: Throwable? = null
        repeat(FOREGROUND_CAMERA_BIND_ATTEMPTS) { attempt ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                return@LaunchedEffect
            }
            try {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                previewUseCaseRef.value?.let { cameraProvider.unbind(it) }
                analysisUseCaseRef.value?.let { cameraProvider.unbind(it) }
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                cameraProviderRef.value = cameraProvider
                previewUseCaseRef.value = preview
                analysisUseCaseRef.value = imageAnalysis
                cameraRef.value = camera
                onCameraReady(camera)
                return@LaunchedEffect
            } catch (throwable: Throwable) {
                lastError = throwable
                if (attempt + 1 < FOREGROUND_CAMERA_BIND_ATTEMPTS) {
                    delay(FOREGROUND_CAMERA_BIND_RETRY_DELAY_MS)
                }
            }
        }
        lastError?.let(onCameraError)
    }

    LaunchedEffect(torchEnabled) {
        cameraRef.value?.cameraControl?.enableTorch(torchEnabled)
    }
}

private const val FOREGROUND_CAMERA_HANDOFF_DELAY_MS = 500L
private const val FOREGROUND_CAMERA_BIND_RETRY_DELAY_MS = 700L
private const val FOREGROUND_CAMERA_BIND_ATTEMPTS = 4
