package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LibrarySearch] —— 曲库搜索过滤与字母索引的行为回归。
 *
 * 搜索是用户每天用得最多的功能，而它的判定散在三个地方（文本 / 全拼 / 首字母），
 * 任何一路失效都只是「搜不到」而不报错 —— 必须逐条钉死。
 *
 * 需要 Robolectric 只因为 [Song] 的 `uri` 是 `android.net.Uri`；
 * 拼音那两路走 pinyin4j（纯 Java 库），JVM 上照跑。
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySearchTest {

    private var seq = 0

    private fun song(
        title: String,
        artist: String? = "陈屿",
        album: String? = "退潮",
        displayName: String = "$title.flac",
    ) = Song(
        id = (seq++).toLong(),
        uri = Uri.parse("content://media/external/audio/media/$seq"),
        title = title,
        artist = artist,
        album = album,
        displayName = displayName,
    )

    @Before
    fun setUp() {
        // PinyinIndex 是进程级单例：每个用例都用自己的歌单重建，避免上一个用例的残留
        PinyinIndex.build(emptyList())
    }

    @After
    fun tearDown() {
        PinyinIndex.build(emptyList())
    }

    // ------------------------------------------------------------ matches（文本三路）

    @Test
    fun `matches 空查询一律不命中`() {
        val s = song("海阔天空")
        assertFalse(LibrarySearch.matches(s, ""))
    }

    @Test
    fun `matches 命中标题 - 大小写不敏感`() {
        val s = song("Yesterday")
        // 注意契约：queryLower 要求**已是小写**（规范化由 search 统一做一次）
        assertTrue(LibrarySearch.matches(s, "yesterday"))
        assertTrue(LibrarySearch.matches(s, "yester"))
        assertFalse(LibrarySearch.matches(s, "tomorrow"))
    }

    @Test
    fun `matches 命中歌手与专辑`() {
        val s = song("无题", artist = "周杰伦", album = "叶惠美")
        assertTrue(LibrarySearch.matches(s, "周杰伦"))
        assertTrue(LibrarySearch.matches(s, "叶惠美"))
        // 拼音不算文本匹配：歌手的拼音要走 search 的全拼那一路
        assertFalse(LibrarySearch.matches(s, "jay"))
    }

    @Test
    fun `matches 命中文件名`() {
        // 标题是「无题」但文件名带着编号，用户很可能照着文件名搜
        val s = song("无题", displayName = "07 晴天 - 周杰伦.flac")
        assertTrue(LibrarySearch.matches(s, "晴天"))
        assertTrue(LibrarySearch.matches(s, ".flac"))
    }

    @Test
    fun `matches 命中未知歌手兜底文案`() {
        // artist 为 null 时 artistOrUnknown 是「未知歌手」，搜「未知」应该能捞到
        val s = song("某首歌", artist = null)
        assertTrue(LibrarySearch.matches(s, "未知"))
    }

    // ------------------------------------------------------------ search（三路并集）

    @Test
    fun `search 空查询原样返回 - 连拷贝都不做`() {
        val songs = listOf(song("海阔天空"), song("晴天"))
        // 长库里每次按键都可能走到这里，不该平白多拷一份几千元素的列表
        assertSame(songs, LibrarySearch.search(songs, ""))
        assertSame(songs, LibrarySearch.search(songs, "   "))
    }

    @Test
    fun `search 查询会 trim 并按小写比较`() {
        val songs = listOf(song("Yesterday"), song("Hey Jude"))
        assertEquals(listOf(songs[0]), LibrarySearch.search(songs, "  YESTERDAY  "))
    }

    @Test
    fun `search 命中全拼 - 索引已建时`() {
        val songs = listOf(song("海阔天空", artist = "信乐团"), song("晴天", artist = "周杰伦"))
        PinyinIndex.build(songs)

        assertEquals(listOf(songs[0]), LibrarySearch.search(songs, "haikuo"))
        assertEquals(listOf(songs[0]), LibrarySearch.search(songs, "海阔"))
        assertEquals(emptyList<Song>(), LibrarySearch.search(songs, "zhouxin"))
    }

    @Test
    fun `search 命中首字母缩写`() {
        val songs = listOf(song("海阔天空", artist = "信乐团"), song("晴天", artist = "周杰伦"))
        PinyinIndex.build(songs)

        // 「海阔天空 信乐团 退潮」的首字母串
        assertEquals(listOf(songs[0]), LibrarySearch.search(songs, "hktk"))
        // 索引文本是 标题+歌手+专辑，歌手的首字母也能命中
        assertEquals(listOf(songs[0]), LibrarySearch.search(songs, "xlt"))
        assertEquals(listOf(songs[1]), LibrarySearch.search(songs, "zjl"))
        // 不够独特的前缀不乱命中
        assertEquals(emptyList<Song>(), LibrarySearch.search(songs, "xyz"))
    }

    @Test
    fun `search 索引没建时退化成纯文本匹配 - 不报错`() {
        val songs = listOf(song("海阔天空", artist = "信乐团"))
        // setUp 里 build(emptyList())：索引是空的，拼音那两路查不到东西
        assertEquals(emptyList<Song>(), LibrarySearch.search(songs, "haikuo"))
        assertEquals(listOf(songs[0]), LibrarySearch.search(songs, "海阔"))
    }

    @Test
    fun `search 三路并集 - 任一中就算命中，顺序按原列表`() {
        val a = song("海阔天空", artist = "信乐团")     // 文本 / 全拼 / 首字母三路全中
        val b = song("晴天", artist = "周杰伦")        // 只中歌手文本
        val c = song("退后", artist = "某人")           // 只中歌手首字母 mr
        val d = song("无关的歌", artist = "无关的人")   // 全不中
        PinyinIndex.build(listOf(a, b, c, d))

        assertEquals(listOf(a), LibrarySearch.search(listOf(a, b, c, d), "海阔天空"))
        assertEquals(listOf(a), LibrarySearch.search(listOf(a, b, c, d), "haikuotiankong"))
        assertEquals(listOf(a), LibrarySearch.search(listOf(a, b, c, d), "hktk"))
        assertEquals(listOf(b), LibrarySearch.search(listOf(a, b, c, d), "周杰伦"))
        assertEquals(listOf(c), LibrarySearch.search(listOf(a, b, c, d), "mr"))
        assertTrue(LibrarySearch.search(listOf(a, b, c, d), "查无此人").isEmpty())
    }

    @Test
    fun `search 全部不命中返回空列表`() {
        val songs = listOf(song("海阔天空", artist = "信乐团"))
        PinyinIndex.build(songs)
        assertTrue(LibrarySearch.search(songs, "完全不相关的词").isEmpty())
    }

    // ------------------------------------------------------------ letterIndex

    @Test
    fun `letterIndex 空列表返回空表`() {
        assertTrue(LibrarySearch.letterIndex(emptyList()).isEmpty())
    }

    @Test
    fun `letterIndex 取每个字母的首次下标并保持字母顺序`() {
        val songs = listOf(
            song("Apple"),
            song("Banana"),
            song("Avocado"),
            song("Cherry"),
        )
        val idx = LibrarySearch.letterIndex(songs)
        assertEquals(mapOf('A' to 0, 'B' to 1, 'C' to 3), idx)
        // LinkedHashMap：遍历顺序即插入顺序
        assertEquals(listOf('A', 'B', 'C'), idx.keys.toList())
    }

    @Test
    fun `letterIndex 汉字按拼音首字母归类`() {
        val songs = listOf(song("上海"), song("北京"), song("山水"), song("东京"))
        val idx = LibrarySearch.letterIndex(songs)
        // 山水也是 S，但上海排在前面 —— 每条只记首次出现的下标
        assertEquals(mapOf('S' to 0, 'B' to 1, 'D' to 3), idx)
    }

    @Test
    fun `letterIndex 没有拼音的条目归到井号`() {
        val songs = listOf(song("123"), song("Apple"))
        val idx = LibrarySearch.letterIndex(songs)
        assertEquals(0, idx['#'])
        assertEquals(1, idx['A'])
    }

    @Test
    fun `letterIndex 空标题也归井号`() {
        val idx = LibrarySearch.letterIndex(listOf(song("")))
        assertEquals(0, idx['#'])
    }
}
