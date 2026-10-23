package com.luckylearning.windtone.data.aliyun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AliyunRules / AliyunEntry / DriveInfo 的纯 JVM 用例（toSong 涉及 Uri，在 AliyunAndroidTest） */
class AliyunDriveTest {

    // ------------------------------------------------------------ 文件类型与展示名

    @Test
    fun `可播放音频的判定与百度页同一套后缀`() {
        assertTrue(AliyunRules.isPlayableAudio("晴天.mp3"))
        assertTrue(AliyunRules.isPlayableAudio("晴天.FLAC"))
        assertTrue(AliyunRules.isPlayableAudio("a.b.c.m4a"))
        assertFalse(AliyunRules.isPlayableAudio("晴天.wma"))
        assertFalse(AliyunRules.isPlayableAudio("封面.png"))
        assertFalse(AliyunRules.isPlayableAudio("无后缀"))
    }

    @Test
    fun `展示名去掉扩展名`() {
        assertEquals("晴天", AliyunRules.displayTitle("晴天.flac"))
        assertEquals("a.b", AliyunRules.displayTitle("a.b.c"))
        assertEquals("无后缀", AliyunRules.displayTitle("无后缀"))
    }

    @Test
    fun `排序文件夹在前且名称不区分大小写`() {
        val sorted = AliyunRules.sorted(
            listOf(
                AliyunEntry("1", "b.flac", false, 1),
                AliyunEntry("2", "专辑A", true, 0),
                AliyunEntry("3", "B.flac", false, 1),
                AliyunEntry("4", "banana.mp3", false, 1),
                AliyunEntry("5", "专辑b", true, 0),
            ),
        )
        // 文件夹在前，文件夹之间按名称不区分大小写（专辑A < 专辑b）
        assertTrue(sorted[0].isDir)
        assertTrue(sorted[1].isDir)
        assertEquals("专辑A", sorted[0].name)
        assertEquals("专辑b", sorted[1].name)
        // 文件之间不区分大小写；同名仅大小写不同的两条保持原有相对顺序（稳定排序）
        assertEquals("b.flac", sorted[2].name)
        assertEquals("B.flac", sorted[3].name)
        assertEquals("banana.mp3", sorted[4].name)
    }

    @Test
    fun `文件条目的公共语义`() {
        val dir = AliyunEntry("1", "专辑", true, 0)
        assertTrue(dir.isDir)
        assertFalse(dir.isPlayableAudio)
        val file = AliyunEntry("2", "a.mp3", false, 10)
        assertTrue(file.isPlayableAudio)
        assertEquals("a", AliyunRules.displayTitle(file.name))
    }

    // ------------------------------------------------------------ 盘选择（备份盘 / 资源库）

    @Test
    fun `盘选择优先 default 再 resource 最后 backup`() {
        assertEquals("D", AliyunRules.pickDrive("D", "R", "B"))
        assertEquals("R", AliyunRules.pickDrive(null, "R", "B"))
        assertEquals("R", AliyunRules.pickDrive("", "R", "B"))
        assertEquals("B", AliyunRules.pickDrive(null, null, "B"))
        assertNull(AliyunRules.pickDrive(null, null, null))
    }

    @Test
    fun `只有一个盘时不出现可切换项`() {
        val onlyDefault = AliyunDrive.DriveInfo("某用户", "D", "", "")
        assertEquals("D", onlyDefault.preferredDriveId())
        // 列表里只有它自己：界面按 size > 1 判断，不会显示切换
        assertEquals(listOf("默认盘" to "D"), onlyDefault.availableDrives())

        val onlyBackup = AliyunDrive.DriveInfo("某用户", "", "", "B")
        assertEquals("B", onlyBackup.preferredDriveId())
        assertEquals(listOf("备份盘" to "B"), onlyBackup.availableDrives())
    }

    @Test
    fun `备份盘与资源库都有时按 备份盘 资源库 顺序列出且默认盘不重复`() {
        val both = AliyunDrive.DriveInfo("某用户", "D", "R", "B")
        val drives = both.availableDrives()
        assertEquals(3, drives.size)
        assertEquals("备份盘" to "B", drives[0])
        assertEquals("资源库" to "R", drives[1])
        // 默认盘 id 与另外两个都不同 —— 必须列出来，否则切走后无法切回
        assertEquals("默认盘" to "D", drives[2])
        // 默认仍优先 default
        assertEquals("D", both.preferredDriveId())
    }

    @Test
    fun `默认盘与备份盘是同一个 id 时只出现一次`() {
        // 线上常见形态：default_drive_id 就指向备份盘
        val info = AliyunDrive.DriveInfo("某用户", "B", "R", "B")
        val drives = info.availableDrives()
        assertEquals(2, drives.size)
        assertEquals("备份盘" to "B", drives[0])
        assertEquals("资源库" to "R", drives[1])
    }
}
