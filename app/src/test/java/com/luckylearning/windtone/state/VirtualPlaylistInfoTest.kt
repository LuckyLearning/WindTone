package com.luckylearning.windtone.state

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 四个虚拟歌单的展示条目（v1.1.13 新增 [virtualPlaylistInfo]；A5 加「最近添加」）。
 *
 * ## 为什么把这条抽出来单测
 *
 * 用户真机上报：快捷方式「打开我的收藏」打开的是**歌单列表页**。
 * 根因是它去 `LibraryUiState.playlists` 里按 id 找虚拟歌单 —— 而那个列表里
 * **只有用户自建歌单**（直接来自 playlist 表），虚拟歌单从来没进去过，
 * 于是查找永远返回 null，代码就"只切了视图"。
 *
 * 修法是把虚拟歌单的构造收到 [virtualPlaylistInfo] 一处，列表页与快捷方式共用；
 * 本测试钉住：id 用的是 `VirtualPlaylist` 常量、名字不能跑、曲目数与封面取自
 * **对应的**那一个字段，以及空收藏夹也要能拼出来（不能崩）。
 *
 * 用 Robolectric 是因为 [Song] 的 `uri` 是 `android.net.Uri`（纯 JVM 下没法构造）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class VirtualPlaylistInfoTest {

    private fun song(id: Long, title: String) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = title,
        artist = "歌手",
        album = "专辑",
        durationMs = 1000L,
    )

    private val favorite = song(1, "收藏的歌")
    private val recent = song(2, "最近听的")
    private val mostPlayed = song(3, "听得最多的")
    private val recentlyAdded = song(4, "刚入库的")

    private val library = LibraryUiState(
        extraFavorites = listOf(favorite),
        extraRecent = listOf(recent),
        extraMostPlayed = listOf(mostPlayed),
        extraRecentlyAdded = listOf(recentlyAdded),
    )

    @Test
    fun favoritesInfoTakesNameAndCountFromFavorites() {
        val info = virtualPlaylistInfo(VirtualPlaylist.FAVORITES, library)
        assertEquals(VirtualPlaylist.FAVORITES, info.id)
        assertEquals("我的收藏", info.name)
        assertEquals(1, info.songCount)
        assertEquals(favorite, info.coverSong)
        assertEquals(listOf(favorite), info.coverSongs)
        assertTrue("虚拟歌单必须带 virtual 标记，界面据此隐藏「重命名 / 删除」", info.virtual)
    }

    @Test
    fun recentAndMostPlayedReadTheirOwnFields() {
        val r = virtualPlaylistInfo(VirtualPlaylist.RECENT, library)
        assertEquals(VirtualPlaylist.RECENT, r.id)
        assertEquals("最近播放", r.name)
        assertEquals(recent, r.coverSong)

        val a = virtualPlaylistInfo(VirtualPlaylist.RECENTLY_ADDED, library)
        assertEquals(VirtualPlaylist.RECENTLY_ADDED, a.id)
        assertEquals("最近添加", a.name)
        assertEquals(recentlyAdded, a.coverSong)

        val m = virtualPlaylistInfo(VirtualPlaylist.MOST_PLAYED, library)
        assertEquals(VirtualPlaylist.MOST_PLAYED, m.id)
        assertEquals("最常播放", m.name)
        assertEquals(mostPlayed, m.coverSong)
    }

    /** 空收藏夹也要能拼出来（打开后由界面显示「还没有收藏」的空状态，而不是崩） */
    @Test
    fun emptyFavoritesStillProducesAnEntry() {
        val info = virtualPlaylistInfo(VirtualPlaylist.FAVORITES, LibraryUiState())
        assertEquals(0, info.songCount)
        assertNull(info.coverSong)
        assertTrue(info.coverSongs.isEmpty())
    }

    /** 传进来的必须是虚拟歌单 id：传自建歌单的 id 属于调用方写错，直接响 */
    @Test
    fun rejectsNonVirtualIds() {
        assertThrows(IllegalStateException::class.java) {
            virtualPlaylistInfo(42L, library)
        }
    }

    @Test
    fun coverSongsTakesAtMostFour() {
        val many = (1L..6L).map { song(it, "歌$it") }
        val lib = LibraryUiState(extraFavorites = many)
        val info = virtualPlaylistInfo(VirtualPlaylist.FAVORITES, lib)
        assertEquals(6, info.songCount)
        assertEquals(4, info.coverSongs.size)
        assertEquals(many.take(4), info.coverSongs)
        assertEquals(many.first(), info.coverSong)
    }
}
