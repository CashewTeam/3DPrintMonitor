package com.con11.a3dprinthelper.network

import com.con11.a3dprinthelper.data.AppSettings
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class BarkNotifier {
    suspend fun send(settings: AppSettings, title: String, body: String) = withContext(Dispatchers.IO) {
        require(settings.barkUrl.trim().length > "https://api.day.app/".length) {
            "请先填写 Bark URL，例如 https://api.day.app/xxxxxxxx/"
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        val url = buildBarkUrl(settings.barkUrl, title, body)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bark 推送失败 ${response.code}: ${responseBody.take(180)}")
            }
        }
    }

    private fun buildBarkUrl(baseUrl: String, title: String, body: String): String {
        val normalizedBase = baseUrl.trim().trimEnd('/') + "/"
        return normalizedBase +
            encodePathSegment(title) +
            "/" +
            encodePathSegment(body) +
            "?group=3DPrintHelper"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
