package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 重复歌曲识别的口径回归。
 *
 * 这一层的规则一旦偏松，用户点一次「清理」就会把**想留的歌**移出曲库——
 * 虽然文件还在、还能从设置里恢复，但那是一次不可预期的批量改动，
 * 也是这批功能里唯一会批量改用户数据的地方。所以两条边界都要钉：
 *  · 判定**不能偏松**：现场版 / 录音室版、同名不同歌手，都不能被判成重复；
 *  · 保留哪首**不能含糊**：必须稳定、可复现，同一份输入永远给同一份建议。
 *
 * 需要 Robolectric 只因为 [Song] 的 `uri` 是 `android.net.Uri`。
 */
@RunWith(RobolectricTestRunner::class)
class DuplicateFinderTest {

    private fun song(
        uri: String,
        title: String = "晴天",
        artist: String? = "周杰伦",
        durationMs: Long = 269_000L,
        sizeBytes: Long = 10_000_000L,
        dateAdded: Long = 1_000L,
    ) = Song(
        id = 0L,
        uri = Uri.parse(uri),
        title = title,
        artist = artist,
        album = "叶惠美",
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        displayName = "$uri.mp3",
        dateAdded = dateAdded,
    )

    // ---------------------------------------------------------------- 分组判定

    @Test
    fun sameFingerprintIsGrouped() {
        val groups = findDuplicateGroups(
            listOf(
                song("a", sizeBytes = 10_000_000L),
                song("b", sizeBytes = 6_000_000L),
                song("c", sizeBytes = 4_000_000L),
            )
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
        assertEquals(2, groups[0].duplicates.size)
    }

    @Test
    fun singleSongNeverFormsAGroup() {
        assertTrue(findDuplicateGroups(listOf(song("a"))).isEmpty())
        assertTrue(findDuplicateGroups(emptyList()).isEmpty())
    }

    /**
     * 时长差 1 秒以上就不算重复。
     *
     * 《加州旅馆》现场版 6:30 / 录音室版 6:31 —— 标题歌手完全一样，
     * 只有时长能区分它们。指纹里带上秒数就是为了这一条，不能为了"多抓点重复"把它放松。
     */
    @Test
    fun differentDurationIsNotDuplicate() {
        val groups = findDuplicateGroups(
            listOf(
                song("live", durationMs = 390_000L),
                song("studio", durationMs = 391_000L),
            )
        )
        assertTrue("现场版与录音室版不能被判成重复", groups.isEmpty())
    }

    @Test
    fun differentArtistIsNotDuplicate() {
        val groups = findDuplicateGroups(
            listOf(song("a", artist = "周杰伦"), song("b", artist = "温岚")),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun differentTitleIsNotDuplicate() {
        val groups = findDuplicateGroups(
            listOf(song("a", title = "晴天"), song("b", title = "晴天 (Live)")),
        )
        assertTrue("带后缀的翻录版本不该自动归并", groups.isEmpty())
    }

    @Test
    fun titleAndArtistMatchingIgnoresCaseAndSurroundingSpaces() {
        // 不同来源的标签质量参差：一个写「ADELE」一个写「adele 」、
        // 标题首尾多带空格。这些属于同一首歌，不该因为空格与大小写被漏判。
        //
        // 这条测试抓到过一个真实缺陷：分组时误把 fingerprintOf 返回的**整个 Pair**
        // 当组键，而 Pair 的第二项（展示标签）用的是原始标题/歌手 —— 于是
        // "归一化后同键、原始标签不同"的副本反而分不到一组，归一化形同虚设。
        val a = song("a", title = "  Hello ", artist = "ADELE")
        val b = song("b", title = "hello", artist = "adele")
        assertEquals(
            "标题/歌手的大小写与首尾空格不应影响指纹：" +
                "a=${fingerprintOf(a).first} b=${fingerprintOf(b).first}",
            fingerprintOf(a).first,
            fingerprintOf(b).first,
        )
        val groups = findDuplicateGroups(listOf(a, b))
        assertEquals("指纹相同就必须归成一组，而不是各成一桶", 1, groups.size)
        // 标签取保留那首的原始文案，用户才能一眼认出这是哪首歌
        assertEquals(fingerprintOf(groups[0].keep).second, groups[0].label)
    }

    @Test
    fun missingArtistFallsBackToTheSamePlaceholder() {
        // artist 为 null 与空白都解析成「未知歌手」，两者应能互相归并
        val groups = findDuplicateGroups(
            listOf(song("a", artist = null), song("b", artist = "  ")),
        )
        assertEquals(1, groups.size)
    }

    // ---------------------------------------------------------------- 保留哪一首

    @Test
    fun keepsTheLargerFile() {
        val groups = findDuplicateGroups(
            listOf(
                song("small", sizeBytes = 3_000_000L),
                song("big", sizeBytes = 12_000_000L),
                song("mid", sizeBytes = 8_000_000L),
            )
        )
        assertEquals("文件更大的那份基本等价于码率更高，应保留", "big", groups[0].keep.stableKey)
        assertTrue(groups[0].duplicates.none { it.stableKey == "big" })
    }

    @Test
    fun sameSizeKeepsTheEarlierAdded() {
        val groups = findDuplicateGroups(
            listOf(
                song("late", sizeBytes = 5L, dateAdded = 9_000L),
                song("early", sizeBytes = 5L, dateAdded = 100L),
            )
        )
        assertEquals("大小相同时保留更早入库的那份", "early", groups[0].keep.stableKey)
    }

    @Test
    fun resultIsStableWhenEverythingTies() {
        // 三个维度的比较器都相等时必须还有一条兜底，否则同一份输入两次扫描
        // 可能给出不同的"建议保留"，用户按第一次的结论清理会删错歌。
        val songs = listOf(song("c"), song("a"), song("b"))
        val first = findDuplicateGroups(songs)[0]
        val second = findDuplicateGroups(songs.reversed())[0]
        assertEquals(first.keep.stableKey, second.keep.stableKey)
        assertEquals("a", first.keep.stableKey)   // stableKey 字典序最小
    }

    // ---------------------------------------------------------------- 顺序与展示

    @Test
    fun groupsAreSortedByRemovableCountDescending() {
        val groups = findDuplicateGroups(
            listOf(
                // A：2 首 → 可清 1
                song("a1", title = "A"),
                song("a2", title = "A"),
                // B：4 首 → 可清 3
                song("b1", title = "B"),
                song("b2", title = "B"),
                song("b3", title = "B"),
                song("b4", title = "B"),
            )
        )
        assertEquals(2, groups.size)
        assertEquals("副本多的组排前面，用户先看到收益最大的", 3, groups[0].duplicates.size)
        assertEquals(1, groups[1].duplicates.size)
    }

    @Test
    fun labelCarriesTitleArtistAndSecondsForTheConfirmDialog() {
        val groups = findDuplicateGroups(listOf(song("a"), song("b")))
        val label = groups[0].label
        assertTrue("标题要在标签里：$label", label.contains("晴天"))
        assertTrue("歌手要在标签里：$label", label.contains("周杰伦"))
        assertTrue("时长秒数要在标签里，用户才能核对为什么判成重复：$label", label.contains("269"))
    }

    @Test
    fun duplicatesNeverContainTheKeptSong() {
        val groups = findDuplicateGroups(listOf(song("x"), song("y"), song("z")))
        val dupKeys = groups[0].duplicates.map { it.stableKey }.toSet()
        assertTrue(groups[0].keep.stableKey !in dupKeys)
        assertEquals("保留 1 首 + 移出 2 首必须覆盖全部 3 首", 3, dupKeys.size + 1)
    }
}
