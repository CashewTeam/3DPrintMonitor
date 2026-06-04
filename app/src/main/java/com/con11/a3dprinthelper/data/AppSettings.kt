package com.con11.a3dprinthelper.data

enum class ImageDetail {
    Low,
    Auto,
    High
}

data class AppSettings(
    val captureIntervalMinutes: Int = 5,
    val jpegQuality: Int = 82,
    val requestTimeoutSeconds: Int = 60,
    val notificationCooldownMinutes: Int = 30,
    val autoFlashEnabled: Boolean = true,
    val openAiApiKey: String = "",
    val openAiModel: String = "gpt-4.1-mini",
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val imageDetail: ImageDetail = ImageDetail.Auto,
    val prompt: String = DEFAULT_PROMPT,
    val barkUrl: String = "https://api.day.app/",
    val barkTitleTemplate: String = "3D 打印异常",
    val webPort: Int = 8080,
    val keepAliveServiceEnabled: Boolean = true,
    val autoScreenOffEnabled: Boolean = true,
    val webPreviewScalePercent: Int = 70,
    val webPreviewFps: Int = 3
)

const val DEFAULT_PROMPT = """
你是一个 3D 打印机视觉巡检助手。请根据照片判断打印机当前状态。

判断范围：
1. 只分析热床/打印平台上的打印件、喷嘴附近、耗材进入喷头的路径。
2. 忽略热床外、打印机外壳外、桌面上、背景里、机身旁边的杂物、工具、线缆、阴影和反光。
3. 如果无法确定热床边界，请保守判断，不要因为画面边缘或背景杂乱而报告失败。

正常情况包括：
- 喷嘴正在打印、模型局部尚未完成、支撑结构、裙边、brim/raft、少量正常拉丝。
- 打印件附着在热床上，虽然形状复杂或有未完成层，但没有明显脱落、移位或堆料。
- 热床外出现废料、擦拭线、耗材碎屑或工具，但未影响热床上的当前打印。

只有在热床/打印件区域出现明确证据时才判定异常，例如：
- 打印件明显从热床脱落、严重翘边、层移位、喷嘴空打。
- 喷嘴周围大量堆料、意外缠料、堵头导致材料异常聚集。
- 耗材明显断料、缠绕卡住，或打印区域出现会继续破坏打印的情况。

如果只是可疑、画面不清楚、视角被遮挡、无法确认是否失败，请返回 unknown 或 warning，
并把 shouldNotify 设为 false。只有明确需要人工干预的异常才设 shouldNotify 为 true。

请只返回 JSON，不要包含 Markdown。格式：
{
  "status": "normal | warning | abnormal | unknown",
  "confidence": 0.0,
  "summary": "一句中文摘要",
  "abnormalReasons": ["异常原因"],
  "shouldNotify": false
}
"""
