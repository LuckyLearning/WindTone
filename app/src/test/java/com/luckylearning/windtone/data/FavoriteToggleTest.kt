package com.luckylearning.windtone.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.model.TrackFragment
import kotlinx.coroutines.runBlocking
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
 * FavoriteToggle：本地曲与阿里云盘曲的收藏切换，以及「认不出身份」时返回 null。
 * 磁贴与播放页红心共用这一层，这里锁住落盘语义。
 */
@RunWith(RobolectricTestRunner::class)
class FavoriteToggleTest {

    private lateinit var context: Context
    private lateinit var db: MusicDb
    private lateinit var index: SongIndex
    private lateinit var bus: MessageBus
    private lateinit var favorites: FavoriteToggle

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java).build()
        index = SongIndex()
        bus = MessageBus()
        favorites = FavoriteToggle(context, db, index, bus)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `本地曲按 mediaId 切换收藏并写入索引`() = runBlocking {
        val uri = "content://media/external/audio/media/42"
        val songId = db.songDao().insert(
            SongEntity(uri = uri, source = "MEDIA_STORE", title = "晴天"),
        )
        index.rebuild(
            listOf(
                com.luckylearning.windtone.data.model.Song(
                    id = 42L,
                    uri = android.net.Uri.parse(uri),
                    title = "晴天",
                    artist = "周杰伦",
                    album = null,
                    dbId = songId,
                ),
            ),
        )

        assertEquals(false, favorites.isFavorite(uri))
        assertTrue(favorites.toggleByMediaId(uri, "晴天") == true)
        assertTrue(favorites.isFavorite(uri) == true)
        assertTrue(index.stateOf(songId)!!.favorite)
        assertTrue(db.songStateDao().favoriteOf(songId) == true)

        assertTrue(favorites.toggleByMediaId(uri, "晴天") == false)
        assertFalse(index.stateOf(songId)!!.favorite)
    }

    @Test
    fun `阿里云盘曲走 AliyunTrackStore`() = runBlocking {
        val fragment = TrackFragment.of("drive", "file")
        val mediaId = TrackFragment.cacheKey(fragment)!!
        assertEquals(false, favorites.isFavorite(mediaId))
        assertTrue(favorites.toggleByMediaId(mediaId, "云曲") == true)
        assertTrue(AliyunTrackStore(context).get("drive", "file")!!.favorite)
        assertTrue(favorites.isFavorite(mediaId) == true)
        assertTrue(favorites.toggleByMediaId(mediaId, "云曲") == false)
    }

    @Test
    fun `未入库的本地 uri 无法收藏`() = runBlocking {
        assertNull(favorites.toggleByMediaId("content://media/external/audio/media/999"))
        assertNull(favorites.isFavorite("content://media/external/audio/media/999"))
    }
}
