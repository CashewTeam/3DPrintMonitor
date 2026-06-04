package com.con11.a3dprinthelper.data

import org.json.JSONObject

class SettingsCodec(val schema: SettingsSchema) {
    fun defaultSettings(): AppSettings {
        var result = AppSettings()
        schema.fields.forEach { field ->
            val defaultValue = if (field.resetAction == "defaultPrompt") DEFAULT_PROMPT else field.defaultValue
            result = setValue(result, field, defaultValue, preserveBlankSecret = false)
        }
        return result
    }

    fun normalize(settings: AppSettings): AppSettings {
        var result = settings
        schema.fields.forEach { field ->
            result = setValue(result, field, getValue(result, field.key), preserveBlankSecret = false)
        }
        return result
    }

    fun update(current: AppSettings, values: JSONObject, preserveBlankSecrets: Boolean): AppSettings {
        var result = current
        schema.fields.forEach { field ->
            if (values.has(field.key)) {
                result = setValue(result, field, values.opt(field.key), preserveBlankSecrets)
            }
        }
        return normalize(result)
    }

    fun updateValue(current: AppSettings, field: SettingField, value: Any?): AppSettings =
        setValue(current, field, value, preserveBlankSecret = false)

    fun toJson(settings: AppSettings, maskSecrets: Boolean): JSONObject {
        val json = JSONObject()
        schema.fields.forEach { field ->
            val value = getValue(settings, field.key)
            json.put(field.key, if (maskSecrets && field.webWriteOnly) "" else value)
            if (field.webWriteOnly) {
                json.put("${field.key}Configured", value.toString().isNotBlank())
            }
        }
        return json
    }

    fun defaultsJson(): JSONObject = toJson(defaultSettings(), maskSecrets = true)

    fun getValue(settings: AppSettings, key: String): Any = when (key) {
        "captureIntervalMinutes" -> settings.captureIntervalMinutes
        "jpegQuality" -> settings.jpegQuality
        "requestTimeoutSeconds" -> settings.requestTimeoutSeconds
        "notificationCooldownMinutes" -> settings.notificationCooldownMinutes
        "autoFlashEnabled" -> settings.autoFlashEnabled
        "openAiApiKey" -> settings.openAiApiKey
        "openAiModel" -> settings.openAiModel
        "openAiBaseUrl" -> settings.openAiBaseUrl
        "imageDetail" -> settings.imageDetail.name
        "prompt" -> settings.prompt
        "barkUrl" -> settings.barkUrl
        "barkTitleTemplate" -> settings.barkTitleTemplate
        "webPort" -> settings.webPort
        "keepAliveServiceEnabled" -> settings.keepAliveServiceEnabled
        "autoScreenOffEnabled" -> settings.autoScreenOffEnabled
        "webPreviewScalePercent" -> settings.webPreviewScalePercent
        "webPreviewFps" -> settings.webPreviewFps
        else -> error("未知设置字段：$key")
    }

    private fun setValue(
        current: AppSettings,
        field: SettingField,
        rawValue: Any?,
        preserveBlankSecret: Boolean
    ): AppSettings {
        if (field.webWriteOnly && preserveBlankSecret && rawValue?.toString().orEmpty().isBlank()) return current
        val text = rawValue?.toString().orEmpty()
        val intValue = (rawValue as? Number)?.toInt() ?: text.toIntOrNull()
        val rangedInt = intValue?.coerceIn(field.min ?: Int.MIN_VALUE, field.max ?: Int.MAX_VALUE)
        val boolValue = rawValue as? Boolean ?: text.equals("true", ignoreCase = true)
        return when (field.key) {
            "captureIntervalMinutes" -> current.copy(captureIntervalMinutes = rangedInt ?: current.captureIntervalMinutes)
            "jpegQuality" -> current.copy(jpegQuality = rangedInt ?: current.jpegQuality)
            "requestTimeoutSeconds" -> current.copy(requestTimeoutSeconds = rangedInt ?: current.requestTimeoutSeconds)
            "notificationCooldownMinutes" -> current.copy(notificationCooldownMinutes = rangedInt ?: current.notificationCooldownMinutes)
            "autoFlashEnabled" -> current.copy(autoFlashEnabled = boolValue)
            "openAiApiKey" -> current.copy(openAiApiKey = text)
            "openAiModel" -> current.copy(openAiModel = text.trim())
            "openAiBaseUrl" -> current.copy(openAiBaseUrl = text.trim().trimEnd('/'))
            "imageDetail" -> current.copy(imageDetail = text.toEnum(current.imageDetail))
            "prompt" -> current.copy(prompt = text)
            "barkUrl" -> current.copy(barkUrl = normalizeBarkUrl(text))
            "barkTitleTemplate" -> current.copy(barkTitleTemplate = text.trim())
            "webPort" -> current.copy(webPort = rangedInt ?: current.webPort)
            "keepAliveServiceEnabled" -> current.copy(keepAliveServiceEnabled = boolValue)
            "autoScreenOffEnabled" -> current.copy(autoScreenOffEnabled = boolValue)
            "webPreviewScalePercent" -> current.copy(webPreviewScalePercent = rangedInt ?: current.webPreviewScalePercent)
            "webPreviewFps" -> current.copy(webPreviewFps = rangedInt ?: current.webPreviewFps)
            else -> current
        }
    }

    private fun normalizeBarkUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return "https://api.day.app/"
        return trimmed.trimEnd('/') + "/"
    }

    private inline fun <reified T : Enum<T>> String.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name.equals(this, ignoreCase = true) } ?: default
}
