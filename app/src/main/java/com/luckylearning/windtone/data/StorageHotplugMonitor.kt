package com.luckylearning.windtone.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.luckylearning.windtone.diag.DiagLog
import java.util.concurrent.Executor

/**
 * 动态注册存储挂载/卸载监听（manifest 隐式广播在 target 26+ 收不到 MEDIA_*）。
 *
 * - 广播：MEDIA_MOUNTED / UNMOUNTED / EJECT / REMOVED / BAD_REMOVAL（带 file scheme）
 * - API 30+ 另挂 [StorageManager.StorageVolumeCallback.onStateChanged] 作车机 ROM 兜底
 *   （部分车机 ROM 只走 StorageManager、少发经典 MEDIA_*）
 *
 * 在 [com.luckylearning.windtone.MusicApp.onCreate] 启一次即可；进程活着就听着。
 */
class StorageHotplugMonitor(private val app: Context) {

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleBroadcast(intent)
        }
    }

    private var volumeCallback: StorageManager.StorageVolumeCallback? = null

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            registered = true
            DiagLog.log(TAG, "广播监听已开启")
        }.onFailure {
            DiagLog.log(TAG, "广播注册失败：${it.javaClass.simpleName}")
        }
        registerVolumeCallback()
    }

    fun stop() {
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
        unregisterVolumeCallback()
    }

    /** 纯入口，单测可直接喂 Intent 字段而不注册系统。 */
    internal fun handleBroadcast(intent: Intent?) {
        val action = intent?.action
        when (StorageHotplugPolicy.classifyAction(action)) {
            StorageHotplugPolicy.BroadcastKind.MOUNTED -> {
                val path = StorageHotplugPolicy.volumePathFromData(intent?.dataString)
                DiagLog.log(TAG, "挂载 path=${path ?: "(未知)"}")
                StorageHotplugBus.onMounted(path)
            }
            StorageHotplugPolicy.BroadcastKind.UNMOUNTED -> {
                val path = StorageHotplugPolicy.volumePathFromData(intent?.dataString)
                DiagLog.log(TAG, "卸载/$action path=${path ?: "(未知)"}")
                StorageHotplugBus.onUnmounted(path)
            }
            StorageHotplugPolicy.BroadcastKind.IGNORE -> Unit
        }
    }

    private fun registerVolumeCallback() {
        // StorageVolumeCallback / registerStorageVolumeCallback 均自 API 30；
        // 方法名是 onStateChanged（不是 onStorageVolumeChanged）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val sm = app.getSystemService(StorageManager::class.java) ?: return
        val main = Handler(Looper.getMainLooper())
        val executor = Executor { r -> main.post(r) }
        val cb = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                // 可移动卷插入时 state 会变；拔出后可能直接消失。
                // 统一走「请求重扫」：挂载带来新歌、卸载靠扫描软删。播放侧另靠广播路径判定。
                val path = volumePath(volume)
                val state = runCatching { volume.state }.getOrNull().orEmpty()
                DiagLog.log(TAG, "StorageVolume 变化 state=$state path=${path ?: "(未知)"}")
                when {
                    state.equals(Environment.MEDIA_MOUNTED, ignoreCase = true) ||
                        state.equals(Environment.MEDIA_MOUNTED_READ_ONLY, ignoreCase = true) ->
                        StorageHotplugBus.onMounted(path)
                    state.equals(Environment.MEDIA_EJECTING, ignoreCase = true) ||
                        state.equals(Environment.MEDIA_UNMOUNTED, ignoreCase = true) ||
                        state.equals(Environment.MEDIA_BAD_REMOVAL, ignoreCase = true) ||
                        state.equals(Environment.MEDIA_REMOVED, ignoreCase = true) ->
                        StorageHotplugBus.onUnmounted(path)
                    else -> {
                        // 未知状态：仍请求一次节流重扫，宁可多扫也不漏歌
                        StorageHotplugBus.onMounted(path)
                    }
                }
            }
        }
        runCatching {
            sm.registerStorageVolumeCallback(executor, cb)
            volumeCallback = cb
            DiagLog.log(TAG, "StorageVolumeCallback 已开启")
        }.onFailure {
            DiagLog.log(TAG, "StorageVolumeCallback 失败：${it.javaClass.simpleName}")
        }
    }

    /** API 30+ 公开接口只有 directory；getPath 已从公开 stub 移除。 */
    private fun volumePath(volume: StorageVolume): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }.getOrNull()

    private fun unregisterVolumeCallback() {
        val cb = volumeCallback ?: return
        volumeCallback = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val sm = app.getSystemService(StorageManager::class.java) ?: return
        runCatching { sm.unregisterStorageVolumeCallback(cb) }
    }

    private companion object {
        const val TAG = "STORAGE_HOTPLUG"
    }
}
