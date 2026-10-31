package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import com.luckylearning.windtone.state.LibraryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机版视图 Tab 的标签资源映射（i18n 第 15 批起走 strings）。
 *
 * 为什么值得单测：车机首页把抽屉里的视图平铺成导航项，标签是**唯一**的视图名来源
 * （抽屉短名不同：歌曲 / 专辑 / …）。SONGS 必须用 car_view_all_songs（「全部歌曲」），
 * 其余复用 [LibraryView.labelRes]。断言资源 id，避免默认 en locale 下中文硬断言失配。
 */
class CarViewLabelTest {

    @Test
    fun everyViewHasItsOwnLabelRes() {
        val ids = LibraryView.entries.map { carViewLabelRes(it) }
        assertEquals("Tab 数量必须与视图枚举一致", LibraryView.entries.size, ids.size)
        assertEquals("Tab 标签资源不能重复", ids.size, ids.toSet().size)
        assertTrue(ids.all { it != 0 })
    }

    @Test
    fun labelResAreTheExpectedIds() {
        assertEquals(R.string.car_view_all_songs, carViewLabelRes(LibraryView.SONGS))
        assertEquals(R.string.view_albums, carViewLabelRes(LibraryView.ALBUM))
        assertEquals(R.string.view_artists, carViewLabelRes(LibraryView.ARTIST))
        assertEquals(R.string.view_album_artists, carViewLabelRes(LibraryView.ALBUM_ARTIST))
        assertEquals(R.string.view_genres, carViewLabelRes(LibraryView.GENRE))
        assertEquals(R.string.view_years, carViewLabelRes(LibraryView.YEAR))
        assertEquals(R.string.view_folders, carViewLabelRes(LibraryView.FOLDER))
        assertEquals(R.string.view_playlists, carViewLabelRes(LibraryView.PLAYLIST))
    }
}
