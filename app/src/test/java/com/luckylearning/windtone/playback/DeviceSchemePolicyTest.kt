package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音效按设备自动挂档的纯策略（竞品借鉴：Salt Player / Symfonium）。
 *
 * 关键契约：
 * - 绑定表编解码幂等、坏行忽略；
 * - 设备键：蓝牙按名字（读不到退化 `bt`）、有线/USB/扬声器按类别；
 * - 决策：重复回调不重复挂档、换设备先还原再套、未绑定设备只还原。
 */
class DeviceSchemePolicyTest {

    // ---------- 编解码 ----------

    @Test
    fun encodeDecodeRoundTripAndSort() {
        val raw = DeviceSchemePolicy.encode(
            mapOf("wired" to "hp_inear", "bt:Sony" to "bass"),
        )
        // 排序稳定：bt: 在前（字典序）
        assertEquals("bt:Sony=bass\nwired=hp_inear", raw)
        assertEquals(mapOf("bt:Sony" to "bass", "wired" to "hp_inear"), DeviceSchemePolicy.decode(raw))
    }

    @Test
    fun decodeIgnoresBlankAndMalformedLines() {
        val raw = "\nwired=hp_open\n\nbroken-line\n=empty_key\ntrailing=\nbt = bass \n"
        assertEquals(mapOf("wired" to "hp_open", "bt" to "bass"), DeviceSchemePolicy.decode(raw))
    }

    @Test
    fun encodeDropsEmptyValues() {
        assertEquals("", DeviceSchemePolicy.encode(mapOf("wired" to "  ")))
    }

    // ---------- 设备键 ----------

    @Test
    fun bluetoothKeyUsesProductNameWhenAvailable() {
        assertEquals("bt:Sony WH-1000XM5", DeviceSchemePolicy.keyOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Sony WH-1000XM5"))
        // 读不到名字 → 类别级键，绑定仍可用
        assertEquals("bt", DeviceSchemePolicy.keyOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO, ""))
    }

    @Test
    fun categoryKeys() {
        assertEquals("wired", DeviceSchemePolicy.keyOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "x"))
        assertEquals("usb", DeviceSchemePolicy.keyOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET, "x"))
        assertEquals("speaker", DeviceSchemePolicy.keyOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "x"))
        assertNull(DeviceSchemePolicy.keyOf(android.media.AudioDeviceInfo.TYPE_HDMI, "x"))
    }

    @Test
    fun routingPriorityPrefersBluetoothThenWiredThenSpeaker() {
        assertTrue(DeviceSchemePolicy.keyPriority("bt:X") < DeviceSchemePolicy.keyPriority("wired"))
        assertTrue(DeviceSchemePolicy.keyPriority("wired") < DeviceSchemePolicy.keyPriority("speaker"))
    }

    // ---------- 决策 ----------

    @Test
    fun noBindingOnFreshDeviceDoesNothing() {
        val d = DeviceSchemePolicy.decide(emptyMap(), "wired", inProfile = false, currentSchemeId = "")
        assertEquals(DeviceSchemePolicy.Decision(restoreFirst = false, applySchemeId = null), d)
    }

    @Test
    fun boundDeviceAppliesSchemeWithoutRestoreWhenNotInProfile() {
        val d = DeviceSchemePolicy.decide(
            mapOf("wired" to "hp_inear"), "wired", inProfile = false, currentSchemeId = "",
        )
        assertEquals(DeviceSchemePolicy.Decision(restoreFirst = false, applySchemeId = "hp_inear"), d)
    }

    @Test
    fun switchingDeviceRestoresThenAppliesNewBinding() {
        val d = DeviceSchemePolicy.decide(
            mapOf("wired" to "hp_inear", "bt:X" to "bass"), "bt:X", inProfile = true, currentSchemeId = "hp_inear",
        )
        assertEquals(DeviceSchemePolicy.Decision(restoreFirst = true, applySchemeId = "bass"), d)
    }

    @Test
    fun sameDeviceRepeatedCallbackIsNoOp() {
        // 系统常连发 added/removed：已在会话里且方案一致 → 不重复挂档（避免每次闪一下）
        val d = DeviceSchemePolicy.decide(
            mapOf("wired" to "hp_inear"), "wired", inProfile = true, currentSchemeId = "hp_inear",
        )
        assertEquals(DeviceSchemePolicy.Decision(restoreFirst = false, applySchemeId = null), d)
    }

    @Test
    fun unknownSchemeIdIsIgnored() {
        val d = DeviceSchemePolicy.decide(
            mapOf("wired" to "scheme_that_does_not_exist"), "wired", inProfile = false, currentSchemeId = "",
        )
        assertNull(d.applySchemeId)
    }
}
