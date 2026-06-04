package com.con11.a3dprinthelper.camera

data class CapturedFrame(
    val jpegBytes: ByteArray,
    val averageLuma: Double,
    val timestampMillis: Long = System.currentTimeMillis()
)
