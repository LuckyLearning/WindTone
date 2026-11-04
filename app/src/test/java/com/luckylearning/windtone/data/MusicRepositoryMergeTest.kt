package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 三来源合并的口径回归。
 *
 * 为什么值得单独钉：`v1.1.33` 起深度扫描对「媒体库已索引的文件」不再读标签，
 * 产出的条目只有文件名、没有时长/歌手/专辑。如果合并逻辑还是「后来的整条替换」，
 * 这一条改动就会把**所有中文标签冲成文件名**、把专辑 ID 冲成 0 —— 曲库看起来还在，
 * 但排序、专辑分组、封面全会坏掉。所以这里把两个方向都钉死。
 */
@RunWith(RobolectricTestRunner::class)
class MusicRepositoryMergeTest {

    private fun song(
        title: String = "夜航",
        artist: String? = "陈鸿宇",
        album: String? = "一如年少模样",
        albumId: Long = 88L,
        durationMs: Long = 240_000L,
        path: String? = null,
        lrcUri: String? = null,
        dbId: Long = 7L,
        id: Long = 100L,
        source: SongSource = SongSource.MEDIA_STORE,
    ) = Song(
        id = id,
        uri = Uri.parse("content://media/external/audio/media/$id"),
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        sizeBytes = 10_000_000L,
        displayName = "01_tide.flac",
        path = path,
        source = source,
        lrcUri = lrcUri?.let { Uri.parse(it) },
        dbId = dbId,
    )

    /** 媒体库条目 + 深扫产出的轻量条目（不读标签）：标签与专辑 ID 必须留住 */
    @Test
    fun lightIncomingKeepsMediaStoreTags() {
        val media = song(albumId = 88L)
        val light = song(
            title = "01_tide",
            artist = null,
            album = null,
            albumId = 0L,
            durationMs = 0L,
            path = "/storage/emulated/0/Music/01_tide.flac",
            lrcUri = "file:///storage/emulated/0/Music/01_tide.lrc",
            dbId = 0L,
            id = 999L,
            source = SongSource.FILE,
        )

        val merged = MusicRepository.mergeSong(media, light)

        assertEquals("中文标签不能被子文件名冲掉", "夜航", merged.title)
        assertEquals("陈鸿宇", merged.artist)
        assertEquals("一如年少模样", merged.album)
        assertEquals("专辑 ID 冲成 0 会让专辑封面全部丢失", 88L, merged.albumId)
        assertEquals(240_000L, merged.durationMs)
        assertEquals("深扫补上的歌词要留下", "file:///storage/emulated/0/Music/01_tide.lrc", merged.lrcUri.toString())
        assertEquals("深扫补上的真实路径要留下", "/storage/emulated/0/Music/01_tide.flac", merged.path)
        assertEquals("曲库主键必须保留，否则收藏/歌单/统计全部脱钩", 7L, merged.dbId)
    }

    /** 反过来：先来的轻量条目、后来的完整条目 —— 完整信息应该顶上来，但主键仍是旧的 */
    @Test
    fun richIncomingUpgradesLightExisting() {
        val light = song(
            title = "01_tide", artist = null, album = null, albumId = 0L,
            durationMs = 0L, dbId = 7L, id = 999L, source = SongSource.FILE,
            lrcUri = "file:///storage/emulated/0/Music/01_tide.lrc",
        )
        val rich = song(albumId = 88L, dbId = 0L, id = 100L)

        val merged = MusicRepository.mergeSong(light, rich)

        assertEquals("夜航", merged.title)
        assertEquals("陈鸿宇", merged.artist)
        assertEquals(88L, merged.albumId)
        assertEquals(240_000L, merged.durationMs)
        assertEquals("主键取旧的那条", 7L, merged.dbId)
        assertEquals("旧条目独有的歌词不能因为被升级而丢掉", "file:///storage/emulated/0/Music/01_tide.lrc", merged.lrcUri.toString())
    }

    /** 两条都完整：保留先到的那条，不来回抖 */
    @Test
    fun bothRichKeepsExisting() {
        val first = song(title = "夜航", artist = "陈鸿宇")
        val second = song(title = "夜航 (Live)", artist = "陈鸿宇", albumId = 0L)

        val merged = MusicRepository.mergeSong(first, second)

        assertEquals("夜航", merged.title)
        assertEquals(88L, merged.albumId)
    }

    /** 没有歌词的两条合并后仍然是「无歌词」，不能凭空造出一个 Uri */
    @Test
    fun noLyricsStaysNull() {
        val merged = MusicRepository.mergeSong(song(), song(title = "01_tide", durationMs = 0L))
        assertNull(merged.lrcUri)
    }

    /**
     * 全盘扫描顺带产出的指纹，与独立 fingerprint() 查询的格式必须逐字一致。
     * 前者写进 prefs、后者在冷启动查；格式一漂移，10 分钟节流就永远跳不过去。
     */
    @Test
    fun scanFingerprintUsesStandaloneQueryFormat() {
        assertEquals("0:0", MediaStoreScanner.formatFingerprint(0L, 0))
        assertEquals("12345:678", MediaStoreScanner.formatFingerprint(12345L, 678))
    }
}
