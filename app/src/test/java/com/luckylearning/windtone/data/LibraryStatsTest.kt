package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 曲库「格式与容量」统计的口径回归（v1.1.17）。
 *
 * 这一页的每个数字用户都会当真（"我把多少空间花在 FLAC 上"），而输入很脏：
 * 文件名可能没有后缀、老设备 `DISPLAY_NAME` 为空、SAF 拿到的 `length()` 是 0、
 * 同一格式有 `wave` / `wav` 两种写法。这些判定全部在纯函数里，正好逐条钉死。
 *
 * 需要 Robolectric 只因为 [Song] 的 `uri` 是 `android.net.Uri`。
 */
@RunWith(RobolectricTestRunner::class)
class LibraryStatsTest {

    private fun song(
        name: String,
        sizeBytes: Long = 5_000_000L,
        durationMs: Long = 200_000L,
        mime: String? = null,
        path: String? = null,
    ) = Song(
        id = name.hashCode().toLong(),
        uri = Uri.parse("content://media/external/audio/media/${name.hashCode()}"),
        title = name.substringBeforeLast('.'),
        artist = "陈屿",
        album = "退潮",
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        mimeType = mime,
        displayName = name,
        path = path,
    )

    // ---------------------------------------------------------------- 格式识别

    @Test
    fun extensionIsReadFromDisplayNameAndLowercased() {
        assertEquals("mp3", formatOf(song("退潮.MP3")))
        assertEquals("flac", formatOf(song("退潮.flac")))
        assertEquals("m4a", formatOf(song("退潮.m4a")))
    }

    /**
     * 别名归一：同一格式的两种写法必须落进同一行，否则用户会看到
     * 「WAV 一半 / WAVE 一半」两个条目，还得自己脑补它们是同一个东西。
     */
    @Test
    fun aliasesCollapseIntoSingleFormat() {
        assertEquals("wav", formatOf(song("a.wave")))
        assertEquals("ogg", formatOf(song("a.oga")))
        assertEquals("m4a", formatOf(song("a.m4b")))
        val stats = buildLibraryStats(
            listOf(song("a.wav", 1000), song("b.wave", 2000), song("c.WAV", 3000)),
        )
        assertEquals(1, stats.formatCount)
        assertEquals(3, stats.formats.single().songs)
        assertEquals(6000L, stats.formats.single().bytes)
    }

    /**
     * 文件名没后缀时退到绝对路径，最后才看 MIME。
     *
     * 顺序不能颠倒：MediaStore 里 `MIME` 常年写 `audio/mpeg`（连 .m4a 都可能这么报），
     * 先信 MIME 会把一半 FLAC 统计成 MP3 —— 而这个错误看起来完全合理，没人会去查。
     */
    @Test
    fun fallsBackToPathThenMime() {
        assertEquals("flac", formatOf(song("无后缀文件", path = "/storage/emulated/0/Music/x.flac")))
        assertEquals("mp3", formatOf(song("无后缀文件", mime = "audio/mpeg")))
        assertEquals("m4a", formatOf(song("无后缀文件", mime = "audio/mp4; codecs=mp4a")))
    }

    /**
     * 文件名有后缀就**压过** MIME：`audio/mpeg` + `.m4a` 这种常见错配，
     * 必须按用户看得见的那个后缀算。
     */
    @Test
    fun filenameExtensionBeatsMisleadingMime() {
        assertEquals("m4a", formatOf(song("退潮.m4a", mime = "audio/mpeg")))
    }

    @Test
    fun unknownFormatBucketsTogetherAsEmptyExtension() {
        val stats = buildLibraryStats(listOf(song("乱码", mime = "application/octet-stream"), song("a.mp3")))
        assertEquals(1, stats.unknownFormatSongs)
        val unknown = stats.formats.first { it.ext.isEmpty() }
        assertEquals("未知格式", unknown.label)
    }

    // ---------------------------------------------------------------- 聚合与排序

    @Test
    fun aggregatesCountBytesAndDurationPerFormat() {
        val stats = buildLibraryStats(
            listOf(
                song("a.mp3", 4_000_000, 200_000),
                song("b.mp3", 6_000_000, 300_000),
                song("c.flac", 40_000_000, 400_000),
            ),
        )
        assertEquals(3, stats.totalSongs)
        assertEquals(50_000_000L, stats.totalBytes)
        assertEquals(900_000L, stats.totalDurationMs)
        // 占用降序：FLAC 一个人压过两首 MP3
        assertEquals(listOf("flac", "mp3"), stats.formats.map { it.ext })
        assertEquals(40_000_000L, stats.formats[0].bytes)
        assertEquals(10_000_000L, stats.formats[1].bytes)
        assertEquals(2, stats.formats[1].songs)
    }

