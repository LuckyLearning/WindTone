package com.luckylearning.windtone.ui.library

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SelectionMath] —— 多选态集合运算的行为回归。
 *
 * 这里每条规则用户都察觉得到，而坏了只是「数字不对 / 顺序奇怪」，不会抛异常：
 * 反选把子集外的歌也翻过来、批量入队的队形每次都不一样、删了歌底部还在报数。
 * 纯 `Set<String>` 运算，需要 Robolectric 只因为 [Song] 的 `uri` 是 `android.net.Uri`。
 */
@RunWith(RobolectricTestRunner::class)
class SelectionMathTest {

    private var seq = 0

    private fun song(name: String) = Song(
        id = (seq++).toLong(),
        uri = Uri.parse("content://media/external/audio/media/$seq"),
        title = name,
        artist = "陈屿",
        album = "退潮",
        displayName = "$name.flac",
    )

    // ------------------------------------------------------------ enter

    @Test
    fun `enter 从空勾选开始 - 长按即选中这一首`() {
        assertEquals(setOf("a"), SelectionMath.enter(emptySet(), "a"))
    }

    @Test
    fun `enter 保留已有勾选 - 不是清空重选`() {
        assertEquals(setOf("a", "b"), SelectionMath.enter(setOf("a"), "b"))
    }

    @Test
    fun `enter 重复长按同一首不产生重复项`() {
        assertEquals(setOf("a"), SelectionMath.enter(setOf("a"), "a"))
    }

    // ------------------------------------------------------------ toggle

    @Test
    fun `toggle 不在集合里就加回来`() {
        assertEquals(setOf("a", "b"), SelectionMath.toggle(setOf("a"), "b"))
    }

    @Test
    fun `toggle 在集合里就摘掉`() {
        assertEquals(setOf("a"), SelectionMath.toggle(setOf("a", "b"), "b"))
    }

    @Test
    fun `toggle 是自反的 - 同一首点两次回到原点`() {
        val start = setOf("a", "b")
        assertEquals(start, SelectionMath.toggle(SelectionMath.toggle(start, "c"), "c"))
        assertEquals(start, SelectionMath.toggle(SelectionMath.toggle(start, "a"), "a"))
    }

    // ------------------------------------------------------------ selectAll

    @Test
    fun `selectAll 得到当前列表的全集`() {
        assertEquals(setOf("a", "b", "c"), SelectionMath.selectAll(listOf("a", "b", "c")))
    }

    @Test
    fun `selectAll 整份替换 - 旧勾选里不在当前列表的被丢弃`() {
        // 先勾了 a、b，改成搜索条件后只剩 c、d，再点全选 → 只剩 c、d
        val after = SelectionMath.selectAll(listOf("c", "d"))
        assertEquals(setOf("c", "d"), after)
    }

    @Test
    fun `selectAll 当前列表为空时得到空集`() {
        assertTrue(SelectionMath.selectAll(emptyList()).isEmpty())
    }

    @Test
    fun `selectAll 忽略重复键`() {
        assertEquals(setOf("a"), SelectionMath.selectAll(listOf("a", "a")))
    }

    // ------------------------------------------------------------ invert

    @Test
    fun `invert 空勾选等于全选当前列表`() {
        val visible = listOf("a", "b", "c")
        assertEquals(SelectionMath.selectAll(visible), SelectionMath.invert(emptySet(), visible))
    }

    @Test
    fun `invert 全勾选时得到空集 - 底部操作栏可以整批取消`() {
        val visible = listOf("a", "b")
        assertEquals(emptySet<String>(), SelectionMath.invert(setOf("a", "b"), visible))
    }

    @Test
    fun `invert 只取当前列表的补集`() {
        val visible = listOf("a", "b", "c")
        assertEquals(setOf("b", "c"), SelectionMath.invert(setOf("a"), visible))
    }

    @Test
    fun `invert 丢弃不在当前列表里的旧勾选`() {
        // 关键：勾了 a、b、c，搜索后只剩 b、c。反选应只翻 b、c，
        // 绝不能把条件外的 a 也「反选」回来（那会让用户看到子集之外多出勾选）。
        val visible = listOf("b", "c")
        assertEquals(setOf("c"), SelectionMath.invert(setOf("a", "b"), visible))
    }

    // ------------------------------------------------------------ drop

    @Test
    fun `drop 摘掉指定键，其余保留`() {
        assertEquals(setOf("a", "c"), SelectionMath.drop(setOf("a", "b", "c"), listOf("b")))
    }

    @Test
    fun `drop 空键集合原样返回`() {
        val selection = setOf("a", "b")
        assertEquals(selection, SelectionMath.drop(selection, emptyList()))
    }

    @Test
    fun `drop 不存在的键不影响结果`() {
        assertEquals(setOf("a"), SelectionMath.drop(setOf("a"), listOf("zzz")))
    }

    // ------------------------------------------------------------ selectedSongs

    @Test
    fun `selectedSongs 按曲库顺序返回 - 不是勾选顺序`() {
        val s1 = song("一")
        val s2 = song("二")
        val s3 = song("三")
        val s4 = song("四")
        val songs = listOf(s1, s2, s3, s4)

        // 勾选顺序是 三 → 一，返回必须仍是曲库顺序
        val result = SelectionMath.selectedSongs(songs, setOf(s3.stableKey, s1.stableKey))
        assertEquals(listOf(s1, s3), result)
    }

    @Test
    fun `selectedSongs 顺序稳定 - 同一勾选集多次调用结果一致`() {
        val songs = (1..5).map { song("歌$it") }
        val selection = setOf(songs[3].stableKey, songs[1].stableKey, songs[4].stableKey)

        val first = SelectionMath.selectedSongs(songs, selection)
        val second = SelectionMath.selectedSongs(songs, selection)
        assertEquals(first, second)
        assertEquals(listOf(songs[1], songs[3], songs[4]), first)
    }

    @Test
    fun `selectedSongs 勾选为空返回空列表`() {
        val songs = listOf(song("一"), song("二"))
        assertTrue(SelectionMath.selectedSongs(songs, emptySet()).isEmpty())
    }

    @Test
    fun `selectedSongs 曲库里没有的键被忽略 - 不产生幽灵行`() {
        val songs = listOf(song("一"), song("二"))
        val result = SelectionMath.selectedSongs(songs, setOf(songs[0].stableKey, "content://不存在的歌"))
        assertEquals(listOf(songs[0]), result)
    }

    @Test
    fun `selectedSongs 与 drop 串联 - 删歌后底部不再报数`() {
        val s1 = song("一")
        val s2 = song("二")
        val songs = listOf(s1, s2)
        val selection = setOf(s1.stableKey, s2.stableKey)

        val afterRemove = SelectionMath.drop(selection, listOf(s2.stableKey))
        assertEquals(listOf(s1), SelectionMath.selectedSongs(songs, afterRemove))
    }
}
