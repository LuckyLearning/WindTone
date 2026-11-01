package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「不显示悬浮窗的应用」名单的进程内快照（A-OV2，2026-11-01）。
 *
 * ## 为什么值得单测
 *
 * 悬浮窗的显隐判定**每帧**都要读这份名单（播放中约 2 次/秒）。`SharedPreferences`
 * 的 `getStringSet` 每次调用都会新建一个 HashSet 返回（它必须防调用方就地篡改），
 * 于是每帧都产生一次无谓分配。这里加了进程内快照，但快照一旦和存储不一致，
 * 表现是「名单改了不生效」或「悬浮窗该收时不收」—— 都是要靠真机看才发现的静默故障。
 *
 * 所以钉三条：
 *  1. 同一次读取返回**同一个实例**（真的缓存了，不是每次新集合）；
 *  2. **写入后立刻读**就能拿到新值（双写过渡里最容易出错的地方）；
 *  3. 返回的是**只读视图**（调用方想就地改要当场抛，而不是悄悄污染共享快照）。
 */
@RunWith(RobolectricTestRunner::class)
class PrefsHiddenAppsTest {

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
    fun `连续读取返回同一实例（真的走了快照）`() {
        prefs.lyricOverlayHiddenApps = setOf("com.byd.avc", "com.byd.cdr")

        val first = prefs.lyricOverlayHiddenApps
        val second = prefs.lyricOverlayHiddenApps

        assertSame("多次读取应命中同一份快照，否则每帧仍会 new 一个 HashSet", first, second)
        assertEquals(setOf("com.byd.avc", "com.byd.cdr"), first)
    }

    /** 写入必须立刻可见 —— 用户勾完名单，下一帧悬浮窗就该按新名单判 */
    @Test
    fun `写入后立刻读到新值`() {
        prefs.lyricOverlayHiddenApps = setOf("com.byd.avc")
        assertEquals(setOf("com.byd.avc"), prefs.lyricOverlayHiddenApps)

        prefs.lyricOverlayHiddenApps = setOf("com.byd.avc", "com.android.launcher3")
        assertEquals(
            setOf("com.byd.avc", "com.android.launcher3"),
            prefs.lyricOverlayHiddenApps,
        )
    }

    /** 清空名单也要立刻生效（用户全部取消勾选 = 功能关闭，检测通道随之停） */
    @Test
    fun `清空名单后读到空集合`() {
        prefs.lyricOverlayHiddenApps = setOf("com.byd.avc")
        prefs.lyricOverlayHiddenApps = emptySet()
        assertTrue(prefs.lyricOverlayHiddenApps.isEmpty())
    }

    /**
     * 快照是共享的，所以必须只读。
     *
     * 若返回可变集合，调用方一句 `set.add(...)` 就会改掉所有读取方看到的名单，
     * 却不落盘 —— 表现为「这次生效、重启后又变回去」，极难排查。
     */
    @Test
    fun `读到的集合不可就地修改`() {
        prefs.lyricOverlayHiddenApps = setOf("com.byd.avc")
        val snapshot = prefs.lyricOverlayHiddenApps

        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot as MutableSet<String>
        assertThrows(UnsupportedOperationException::class.java) { mutable.add("com.byd.sr") }
    }

    /** 未写过时给默认值，且默认值同样走快照（不每次返回新集合） */
    @Test
    fun `未设置时返回默认值且可缓存`() {
        val a = prefs.lyricOverlayHiddenApps
        val b = prefs.lyricOverlayHiddenApps
        assertEquals(SettingsDefaults.OVERLAY_HIDDEN_APPS, a)
        assertSame(a, b)
    }
}
