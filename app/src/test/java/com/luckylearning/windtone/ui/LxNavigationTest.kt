package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.SourceMode
import com.luckylearning.windtone.state.LibraryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LxNavigationTest {

    @Test
    fun backPopsDetailThenPreviousSectionThenStops() {
        var stack = LxBackStack.root()
        stack = LxBackStack.openDetail(stack, LxDetail.RESULTS)
        stack = LxBackStack.select(stack, LxSection.SHEETS)
        stack = LxBackStack.openDetail(stack, LxDetail.SHEET)

        stack = LxBackStack.back(stack)!!
        assertEquals(LxSection.SHEETS, stack.last().section)
        assertEquals(LxDetail.NONE, stack.last().detail)

        stack = LxBackStack.back(stack)!!
        assertEquals(LxSection.SEARCH, stack.last().section)
        assertEquals(LxDetail.RESULTS, stack.last().detail)

        stack = LxBackStack.back(stack)!!
        assertEquals(LxDetail.NONE, stack.last().detail)
        assertNull(LxBackStack.back(stack))
        assertFalse(LxBackStack.canPop(stack))
    }

    @Test
    fun reselectingCurrentSectionExitsDetailWithoutGrowingTheStack() {
        var stack = LxBackStack.select(LxBackStack.root(), LxSection.BOARDS)
        stack = LxBackStack.openDetail(stack, LxDetail.BOARD)
        stack = LxBackStack.select(stack, LxSection.BOARDS)
        assertEquals(listOf(LxSection.SEARCH, LxSection.BOARDS), stack.map { it.section })
        assertEquals(LxDetail.NONE, stack.last().detail)
    }

    @Test
    fun selectingAnExistingSectionPopsBackToIt() {
        var stack = LxBackStack.root()
        stack = LxBackStack.openDetail(stack, LxDetail.RESULTS)
        stack = LxBackStack.select(stack, LxSection.SHEETS)
        stack = LxBackStack.select(stack, LxSection.BOARDS)
        stack = LxBackStack.select(stack, LxSection.SEARCH)
        assertEquals(1, stack.size)
        assertEquals(LxDetail.RESULTS, stack.single().detail)
    }

    @Test
    fun forwardIsDeeperDetailOrALaterSection() {
        val search = LxFrame(LxSection.SEARCH)
        val results = search.copy(detail = LxDetail.RESULTS)
        assertTrue(LxBackStack.forward(search, results))
        assertFalse(LxBackStack.forward(results, search))
        assertTrue(LxBackStack.forward(search, LxFrame(LxSection.BOARDS)))
        assertFalse(LxBackStack.forward(LxFrame(LxSection.BOARDS), search))
    }

    @Test
    fun eachModeMenuIsOnlyItsOwnItems() {
        val local = SourceMenus.ids(SourceMode.LOCAL)
        assertTrue(local.contains("view:${LibraryView.SONGS.name}"))
        assertTrue(local.contains("view:${LibraryView.PLAYLIST.name}"))
        assertTrue(local.contains("scan"))
        assertTrue(local.contains("settings"))
        assertFalse(local.any { it.startsWith("lx:") })
        assertFalse(local.contains("aliyun"))

        val cloud = SourceMenus.ids(SourceMode.CLOUD)
        assertEquals(listOf("aliyun", "settings"), cloud)

        val lx = SourceMenus.ids(SourceMode.LX)
        assertEquals(
            listOf("lx:SEARCH", "lx:SHEETS", "lx:BOARDS", "lx:SOURCES", "settings"),
            lx,
        )
    }

    @Test
    fun selectionFollowsTheActiveSectionNotAnotherMode() {
        val search = SourceMenuItem.Lx(LxSection.SEARCH)
        val songs = SourceMenuItem.Library(LibraryView.SONGS)
        assertTrue(
            SourceMenus.isSelected(search, SourceMode.LX, LibraryView.SONGS, false, LxSection.SEARCH),
        )
        assertFalse(
            SourceMenus.isSelected(songs, SourceMode.LX, LibraryView.SONGS, false, LxSection.SEARCH),
        )
        assertFalse(
            SourceMenus.isSelected(SourceMenuItem.Aliyun, SourceMode.LX, LibraryView.SONGS, false, LxSection.SEARCH),
        )
        assertTrue(
            SourceMenus.isSelected(songs, SourceMode.LOCAL, LibraryView.SONGS, false, LxSection.SEARCH),
        )
    }
}
