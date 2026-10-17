package com.luckylearning.windtone.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面入口「现在能不能执行」的判据（v1.1.13 修复后新增）。
 *
 * ## 守的是哪一个故障
 *
 * 用户真机上报（2026-10-17）：「打开收藏 / 播放全部 / 随机播放」点下去**只是把应用打开**，
 * 一个都没执行自己的动作。根因是判据在**冷启动第一帧**就判成"已就绪"：
 *
 * ```
 * 旧判据：songs 非空 || 扫描范围未选 || 不在扫描
 * 第一帧实况：songs=[], scanning=false（还没开始扫）, scanScope=上次选过的值
 *            → 第三条（不在扫描）为真 → "就绪" → 动作被消费
 *            → playQueue(空) 被 startQueue 的 if (queue.isEmpty()) return 静默吞掉
 * ```
 *
 * 所以判据现在只用「曲库自己报出来的结论」（`LibraryUiState.libraryLoaded` /
 * `playlistsLoaded`），不再看"还没发生的事"。下面的用例把这些组合逐一钉住。
 *
 * 本文件是纯 JVM 测试（[AppShortcut] 不碰 Android API），不需要 Robolectric。
 */
class AppShortcutGateTest {

    /** 冷启动第一帧：曲库还没装载、没开始扫描、歌单也还没算 —— 三个入口都必须**等** */
    @Test
    fun waitsOnTheVeryFirstFrame() {
        val firstFrame = Gate(
            songsLoaded = false, hasSongs = false, scanning = false, playlistsLoaded = false,
        )
        for (action in SHORTCUT_ACTIONS) {
            assertFalse("冷启动第一帧就判成就绪 → 动作被消费、歌又没播（v1.1.12 之前的实际表现）：$action", canRun(action, firstFrame))
        }
    }

    /** 缓存里已经有歌（Room 秒开那条路）：三个入口立刻可执行 */
    @Test
    fun runsOnceTheCachedLibraryIsIn() {
        val cached = Gate(songsLoaded = true, hasSongs = true, scanning = false, playlistsLoaded = true)
        for (action in SHORTCUT_ACTIONS) assertTrue(action, canRun(action, cached))
    }

    /**
     * 「打开我的收藏」比另外两个多等一步：虚拟歌单的曲目是另一条并行任务算出来的
     * （`PlaylistStore.refreshPlaylistsNow`）。曲库就绪但歌单还没算完时就不能进去 ——
     * 否则打开的是一个还没算出内容的空收藏夹。
     */
    @Test
    fun favoritesWaitsForVirtualPlaylists() {
        val songsReadyButPlaylistsNot = Gate(
            songsLoaded = true, hasSongs = true, scanning = false, playlistsLoaded = false,
        )
        assertFalse(
            "歌单还没算完就打开收藏 → 空收藏夹",
            canRun(AppShortcut.FAVORITES, songsReadyButPlaylistsNot),
        )
        // 另外两个不受影响：它们只依赖曲库
        assertTrue(canRun(AppShortcut.PLAY_ALL, songsReadyButPlaylistsNot))
        assertTrue(canRun(AppShortcut.SHUFFLE, songsReadyButPlaylistsNot))
    }

    /**
     * 扫描中且还没有歌 → 继续等（扫完可能就有歌了）。
     * 这一条与下一条**必须相反**：空曲库「扫描中」和「装载完了确实没有」处理完全不同。
     */
    @Test
    fun waitsWhileScanning() {
        val scanning = Gate(songsLoaded = true, hasSongs = false, scanning = true, playlistsLoaded = true)
        for (action in SHORTCUT_ACTIONS) assertFalse(action, canRun(action, scanning))
    }

    /**
     * 装载结束、确实没有歌 → 放行，让界面去弹「曲库里还没有歌曲」那句提示，而不是静默。
     * 首次安装（还没选扫描范围）走的就是这条路：必须**立刻**给出提示，不能一直等。
     */
    @Test
    fun letsThroughWhenTheLibraryIsConfirmedEmpty() {
        val empty = Gate(songsLoaded = true, hasSongs = false, scanning = false, playlistsLoaded = true)
        for (action in SHORTCUT_ACTIONS) assertTrue(action, canRun(action, empty))
    }

    /** 小组件深链只切界面、不碰曲库，任何时候都该立刻响应（跟曲库状态无关） */
    @Test
    fun widgetDeepLinksNeverWait() {
        val cold = Gate(songsLoaded = false, hasSongs = false, scanning = true, playlistsLoaded = false)
        for (action in listOf(AppShortcut.OPEN_PLAYER, AppShortcut.OPEN_LIBRARY, AppShortcut.OPEN_PLAYLISTS)) {
            assertTrue("$action 不该等曲库", canRun(action, cold))
        }
    }

    /** 判据的四个入参（对应 `LibraryUiState` 的四个字段） */
    private data class Gate(
        val songsLoaded: Boolean,
        val hasSongs: Boolean,
        val scanning: Boolean,
        val playlistsLoaded: Boolean,
    )

    private fun canRun(action: String, g: Gate): Boolean =
        AppShortcut.canRun(action, g.songsLoaded, g.hasSongs, g.scanning, g.playlistsLoaded)

    private companion object {
        val SHORTCUT_ACTIONS = listOf(AppShortcut.PLAY_ALL, AppShortcut.SHUFFLE, AppShortcut.FAVORITES)
    }
}
