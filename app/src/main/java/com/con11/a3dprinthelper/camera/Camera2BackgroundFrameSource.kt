package com.con11.a3dprinthelper.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.app.ActivityCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class Camera2BackgroundFrameSource(
    context: Context,
    private val jpegQualityProvider: () -> Int,
    private val torchEnabledProvider: () -> Boolean,
    private val onState: (Boolean, String?) -> Unit
) : CameraFrameSource {
    override val name = CameraSourceName.Background

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val latestFrame = AtomicReference<RawFrame?>(null)
    private val lastFrameTimestamp = AtomicLong(0L)
    private val lock = Any()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var running = false
    private var lastTorchEnabled = false
    private var consecutiveFrameErrors = 0

    override fun latestFrame(): CapturedFrame? {
        val frame = latestFrame.get() ?: return null
        val jpegBytes = frame.toJpeg(jpegQualityProvider().coerceIn(40, 100))
        return CapturedFrame(
            jpegBytes = jpegBytes,
            averageLuma = frame.averageLuma,
            timestampMillis = frame.timestampMillis
        )
    }

    override fun latestAverageLuma(): Double? = latestFrame.get()?.averageLuma

    fun lastFrameAtMillis(): Long = lastFrameTimestamp.get()

    fun start() {
        synchronized(lock) {
            if (running) return
            stopThreadLocked()
            running = true
            consecutiveFrameErrors = 0
            lastFrameTimestamp.set(0L)
            startThreadLocked()
        }
        openCamera()
    }

    fun stop() {
        synchronized(lock) {
            running = false
            closeCameraLocked()
            stopThreadLocked()
        }
        onState(false, null)
    }

    private fun startThreadLocked() {
        val thread = HandlerThread("Camera2Background")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopThreadLocked() {
        backgroundThread?.quitSafely()
        runCatching { backgroundThread?.join(1_000) }
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("后台相机权限未授权")
            return
        }
        val handler = backgroundHandler ?: run {
            fail("后台相机线程未启动")
            return
        }
        handler.post {
            runCatching {
                val cameraId = selectBackCameraId()
                cameraManager.openCamera(cameraId, cameraStateCallback, handler)
            }.onFailure { fail("后台相机打开失败：${it.readableMessage()}") }
        }
    }

    private fun selectBackCameraId(): String {
        val ids = cameraManager.cameraIdList
        return ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull() ?: error("未发现可用相机")
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            synchronized(lock) {
                if (!running) {
                    camera.close()
                    return
                }
                cameraDevice = camera
            }
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            synchronized(lock) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
            }
            fail("后台相机连接已断开")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            synchronized(lock) {
                camera.close()
                if (cameraDevice === camera) cameraDevice = null
            }
            fail("后台相机错误：${error.cameraErrorName()}")
        }

        override fun onClosed(camera: CameraDevice) {
            synchronized(lock) {
                if (cameraDevice === camera) cameraDevice = null
            }
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        val handler = backgroundHandler ?: return
        handler.post {
            runCatching {
                val size = selectAnalysisSize(camera.id)
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
                reader.setOnImageAvailableListener({ imageReader ->
                    val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    var frameError: Throwable? = null
                    try {
                        latestFrame.set(image.toRawFrame())
                        lastFrameTimestamp.set(System.currentTimeMillis())
                        consecutiveFrameErrors = 0
                        updateTorchIfNeeded()
                    } catch (error: Throwable) {
                        consecutiveFrameErrors += 1
                        if (consecutiveFrameErrors >= MAX_CONSECUTIVE_FRAME_ERRORS) {
                            frameError = error
                        }
                    } finally {
                        image.close()
                    }
                    if (frameError != null) {
                        handler.post {
                            fail("后台相机连续取帧转换失败：${frameError?.readableMessage() ?: "未知错误"}")
                        }
                    }
                }, handler)

                synchronized(lock) {
                    imageReader?.close()
                    imageReader = reader
                    lastTorchEnabled = torchEnabledProvider()
                    captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.FLASH_MODE, if (lastTorchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                    }
                }
                camera.createCaptureSession(listOf(reader.surface), captureSessionCallback, handler)
            }.onFailure { fail("后台相机取帧会话创建失败：${it.readableMessage()}") }
        }
    }

    private val captureSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val builder = synchronized(lock) {
                if (!running || cameraDevice == null) {
                    session.close()
                    return
                }
                captureSession = session
                captureRequestBuilder
            }
            runCatching {
                session.setRepeatingRequest(builder?.build() ?: return, null, backgroundHandler)
                onState(true, null)
            }.onFailure {
                fail("后台相机连续取帧启动失败：${it.readableMessage()}")
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            session.close()
            fail("后台相机会话配置失败")
        }

        override fun onClosed(session: CameraCaptureSession) {
            synchronized(lock) {
                if (captureSession === session) captureSession = null
            }
        }
    }

    private fun updateTorchIfNeeded() {
        val enabled = torchEnabledProvider()
        if (enabled == lastTorchEnabled) return
        val session = captureSession ?: return
        val builder = captureRequestBuilder ?: return
        lastTorchEnabled = enabled
        runCatching {
            builder.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        }
    }

    private fun selectAnalysisSize(cameraId: String): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return Size(1280, 960)
        return sizes
            .filter { it.width <= 1600 && it.height <= 1200 }
            .minByOrNull { abs((it.width.toFloat() / it.height) - (4f / 3f)) * 10_000 + abs(it.width - 1280) }
            ?: sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 960) }
            ?: sizes.first()
    }

    private fun fail(message: String) {
        synchronized(lock) {
            if (!running) return
            running = false
            closeCameraLocked()
        }
        onState(false, message)
    }

    private fun closeCameraLocked() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        captureSession = null
        captureRequestBuilder = null
        cameraDevice = null
        imageReader = null
    }

    private fun Image.toRawFrame(): RawFrame {
        val nv21 = yuv420ToNv21(this)
        return RawFrame(
            nv21Bytes = nv21,
            width = width,
            height = height,
            averageLuma = averageLuma(),
            timestampMillis = System.currentTimeMillis()
        )
    }

    private fun Image.averageLuma(): Double {
        val plane = planes.firstOrNull() ?: return 0.0
        val buffer = plane.buffer.duplicate()
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return 0.0
        var sum = 0L
        var count = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                sum += buffer.getOrZero(row * plane.rowStride + col * plane.pixelStride).toInt() and 0xFF
                count++
            }
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    private fun RawFrame.toJpeg(quality: Int): ByteArray {
        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { output ->
            yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, output)
            output.toByteArray()
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        copyPlane(
            buffer = image.planes[0].buffer,
            rowStride = image.planes[0].rowStride,
            pixelStride = image.planes[0].pixelStride,
            width = width,
            height = height,
            output = nv21,
            outputOffset = 0,
            outputPixelStride = 1
        )

        val vPlane = image.planes[2]
        val uPlane = image.planes[1]
        copyInterleavedUvPlane(
            vBuffer = vPlane.buffer,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            uBuffer = uPlane.buffer,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            width = width / 2,
            height = height / 2,
            output = nv21,
            outputOffset = ySize
        )
        return nv21
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val source = buffer.duplicate()
        for (rowIndex in 0 until height) {
            for (col in 0 until width) {
                val outputIndex = outputOffset + (rowIndex * width + col) * outputPixelStride
                val inputIndex = rowIndex * rowStride + col * pixelStride
                output[outputIndex] = source.getOrZero(inputIndex)
            }
        }
    }

    private fun copyInterleavedUvPlane(
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int
    ) {
        val vSource = vBuffer.duplicate()
        val uSource = uBuffer.duplicate()
        for (rowIndex in 0 until height) {
            for (col in 0 until width) {
                val outputIndex = outputOffset + (rowIndex * width + col) * 2
                output[outputIndex] = vSource.getOrZero(rowIndex * vRowStride + col * vPixelStride)
                output[outputIndex + 1] = uSource.getOrZero(rowIndex * uRowStride + col * uPixelStride)
            }
        }
    }

    private fun ByteBuffer.getOrZero(index: Int): Byte =
        if (index in 0 until limit()) get(index) else 0

    private fun Throwable.readableMessage(): String = message ?: javaClass.simpleName

    private fun Int.cameraErrorName(): String = when (this) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
        else -> "UNKNOWN($this)"
    }

    private data class RawFrame(
        val nv21Bytes: ByteArray,
        val width: Int,
        val height: Int,
        val averageLuma: Double,
        val timestampMillis: Long
    )

    private companion object {
        const val MAX_CONSECUTIVE_FRAME_ERRORS = 10
    }
}
