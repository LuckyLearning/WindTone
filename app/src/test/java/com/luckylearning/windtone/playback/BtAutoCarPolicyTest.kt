package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G7 蓝牙自动车机：[BtAutoCarPolicy] 类型判定、名单编解码、匹配与进/出动作。
 *
 * 边界重点（相对初版）：
 * - 连接 → EnterCar，断开 → ExitCar（可关还原）
 * - 总开关关：永不 EnterCar
 * - 已在会话内再连：幂等 None
 */
class BtAutoCarPolicyTest {

    @Test
    fun bluetoothAudioTypes() {
        assertTrue(BtAutoCarPolicy.isBluetoothAudioType(BtAutoCarPolicy.TYPE_BLUETOOTH_A2DP))
        assertTrue(BtAutoCarPolicy.isBluetoothAudioType(BtAutoCarPolicy.TYPE_BLUETOOTH_SCO))
        assertTrue(BtAutoCarPolicy.isBluetoothAudioType(BtAutoCarPolicy.TYPE_BLE_HEADSET))
        assertTrue(BtAutoCarPolicy.isBluetoothAudioType(BtAutoCarPolicy.TYPE_BLE_SPEAKER))
        assertFalse(BtAutoCarPolicy.isBluetoothAudioType(2)) // builtin speaker
    }

    @Test
    fun deviceIdPrefersAddress() {
        assertEquals("aa:bb", BtAutoCarPolicy.deviceId("AA:BB", "Car Kit"))
        assertEquals("n:Car Kit", BtAutoCarPolicy.deviceId(null, "Car Kit"))
        assertEquals(null, BtAutoCarPolicy.deviceId("  ", "  "))
    }

    @Test
    fun matchesAnyVsRemembered() {
        val connected = setOf("aa:bb")
        assertTrue(BtAutoCarPolicy.matches(true, emptySet(), connected))
        assertFalse(BtAutoCarPolicy.matches(false, emptySet(), connected))
        assertTrue(BtAutoCarPolicy.matches(false, setOf("aa:bb"), connected))
        assertFalse(BtAutoCarPolicy.matches(false, setOf("cc:dd"), connected))
        assertFalse(BtAutoCarPolicy.matches(true, emptySet(), emptySet()))
    }

