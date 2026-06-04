package com.con11.a3dprinthelper.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.con11.a3dprinthelper.network.PrintStatus
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import org.json.JSONObject

class MonitorWebServer(
    private val bridge: WebControlBridge,
    private val portNumber: Int = DEFAULT_PORT
) : NanoHTTPD(portNumber) {
    init {
        setServerSocketFactory {
            ServerSocket().apply {
                reuseAddress = true
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/" -> htmlResponse(WebConsolePage.html)
                session.method == Method.GET && session.uri == "/stream.mjpg" -> mjpegResponse()
                session.method == Method.GET && session.uri == "/api/status" -> jsonResponse(statusJson().toString())
                session.method == Method.GET && session.uri == "/api/settings/schema" -> jsonResponse(bridge.settingsSchemaJson())
                session.method == Method.GET && session.uri == "/api/settings/defaults" -> jsonResponse(bridge.defaultSettingsJson().toString())
                session.method == Method.GET && session.uri == "/api/settings" -> jsonResponse(settingsJson(maskSecrets = true).toString())
                session.method == Method.POST && session.uri == "/api/control" -> handleControl(session)
                session.method == Method.POST && session.uri == "/api/settings" -> handleSettings(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (throwable: Throwable) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                throwable.message ?: "Server error"
            )
        }
    }

    private fun handleControl(session: IHTTPSession): Response {
        val body = session.bodyText()
        val json = JSONObject(body.ifBlank { "{}" })
        val action = json.optString("action")
        bridge.control(action, json.optInt("durationMinutes", 0))
        return jsonResponse(JSONObject().put("ok", true).toString())
    }

    private fun handleSettings(session: IHTTPSession): Response {
        val body = session.bodyText()
        val json = JSONObject(body.ifBlank { "{}" })
        val next = bridge.decodeSettings(json)
        bridge.updateSettings(next)
        return jsonResponse(
            JSONObject()
                .put("ok", true)
                .put("reloadUrl", NetworkAddress.localHttpUrl(next.webPort))
                .put("reloadAfterMs", SETTINGS_RELOAD_DELAY_MS)
                .toString()
        )
    }

    private fun settingsJson(maskSecrets: Boolean): JSONObject = bridge.settingsJson(maskSecrets)

    private fun statusJson(): JSONObject {
        val ui = bridge.uiState()
        val settings = bridge.settings()
        val battery = bridge.batteryStatus()
        val wifi = bridge.wifiStatus()
        val result = ui.lastResult
        return JSONObject()
            .put("isRunning", ui.isRunning)
            .put("isAnalyzing", ui.isAnalyzing)
            .put("analysisStage", ui.analysisStage.name)
            .put("analysisStageLabel", ui.analysisStage.displayName)
            .put("analysisStartedAtMillis", ui.analysisStartedAtMillis ?: JSONObject.NULL)
            .put("analysisFirstTokenAtMillis", ui.analysisFirstTokenAtMillis ?: JSONObject.NULL)
            .put("analysisReceivedChars", ui.analysisReceivedChars)
            .put("nextCaptureAtMillis", ui.nextCaptureAtMillis ?: JSONObject.NULL)
            .put("monitoringStartedAtMillis", ui.monitoringStartedAtMillis ?: JSONObject.NULL)
            .put("monitoringStopAtMillis", ui.monitoringStopAtMillis ?: JSONObject.NULL)
            .put("lastAnalysisAtMillis", ui.lastAnalysisAtMillis ?: JSONObject.NULL)
            .put("statusMessage", ui.statusMessage)
            .put("errorMessage", ui.errorMessage ?: JSONObject.NULL)
            .put("torchEnabled", ui.torchEnabled)
            .put("screenOff", ui.screenOff)
            .put("screenSaverFallbackActive", ui.screenSaverFallbackActive)
            .put("cameraSource", ui.cameraSource)
            .put("lastFrameAtMillis", ui.lastFrameAtMillis ?: JSONObject.NULL)
            .put("cameraError", ui.cameraError ?: JSONObject.NULL)
            .put("keepAliveServiceRunning", ui.keepAliveServiceRunning)
            .put("keepAliveTemporarilyStopped", ui.keepAliveTemporarilyStopped)
            .put("batteryPercent", battery.percent ?: JSONObject.NULL)
            .put("batteryCharging", battery.charging)
            .put("wifiConnected", wifi.connected)
            .put("wifiRssi", wifi.rssi ?: JSONObject.NULL)
            .put("wifiSignalLevel", wifi.level ?: JSONObject.NULL)
            .put("wifiPermissionGranted", wifi.permissionGranted)
            .put("webUrl", NetworkAddress.localHttpUrl(portNumber))
            .put("webPreviewScalePercent", settings.webPreviewScalePercent)
            .put("webPreviewFps", settings.webPreviewFps)
            .put("settings", settingsJson(maskSecrets = true))
            .put(
                "lastResult",
                if (result == null) {
                    JSONObject.NULL
                } else {
                    JSONObject()
                        .put("status", result.status.name)
                        .put("confidence", result.confidence)
                        .put("summary", result.summary)
                        .put("shouldNotify", result.shouldNotify)
                        .put("abnormalReasons", result.abnormalReasons)
                }
            )
            .put("printStatus", result?.status?.name ?: PrintStatus.Unknown.name)
    }

    private fun mjpegResponse(): Response {
        val stream = MjpegInputStream(
            frameProvider = { bridge.latestFrame()?.jpegBytes },
            fpsProvider = { bridge.settings().webPreviewFps.coerceIn(1, 10) },
            scalePercentProvider = { bridge.settings().webPreviewScalePercent.coerceIn(20, 100) }
        )
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY", stream)
    }

    private fun htmlResponse(html: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)

    private fun jsonResponse(json: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
            .apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Access-Control-Allow-Origin", "*")
            }

    private fun IHTTPSession.bodyText(): String {
        val length = headers["content-length"]?.toIntOrNull() ?: headers["Content-Length"]?.toIntOrNull()
        if (length != null && length > 0) {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = inputStream.read(bytes, offset, length - offset)
                if (read <= 0) break
                offset += read
            }
            return bytes.copyOf(offset).toString(Charsets.UTF_8)
        }

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private class MjpegInputStream(
        private val frameProvider: () -> ByteArray?,
        private val fpsProvider: () -> Int,
        private val scalePercentProvider: () -> Int
    ) : InputStream() {
        private var buffer = ByteArray(0)
        private var index = 0
        private var lastFrameAt = 0L

        override fun read(): Int {
            if (index >= buffer.size) {
                refill()
            }
            if (buffer.isEmpty()) return -1
            return buffer[index++].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (index >= buffer.size) {
                refill()
            }
            if (buffer.isEmpty()) return -1
            val count = minOf(length, buffer.size - index)
            System.arraycopy(buffer, index, bytes, offset, count)
            index += count
            return count
        }

        private fun refill() {
            val waitMillis = (1000L / fpsProvider().coerceAtLeast(1)) - (System.currentTimeMillis() - lastFrameAt)
            if (waitMillis > 0) {
                Thread.sleep(waitMillis)
            }
            val frame = frameProvider()?.scaled(scalePercentProvider()) ?: PLACEHOLDER_JPEG
            val header = "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                .toByteArray()
            val footer = "\r\n".toByteArray()
            buffer = header + frame + footer
            index = 0
            lastFrameAt = System.currentTimeMillis()
        }

        private fun ByteArray.scaled(scalePercent: Int): ByteArray {
            if (scalePercent >= 100) return this
            val bitmap = BitmapFactory.decodeByteArray(this, 0, size) ?: return this
            val scale = scalePercent.coerceIn(20, 100) / 100f
            val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 82, output)
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
            return output.toByteArray()
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val SETTINGS_RELOAD_DELAY_MS = 1_500
        private const val MJPEG_BOUNDARY = "printhelperframe"

        private val PLACEHOLDER_JPEG = android.util.Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAEFAqf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/ASP/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/ASP/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAY/Al//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/IV//2gAMAwEAAgADAAAAEP/EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQMBAT8QH//EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQIBAT8QH//EABQQAQAAAAAAAAAAAAAAAAAAABD/2gAIAQEAAT8QH//Z",
            android.util.Base64.DEFAULT
        )
    }
}
