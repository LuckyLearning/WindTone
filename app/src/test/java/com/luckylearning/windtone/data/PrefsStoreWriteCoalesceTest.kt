package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore 写合并（A14，2026-11-01）。
 *
 * ## 背景
 *
 * 设置页拖悬浮窗的宽度 / 高度 / 位置 / 透明度时，同一个键会在几十毫秒里被写十几次。
 * 每次 setter 都开一个 DataStore 事务，IO 队列被塞满，设置页跟着迟钝 ——
 * 而中间那些值没有任何意义（用户没停在那些位置）。
 *
 * ## 合并为什么是安全的
 *
 * 读的权威一直是 `SharedPreferences`（setter 里同步 `apply()`）。DataStore 目前只是
 * 双写镜像，晚 120ms 落盘不影响任何读取；进程若在窗口内被杀，丢的只是镜像里的
 * 中间值，下次启动 `warmUp` 会从 `SharedPreferences` 补齐。
 *
 * 所以这里要钉的是两件事：**设置一个都不能丢**，以及**待落盘的键不会随拖动次数增长**。
 */
@RunWith(RobolectricTestRunner::class)
class PrefsStoreWriteCoalesceTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    private fun rawPreferences() =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPreferences().edit().clear().commit()
        Prefs.clearCacheForTest()
        prefs = Prefs(context)
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
        Prefs.clearCacheForTest()
    }

    @Test
    fun `连续拖同一滑块：读到的是最后一个值`() {
        repeat(20) { prefs.lyricOverlayWidthPercent = 50 + it }
        assertEquals("设置必须立刻可读（权威源是 SharedPreferences）", 69, prefs.lyricOverlayWidthPercent)
    }

    @Test
    fun `连续拖同一滑块：待落盘只留一条`() {
        repeat(20) { prefs.lyricOverlayWidthPercent = 50 + it }
        assertTrue(
            "同一键 20 次写入应合并成 1 条待落盘，实际 ${prefs.pendingStoreWritesForTest()} 条",
            prefs.pendingStoreWritesForTest() <= 1,
        )
    }

    /** 不同键各留各的，不能互相覆盖 */
    @Test
    fun `不同键的待落盘互不覆盖`() {
        prefs.lyricOverlayWidthPercent = 70
        prefs.lyricOverlayXPercent = 30
        assertTrue(
            "两个不同的键都应待落盘，实际 ${prefs.pendingStoreWritesForTest()} 条",
            prefs.pendingStoreWritesForTest() >= 2,
        )
        assertEquals(70, prefs.lyricOverlayWidthPercent)
        assertEquals(30, prefs.lyricOverlayXPercent)
    }

    /** 位置与透明度同样走合并（拖窗 / 调底板透明度是最密集的两处） */
    @Test
    fun `位置与透明度也走合并`() {
        repeat(10) { prefs.lyricOverlayYPercent = 10 + it }
        repeat(10) { prefs.lyricOverlayBgAlphaPercent = 40 + it }
        assertEquals(19, prefs.lyricOverlayYPercent)
        assertEquals(49, prefs.lyricOverlayBgAlphaPercent)
        assertTrue(
            "两个键各自合并成一条，实际 ${prefs.pendingStoreWritesForTest()} 条",
            prefs.pendingStoreWritesForTest() <= 2,
        )
    }

    /** 退出设置页时可以把窗口提前推下去，不必等 120ms */
    @Test
    fun `立刻落盘不会抛异常且不影响读取`() {
        prefs.lyricOverlayWidthPercent = 66
        prefs.flushStoreWritesNow()
        assertEquals(66, prefs.lyricOverlayWidthPercent)
    }
}