    @Test
    fun decideEnterExit() {
        assertEquals(
            BtAutoCarPolicy.Action.EnterCar,
            BtAutoCarPolicy.decide(true, matchingConnected = true, sessionActive = false, restoreOnDisconnect = true),
        )
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(true, matchingConnected = true, sessionActive = true, restoreOnDisconnect = true),
        )
        assertEquals(
            BtAutoCarPolicy.Action.ExitCar,
            BtAutoCarPolicy.decide(true, matchingConnected = false, sessionActive = true, restoreOnDisconnect = true),
        )
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(true, matchingConnected = false, sessionActive = true, restoreOnDisconnect = false),
        )
        assertEquals(
            BtAutoCarPolicy.Action.ExitCar,
            BtAutoCarPolicy.decide(false, matchingConnected = false, sessionActive = true, restoreOnDisconnect = true),
        )
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(false, matchingConnected = false, sessionActive = true, restoreOnDisconnect = false),
        )
    }

    @Test
    fun encodeDecodeRoundtrip() {
        val list = listOf(
            BtAutoCarPolicy.DeviceRef("aa:bb", "Car"),
            BtAutoCarPolicy.DeviceRef("n:Headset", "Headset"),
        )
        val raw = BtAutoCarPolicy.encodeDevices(list)
        assertEquals(list, BtAutoCarPolicy.decodeDevices(raw))
        assertEquals(setOf("aa:bb", "n:Headset"), BtAutoCarPolicy.rememberedIdSet(raw))
        assertTrue(BtAutoCarPolicy.decodeDevices("").isEmpty())
    }

    // ---- 边界：连接/断开触发、开关关闭不触发、重复连接幂等 ----

    @Test
    fun `连接匹配设备触发进车机 断开触发还原`() {
        // 模拟 Monitor.evaluate：先匹配连上 → Enter；再全断 → Exit
        var session = false
        val enter = BtAutoCarPolicy.decide(
            enabled = true,
            matchingConnected = true,
            sessionActive = session,
            restoreOnDisconnect = true,
        )
        assertEquals(BtAutoCarPolicy.Action.EnterCar, enter)
        session = true // Monitor 在 EnterCar 后置 sessionActive

        val stillConnected = BtAutoCarPolicy.decide(
            enabled = true,
            matchingConnected = true,
            sessionActive = session,
            restoreOnDisconnect = true,
        )
        assertEquals(BtAutoCarPolicy.Action.None, stillConnected)

        val disconnect = BtAutoCarPolicy.decide(
            enabled = true,
            matchingConnected = false,
            sessionActive = session,
            restoreOnDisconnect = true,
        )
        assertEquals(BtAutoCarPolicy.Action.ExitCar, disconnect)
    }

    @Test
    fun `断开且关闭还原时不 ExitCar`() {
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(
                enabled = true,
                matchingConnected = false,
                sessionActive = true,
                restoreOnDisconnect = false,
            ),
        )
        // 断开后会话仍在、再连上：因 sessionActive 仍为 true → 幂等 None（不二次 Enter）
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(
                enabled = true,
                matchingConnected = true,
                sessionActive = true,
                restoreOnDisconnect = false,
            ),
        )
    }

    @Test
    fun `总开关关闭永不 EnterCar`() {
        // 关开关时即便当前仍匹配蓝牙，也不得切入
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(
                enabled = false,
                matchingConnected = true,
                sessionActive = false,
                restoreOnDisconnect = true,
            ),
        )
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(
                enabled = false,
                matchingConnected = true,
                sessionActive = false,
                restoreOnDisconnect = false,
            ),
        )
        // 会话中关开关 + 允许还原 → Exit；不允许还原 → None（清会话由 Monitor 负责）
        assertEquals(
            BtAutoCarPolicy.Action.ExitCar,
            BtAutoCarPolicy.decide(
                enabled = false,
                matchingConnected = true,
                sessionActive = true,
                restoreOnDisconnect = true,
            ),
        )
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(
                enabled = false,
                matchingConnected = true,
                sessionActive = true,
                restoreOnDisconnect = false,
            ),
        )
    }

    @Test
    fun `重复连接幂等 不二次 EnterCar`() {
        // 第一次连上
        assertEquals(
            BtAutoCarPolicy.Action.EnterCar,
            BtAutoCarPolicy.decide(true, matchingConnected = true, sessionActive = false, restoreOnDisconnect = true),
        )
        // 同设备再次 added 回调 / A2DP+SCO 双类型连上：session 已活跃 → None
        repeat(5) {
            assertEquals(
                "第 ${it + 1} 次重复连接不得再 Enter",
                BtAutoCarPolicy.Action.None,
                BtAutoCarPolicy.decide(true, matchingConnected = true, sessionActive = true, restoreOnDisconnect = true),
            )
        }
        // 无会话且无匹配：None
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(true, matchingConnected = false, sessionActive = false, restoreOnDisconnect = true),
        )
    }

    @Test
    fun `完整会话 进档出档再进档`() {
        var session = false
        fun tick(matching: Boolean) = BtAutoCarPolicy.decide(
            enabled = true,
            matchingConnected = matching,
            sessionActive = session,
            restoreOnDisconnect = true,
        ).also { action ->
            when (action) {
                BtAutoCarPolicy.Action.EnterCar -> session = true
                BtAutoCarPolicy.Action.ExitCar -> session = false
                BtAutoCarPolicy.Action.None -> Unit
            }
        }

        assertEquals(BtAutoCarPolicy.Action.EnterCar, tick(matching = true))
        assertTrue(session)
        assertEquals(BtAutoCarPolicy.Action.None, tick(matching = true))
        assertEquals(BtAutoCarPolicy.Action.ExitCar, tick(matching = false))
        assertFalse(session)
        assertEquals(BtAutoCarPolicy.Action.None, tick(matching = false))
        assertEquals(BtAutoCarPolicy.Action.EnterCar, tick(matching = true))
        assertTrue(session)
    }

    @Test
    fun `deviceId 拒绝字面 null 与仅空白名`() {
        assertEquals(null, BtAutoCarPolicy.deviceId("null", null))
        assertEquals(null, BtAutoCarPolicy.deviceId("NULL", "  "))
        assertEquals("n:Kit", BtAutoCarPolicy.deviceId("null", "Kit"))
        assertEquals("aa:bb:cc", BtAutoCarPolicy.deviceId("  AA:BB:CC  ", "x"))
        assertEquals(null, BtAutoCarPolicy.deviceId(null, null))
        assertEquals(null, BtAutoCarPolicy.deviceId("", ""))
    }

    @Test
    fun `matches 部分命中与多设备`() {
        val remembered = setOf("aa:bb", "n:Car")
        assertTrue(BtAutoCarPolicy.matches(false, remembered, setOf("aa:bb", "zz:zz")))
        assertTrue(BtAutoCarPolicy.matches(false, remembered, setOf("n:Car")))
        assertFalse(BtAutoCarPolicy.matches(false, remembered, setOf("zz:zz")))
        assertFalse(BtAutoCarPolicy.matches(false, emptySet(), setOf("aa:bb")))
        // anyDevice 只看「有没有已连蓝牙音频 id」，名单无关
        assertTrue(BtAutoCarPolicy.matches(true, emptySet(), setOf("whatever")))
        assertTrue(BtAutoCarPolicy.matches(true, setOf("unused"), setOf("aa:bb")))
    }

    @Test
    fun `名单编解码去重空白行与缺名`() {
        val sep = '\u001f'
        val raw = listOf(
            "aa:bb${sep}Car",
            "",
            "  ",
            "aa:bb${sep}Later Name Ignored", // distinctBy id → 保留先到
            "n:Headset", // 无 UNIT SEPARATOR → displayName 回落去 n:
            "${sep}orphan-name", // 空 id 丢弃
            "cc:dd${sep}",
        ).joinToString("\n")
        val decoded = BtAutoCarPolicy.decodeDevices(raw)
        assertEquals(
            listOf(
                BtAutoCarPolicy.DeviceRef("aa:bb", "Car"),
                BtAutoCarPolicy.DeviceRef("n:Headset", "Headset"),
                BtAutoCarPolicy.DeviceRef("cc:dd", "cc:dd"),
            ),
            decoded,
        )
        assertEquals(setOf("aa:bb", "n:Headset", "cc:dd"), BtAutoCarPolicy.rememberedIdSet(raw))
        assertTrue(BtAutoCarPolicy.decodeDevices("   \n\n  ").isEmpty())
        assertEquals("", BtAutoCarPolicy.encodeDevices(emptyList()))
    }

    @Test
    fun `非蓝牙类型与蓝牙类型可区分`() {
        // 常见 AudioDeviceInfo 输出类型：勿误判
        val nonBt = listOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14, 18, 19, 22)
        for (t in nonBt) {
            assertFalse("type=$t 不应视为蓝牙音频", BtAutoCarPolicy.isBluetoothAudioType(t))
        }
        for (t in listOf(7, 8, 26, 27)) {
            assertTrue("type=$t 应为蓝牙音频", BtAutoCarPolicy.isBluetoothAudioType(t))
        }
    }

    @Test
    fun `关开关后无会话再开开关须重新匹配才进车机`() {
        // 关开关且无会话 → None；再开时若仍 matching 且 session 已清 → Enter
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(false, matchingConnected = true, sessionActive = false, restoreOnDisconnect = true),
        )
        assertEquals(
            BtAutoCarPolicy.Action.EnterCar,
            BtAutoCarPolicy.decide(true, matchingConnected = true, sessionActive = false, restoreOnDisconnect = true),
        )
        // 对比：若关开关时误留 sessionActive=true 且 restore=false，再开仍 matching → None（幂等）
        assertEquals(
            BtAutoCarPolicy.Action.None,
            BtAutoCarPolicy.decide(true, matchingConnected = true, sessionActive = true, restoreOnDisconnect = false),
        )
        assertNotEquals(
            BtAutoCarPolicy.Action.EnterCar,
            BtAutoCarPolicy.decide(false, matchingConnected = true, sessionActive = true, restoreOnDisconnect = false),
        )
    }
}