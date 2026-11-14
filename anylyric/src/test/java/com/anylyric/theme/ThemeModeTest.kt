package com.anylyric.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题存储值的容错。
 *
 * 这个值是在 `attachBaseContext` 里读的：一旦解析抛异常，整个界面根本起不来。
 * 所以「认不出就回默认」必须是硬约定，而不是碰巧。
 */
class ThemeModeTest {

    @Test
    fun missingValueFallsBackToAuto() {
        assertEquals(ThemeMode.AUTO, ThemeMode.of(null))
    }

    @Test
    fun blankValueFallsBackToAuto() {
        assertEquals(ThemeMode.AUTO, ThemeMode.of(""))
    }

    @Test
    fun unknownValueFallsBackToAuto() {
        assertEquals(ThemeMode.AUTO, ThemeMode.of("sepia"))
    }

    @Test
    fun caseSensitiveOnPurpose() {
        // 存储值由我们自己写，只认小写；手工改坏（"DARK"）按未知处理，不加隐式兼容。
        assertEquals(ThemeMode.AUTO, ThemeMode.of("DARK"))
    }

    @Test
    fun everyModeRoundTripsThroughItsKey() {
        ThemeMode.values().forEach { mode ->
            assertEquals(mode, ThemeMode.of(mode.key))
        }
    }

    @Test
    fun keysAreUnique() {
        val keys = ThemeMode.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
