package com.luckylearning.windtone.playback

import android.media.AudioDeviceInfo

/**
 * 音效按输出设备自动挂档（竞品借鉴：Salt Player / Symfonium）——纯策略。
 *
 * 用户把某个音效方案绑定到一类输出设备（如「耳机·入耳」绑到蓝牙耳机），
 * 设备接上时自动套用，拔掉回到挂档前的样子。车载模式**不参与**：
 * 它有自己的车载音效档案（`carSchemeId` + SoundStash），两套快照互不覆盖。
 *
 * 绑定表存储格式：每行一条 `设备键=方案id`，空行与坏行忽略 ——
 * 与 Prefs 的换行拼接口径一致，普通 JVM 单测可断言。
 *
 * 设备键粒度：蓝牙按**设备名**分（`bt:WH-1000XM5`），有线 / USB / 扬声器按类别
 * （`wired` / `usb` / `speaker`）。读不到蓝牙产品名时退化为 `bt`（类别级绑定仍可用）。
 */
object DeviceSchemePolicy {

    /** 绑定表：设备键 → 方案 id（空串值的键视为未绑定，编码时剔除） */
    fun encode(bindings: Map<String, String>): String =
        bindings.entries
            .map { it.key.trim() to it.value.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .sortedBy { it.first }
            .joinToString("\n") { "${it.first}=${it.second}" }

    fun decode(raw: String): Map<String, String> =
        raw.split('\n')
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isEmpty() || v.isEmpty()) null else k to v
            }
            .toMap()

    /**
     * `AudioDeviceInfo.type` → 设备键。多个候选设备并存时按 [keyPriority] 取优先级
     * 最高的那个（蓝牙 > USB > 有线 > 扬声器，与系统实际路由倾向一致）。
     */
    fun keyOf(type: Int, productName: String): String? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> {
            val name = productName.trim()
            if (name.isEmpty()) "bt" else "bt:$name"
        }
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> "wired"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> "usb"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> "speaker"
        else -> null
    }

    /** 设备键的路由优先级（小者胜）；键未识别时排最后 */
    fun keyPriority(key: String): Int = when {
        key.startsWith("bt") -> 0
        key == "usb" -> 1
        key == "wired" -> 2
        key == "speaker" -> 3
        else -> 4
    }

    /**
     * 设备变化后的决策（[AudioFx.applyForDevice] 消费）。
     *
     * @param inProfile 当前是否已在某台设备的挂档会话中（`deviceStash` 非空）
     * @param currentSchemeId 当前生效的方案 id（空 = 自定义）
     */
    data class Decision(
        /** 先把挂档前的音效还原（离开上一台设备的会话） */
        val restoreFirst: Boolean,
        /** 要自动套用的方案 id；null = 该设备没有绑定，只还原 */
        val applySchemeId: String?,
    )

    fun decide(
        bindings: Map<String, String>,
        deviceKey: String,
        inProfile: Boolean,
        currentSchemeId: String,
    ): Decision {
        val target = bindings[deviceKey].orEmpty()
        // 同一台设备重复回调（系统常会连发 added）：已在它的会话里且方案一致 → 不动
        if (inProfile && target == currentSchemeId && target.isNotEmpty()) {
            return Decision(restoreFirst = false, applySchemeId = null)
        }
        val known = target.takeIf { it.isNotEmpty() && SoundSchemes.byId(it) != null }
        return Decision(restoreFirst = inProfile, applySchemeId = known)
    }
}
