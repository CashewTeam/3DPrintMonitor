package com.con11.a3dprinthelper.power

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

class ShizukuScreenPowerController(private val context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val powerModeLock = Mutex()
    private var serviceBinder: IBinder? = null
    private var binding = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuDisplayPowerService::class.java.name)
        )
            .daemon(false)
            .debuggable(false)
            .processNameSuffix("display_power")
            .version(USER_SERVICE_VERSION)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                serviceBinder = service
                binding = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                serviceBinder = null
                binding = false
            }
        }
    }

    suspend fun turnScreenOff(): ShizukuScreenPowerResult =
        setDisplayPowerMode(ShizukuDisplayPowerService.POWER_MODE_OFF)

    suspend fun turnScreenOn(): ShizukuScreenPowerResult =
        setDisplayPowerMode(ShizukuDisplayPowerService.POWER_MODE_NORMAL)

    suspend fun prepare(): ShizukuScreenPowerResult = withContext(Dispatchers.IO) {
        val permission = ensurePermission()
        if (!permission.success) return@withContext permission
        if (bindService() != null) {
            ShizukuScreenPowerResult(true, null)
        } else {
            ShizukuScreenPowerResult(false, "Shizuku 显示控制服务绑定失败")
        }
    }

    suspend fun ensurePermission(): ShizukuScreenPowerResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!Shizuku.pingBinder()) return@withContext ShizukuScreenPowerResult(false, "Shizuku 未运行")
            if (Shizuku.isPreV11()) return@withContext ShizukuScreenPowerResult(false, "Shizuku 版本过旧")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuScreenPowerResult(true, null)
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                ShizukuScreenPowerResult(false, "请在 Shizuku 授权弹窗中允许本应用")
            }
        }.getOrElse {
            ShizukuScreenPowerResult(false, it.message ?: "Shizuku 不可用")
        }
    }

    private suspend fun setDisplayPowerMode(mode: Int): ShizukuScreenPowerResult =
        powerModeLock.withLock {
            withContext(Dispatchers.IO) {
                val permission = ensurePermission()
                if (!permission.success) return@withContext permission

                var lastFailure = ShizukuScreenPowerResult(false, "Shizuku 显示控制调用失败")
                repeat(if (mode == ShizukuDisplayPowerService.POWER_MODE_OFF) OFF_ATTEMPTS else ON_ATTEMPTS) { attempt ->
                    val binder = bindService()
                    if (binder == null) {
                        lastFailure = ShizukuScreenPowerResult(false, "Shizuku 显示控制服务绑定失败")
                    } else {
                        val result = transactPowerMode(binder, mode)
                        if (result.success) {
                            if (attempt + 1 < OFF_ATTEMPTS && mode == ShizukuDisplayPowerService.POWER_MODE_OFF) {
                                delay(OFF_RETRY_DELAY_MS)
                            } else {
                                return@withContext result
                            }
                        } else {
                            lastFailure = result
                            synchronized(lock) { serviceBinder = null }
                            delay(REBIND_RETRY_DELAY_MS)
                        }
                    }
                }
                lastFailure
            }
        }

    private fun transactPowerMode(binder: IBinder, mode: Int): ShizukuScreenPowerResult = runCatching {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayPowerService.DESCRIPTOR)
            data.writeInt(mode)
            if (!binder.transact(ShizukuDisplayPowerService.TRANSACTION_SET_DISPLAY_POWER_MODE, data, reply, 0)) {
                return@runCatching ShizukuScreenPowerResult(false, "Shizuku 显示控制服务未接受命令")
            }
            reply.readException()
            val error = reply.readString()
            if (error == null) ShizukuScreenPowerResult(true, null) else ShizukuScreenPowerResult(false, error)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }.getOrElse {
        ShizukuScreenPowerResult(false, it.message ?: "Shizuku 显示控制调用失败")
    }

    private suspend fun bindService(): IBinder? {
        synchronized(lock) {
            serviceBinder?.takeIf { it.isBinderAlive && it.pingBinder() }?.let { return it }
            serviceBinder = null
            if (!binding) {
                binding = true
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val start = System.currentTimeMillis()
            fun poll() {
                if (!continuation.isActive) return
                val binder = synchronized(lock) { serviceBinder }
                if (binder != null && binder.isBinderAlive && binder.pingBinder()) {
                    continuation.resume(binder)
                    return
                }
                if (System.currentTimeMillis() - start >= BIND_TIMEOUT_MS) {
                    synchronized(lock) { binding = false }
                    continuation.resume(null)
                    return
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(::poll, 50)
            }
            poll()
        }
    }

    companion object {
        private const val USER_SERVICE_VERSION = 1
        private const val BIND_TIMEOUT_MS = 3_000L
        private const val OFF_ATTEMPTS = 3
        private const val ON_ATTEMPTS = 2
        private const val OFF_RETRY_DELAY_MS = 180L
        private const val REBIND_RETRY_DELAY_MS = 120L
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 6401
    }
}

data class ShizukuScreenPowerResult(
    val success: Boolean,
    val message: String?
)
