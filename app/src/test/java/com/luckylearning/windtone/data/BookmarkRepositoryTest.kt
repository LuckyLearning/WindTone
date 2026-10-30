package com.luckylearning.windtone.data

import android.content.SharedPreferences
import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * H3 播放书签（Prefs JSON）：多条排序、删除、按 stableKey 隔离。
 * 内存 SharedPreferences，避免 Robolectric / Maven 429。
 */
@RunWith(RobolectricTestRunner::class)
class BookmarkRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var bus: MessageBus
    private lateinit var repo: BookmarkRepository

    @Before
    fun setUp() {
        prefs = MemoryPrefs()
        bus = MessageBus()
        repo = BookmarkRepository(prefs, bus)
    }

    @After
    fun tearDown() = runBlocking { repo.clearAll() }

    private fun song(uri: String, title: String = "Audiobook") = Song(
        id = 1L,
        uri = Uri.parse(uri),
        title = title,
        artist = null,
        album = null,
        source = SongSource.FOLDER,
    )

    @Test
    fun formatClock_hoursAndMinutes() {
        assertEquals("0:05", BookmarkRepository.formatClock(5_000))
        assertEquals("1:02", BookmarkRepository.formatClock(62_000))
        assertEquals("1:05:09", BookmarkRepository.formatClock(3_909_000))
    }

    @Test
    fun addListDelete_orderedByPosition() = runBlocking {
        val s = song("content://media/book1")
        repo.addAt(s, 60_000L, announce = false)
        repo.addAt(s, 10_000L, announce = false)
        repo.addAt(s, 120_000L, note = "第三章", announce = false)
        val list = repo.listForSong(s)
        assertEquals(3, list.size)
        assertEquals(10_000L, list[0].positionMs)
        assertEquals(60_000L, list[1].positionMs)
        assertEquals(120_000L, list[2].positionMs)
        assertEquals("第三章", list[2].note)
        repo.delete(list[1].id, announce = false)
        assertEquals(2, repo.listForSong(s).size)
    }

    @Test
    fun tracksAreIsolated() = runBlocking {
        val a = song("content://media/a")
        val b = song("content://media/b")
        repo.addAt(a, 1_000L, announce = false)
        repo.addAt(b, 2_000L, announce = false)
        assertEquals(1, repo.listForSong(a).size)
        assertEquals(1, repo.listForSong(b).size)
        assertEquals(1_000L, repo.listForSong(a).single().positionMs)
    }

    @Test
    fun survivesReloadFromPrefs() = runBlocking {
        val s = song("content://media/persist")
        assertNotNull(repo.addAt(s, 9_000L, note = "mid", announce = false))
        val again = BookmarkRepository(prefs, bus)
        val list = again.listForSong(s)
        assertEquals(1, list.size)
        assertEquals(9_000L, list[0].positionMs)
        assertEquals("mid", list[0].note)
        assertTrue(list[0].id.isNotBlank())
    }

    private class MemoryPrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                map.putAll(pending)
                pending.clear(); removals.clear(); clearAll = false
            }
        }
    }
}
