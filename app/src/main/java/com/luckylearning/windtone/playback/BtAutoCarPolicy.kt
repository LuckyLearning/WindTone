package com.luckylearning.windtone.playback

/**
 * G7 蓝牙自动车机：纯决策与设备名单编解码（普通 JVM 可测）。
 *
 * 匹配规则：
 * - [anyDevice]=true → 任意蓝牙音频设备（A2DP/SCO/BLE）连上即命中；
 * - [anyDevice]=false → 仅当已连接设备的 id（优先地址，否则产品名）落在记住名单内。
 *
 * 会话：自动切入后记 sessionActive；断开且 restoreOnDisconnect 时还原。
 * 用户关掉总开关时：若会话仍在且允许还原 → 退出；否则只清会话标记。
 */
object BtAutoCarPolicy {

    /** 与 android.media.AudioDeviceInfo 常量对齐，便于单测不依赖 android.jar */
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27

    sealed class Action {
        data object None : Action()
        data object EnterCar : Action()
        data object ExitCar : Action()
    }

    data class DeviceRef(
        /** 稳定 id：小写地址，或「n:」+ 产品名（无地址时） */
        val id: String,
        val displayName: String,
    )

    fun isBluetoothAudioType(type: Int): Boolean = when (type) {
        TYPE_BLUETOOTH_SCO,
        TYPE_BLUETOOTH_A2DP,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        -> true
        else -> false
    }

    fun deviceId(address: String?, productName: CharSequence?): String? {
        val addr = address?.trim()?.lowercase().orEmpty()
        if (addr.isNotEmpty() && addr != "null") return addr
        val name = productName?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return null
        return "n:$name"
    }

    fun matches(
        anyDevice: Boolean,
        rememberedIds: Set<String>,
        connectedIds: Set<String>,
    ): Boolean {
        if (connectedIds.isEmpty()) return false
        if (anyDevice) return true
        if (rememberedIds.isEmpty()) return false
        return connectedIds.any { it in rememberedIds }
    }

    fun decide(
        enabled: Boolean,
        matchingConnected: Boolean,
        sessionActive: Boolean,
        restoreOnDisconnect: Boolean,
    ): Action {
        if (!enabled) {
            return if (sessionActive && restoreOnDisconnect) Action.ExitCar else Action.None
        }
        if (matchingConnected) {
            return if (!sessionActive) Action.EnterCar else Action.None
        }
        if (sessionActive && restoreOnDisconnect) return Action.ExitCar
        return Action.None
    }

    /** 名单落盘：每行 id + UNIT SEPARATOR + displayName */
    fun encodeDevices(list: List<DeviceRef>): String {
        val sep = '\u001f'
        return list.joinToString("\n") { "${it.id}$sep${it.displayName}" }
    }

    fun decodeDevices(raw: String): List<DeviceRef> {
        if (raw.isBlank()) return emptyList()
        val sep = '\u001f'
        return raw.lineSequence().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val parts = t.split(sep, limit = 2)
            val id = parts[0].trim()
            if (id.isEmpty()) return@mapNotNull null
            val name = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { id.removePrefix("n:") }
            DeviceRef(id = id, displayName = name)
        }.distinctBy { it.id }.toList()
    }

    fun rememberedIdSet(raw: String): Set<String> =
        decodeDevices(raw).map { it.id }.toSet()
}