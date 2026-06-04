package com.con11.a3dprinthelper.camera

interface CameraFrameSource {
    val name: CameraSourceName
    fun latestFrame(): CapturedFrame?
    fun latestAverageLuma(): Double?
    fun latestFrameTimestampMillis(): Long?
    fun setTorchEnabled(enabled: Boolean): Boolean
}

enum class CameraSourceName {
    Foreground,
    Background
}
