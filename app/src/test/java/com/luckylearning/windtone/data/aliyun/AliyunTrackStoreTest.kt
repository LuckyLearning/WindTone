package com.luckylearning.windtone.data.aliyun

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AliyunTrackStoreTest {

    private val store = AliyunTrackStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `第一次见到用文件名和文件夹，标签再盖过去`() {
        store.remember("d", "f", "周杰伦 - 晴天.flac", "范特西", "parent")
        val first = store.get("d", "f")!!
        assertEquals("晴天", first.title)
        assertEquals("周杰伦", first.artist)
        assertEquals("范特西", first.album)
        assertEquals("parent", first.parentFileId)

        store.applyTags("d", "f", "Sunny", "Jay", "Fantasy", 200_000)
        val tagged = store.get("d", "f")!!
        assertEquals("Sunny", tagged.title)
        assertEquals("Jay", tagged.artist)
        assertEquals("Fantasy", tagged.album)
        assertEquals(200_000, tagged.durationMs)

        store.applyTags("d", "f", "", null, null, 0)
        assertEquals("Sunny", store.get("d", "f")!!.title)
        assertEquals("Jay", store.get("d", "f")!!.artist)
    }

    @Test
    fun `收藏次数和收听时长只记在这条身份上`() {
        store.remember("d", "f", "a.mp3", null, null)
        assertNull(store.get("d", "f")!!.album)
        assertTrue(store.toggleFavorite("d", "f"))
        val mediaId = store.get("d", "f")!!.key
        store.recordPlay(mediaId, 10)
        store.recordPlay(mediaId, 20)
        store.addListenTime(mediaId, 5_000, 20)
        val row = store.get("d", "f")!!
        assertEquals(setOf(mediaId), store.favoriteKeys())
        assertEquals(2, row.playCount)
        assertEquals(20, row.lastPlayedAt)
        assertEquals(5_000, row.playedMs)
        assertEquals(5_000, store.listenBuckets().single().ms)
        assertEquals(listOf("f"), store.favorites().map { it.fileId })
        assertEquals(listOf("f"), store.recent().map { it.fileId })
        assertEquals(listOf("f"), store.mostPlayed().map { it.fileId })
    }

    @Test
    fun `收听时长按小时累加，超出保留期的桶会丢掉`() {
        val mediaId = "aliyun:ay=d/f"
        store.addListenTime(mediaId, 1_000, 0L)
        val recent = (400L * 24 + 5) * 3_600_000L
        store.addListenTime(mediaId, 2_000, recent)
        store.addListenTime(mediaId, 3_000, recent)
        val buckets = store.listenBuckets()
        assertEquals(1, buckets.size)
        assertEquals(recent / 3_600_000L, buckets.single().hourEpoch)
        assertEquals(5_000, buckets.single().ms)
    }

    @Test
    fun `续播队列只存身份，清空后读不到`() {
        store.saveQueue(
            AliyunQueueSnapshot(
                items = listOf(AliyunQueueItem("d", "f", "晴天.flac", "范特西", "p")),
                index = 0,
                positionMs = 1_200,
            ),
        )
        val loaded = store.loadQueue()!!
        assertEquals("d", loaded.items.single().driveId)
        assertEquals("f", loaded.items.single().fileId)
        assertEquals(1_200, loaded.positionMs)
        store.saveQueue(null)
        assertNull(store.loadQueue())
    }
}
