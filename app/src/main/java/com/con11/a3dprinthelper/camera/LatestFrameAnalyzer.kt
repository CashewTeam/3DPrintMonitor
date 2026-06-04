package com.con11.a3dprinthelper.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class LatestFrameAnalyzer(
    private val jpegQualityProvider: () -> Int
) : ImageAnalysis.Analyzer {
    private val latestFrame = AtomicReference<RawFrame?>(null)
    private val latestCompressedFrame = AtomicReference<CompressedFrame?>(null)

    override fun analyze(image: ImageProxy) {
        try {
            val luma = image.averageLuma()
            latestFrame.set(
                RawFrame(
                    nv21Bytes = yuv420ToNv21(image),
                    width = image.width,
                    height = image.height,
                    averageLuma = luma,
                    timestampMillis = System.currentTimeMillis()
                )
            )
        } catch (_: Throwable) {
            // Camera buffers vary across older Android devices. Drop a bad frame
            // instead of letting the analyzer thread crash the process.
        } finally {
            image.close()
        }
    }

    fun latest(): CapturedFrame? {
        val frame = latestFrame.get() ?: return null
        val quality = jpegQualityProvider().coerceIn(40, 100)
        val cached = latestCompressedFrame.get()
        if (cached?.timestampMillis == frame.timestampMillis && cached.quality == quality) {
            return cached.frame
        }
        return CapturedFrame(
            jpegBytes = frame.toJpeg(quality),
            averageLuma = frame.averageLuma,
            timestampMillis = frame.timestampMillis
        ).also {
            latestCompressedFrame.set(CompressedFrame(frame.timestampMillis, quality, it))
        }
    }

    fun latestAverageLuma(): Double? = latestFrame.get()?.averageLuma

    fun latestTimestampMillis(): Long? = latestFrame.get()?.timestampMillis

    private fun ImageProxy.averageLuma(): Double {
        val buffer = planes.firstOrNull()?.buffer?.duplicate() ?: return 0.0
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        if (data.isEmpty()) return 0.0
        return data.fold(0L) { sum, byte -> sum + (byte.toInt() and 0xFF) }.toDouble() / data.size
    }

    private fun RawFrame.toJpeg(quality: Int): ByteArray {
        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
        return ByteArrayOutputStream().use { output ->
            yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, output)
            output.toByteArray()
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
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

    private data class RawFrame(
        val nv21Bytes: ByteArray,
        val width: Int,
        val height: Int,
        val averageLuma: Double,
        val timestampMillis: Long
    )

    private data class CompressedFrame(
        val timestampMillis: Long,
        val quality: Int,
        val frame: CapturedFrame
    )
}
