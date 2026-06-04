package com.con11.a3dprinthelper.network

data class AnalysisResult(
    val status: PrintStatus = PrintStatus.Unknown,
    val confidence: Double = 0.0,
    val summary: String = "暂无分析结果",
    val abnormalReasons: List<String> = emptyList(),
    val shouldNotify: Boolean = false,
    val rawText: String = ""
) {
    val isAbnormal: Boolean
        get() = shouldNotify || status == PrintStatus.Abnormal || status == PrintStatus.Warning
}

enum class PrintStatus {
    Normal,
    Warning,
    Abnormal,
    Unknown
}
