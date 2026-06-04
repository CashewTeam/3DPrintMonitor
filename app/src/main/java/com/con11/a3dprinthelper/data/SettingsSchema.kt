package com.con11.a3dprinthelper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SettingControlType {
    Slider,
    Number,
    Text,
    Secret,
    Textarea,
    Enum,
    Boolean
}

data class SettingOption(val value: String, val label: String)

data class SettingField(
    val key: String,
    val label: String,
    val type: SettingControlType,
    val defaultValue: Any?,
    val min: Int? = null,
    val max: Int? = null,
    val unit: String = "",
    val options: List<SettingOption> = emptyList(),
    val webWriteOnly: Boolean = false,
    val rows: Int = 4,
    val resetAction: String? = null
)

data class SettingSection(
    val id: String,
    val title: String,
    val fields: List<SettingField>
)

data class SettingsSchema(
    val version: Int,
    val sections: List<SettingSection>,
    val rawJson: String
) {
    val fields: List<SettingField> = sections.flatMap { it.fields }
}

class SettingsSchemaRepository(context: Context) {
    private val loadResult: Result<SettingsSchema> by lazy {
        runCatching {
            val raw = context.assets.open(SCHEMA_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(raw)
        }
    }

    val schema: SettingsSchema
        get() = loadResult.getOrElse { FALLBACK_SCHEMA }

    val errorMessage: String?
        get() = loadResult.exceptionOrNull()?.let { "设置描述文件加载失败：${it.message ?: it.javaClass.simpleName}" }

    companion object {
        private const val SCHEMA_ASSET = "settings_schema.json"
        private val FALLBACK_SCHEMA = SettingsSchema(1, emptyList(), """{"version":1,"sections":[]}""")

        fun parse(raw: String): SettingsSchema {
            val root = JSONObject(raw)
            val sections = root.getJSONArray("sections").mapObjects { section ->
                SettingSection(
                    id = section.getString("id"),
                    title = section.getString("title"),
                    fields = section.getJSONArray("fields").mapObjects(::parseField)
                )
            }
            return SettingsSchema(root.optInt("version", 1), sections, root.toString())
        }

        private fun parseField(json: JSONObject): SettingField {
            val options = json.optJSONArray("options")?.mapObjects {
                SettingOption(it.getString("value"), it.optString("label", it.getString("value")))
            }.orEmpty()
            return SettingField(
                key = json.getString("key"),
                label = json.getString("label"),
                type = json.getString("type").toControlType(),
                defaultValue = json.opt("default").takeUnless { it == JSONObject.NULL },
                min = json.optIntOrNull("min"),
                max = json.optIntOrNull("max"),
                unit = json.optString("unit"),
                options = options,
                webWriteOnly = json.optBoolean("webWriteOnly"),
                rows = json.optInt("rows", 4),
                resetAction = json.optString("resetAction").ifBlank { null }
            )
        }

        private fun String.toControlType(): SettingControlType =
            SettingControlType.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
                ?: error("未知设置控件类型：$this")
    }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key)) optInt(key) else null
