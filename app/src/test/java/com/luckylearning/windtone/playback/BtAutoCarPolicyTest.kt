package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
