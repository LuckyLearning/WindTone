package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
