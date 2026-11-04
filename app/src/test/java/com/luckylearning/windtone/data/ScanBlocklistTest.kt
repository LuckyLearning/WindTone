package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描黑名单匹配语义（竞品借鉴：Salt Player 文件夹黑名单）。
 *
 * 核心关切：**不能误伤**。`录音` 不能剪掉 `录音棚精选`，`Music` 不能因为
 * 是 `MyMusic` 的子串就把整个库剪空 —— 所以匹配必须是路径段级（全等或
 * `/片段` 前缀），而不是朴素 `contains`。
 */
class ScanBlocklistTest {

    // ---------- normalize ----------

    @Test
    fun normalizeTrimsSlashesAndWhitespaceAndDedups() {
        val rules = ScanBlocklist.normalize(listOf(" /录音/ ", "录音", "", "DCIM/Camera", "dcim/camera"))
        assertEquals(listOf("录音", "dcim/camera"), rules)
    }

    // ---------- blocksDir ----------

    @Test
    fun directoryNameExactMatchPrunesWholeTree() {
        val rules = ScanBlocklist.normalize(listOf("录音"))
        assertTrue(ScanBlocklist.blocksDir("录音", "/sdcard/Music/录音", rules))
    }

    @Test
    fun similarNameIsNotBlocked() {
        val rules = ScanBlocklist.normalize(listOf("录音"))
        assertFalse(ScanBlocklist.blocksDir("录音棚精选", "/sdcard/Music/录音棚精选", rules))
        assertFalse(ScanBlocklist.blocksDir("录音2", "/sdcard/Music/录音2", rules))
        // 注：不测「录音棚精选 位于 录音 之内」——那是不可达场景，父目录命中即整棵剪枝，
        // 扫描根本不会下钻到它。blocksDir 对祖先命中的后代返回 true 是有意的（漏判更危险）。
    }

    @Test
    fun pathFragmentMatchesDirectoryByPrefix() {
        val rules = ScanBlocklist.normalize(listOf("DCIM/Camera"))
        assertTrue(ScanBlocklist.blocksDir("Camera", "/sdcard/DCIM/Camera", rules))
        assertTrue(ScanBlocklist.blocksDir(" subdir ", "/sdcard/DCIM/Camera/subdir", rules))
        assertFalse(ScanBlocklist.blocksDir("CameraHD", "/sdcard/DCIM/CameraHD", rules))
    }

    @Test
    fun caseInsensitiveMatching() {
        val rules = ScanBlocklist.normalize(listOf("Recordings"))
        assertTrue(ScanBlocklist.blocksDir("recordings", "/sdcard/Recordings", rules))
    }

    @Test
    fun emptyRulesNeverBlock() {
        assertFalse(ScanBlocklist.blocksDir("anything", "/sdcard/anything", emptyList()))
        assertFalse(ScanBlocklist.blocksPath("/sdcard/anything/a.mp3", emptyList()))
    }

    // ---------- blocksPath（MediaStore 行过滤） ----------

    @Test
    fun filePathInsideBlockedDirectoryIsBlocked() {
        val rules = ScanBlocklist.normalize(listOf("录音"))
        assertTrue(ScanBlocklist.blocksPath("/sdcard/Music/录音/clip001.mp3", rules))
        // 深层嵌套同样命中
        assertTrue(ScanBlocklist.blocksPath("/sdcard/Music/录音/2024/01/clip.mp3", rules))
    }

    @Test
    fun similarPathIsNotBlocked() {
        val rules = ScanBlocklist.normalize(listOf("录音"))
        assertFalse(ScanBlocklist.blocksPath("/sdcard/Music/录音棚精选/song.mp3", rules))
        // 「规则是路径段」——`音/clip` 不是 `/录音/` 的包含关系之外的东西
        assertFalse(ScanBlocklist.blocksPath("/sdcard/Music/电影原声/clip.mp3", rules))
    }

    @Test
    fun pathFragmentRuleMatchesFileUnderIt() {
        val rules = ScanBlocklist.normalize(listOf("DCIM/Camera"))
        assertTrue(ScanBlocklist.blocksPath("/sdcard/DCIM/Camera/VID_001.mp4", rules))
        assertFalse(ScanBlocklist.blocksPath("/sdcard/DCIM/CameraBackup/a.mp3", rules))
    }
}
