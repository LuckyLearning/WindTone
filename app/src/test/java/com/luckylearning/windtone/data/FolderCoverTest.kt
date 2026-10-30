package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A6：同目录 folder.jpg / cover.jpg / AlbumArt.jpg 封面回退。
 *
 * 纯文件 / 纯函数，不碰 MediaMetadataRetriever。
 * Song.uri 构造需真实 Uri.parse，故挂 Robolectric（与 PlaylistCoverTest 等一致）。
 */
@RunWith(RobolectricTestRunner::class)
class FolderCoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `候选优先级 folder 高于 cover 高于 AlbumArt`() {
        assertEquals(
            "folder.jpg",
            FolderCover.pickName(listOf("cover.jpg", "AlbumArt.jpg", "folder.jpg", "other.png")),
        )
        assertEquals("cover.jpg", FolderCover.pickName(listOf("AlbumArt.jpg", "cover.jpg")))
        assertEquals("AlbumArt.jpg", FolderCover.pickName(listOf("readme.txt", "AlbumArt.jpg")))
        assertNull(FolderCover.pickName(listOf("art.png", "front.jpg")))
    }

    @Test
    fun `大小写不敏感且保留磁盘真实文件名`() {
        assertEquals("Folder.JPG", FolderCover.pickName(listOf("readme.txt", "Folder.JPG")))
        assertEquals("COVER.Jpg", FolderCover.pickName(listOf("COVER.Jpg")))
    }

    @Test
    fun `path 父目录命中 folder jpg`() {
        val dir = tmp.newFolder("album")
        val audio = File(dir, "track.flac").apply { writeText("audio") }
        val cover = File(dir, "folder.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val song = song(path = audio.absolutePath)
        assertEquals(cover.absolutePath, FolderCover.resolveFile(song)?.absolutePath)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), FolderCover.readBytes(song))
    }

    @Test
    fun `folderPath 目录也可回退`() {
        val dir = tmp.newFolder("by-folder-path")
        File(dir, "cover.jpg").writeBytes(byteArrayOf(9, 9))
        val song = song(path = null, folderPath = dir.absolutePath)
        assertArrayEquals(byteArrayOf(9, 9), FolderCover.readBytes(song))
    }

    @Test
    fun `空文件跳过 无候选返回 null`() {
        val dir = tmp.newFolder("bad")
        File(dir, "folder.jpg").writeBytes(ByteArray(0))
        val audio = File(dir, "a.mp3").apply { writeText("x") }
        assertNull(FolderCover.resolveFile(song(path = audio.absolutePath)))

        val dir2 = tmp.newFolder("none")
        val audio2 = File(dir2, "b.mp3").apply { writeText("x") }
        assertNull(FolderCover.resolveFile(song(path = audio2.absolutePath)))
    }

    @Test
    fun `远程来源不找文件夹封面`() {
        val dir = tmp.newFolder("remote-album")
        File(dir, "folder.jpg").writeBytes(byteArrayOf(1))
        val audio = File(dir, "c.mp3").apply { writeText("x") }
        val song = song(path = audio.absolutePath, source = SongSource.REMOTE)
        assertNull(FolderCover.resolveFile(song))
        assertNull(FolderCover.readBytes(song))
    }

    @Test
    fun `候选名列表钉死三项`() {
        assertEquals(listOf("folder.jpg", "cover.jpg", "AlbumArt.jpg"), FolderCover.CANDIDATES)
        assertTrue(FolderCover.MAX_BYTES == 8 * 1024 * 1024)
    }

    private fun song(
        path: String? = null,
        folderPath: String? = null,
        source: SongSource = SongSource.FILE,
    ): Song = Song(
        id = 1L,
        uri = Uri.parse("file:///music/track.flac"),
        title = "t",
        artist = "a",
        album = "al",
        displayName = "track.flac",
        path = path,
        folderPath = folderPath,
        source = source,
    )
}