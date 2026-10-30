package com.luckylearning.windtone.ui

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import com.luckylearning.windtone.data.model.TrackFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * N1 分享当前曲：文本格式与云盘/本地判定（不碰 FileProvider / 启动器）。
 */
@RunWith(RobolectricTestRunner::class)
class ShareCurrentSongTest {

    private fun song(
        title: String = "标题",
        artist: String? = "艺人",
        album: String? = "专辑",
        uri: String = "content://media/external/audio/media/1",
        path: String? = null,
        source: SongSource = SongSource.MEDIA_STORE,
        displayName: String = "track.flac",
        mimeType: String? = "audio/flac",
    ) = Song(
        id = 1L,
        uri = Uri.parse(uri),
        title = title,
        artist = artist,
        album = album,
        displayName = displayName,
        path = path,
        source = source,
        mimeType = mimeType,
    )

    @Test
    fun `文本三行 标题 艺人专辑 来源`() {
        val t = ShareCurrentSong.textOf(song())
        assertEquals(
            "标题\n艺人 · 专辑\n来自听风 WindTone",
            t,
        )
    }

    @Test
    fun `空标题回退 displayName`() {
        val t = ShareCurrentSong.textOf(song(title = "  ", displayName = "foo.mp3"))
        assertTrue(t.startsWith("foo.mp3\n"))
    }

    @Test
    fun `REMOTE 视为云端 不可附带`() {
        val s = song(uri = "https://example.com/a.mp3", source = SongSource.REMOTE)
        assertTrue(ShareCurrentSong.isCloudOrRemote(s))
        assertFalse(ShareCurrentSong.canAttachAudio(s))
    }

    @Test
    fun `阿里云盘 fragment 不可附带`() {
        val frag = TrackFragment.of("drive", "file")
        val s = song(uri = "https://cdn.example/x#$frag", source = SongSource.MEDIA_STORE)
        assertTrue(ShareCurrentSong.isCloudOrRemote(s))
        assertFalse(ShareCurrentSong.canAttachAudio(s))
    }

    @Test
    fun `http 直链不可附带`() {
        val s = song(uri = "http://host/a.mp3", source = SongSource.FOLDER)
        assertTrue(ShareCurrentSong.isCloudOrRemote(s))
        assertFalse(ShareCurrentSong.canAttachAudio(s))
    }

    @Test
    fun `MediaStore content 可附带`() {
        val s = song()
        assertFalse(ShareCurrentSong.isCloudOrRemote(s))
        assertTrue(ShareCurrentSong.canAttachAudio(s))
    }

    @Test
    fun `绝对路径可附带`() {
        val s = song(uri = "file:///storage/emulated/0/Music/a.flac", path = "/storage/emulated/0/Music/a.flac", source = SongSource.FILE)
        assertTrue(ShareCurrentSong.canAttachAudio(s))
    }
}
