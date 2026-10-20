package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.ScanScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主内容区路由的优先级回归（P2-4）。
 *
 * 纯枚举判断，不需要 Robolectric：把「谁优先」钉死，避免后续加页面时
 * 把扫描引导 / 详情页的顺序改乱。
 */
class MainNavigationTest {

    @Test
    fun unsetScopeWithEmptyLibraryShowsScanSetupFirst() {
        assertEquals(
            MainRoute.SCAN_SETUP,
            mainRouteOf(
                scanScope = ScanScope.UNSET,
                songsEmpty = true,
                scanning = false,
                hasSelectedPlaylist = true,
                hasSelectedGroup = true,
            ),
        )
    }

    @Test
    fun scanningWithEmptyLibraryShowsScanning() {
        assertEquals(
            MainRoute.SCANNING,
            mainRouteOf(
                scanScope = ScanScope.FOLDERS,
                songsEmpty = true,
                scanning = true,
                hasSelectedPlaylist = false,
                hasSelectedGroup = false,
            ),
        )
    }

    @Test
    fun playlistDetailWinsOverGroupDetail() {
        assertEquals(
            MainRoute.PLAYLIST_DETAIL,
            mainRouteOf(
                scanScope = ScanScope.FULL,
                songsEmpty = false,
                scanning = false,
                hasSelectedPlaylist = true,
                hasSelectedGroup = true,
            ),
        )
    }

    @Test
    fun groupDetailWhenNoPlaylistSelected() {
        assertEquals(
            MainRoute.GROUP_DETAIL,
            mainRouteOf(
                scanScope = ScanScope.FULL,
                songsEmpty = false,
                scanning = false,
                hasSelectedPlaylist = false,
                hasSelectedGroup = true,
            ),
        )
    }

    @Test
    fun otherwiseShowsLibrary() {
        assertEquals(
            MainRoute.LIBRARY,
            mainRouteOf(
                scanScope = ScanScope.FULL,
                songsEmpty = false,
                scanning = true,
                hasSelectedPlaylist = false,
                hasSelectedGroup = false,
            ),
        )
    }
}
