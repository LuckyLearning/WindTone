package com.luckylearning.windtone.playback

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「播完当前专辑」的时长计算（[SleepTimer.albumRemainingMs]）。
 *
 * 为什么单独测：这一档的语义全靠「走到不同专辑就停」这一条规则撑着 ——
 * 算多了会「到点没停」，算少了会「正听着就断」。两种表现都只在真机上听得到，
 * 而本机没有设备，所以规则必须先在这里钉死。
 *
 * ⚠️ 挂 Robolectric 是因为 `Song` 带 `android.net.Uri`（与项目其它碰 Uri 的用例同口径）。
 */
@RunWith(RobolectricTestRunner::class)
class SleepTimerTest {

    private fun song(uri: String, album: String?, durationMs: Long) = Song(
        id = 0L,
        uri = Uri.parse(uri),
        title = uri,
        artist = null,
        album = album,
        durationMs = durationMs,
    )

    /** A 专辑三首（各 4 分钟）+ B 专辑一首，模拟「从专辑详情页播放全部」得到的队列 */
    private fun albumQueue() = listOf(
        song("a1", "A", 240_000L),
        song("a2", "A", 240_000L),
        song("a3", "A", 240_000L),
        song("b1", "B", 300_000L),
    )

    @Test
    fun `第一首起播时等于整张专辑剩余`() {
        // 240s − 60s 已播 + 240 + 240 = 660s
        val ms = SleepTimer.albumRemainingMs(albumQueue(), 0, positionMs = 60_000L, durationMs = 240_000L)
        assertEquals(660_000L, ms)
    }

    @Test
    fun `最后一首起播时只等于本曲剩余`() {
        val ms = SleepTimer.albumRemainingMs(albumQueue(), 2, positionMs = 30_000L, durationMs = 240_000L)
        assertEquals(210_000L, ms)
    }

    @Test
    fun `遇到不同专辑就停止累加`() {
        // 当前是 A 的第二首：剩 200s + 第三首 240s，**不能**把 B 的 300s 算进来
        val ms = SleepTimer.albumRemainingMs(albumQueue(), 1, positionMs = 40_000L, durationMs = 240_000L)
        assertEquals(440_000L, ms)
    }

    @Test
    fun `专辑未知时退化为播完当前曲`() {
        val queue = listOf(
            song("x", null, 200_000L),
            song("y", null, 200_000L),
        )
        val ms = SleepTimer.albumRemainingMs(queue, 0, positionMs = 50_000L, durationMs = 200_000L)
        assertEquals(150_000L, ms)
    }

    @Test
    fun `专辑名为空串时同样退化`() {
        val queue = listOf(song("x", "  ", 200_000L), song("y", "  ", 200_000L))
        val ms = SleepTimer.albumRemainingMs(queue, 0, positionMs = 0L, durationMs = 200_000L)
        assertEquals(200_000L, ms)
    }

    @Test
    fun `索引越界不崩，按当前曲剩余处理`() {
        val queue = albumQueue()
        val ms = SleepTimer.albumRemainingMs(queue, index = 99, positionMs = 10_000L, durationMs = 100_000L)
        assertEquals(90_000L, ms)
    }

    @Test
    fun `已播过总长时剩余按 0 算，不会出现负数`() {
        val queue = albumQueue()
        val ms = SleepTimer.albumRemainingMs(queue, 0, positionMs = 300_000L, durationMs = 240_000L)
        // 当前曲剩余 0 + 后两首 480s
        assertEquals(480_000L, ms)
    }

    @Test
    fun `后续曲目时长缺失时只累加已知部分`() {
        // 时长未解析出来（0）不是算错，是不算 —— 宁可早停一点，也不要把定时拉到几分钟后
        val queue = listOf(
            song("a1", "A", 120_000L),
            song("a2", "A", 0L),
            song("a3", "A", 120_000L),
        )
        val ms = SleepTimer.albumRemainingMs(queue, 0, positionMs = 0L, durationMs = 120_000L)
        assertEquals(240_000L, ms)
    }

    @Test
    fun `后续曲目专辑为空时视为不同专辑并停止`() {
        val queue = listOf(
            song("a1", "A", 100_000L),
            song("a2", null, 100_000L),
            song("a3", "A", 100_000L),
        )
        val ms = SleepTimer.albumRemainingMs(queue, 0, positionMs = 0L, durationMs = 100_000L)
        assertEquals(100_000L, ms)
    }
}
