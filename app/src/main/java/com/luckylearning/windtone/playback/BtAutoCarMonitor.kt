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
 * G7：监听 [AudioManager] 音频设备增删，按 [BtAutoCarPolicy] 自动进/出车载布局。
 *
 * - 总开关关时不注册回调（省电）；开时才 [registerAudioDeviceCallback]。
 * - 不依赖 BLUETOOTH_CONNECT 也能感知「有蓝牙音频路由」（类型即可）；
 *   读产品名/地址在部分 ROM 上需要权限，读不到就只用类型（任意设备模式仍可用）。
 * - 切布局走 [onEnterCar] / [onExitCar]（由 [PlaybackEngine] 接到 setCarMode）。
 */
class BtAutoCarMonitor(
    private val app: Context,
    private val prefs: Prefs,
    private val onEnterCar: () -> Unit,
    private val onExitCar: () -> Unit,
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
        val want = prefs.btAutoCarEnabled
        if (want && !registered) {
            val mgr = am ?: return
            runCatching {
                mgr.registerAudioDeviceCallback(callback, mainHandler)
                registered = true
                DiagLog.log("BT_AUTO_CAR", "监听已开启")
                evaluate("register")
            }.onFailure {
                DiagLog.log("BT_AUTO_CAR", "注册失败：${it.javaClass.simpleName}")
            }
        } else if (!want && registered) {
            unregisterQuiet()
            // 关开关：若会话仍在且允许还原 → 退出车载；否则只清会话标记
            applyAction(
                BtAutoCarPolicy.decide(
                    enabled = false,
                    matchingConnected = false,
                    sessionActive = prefs.btAutoCarSessionActive,
                    restoreOnDisconnect = prefs.btAutoCarRestore,
                ),
                reason = "disable",
            )
            if (prefs.btAutoCarSessionActive) {
                prefs.btAutoCarSessionActive = false
                prefs.btAutoCarStashCarMode = ""
            }
        } else if (want && registered) {
            evaluate("prefs")
        }
    }

    fun release() {
        unregisterQuiet()
    }

    private fun unregisterQuiet() {
        if (!registered) return
        runCatching { am?.unregisterAudioDeviceCallback(callback) }
        registered = false
        DiagLog.log("BT_AUTO_CAR", "监听已关闭")
    }

    private fun evaluate(reason: String) {
        if (!prefs.btAutoCarEnabled) return
        val connected = connectedBluetoothIds()
        val remembered = BtAutoCarPolicy.rememberedIdSet(prefs.btAutoCarDevices)
        val matching = BtAutoCarPolicy.matches(
            anyDevice = prefs.btAutoCarAnyDevice,
            rememberedIds = remembered,
            connectedIds = connected,
        )
        val action = BtAutoCarPolicy.decide(
            enabled = true,
            matchingConnected = matching,
            sessionActive = prefs.btAutoCarSessionActive,
            restoreOnDisconnect = prefs.btAutoCarRestore,
        )
        applyAction(action, reason)
    }

    private fun applyAction(action: BtAutoCarPolicy.Action, reason: String) {
        when (action) {
            BtAutoCarPolicy.Action.None -> Unit
            BtAutoCarPolicy.Action.EnterCar -> {
                // 快照进档前的车载开关；已是车载也照记，断开仍可还原到「进档前」
                prefs.btAutoCarStashCarMode = if (prefs.carMode) "1" else "0"
                prefs.btAutoCarSessionActive = true
                DiagLog.log("BT_AUTO_CAR", "自动进车载 ($reason)")
                onEnterCar()
            }
            BtAutoCarPolicy.Action.ExitCar -> {
                val stash = prefs.btAutoCarStashCarMode
                prefs.btAutoCarSessionActive = false
                prefs.btAutoCarStashCarMode = ""
                DiagLog.log("BT_AUTO_CAR", "自动出车载 stash=$stash ($reason)")
                val wantCar = stash == "1"
                if (prefs.carMode != wantCar) {
                    if (wantCar) onEnterCar() else onExitCar()
                }
            }
        }
    }

    /** 当前已连接的蓝牙音频设备 id 集合（对外给设置页「添加当前」用）。 */
    fun connectedBluetoothRefs(): List<BtAutoCarPolicy.DeviceRef> {
        val mgr = am ?: return emptyList()
        val out = LinkedHashMap<String, BtAutoCarPolicy.DeviceRef>()
        for (d in mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!BtAutoCarPolicy.isBluetoothAudioType(d.type)) continue
            val id = BtAutoCarPolicy.deviceId(
                address = runCatching { d.address }.getOrNull(),
                productName = runCatching { d.productName }.getOrNull(),
            ) ?: continue
            val name = runCatching { d.productName?.toString() }.getOrNull()
                ?.trim().orEmpty().ifEmpty { id.removePrefix("n:") }
            out.putIfAbsent(id, BtAutoCarPolicy.DeviceRef(id, name))
        }
        return out.values.toList()
    }

    private fun connectedBluetoothIds(): Set<String> =
        connectedBluetoothRefs().map { it.id }.toSet()
}