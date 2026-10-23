package com.luckylearning.windtone.data.aliyun

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AliyunPlaylistEditTest {

    @Test
    fun `歌单可删一首也可调顺序，没点到的留在末尾`() {
        val playlists = AliyunSavedPlaylists(ApplicationProvider.getApplicationContext())
        val added = playlists.add(
            "专辑",
            "d",
            "根",
            listOf(
                AliyunScanRules.ScanHit("a", "a.mp3", "根", "parent"),
                AliyunScanRules.ScanHit("b", "b.mp3", "根", "parent"),
                AliyunScanRules.ScanHit("c", "c.mp3", "根", "parent"),
            ),
        )
        assertEquals("parent", playlists.find(added.id)!!.files.first().parentFileId)
        playlists.reorder(added.id, listOf("c", "a"))
        assertEquals(listOf("c", "a", "b"), playlists.find(added.id)!!.files.map { it.fileId })
        playlists.removeFile(added.id, "a")
        assertEquals(listOf("c", "b"), playlists.find(added.id)!!.files.map { it.fileId })
    }
}
