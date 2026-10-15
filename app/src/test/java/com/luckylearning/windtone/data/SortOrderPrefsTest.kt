package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.ui.defaultSort
import com.luckylearning.windtone.ui.labelFor
import com.luckylearning.windtone.ui.sortOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 排序下沉到各视图（v1.1.4 需求 4）的存储层回归。
 *
 * ## 为什么要测
 *
 * 排序以前是**一个全局值**（`Prefs` 里只有一个 `sort` 键）。改成按视图各存一份之后，
 * 新键是 `sort_<视图名>`，旧键 `sort` 留着给老用户做兼容回落。
 *
 * 这套「新键 → 旧键 → 各视图默认值」的三级回落里，每一级错了都不会报错：
 *  - 键名拼错 → 永远读不到自己的选择，用户改完排序退出再进就变回去；
 *  - 少回落一级 → 老用户升级后排序全被打回默认；
 *  - 多回落一级（比如读一次旧键就清掉） → 另一个视图拿不到兼容值。
 *
 * ⚠️ 这里刻意用**字面量 `"sort"`** 而不是引用常量：兼容性依赖的正是磁盘上那个键名，
 * 常量改名时这条测试必须一起红 —— 那说明老用户的数据真的读不到了。
 */
@RunWith(RobolectricTestRunner::class)
class SortOrderPrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPreferences().edit().clear().commit()
        prefs = Prefs(context)
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
    }

    /** 五个视图各存各的：改「歌曲」的排序不能连带改「专辑」的 */
    @Test
    fun eachViewKeepsItsOwnOrder() {
        prefs.setSortOrder(LibraryView.SONGS.name, SortOrder.DURATION)

        assertEquals(
            SortOrder.DURATION,
            prefs.sortOrder(LibraryView.SONGS.name, LibraryView.SONGS.defaultSort),
        )
        // 没写过新键的视图拿到的是**它自己的**默认值，不是「上一个视图的选择」
        assertEquals(
            LibraryView.ALBUM.defaultSort,
            prefs.sortOrder(LibraryView.ALBUM.name, LibraryView.ALBUM.defaultSort),
        )
        assertEquals(SortOrder.ALBUM, LibraryView.ALBUM.defaultSort)
    }

    /** 老数据的全局键只在「本视图还没写过新键」时生效，且**读多少次都不会被清掉** */
    @Test
    fun legacyGlobalKeyIsHonouredUntilAViewWritesItsOwn() {
        rawPreferences().edit().putString("sort", SortOrder.ARTIST.name).commit()

        // 全新 Prefs：模拟升级后第一次启动
        val upgraded = Prefs(context)
        assertEquals(
            SortOrder.ARTIST,
            upgraded.sortOrder(LibraryView.SONGS.name, LibraryView.SONGS.defaultSort),
        )

        upgraded.setSortOrder(LibraryView.SONGS.name, SortOrder.DURATION)
        assertEquals(
            SortOrder.DURATION,
            upgraded.sortOrder(LibraryView.SONGS.name, LibraryView.SONGS.defaultSort),
        )
        // 另一个视图仍读得到旧键 —— 回落是「新键没写过就读它」，不是「读一次就消费掉」
        assertEquals(
            SortOrder.ARTIST,
            upgraded.sortOrder(LibraryView.FOLDER.name, LibraryView.FOLDER.defaultSort),
        )
    }

    /** 「恢复默认」= 删掉本视图的覆盖，回落链条重新生效（订单：新键 → 旧键 → 默认值） */
    @Test
    fun resetDropsTheOverlayAndFallsBackAgain() {
        rawPreferences().edit().putString("sort", SortOrder.ARTIST.name).commit()
        prefs.setSortOrder(LibraryView.SONGS.name, SortOrder.DURATION)

        prefs.resetSortOrder(LibraryView.SONGS.name)

        assertEquals(
            SortOrder.ARTIST,
            prefs.sortOrder(LibraryView.SONGS.name, LibraryView.SONGS.defaultSort),
        )

        // 旧键也没有时，才轮到各视图的默认值
        rawPreferences().edit().remove("sort").commit()
        assertEquals(
            SortOrder.TITLE,
            prefs.sortOrder(LibraryView.SONGS.name, LibraryView.SONGS.defaultSort),
        )
    }

    /** 存进去的名字认不出来（手改 / 从新版本降级）时回落到默认值，不能崩 */
    @Test
    fun unknownStoredValueFallsBackToDefault() {
        rawPreferences().edit().putString("sort_SONGS", "NOT_A_SORT_ORDER").commit()
        assertEquals(
            SortOrder.TITLE,
            prefs.sortOrder(LibraryView.SONGS.name, LibraryView.SONGS.defaultSort),
        )
    }

    /**
     * 每个视图的排序菜单都必须包含它自己的默认项。
     *
     * 否则「恢复默认」会选出一个菜单里根本列不出来的值 —— 车机上的排序键是按
     * `sortOptions` 依次轮换的，落到菜单外就等于那个按钮再也转不回来。
     */
    @Test
    fun everyViewOffersItsOwnDefault() {
        LibraryView.entries.forEach { view ->
            assertTrue(
                "${view.name} 的排序菜单里没有它自己的默认项 ${view.defaultSort}",
                view.sortOptions.contains(view.defaultSort),
            )
        }
    }

    /** 歌单视图必须能选「按歌单内顺序」—— 那是它唯一说得通的默认（用户自己拖出来的顺序） */
    @Test
    fun playlistViewDefaultsToItsOwnOrder() {
        assertEquals(SortOrder.PLAYLIST_ORDER, LibraryView.PLAYLIST.defaultSort)
        assertTrue(LibraryView.PLAYLIST.sortOptions.contains(SortOrder.PLAYLIST_ORDER))
    }

    /** 同一个枚举在不同视图里叫不同名字：「按标题」在文件夹视图里其实是「按文件夹名」 */
    @Test
    fun labelFollowsTheView() {
        assertEquals("按文件夹名", SortOrder.TITLE.labelFor(LibraryView.FOLDER))
        assertEquals("按专辑名", SortOrder.ALBUM.labelFor(LibraryView.ALBUM))
        assertEquals("按歌手名", SortOrder.ARTIST.labelFor(LibraryView.ARTIST))
        // 视图不改变语义时用枚举自带的标签
        assertEquals("按标题", SortOrder.TITLE.labelFor(LibraryView.SONGS))
        assertFalse(
            "文件夹视图的「按标题」不该还是「按标题」",
            SortOrder.TITLE.labelFor(LibraryView.FOLDER) == SortOrder.TITLE.label,
        )
    }

    /** 各视图的排序项本来就该不一样 —— 「专辑」视图列「按文件名」没有意义 */
    @Test
    fun viewSortOptionsAreTrimmedToWhatMakesSense() {
        assertFalse(LibraryView.ALBUM.sortOptions.contains(SortOrder.FILE_NAME))
        assertFalse(LibraryView.SONGS.sortOptions.contains(SortOrder.PLAYLIST_ORDER))
        assertTrue(LibraryView.FOLDER.sortOptions.contains(SortOrder.FOLDER_PATH))
        // 全集有 8 项，但没有任何一个视图需要全列出
        LibraryView.entries.forEach { view ->
            assertTrue("${view.name} 的排序项多得不合理", view.sortOptions.size <= 6)
        }
    }

    private fun rawPreferences() =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
}
