package com.luckylearning.windtone.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.diag.DiagLog

/**
 * 音效按输出设备自动挂档的设备监听（竞品借鉴：Salt Player / Symfonium）。
 *
 * 与 [BtAutoCarMonitor] 同一套 `registerAudioDeviceCallback` 思路，但决策与
 * 动作走 [DeviceSchemePolicy] / [AudioFx.applyForDevice]：
 * - 总开关关时不注册回调（省电）；
 * - carMode 下不介入（车载有自己的音效档案）；
 * - 设备增删后重算「当前路由键」，键变了就触发挂档 / 还原。
 */
class DeviceSchemeMonitor(
    private val app: Context,
    private val prefs: Prefs,
) {
    private val am: AudioManager? =
        app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            evaluate("added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            evaluate("removed")
        }
    }

    /** 按 Prefs 开关同步注册；设置页切换后调用。 */
    fun syncRegistration() {
        val want = prefs.deviceSchemeEnabled
        if (want && !registered) {
            val mgr = am ?: return
            runCatching {
                mgr.registerAudioDeviceCallback(callback, mainHandler)
                registered = true
                evaluate("register")
            }
        } else if (!want && registered) {
            unregisterQuiet()
        }
    }

    fun release() {
        unregisterQuiet()
    }

    private fun unregisterQuiet() {
        if (!registered) return
        runCatching { am?.unregisterAudioDeviceCallback(callback) }
        registered = false
    }

    private fun evaluate(reason: String) {
        if (!prefs.deviceSchemeEnabled) return
        if (prefs.carMode) return // 车载档案接管，设备挂档不介入
        val key = currentOutputKey() ?: return
        if (key == prefs.deviceSchemeCurrent) return
        DiagLog.log("DEVICE_SCHEME", "设备变化 ($reason)：$key")
        AudioFx.applyForDevice(key)
    }

    /**
     * 当前最可能的输出路由键：在已接入的输出设备里按 [DeviceSchemePolicy.keyPriority]
     * 取优先级最高者。系统在多设备并存时的真实路由未必与优先级一致，但作为
     * 「用户刚插上什么」的信号足够准 —— 设备增删回调本身就是切换信号。
     */
    private fun currentOutputKey(): String? {
        val mgr = am ?: return null
        val devices = runCatching { mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return null
        var best: Pair<String, Int>? = null
        for (d in devices) {
            val name = runCatching { d.productName?.toString() }.getOrNull().orEmpty()
            val key = DeviceSchemePolicy.keyOf(d.type, name) ?: continue
            val priority = DeviceSchemePolicy.keyPriority(key)
            if (best == null || priority < best.second) best = key to priority
        }
        return best?.first
    }

    companion object {
        /** 设置页用：当前已接入、可参与绑定的输出设备（键 + 展示名），按优先级升序 */
        fun connectedOutputRefs(app: Context): List<Pair<String, String>> {
            val mgr = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
            val devices = runCatching { mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return emptyList()
            val out = LinkedHashMap<String, String>()
            for (d in devices) {
                val name = runCatching { d.productName?.toString() }.getOrNull()?.trim().orEmpty()
                val key = DeviceSchemePolicy.keyOf(d.type, name) ?: continue
                val label = when {
                    key == "wired" -> "有线耳机"
                    key == "usb" -> "USB 音频"
                    key == "speaker" -> "扬声器"
                    key.startsWith("bt:") -> "蓝牙 ${key.removePrefix("bt:")}"
                    else -> "蓝牙设备"
                }
                out.putIfAbsent(key, label)
            }
            return out.entries.sortedBy { DeviceSchemePolicy.keyPriority(it.key) }
                .map { it.key to it.value }
        }
    }
}
