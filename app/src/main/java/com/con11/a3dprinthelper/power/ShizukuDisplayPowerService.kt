package com.con11.a3dprinthelper.power

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

class ShizukuDisplayPowerService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_SET_DISPLAY_POWER_MODE -> {
                data.enforceInterface(DESCRIPTOR)
                val mode = data.readInt()
                runCatching {
                    ScrcpyDisplayPower.setDisplayPowerMode(mode)
                }.onSuccess {
                    reply?.writeNoException()
                    reply?.writeString(null)
                }.onFailure {
                    reply?.writeNoException()
                    reply?.writeString(it.message ?: it.javaClass.simpleName)
                }
                true
            }

            TRANSACTION_DESTROY -> {
                reply?.writeNoException()
                System.exit(0)
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        const val DESCRIPTOR = "com.con11.a3dprinthelper.power.IShizukuDisplayPowerService"
        const val TRANSACTION_SET_DISPLAY_POWER_MODE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_DESTROY = 16777115
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
    }
}

private object ScrcpyDisplayPower {
    private const val BUILT_IN_DISPLAY_ID_MAIN = 0

    fun setDisplayPowerMode(mode: Int) {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val displayToken = getDisplayToken(surfaceControl)
        val method = surfaceControl.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        method.invoke(null, displayToken, mode)
    }

    private fun getDisplayToken(surfaceControl: Class<*>): IBinder {
        val internalDisplayToken = runCatching {
            surfaceControl.getMethod("getInternalDisplayToken").invoke(null) as? IBinder
        }.getOrNull()
        if (internalDisplayToken != null) return internalDisplayToken

        val builtInDisplay = runCatching {
            surfaceControl
                .getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                .invoke(null, BUILT_IN_DISPLAY_ID_MAIN) as? IBinder
        }.getOrNull()
        if (builtInDisplay != null) return builtInDisplay

        throw RemoteException("无法获取主显示屏 token")
    }
}
