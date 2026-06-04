package com.con11.a3dprinthelper

import com.con11.a3dprinthelper.data.AppSettings
import com.con11.a3dprinthelper.data.SettingsCodec
import com.con11.a3dprinthelper.data.SettingsSchemaRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsCodecTest {
    private val schema = SettingsSchemaRepository.parse(
        File("src/main/assets/settings_schema.json").readText()
    )
    private val codec = SettingsCodec(schema)

    @Test
    fun schemaContainsEveryAppSetting() {
        val keys = schema.fields.map { it.key }.toSet()
        assertEquals(
            setOf(
                "captureIntervalMinutes", "jpegQuality", "requestTimeoutSeconds",
                "notificationCooldownMinutes", "autoFlashEnabled", "openAiApiKey", "openAiModel",
                "openAiBaseUrl", "imageDetail", "prompt", "barkUrl", "barkTitleTemplate",
                "webPort", "keepAliveServiceEnabled", "autoScreenOffEnabled", "webAliveInBackground",
                "webPreviewScalePercent", "webPreviewFps"
            ),
            keys
        )
    }

    @Test
    fun updateClampsNumbersAndPreservesBlankWebSecret() {
        val current = AppSettings(openAiApiKey = "secret")
        val next = codec.update(
            current,
            JSONObject()
                .put("jpegQuality", 999)
                .put("webPort", 12)
                .put("openAiApiKey", ""),
            preserveBlankSecrets = true
        )

        assertEquals(100, next.jpegQuality)
        assertEquals(1024, next.webPort)
        assertEquals("secret", next.openAiApiKey)
    }

    @Test
    fun maskedJsonDoesNotExposeOpenAiKey() {
        val json = codec.toJson(AppSettings(openAiApiKey = "secret"), maskSecrets = true)
        assertEquals("", json.getString("openAiApiKey"))
        assertTrue(json.getBoolean("openAiApiKeyConfigured"))
    }
}