    /** 占用相同时排序必须稳定（首数降序、再按扩展名升序），否则每次进页面顺序都在跳 */
    @Test
    fun orderingIsStableWhenBytesTie() {
        val stats = buildLibraryStats(
            listOf(song("a.opus", 1_000), song("b.flac", 1_000), song("c.flac", 0)),
        )
        // 1_000 字节的有 flac(2 首) 与 opus(1 首) → 首数多的在前
        assertEquals(listOf("flac", "opus"), stats.formats.map { it.ext })
    }

    @Test
    fun shareIsRatioOfTotalBytes() {
        val stats = buildLibraryStats(listOf(song("a.flac", 30_000_000), song("b.mp3", 10_000_000)))
        val flac = stats.formats.first { it.ext == "flac" }
        assertEquals(0.75f, stats.shareOf(flac), 0.0001f)
    }

    @Test
    fun averageBytesRoundsDownToWholeFiles() {
        val stats = buildLibraryStats(listOf(song("a.mp3", 3_000_000), song("b.mp3", 4_000_000)))
        assertEquals(3_500_000L, stats.averageBytes)
        assertEquals(3_500_000L, stats.formats.single().averageBytes)
    }

    // ---------------------------------------------------------------- 脏数据

    /**
     * 大小为 0 的歌**只计首数、不计占用**，并单独报出条数 —— 否则用户会发现
     * "占用加起来跟文件管理里对不上"，而界面上没有任何线索。
     */
    @Test
    fun zeroSizeSongsAreCountedButAddNothing() {
        val stats = buildLibraryStats(
            listOf(song("a.mp3", sizeBytes = 0), song("b.mp3", 1_000_000), song("c.flac", sizeBytes = 0)),
        )
        assertEquals(3, stats.totalSongs)
        assertEquals(1_000_000L, stats.totalBytes)
        assertEquals(2, stats.unknownSizeSongs)
    }

    @Test
    fun averageKbpsIsComputedFromBytesAndDuration() {
        // 4 MB / 200 秒 = 160 kbps
        assertEquals(160, buildLibraryStats(listOf(song("a.mp3", 4_000_000, 200_000))).formats.single().averageKbps)
    }

    @Test
    fun averageKbpsIsZeroWhenDurationUnknown() {
        assertEquals(0, buildLibraryStats(listOf(song("a.mp3", 4_000_000, 0))).formats.single().averageKbps)
    }

    // ---------------------------------------------------------------- 无损 / 有损

    /**
     * `m4a` / `mp4` 归有损：它们既可能是 ALAC 也可能是 AAC，光看扩展名判不出来。
     * 这条如果反过来写（m4a 算无损），无损占用的数字会凭空翻倍，且无法解释。
     */
    @Test
    fun losslessSplitExcludesM4a() {
        val stats = buildLibraryStats(
            listOf(song("a.flac", 30_000_000), song("b.wav", 20_000_000), song("c.m4a", 8_000_000), song("d.mp3", 2_000_000)),
        )
        assertEquals(2, stats.losslessSongs)
        assertEquals(50_000_000L, stats.losslessBytes)
        assertEquals(2, stats.lossySongs)
        assertEquals(10_000_000L, stats.lossyBytes)
    }

    @Test
    fun emptyLibraryHasNoFormatAndNoLargest() {
        val stats = buildLibraryStats(emptyList())
        assertEquals(0, stats.totalSongs)
        assertEquals(0L, stats.averageBytes)
        assertNull(stats.largest)
        assertTrue(stats.formats.isEmpty())
    }

    // ---------------------------------------------------------------- 文本

    @Test
    fun byteTextKeepsTwoDecimalsForGigabytes() {
        assertEquals("1.00 GB", formatByteSize(1024L * 1024L * 1024L))
        assertEquals("1.50 GB", formatByteSize(1024L * 1024L * 1536L))
        assertEquals("2.0 MB", formatByteSize(2L * 1024L * 1024L))
        assertEquals("800 KB", formatByteSize(800L * 1024L))
        assertEquals("0 B", formatByteSize(0L))
    }
}
