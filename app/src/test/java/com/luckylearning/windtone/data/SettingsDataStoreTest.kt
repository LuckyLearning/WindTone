package com.luckylearning.windtone.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SettingsDataStore] —— DataStore 迁移的「新存储侧」。
 *
 * 钉四件事：存量能按**运行时类型**通盘搬过来（含动态的 `sort_<视图>` 键）、
 * 搬完幂等不会重搬、typed 读写能 round-trip、以及 remove 对只知道键名的动态键也管用。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDataStoreTest {

    private lateinit var context: Context
    private lateinit var store: SettingsDataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearBoth()
        store = SettingsDataStore(context)
    }

    @After
    fun tearDown() {
        clearBoth()
    }

    private fun sp() = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    private fun clearBoth() {
        sp().edit().clear().commit()
        runBlocking { SettingsDataStore(context).clearForTest() }
    }

    @Test
    fun `backfill 把各类型存量搬进 DataStore`() = runBlocking {
        sp().edit().clear().commit()
        sp().edit()
            .putBoolean("lyric_overlay", true)
            .putInt("lyric_overlay_lines", 3)
            .putLong("last_scan_at", 1_725_000_000_000L)
            .putFloat("lyric_scale", 1.5f)
            .putString("library_view", "ALBUM")
            .putStringSet("lyric_overlay_hidden_apps", setOf("com.a", "com.b"))
            .commit()

        store.backfillFrom(sp())

        val p = withTimeout(5_000) { store.flowForTest().first() }
        assertEquals(true, p[booleanPreferencesKey("lyric_overlay")])
        assertEquals(3, p[intPreferencesKey("lyric_overlay_lines")])
        assertEquals(1_725_000_000_000L, p[longPreferencesKey("last_scan_at")])
        assertEquals(1.5f, p[floatPreferencesKey("lyric_scale")])
        assertEquals("ALBUM", p[stringPreferencesKey("library_view")])
        assertEquals(setOf("com.a", "com.b"), p[stringSetPreferencesKey("lyric_overlay_hidden_apps")])
    }

    @Test
    fun `backfill 覆盖动态排序键`() = runBlocking {
        // 排序键是「sort_ + 视图名」动态拼的，列清单必然漏 —— 通盘搬才靠得住
        sp().edit().clear().commit()
        sp().edit().putString("sort_SONGS", "TITLE").putString("sort_ALBUM", "ARTIST").commit()

        store.backfillFrom(sp())

        val p = withTimeout(5_000) { store.flowForTest().first() }
        assertEquals("TITLE", p[stringPreferencesKey("sort_SONGS")])
        assertEquals("ARTIST", p[stringPreferencesKey("sort_ALBUM")])
    }

    @Test
    fun `backfill 幂等 - 已迁移后不会用 SP 旧值覆盖 DS 新值`() = runBlocking {
        sp().edit().clear().commit()
        sp().edit().putBoolean("lyric_overlay", false).commit()
        store.backfillFrom(sp())

        // 迁移完成后设置又改过一次：DataStore 已是新值，SharedPreferences 因某种原因停在旧值。
        // 此时再触发 backfill 必须直接返回 —— 否则会把 DataStore 的新值覆盖回旧值（用户设置倒退）。
        store.putBoolean("lyric_overlay", true)
        sp().edit().putBoolean("lyric_overlay", false).commit()

        store.backfillFrom(sp())

        val p = withTimeout(5_000) { store.flowForTest().first() }
        assertEquals(true, p[booleanPreferencesKey("lyric_overlay")])
    }

    @Test
    fun `空 SharedPreferences 也能安全 backfill 并落下完成标记`() = runBlocking {
        sp().edit().clear().commit()
        store.backfillFrom(sp())

        val p = withTimeout(5_000) { store.flowForTest().first() }
        // 只有迁移标记，没有任何设置键
        assertTrue(p.asMap().keys.all { it.name == "__ds_migrated_v1" })
    }

    @Test
    fun `typed 写入能 round-trip`() = runBlocking {
        store.putBoolean("b", true)
        store.putInt("i", 42)
        store.putLong("l", 7L)
        store.putFloat("f", 0.25f)
        store.putString("s", "hello")
        store.putStringSet("ss", setOf("x", "y"))

        val p = withTimeout(5_000) {
            store.flowForTest().first { it.contains(booleanPreferencesKey("b")) && it.contains(stringSetPreferencesKey("ss")) }
        }
        assertEquals(true, p[booleanPreferencesKey("b")])
        assertEquals(42, p[intPreferencesKey("i")])
        assertEquals(7L, p[longPreferencesKey("l")])
        assertEquals(0.25f, p[floatPreferencesKey("f")])
        assertEquals("hello", p[stringPreferencesKey("s")])
        assertEquals(setOf("x", "y"), p[stringSetPreferencesKey("ss")])
    }

    @Test
    fun `remove 删掉指定键`() = runBlocking {
        store.putString("k", "v")
        store.remove("k")
        val p = withTimeout(5_000) { store.flowForTest().first { !it.contains(stringPreferencesKey("k")) } }
        assertNull(p[stringPreferencesKey("k")])
        assertFalse(p.contains(stringPreferencesKey("k")))
    }

    @Test
    fun `remove 不认识的类型也不会误伤别的键`() = runBlocking {
        // 动态排序键是 String，但 remove 只拿得到键名 —— 六种类型全试，命中即删
        store.putString("sort_SONGS", "TITLE")
        store.putInt("lyric_overlay_lines", 3)
        store.remove("sort_SONGS")

        val p = withTimeout(5_000) {
            store.flowForTest().first { !it.contains(stringPreferencesKey("sort_SONGS")) }
        }
        assertFalse(p.contains(stringPreferencesKey("sort_SONGS")))
        assertEquals(3, p[intPreferencesKey("lyric_overlay_lines")])
    }
}
