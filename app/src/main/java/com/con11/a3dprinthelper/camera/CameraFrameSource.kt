package com.con11.a3dprinthelper.camera

interface CameraFrameSource {
    val name: CameraSourceName
    fun latestFrame(): CapturedFrame?
    fun latestAverageLuma(): Double?
}

enum class CameraSourceName {
    Foreground,
    Background
}
