package com.luckylearning.windtone.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「SharedPreferences → DataStore」双写迁移的**行为**验证。
 *
 * 迁移的命门是「用户设置一条都不能丢、不能退」。本版只切了写路径（读仍走
 * SharedPreferences，见 [Prefs] 类文档），所以这里钉的是写这一侧的保证：
 *  1. **存量不丢**：老用户升级后，SharedPreferences 里的值必须被搬进 DataStore；
 *  2. **双写不偏科**：经 `Prefs` 落盘的值必须同时进 DataStore，否则新存储会悄悄变旧；
 *  3. **夹取后才落库**：越界值先夹再写，不把非法值灌进新存储；
 *  4. **保序**：连着改同一个设置，DataStore 必须停在最新值（异步写不能乱序）；
 *  5. **读行为零回归**：写完立刻回读，拿到的必须还是刚写的值。
 *
 * 用 Robolectric：`Prefs` 要 `getSharedPreferences`，DataStore 要真实文件系统。
 */
@RunWith(RobolectricTestRunner::class)
class PrefsDataStoreMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每个用例都从「没有 Prefs 实例、两边存储都空」开始。若不清缓存，后一个用例拿到的是
        // 上一个用例建的实例（它的预热协程早跑完了），「构造即预热」这条就验不到。
        Prefs.clearCacheForTest()
        clearBoth()
    }

    @After
    fun tearDown() {
        clearBoth()
        Prefs.clearCacheForTest()
    }

    private fun sp() = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    private fun clearBoth() {
        sp().edit().clear().commit()
        runBlocking { SettingsDataStore(context).clearForTest() }
    }

    /** DataStore 当前内容（一次性读，读完就返回，不挂长期 collect） */
    private fun dsNow(): Preferences = runBlocking { SettingsDataStore(context).flowForTest().first() }

    /**
     * 轮询 DataStore 直到某键出现。双写是异步的，不能假设它立刻完成。
     *
     * 只做**一次性**的 `first()` 读（读完就结束），不用 `first { predicate }` 挂等：
     * `runBlocking` 的事件循环只驱动自己队列里的续体，驱动不了 Robolectric 那个永久暂停的
     * 主 looper。早期版本就是「挂等 + `Dispatchers.Main.immediate` 预热协程」这个组合把整个
     * 测试类挂死的 —— 等待一律拆成「读一次 + 睡一会儿」，从根上断掉这条链。
     */
    private fun awaitKey(key: String) = runBlocking {
        withTimeout(10_000) {
            while (true) {
                val p = dsNow()
                if (p.contains(booleanPreferencesKey(key)) ||
                    p.contains(intPreferencesKey(key)) ||
                    p.contains(stringPreferencesKey(key))
                ) {
                    return@withTimeout
                }
                delay(20)
            }
        }
    }

    /** 轮询 DataStore 直到这些键**全部**出现（多个 setter 是串行异步落盘的，只等第一个会漏） */
    private fun awaitKeys(vararg keys: String) = runBlocking {
        withTimeout(10_000) {
            while (true) {
                val p = dsNow()
                if (keys.all { k ->
                        p.contains(booleanPreferencesKey(k)) ||
                            p.contains(intPreferencesKey(k)) ||
                            p.contains(stringPreferencesKey(k))
                    }
                ) {
                    return@withTimeout
                }
                delay(20)
            }
        }
    }

    /** 轮询 DataStore 直到某键消失 */
    private fun awaitKeyGone(key: String) = runBlocking {
        withTimeout(10_000) {
            while (dsNow().contains(stringPreferencesKey(key))) delay(20)
        }
    }

    /** 轮询 DataStore 直到某个 Float 键等于目标值 */
    private fun awaitFloat(key: String, value: Float) = runBlocking {
        withTimeout(10_000) {
            while (dsNow()[floatPreferencesKey(key)] != value) delay(20)
        }
    }

    @Test
    fun `老用户存量经迁移后进 DataStore - 构造即预热无需手动干预`() {
        // 模拟一个老用户：设置只存在于 SharedPreferences
        sp().edit()
            .putBoolean("lyric_overlay", true)
            .putInt("lyric_overlay_lines", 4)
            .putString("library_view", "FOLDER")
            .commit()

        Prefs(context) // 生产入口：构造时就该把存量搬过去

        awaitKey("lyric_overlay")
        val p = dsNow()
        assertEquals(true, p[booleanPreferencesKey("lyric_overlay")])
        assertEquals(4, p[intPreferencesKey("lyric_overlay_lines")])
        assertEquals("FOLDER", p[stringPreferencesKey("library_view")])
        // 搬完落下完成标记：重复启动不会重搬
        assertEquals(true, p[booleanPreferencesKey("__ds_migrated_v1")])
    }

    @Test
    fun `经 Prefs 落盘的值同时进 DataStore - 双写不偏科`() {
        val prefs = Prefs(context)
        prefs.lyricOverlay = true
        prefs.lyricOverlayLines = 5
        prefs.libraryView = "PLAYLIST"

        awaitKeys("lyric_overlay", "lyric_overlay_lines", "library_view")

        // 新存储侧拿到了
        val p = dsNow()
        assertEquals(true, p[booleanPreferencesKey("lyric_overlay")])
        assertEquals(5, p[intPreferencesKey("lyric_overlay_lines")])
        assertEquals("PLAYLIST", p[stringPreferencesKey("library_view")])

        // 旧存储侧也必须同步写到位（双写）
        assertTrue(sp().getBoolean("lyric_overlay", false))
        assertEquals(5, sp().getInt("lyric_overlay_lines", 0))
        assertEquals("PLAYLIST", sp().getString("library_view", null))
    }

    @Test
    fun `夹取后的值才落 DataStore - 不会把越界值灌进新存储`() {
        val prefs = Prefs(context)
        // 行数合法区间 1..5，传 99 应夹成 5
        prefs.lyricOverlayLines = 99

        awaitKey("lyric_overlay_lines")

        assertEquals(5, dsNow()[intPreferencesKey("lyric_overlay_lines")])
        assertEquals(5, prefs.lyricOverlayLines)
    }

    @Test
    fun `按视图的排序键同样双写，重置时两边一起清`() {
        val prefs = Prefs(context)
        prefs.setSortOrder("SONGS", SortOrder.ARTIST)
        awaitKey("sort_SONGS")
        assertEquals("ARTIST", dsNow()[stringPreferencesKey("sort_SONGS")])

        prefs.resetSortOrder("SONGS")
        awaitKeyGone("sort_SONGS")
        assertFalse(dsNow().contains(stringPreferencesKey("sort_SONGS")))
        assertFalse(sp().contains("sort_SONGS"))
    }

    @Test
    fun `连着改同一个设置 - DataStore 停在最新值`() {
        // 异步双写最怕乱序：若两次写落到不同线程，DataStore 可能停在旧值而 SharedPreferences
        // 已是新值，两边从此分家。Prefs 的写作用域并行度锁成 1，正是为了这个。
        val prefs = Prefs(context)
        prefs.playbackSpeed = 0.75f
        prefs.playbackSpeed = 1.5f
        prefs.playbackSpeed = 1.25f

        awaitFloat(Prefs.KEY_SPEED, 1.25f)
        assertEquals(1.25f, dsNow()[floatPreferencesKey(Prefs.KEY_SPEED)]!!, 1e-6f)
        assertEquals(1.25f, sp().getFloat(Prefs.KEY_SPEED, 0f), 1e-6f)
    }

    @Test
    fun `读行为零回归 - 写完立刻回读得到刚写的值`() {
        // 锁住「切写路径不许动读路径」：读只走 SharedPreferences，而 setter 的 SP 写是同步的，
        // 所以写完立刻回读必然拿到刚写的值。哪天有人把 getter 改读 DataStore 的快照，这条会红。
        val prefs = Prefs(context)
        prefs.playbackSpeed = 1.5f
        assertEquals(1.5f, prefs.playbackSpeed, 1e-6f)

        prefs.lyricOverlayLines = 3
        assertEquals(3, prefs.lyricOverlayLines)

        prefs.lyricOverlay = true
        assertTrue(prefs.lyricOverlay)

        prefs.libraryView = "ALBUM"
        assertEquals("ALBUM", prefs.libraryView)
    }

    @Test
    fun `全新安装 - 两边皆空时读返回出厂默认`() {
        val prefs = Prefs(context)
        assertFalse(prefs.lyricOverlay)
        assertEquals(SettingsDefaults.OVERLAY_LINES, prefs.lyricOverlayLines)
        assertEquals(1.0f, prefs.playbackSpeed, 1e-6f)
    }
}
