package com.con11.a3dprinthelper.network

import android.util.Base64
import com.con11.a3dprinthelper.data.AppSettings
import com.con11.a3dprinthelper.data.ImageDetail
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class OpenAiVisionClient {
    suspend fun analyze(imageJpeg: ByteArray, settings: AppSettings): AnalysisResult =
        withContext(Dispatchers.IO) {
            require(settings.openAiApiKey.isNotBlank()) { "请先填写 OpenAI API Key" }

            val client = OkHttpClient.Builder()
                .connectTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()

            val requestJson = buildRequestJson(imageJpeg, settings)
            val request = Request.Builder()
                .url("${settings.openAiBaseUrl.trimEnd('/')}/responses")
                .addHeader("Authorization", "Bearer ${settings.openAiApiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("OpenAI 请求失败 ${response.code}: ${body.take(240)}")
                }
                parseResponse(body)
            }
        }

    private fun buildRequestJson(imageJpeg: ByteArray, settings: AppSettings): JSONObject {
        val base64 = Base64.encodeToString(imageJpeg, Base64.NO_WRAP)
        val imageUrl = "data:image/jpeg;base64,$base64"
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", settings.prompt))
            .put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", imageUrl)
                    .put("detail", settings.imageDetail.apiValue)
            )
        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", content)
        )
        return JSONObject()
            .put("model", settings.openAiModel.ifBlank { "gpt-4.1-mini" })
            .put("input", input)
    }

    private fun parseResponse(body: String): AnalysisResult {
        val root = JSONObject(body)
        val text = root.optString("output_text").ifBlank { extractOutputText(root) }
        val jsonText = extractJsonObject(text)
        return try {
            val json = JSONObject(jsonText)
            AnalysisResult(
                status = json.optString("status").toStatus(),
                confidence = json.optDouble("confidence", 0.0),
                summary = json.optString("summary", text.take(180)).ifBlank { text.take(180) },
                abnormalReasons = json.optJSONArray("abnormalReasons").toStringList(),
                shouldNotify = json.optBoolean("shouldNotify", false),
                rawText = text
            )
        } catch (_: JSONException) {
            AnalysisResult(
                status = PrintStatus.Unknown,
                summary = text.ifBlank { "模型未返回可读内容" }.take(240),
                rawText = text
            )
        }
    }

    private fun extractOutputText(root: JSONObject): String {
        val output = root.optJSONArray("output") ?: return ""
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val contentItem = content.optJSONObject(j) ?: continue
                val type = contentItem.optString("type")
                if (type == "output_text" || type == "text") {
                    parts += contentItem.optString("text")
                }
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun extractJsonObject(text: String): String {
        val withoutFence = text
            .replace("```json", "")
            .replace("```JSON", "")
            .replace("```", "")
            .trim()
        val start = withoutFence.indexOf('{')
        val end = withoutFence.lastIndexOf('}')
        return if (start >= 0 && end > start) withoutFence.substring(start, end + 1) else withoutFence
    }

    private fun String.toStatus(): PrintStatus = when (lowercase()) {
        "normal" -> PrintStatus.Normal
        "warning" -> PrintStatus.Warning
        "abnormal" -> PrintStatus.Abnormal
        else -> PrintStatus.Unknown
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private val ImageDetail.apiValue: String
        get() = when (this) {
            ImageDetail.Low -> "low"
            ImageDetail.Auto -> "auto"
            ImageDetail.High -> "high"
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
