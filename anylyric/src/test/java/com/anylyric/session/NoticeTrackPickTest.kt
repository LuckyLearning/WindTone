package com.anylyric.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体通知兜底（0.2.26）的回归测试。
 *
 * 这一组规则全部来自真机反馈「装了酷我，连歌名都没识别到，还显示上一个 App 的名字」，
 * 每条都对应一个会让画面出错的具体场景：
 *  · 把常驻通知的应用名当歌名 → 在线搜不到词，还盖掉了真歌名；
 *  · 过期通知不清 → 播放器退出后继续顶着它的歌名；
 *  · 该不该接管判错 → 要么旧会话一直压着新播放器，要么两个播放器互抢。
 */
class NoticeTrackPickTest {

    private fun track(
        pkg: String = "cn.kuwo.kwmusiccar",
        title: String = "稻香",
        artist: String? = "周杰伦",
        body: String? = null,
        at: Long = 1_000L
    ) = NoticeTrack(pkg, title, artist, body, at)

    @Test
    fun `normal title is used`() {
        assertEquals("稻香", NoticeTrackPick.usableTitle("稻香", "酷我音乐"))
    }

    @Test
    fun `title equal to app label is rejected`() {
        // 常驻通知的标题就是应用名：当歌名去在线搜，只会搜到「酷我音乐」这首歌。
        assertNull(NoticeTrackPick.usableTitle("酷我音乐", "酷我音乐"))
    }

    @Test
    fun `title prefixed by app label is rejected`() {
        assertNull(NoticeTrackPick.usableTitle("酷我音乐 正在播放", "酷我音乐"))
    }

    @Test
    fun `case differences do not defeat the label filter`() {
        assertNull(NoticeTrackPick.usableTitle("QQMusic", "qqmusic"))
    }

    @Test
    fun `blank title is rejected`() {
        assertNull(NoticeTrackPick.usableTitle("   ", "酷我音乐"))
        assertNull(NoticeTrackPick.usableTitle(null, "酷我音乐"))
    }

    @Test
    fun `title is trimmed`() {
        assertEquals("稻香", NoticeTrackPick.usableTitle("  稻香  ", null))
    }

    @Test
    fun `pick returns the freshest notice`() {
        val old = track(pkg = "a", at = 1_000L)
        val fresh = track(pkg = "b", at = 5_000L)
        assertEquals("b", NoticeTrackPick.pick(listOf(old, fresh), 6_000L)?.pkg)
    }

    @Test
    fun `stale notice is ignored`() {
        val stale = track(at = 1_000L)
        assertNull(
            NoticeTrackPick.pick(
                listOf(stale),
                1_000L + NoticeTrackPick.FRESH_MS + 1
            )
        )
    }

    @Test
    fun `notice exactly at the freshness edge is still usable`() {
        val edge = track(at = 1_000L)
        assertEquals(
            "cn.kuwo.kwmusiccar",
            NoticeTrackPick.pick(listOf(edge), 1_000L + NoticeTrackPick.FRESH_MS)?.pkg
        )
    }

    @Test
    fun `pinned package wins and others are dropped`() {
        val other = track(pkg = "com.netease.cloudmusic", at = 9_000L)
        val pinned = track(pkg = "cn.kuwo.kwmusiccar", at = 2_000L)
        assertEquals(
            "cn.kuwo.kwmusiccar",
            NoticeTrackPick.pick(listOf(other, pinned), 9_500L, "cn.kuwo.kwmusiccar")?.pkg
        )
    }

    @Test
    fun `pinned package without notice yields null`() {
        val other = track(pkg = "com.netease.cloudmusic", at = 9_000L)
        assertNull(NoticeTrackPick.pick(listOf(other), 9_500L, "cn.kuwo.kwmusiccar"))
    }

    @Test
    fun `same package never takes over`() {
        // 同一个包走到这里说明只是通知比元数据更快，正常链路会补齐，不该抢。
        assertFalse(
            NoticeTrackPick.shouldTakeOver(
                sessionPkg = "cn.kuwo.kwmusiccar",
                sessionPlaying = false,
                sessionTitle = null,
                notice = track()
            )
        )
    }

    @Test
    fun `stopped session is taken over`() {
        // 车机最常见的一幕：上一个播放器停了但会话没释放，用户已经切到酷我。
        assertTrue(
            NoticeTrackPick.shouldTakeOver(
                sessionPkg = "com.netease.cloudmusic",
                sessionPlaying = false,
                sessionTitle = "晴天",
                notice = track()
            )
        )
    }

    @Test
    fun `playing session with a title is not taken over`() {
        // 真的有两个播放器在放，谁都不该被抢。
        assertFalse(
            NoticeTrackPick.shouldTakeOver(
                sessionPkg = "com.netease.cloudmusic",
                sessionPlaying = true,
                sessionTitle = "晴天",
                notice = track()
            )
        )
    }

    @Test
    fun `playing but empty session is taken over`() {
        // 空壳会话：状态是播放中，却连歌名都没有 —— 通知比它可信。
        assertTrue(
            NoticeTrackPick.shouldTakeOver(
                sessionPkg = "com.byd.mediacenter",
                sessionPlaying = true,
                sessionTitle = "   ",
                notice = track()
            )
        )
    }
}
