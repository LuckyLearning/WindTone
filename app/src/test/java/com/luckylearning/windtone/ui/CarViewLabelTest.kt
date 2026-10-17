package com.luckylearning.windtone.ui

import com.luckylearning.windtone.state.LibraryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机版视图 Tab 的标签（v1.1.19）。
 *
 * 为什么值得单测：车机首页把抽屉里的五个视图平铺成 Tab，标签是**唯一**的视图名来源
 * （抽屉里的短名不同：歌曲 / 专辑 / 歌手 / 文件夹 / 歌单）。一旦漏一个枚举分支，
 * `when` 会编译失败；但"两个视图拿到同一个标签"编译得过、只有肉眼能发现 ——
 * 那时用户会看到两个长得一模一样的 Tab，点哪个都像坏了。
 */
class CarViewLabelTest {

    @Test
    fun everyViewHasItsOwnLabel() {
        val labels = LibraryView.entries.map { carViewLabel(it) }
        assertEquals("Tab 数量必须与视图枚举一致", LibraryView.entries.size, labels.size)
        assertTrue("Tab 标签不能为空", labels.none { it.isBlank() })
        assertEquals("Tab 标签不能重复（重复的话两个 Tab 长得一样）", labels.size, labels.toSet().size)
    }

    @Test
    fun labelsAreTheExpectedWording() {
        // 车机上「歌曲」单独一个词太含糊，统一叫「全部歌曲」
        assertEquals("全部歌曲", carViewLabel(LibraryView.SONGS))
        assertEquals("专辑", carViewLabel(LibraryView.ALBUM))
        assertEquals("歌手", carViewLabel(LibraryView.ARTIST))
        assertEquals("文件夹", carViewLabel(LibraryView.FOLDER))
        assertEquals("歌单", carViewLabel(LibraryView.PLAYLIST))
    }
}
