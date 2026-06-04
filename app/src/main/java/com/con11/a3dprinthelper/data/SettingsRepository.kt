package com.con11.a3dprinthelper.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(
    private val context: Context,
    private val codec: SettingsCodec
) {
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = codec.defaultSettings()
        codec.normalize(AppSettings(
            captureIntervalMinutes = prefs[Keys.captureIntervalMinutes] ?: defaults.captureIntervalMinutes,
            jpegQuality = prefs[Keys.jpegQuality] ?: defaults.jpegQuality,
            requestTimeoutSeconds = prefs[Keys.requestTimeoutSeconds] ?: defaults.requestTimeoutSeconds,
            notificationCooldownMinutes = prefs[Keys.notificationCooldownMinutes] ?: defaults.notificationCooldownMinutes,
            autoFlashEnabled = prefs[Keys.autoFlashEnabled] ?: defaults.autoFlashEnabled,
            openAiApiKey = securePrefs.getString(SECURE_OPENAI_KEY, "") ?: "",
            openAiModel = prefs[Keys.openAiModel] ?: defaults.openAiModel,
            openAiBaseUrl = prefs[Keys.openAiBaseUrl] ?: defaults.openAiBaseUrl,
            imageDetail = prefs[Keys.imageDetail]?.toEnum(defaults.imageDetail) ?: defaults.imageDetail,
            prompt = prefs[Keys.prompt] ?: defaults.prompt,
            barkUrl = prefs[Keys.barkUrl] ?: migrateLegacyBarkUrl(prefs[Keys.barkServerUrl]),
            barkTitleTemplate = prefs[Keys.barkTitleTemplate] ?: defaults.barkTitleTemplate,
            webPort = prefs[Keys.webPort] ?: defaults.webPort,
            keepAliveServiceEnabled = prefs[Keys.keepAliveServiceEnabled] ?: defaults.keepAliveServiceEnabled,
            autoScreenOffEnabled = prefs[Keys.autoScreenOffEnabled] ?: defaults.autoScreenOffEnabled,
            webAliveInBackground = prefs[Keys.webAliveInBackground] ?: defaults.webAliveInBackground,
            webPreviewScalePercent = prefs[Keys.webPreviewScalePercent] ?: defaults.webPreviewScalePercent,
            webPreviewFps = prefs[Keys.webPreviewFps] ?: defaults.webPreviewFps
        ))
    }

    suspend fun save(settings: AppSettings) {
        val normalized = codec.normalize(settings)
        securePrefs.edit()
            .putString(SECURE_OPENAI_KEY, normalized.openAiApiKey)
            .apply()

        context.settingsDataStore.edit { prefs ->
            prefs[Keys.captureIntervalMinutes] = normalized.captureIntervalMinutes
            prefs[Keys.jpegQuality] = normalized.jpegQuality
            prefs[Keys.requestTimeoutSeconds] = normalized.requestTimeoutSeconds
            prefs[Keys.notificationCooldownMinutes] = normalized.notificationCooldownMinutes
            prefs[Keys.autoFlashEnabled] = normalized.autoFlashEnabled
            prefs[Keys.openAiModel] = normalized.openAiModel
            prefs[Keys.openAiBaseUrl] = normalized.openAiBaseUrl
            prefs[Keys.imageDetail] = normalized.imageDetail.name
            prefs[Keys.prompt] = normalized.prompt
            prefs[Keys.barkUrl] = normalized.barkUrl
            prefs[Keys.barkTitleTemplate] = normalized.barkTitleTemplate
            prefs[Keys.webPort] = normalized.webPort
            prefs[Keys.keepAliveServiceEnabled] = normalized.keepAliveServiceEnabled
            prefs[Keys.autoScreenOffEnabled] = normalized.autoScreenOffEnabled
            prefs[Keys.webAliveInBackground] = normalized.webAliveInBackground
            prefs[Keys.webPreviewScalePercent] = normalized.webPreviewScalePercent
            prefs[Keys.webPreviewFps] = normalized.webPreviewFps
            prefs[Keys.secureRevision] = (prefs[Keys.secureRevision] ?: 0) + 1
        }
    }

    private fun migrateLegacyBarkUrl(legacyServerUrl: String?): String {
        val legacyKey = securePrefs.getString(SECURE_BARK_KEY, "") ?: ""
        if (legacyKey.isBlank()) return "https://api.day.app/"
        val serverUrl = (legacyServerUrl ?: "https://api.day.app").trim().trimEnd('/')
        return "$serverUrl/${legacyKey.trim()}/"
    }

    private inline fun <reified T : Enum<T>> String.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    private object Keys {
        val captureIntervalMinutes = intPreferencesKey("capture_interval_minutes")
        val jpegQuality = intPreferencesKey("jpeg_quality")
        val requestTimeoutSeconds = intPreferencesKey("request_timeout_seconds")
        val notificationCooldownMinutes = intPreferencesKey("notification_cooldown_minutes")
        val autoFlashEnabled = booleanPreferencesKey("auto_flash_enabled")
        val openAiModel = stringPreferencesKey("openai_model")
        val openAiBaseUrl = stringPreferencesKey("openai_base_url")
        val imageDetail = stringPreferencesKey("image_detail")
        val prompt = stringPreferencesKey("prompt")
        val barkUrl = stringPreferencesKey("bark_url")
        val barkServerUrl = stringPreferencesKey("bark_server_url")
        val barkTitleTemplate = stringPreferencesKey("bark_title_template")
        val webPort = intPreferencesKey("web_port")
        val keepAliveServiceEnabled = booleanPreferencesKey("keep_alive_service_enabled")
        val autoScreenOffEnabled = booleanPreferencesKey("auto_screen_off_enabled")
        val webAliveInBackground = booleanPreferencesKey("web_alive_in_background")
        val webPreviewScalePercent = intPreferencesKey("web_preview_scale_percent")
        val webPreviewFps = intPreferencesKey("web_preview_fps")
        val secureRevision = intPreferencesKey("secure_revision")
    }

    private companion object {
        const val SECURE_OPENAI_KEY = "openai_api_key"
        const val SECURE_BARK_KEY = "bark_device_key"
    }
}
