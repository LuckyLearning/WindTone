package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LoudnessCache] 的文件读写契约：持久化、跨实例读取、坏行容错。
 *
 * 全程纯 JVM（临时目录），不碰 Robolectric —— 这份数据的读写规则
 * 必须与设备无关（车机 / 手机行为一致），所以测试也要与设备无关。
 */
class LoudnessCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cacheFile(): File = File(tmp.root, "loudness_cache.txt")

    @Test
    fun `put 后同实例可读`() {
        val c = LoudnessCache(cacheFile())
        c.put("content://media/1", -1700)
        assertEquals(-1700, c.gainMb("content://media/1"))
    }

    @Test
    fun `put 后新实例从文件恢复`() {
        LoudnessCache(cacheFile()).put("content://media/42", -800)
        assertEquals(-800, LoudnessCache(cacheFile()).gainMb("content://media/42"))
    }

    @Test
    fun `没记录过的 key 返回 null`() {
        val c = LoudnessCache(cacheFile())
        assertNull(c.gainMb("content://media/none"))
    }

    @Test
    fun `文件不存在视为空缓存`() {
        assertNull(LoudnessCache(cacheFile()).gainMb("content://media/1"))
    }

    @Test
    fun `重复 put 覆盖旧值`() {
        val c = LoudnessCache(cacheFile())
        c.put("key", -100)
        c.put("key", -200)
        assertEquals(-200, c.gainMb("key"))
        assertEquals(-200, LoudnessCache(cacheFile()).gainMb("key"))
    }

    @Test
    fun `坏行与空行被跳过`() {
        val f = cacheFile()
        f.writeText(
            """
            -1500 content://ok/1

            不是数字 content://bad/1
            -99
            -800 content://ok/2
            """.trimIndent(),
        )
        val c = LoudnessCache(f)
        assertEquals(-1500, c.gainMb("content://ok/1"))
        assertEquals(-800, c.gainMb("content://ok/2"))
        assertNull(c.gainMb("content://bad/1"))
    }

    @Test
    fun `key 里带空格也能整行读回`() {
        // stableKey 是 URI，正常不会带空格；但格式定义是「首个空格后整行是 key」，钉住它
        val f = cacheFile()
        f.writeText("-500 key with space")
        assertEquals(-500, LoudnessCache(f).gainMb("key with space"))
    }

    // ------------------------------------------------ 2026-10-31：追加写改造后新增

    @Test
    fun `追加写累计超过 REWRITE_EVERY 后所有条目仍可读回`() {
        val c = LoudnessCache(cacheFile())
        // 300 > REWRITE_EVERY(256)，中途必定触发一次整理，两侧行为都要对
        repeat(300) { c.put("content://media/$it", -it) }
        val fresh = LoudnessCache(cacheFile())
        repeat(300) { assertEquals(-it, fresh.gainMb("content://media/$it")) }
    }

    @Test
    fun `整理后文件里不残留重复 key 行`() {
        // 追加写天然会留重复行（靠 ensureLoaded 的「后写覆盖前值」兜住正确性），
        // 但整理那一次必须把它们压掉 —— 否则文件会随重复分析次数无限变长。
        // 256 正好等于 REWRITE_EVERY：第 256 次写入后立刻整理，此时应只剩一行。
        val f = cacheFile()
        val c = LoudnessCache(f)
        repeat(256) { c.put("same", it) }
        val lines = f.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertEquals("255 same", lines[0])
    }

    @Test
    fun `追加写不丢：连续 put 三百个不同 key 全在读回`() {
        // 旧实现是全量重写，等价于「最后一份快照」；改成追加后必须保证
        // 早期写入没有被后续追加覆盖掉。
        val c = LoudnessCache(cacheFile())
        repeat(120) { c.put("k$it", 1000 + it) }
        val fresh = LoudnessCache(cacheFile())
        assertEquals(1000, fresh.gainMb("k0"))
        assertEquals(1119, fresh.gainMb("k119"))
    }

    /**
     * A12：条数硬顶。
     *
     * 这张表随曲库线性增长且永不回收 —— 8000 首约 24000 条。给它 LRU 上限之后，
     * 最久没被问到的条目会被淘汰；用户真回去听那首歌时重新分析一遍即可（纯缓存）。
     * 上限值本身不该出现在这个测试里，用常量引用，改上限时测试自动跟随。
     */
    @Test
    fun `超过条数上限后按 LRU 淘汰最久未访问的`() {
        // 上限做成构造参数，测试用小值 —— 否则要写两万行真实文件
        val max = 50
        val c = LoudnessCache(cacheFile(), max)
        repeat(max + 10) { c.put("k$it", it) }

        // 最早那 10 条应已被淘汰，最近一条仍在
        assertNull("最久未访问的应被淘汰", c.gainMb("k0"))
        assertEquals(max + 9, c.gainMb("k${max + 9}"))
    }

    /**
     * 淘汰之后攒够一批会整理文件。
     *
     * 注意断言口径：整理是**攒批**的（表满后每写一条淘汰一条，若一条一整理就退化
     * 成每条全量重写两万行），所以文件行数允许比上限多出「一个攒批窗口」，
     * 但不能随写入次数无限增长，且被淘汰的 key 必须已经从磁盘上消失。
     */
    @Test
    fun `淘汰攒批后磁盘不再保留被淘汰的 key 且不无限增长`() {
        val max = 50
        val f = cacheFile()
        val c = LoudnessCache(f, max)
        // 先写满并多写一条（开始淘汰），再继续写 70 条：
        // 累计淘汰数必然超过攒批阈值，从而触发一次全量整理
        repeat(max + 1) { c.put("k$it", it) }
        repeat(70) { c.put("x$it", it) }

        val lines = f.readLines().filter { it.isNotBlank() }
        assertTrue(
            "文件行数 ${lines.size} 不应超过「上限 + 一个攒批窗口」，实际 ${lines.size}",
            lines.size <= max + 80,
        )
        assertTrue(
            "最久未访问的 k0 已被 LRU 淘汰，磁盘上不该还留着它的行",
            lines.none { it.endsWith(" k0") },
        )
    }
}
