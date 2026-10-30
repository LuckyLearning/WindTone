package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储热插拔策略纯逻辑：广播归类、卷路径解析、曲是否属卷、重扫节流、卸载播放决策。
 */
class StorageHotplugPolicyTest {

    @Test
    fun classifyMountedUnmountedAndIgnore() {
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.MOUNTED,
            StorageHotplugPolicy.classifyAction("android.intent.action.MEDIA_MOUNTED"),
        )
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.UNMOUNTED,
            StorageHotplugPolicy.classifyAction("android.intent.action.MEDIA_UNMOUNTED"),
        )
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.UNMOUNTED,
            StorageHotplugPolicy.classifyAction("android.intent.action.MEDIA_EJECT"),
        )
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.UNMOUNTED,
            StorageHotplugPolicy.classifyAction("android.intent.action.MEDIA_REMOVED"),
        )
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.UNMOUNTED,
            StorageHotplugPolicy.classifyAction("android.intent.action.MEDIA_BAD_REMOVAL"),
        )
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.IGNORE,
            StorageHotplugPolicy.classifyAction("android.intent.action.BOOT_COMPLETED"),
        )
        assertEquals(
            StorageHotplugPolicy.BroadcastKind.IGNORE,
            StorageHotplugPolicy.classifyAction(null),
        )
    }

    @Test
    fun volumePathFromData_fileUriAndRaw() {
        assertEquals(
            "/storage/ABCD-1234",
            StorageHotplugPolicy.volumePathFromData("file:///storage/ABCD-1234"),
        )
        assertEquals(
            "/storage/ABCD-1234",
            StorageHotplugPolicy.volumePathFromData("file:///storage/ABCD-1234/"),
        )
        assertEquals(
            "/storage/ABCD-1234",
            StorageHotplugPolicy.volumePathFromData("/storage/ABCD-1234"),
        )
        assertEquals(
            "/storage/ABCD-1234",
            StorageHotplugPolicy.volumePathFromData("file://localhost/storage/ABCD-1234"),
        )
        assertNull(StorageHotplugPolicy.volumePathFromData(null))
        assertNull(StorageHotplugPolicy.volumePathFromData(""))
        assertNull(StorageHotplugPolicy.volumePathFromData("content://media/external/audio/media/1"))
    }

    @Test
    fun pathBelongsToVolume_prefixAndExact() {
        val root = "/storage/ABCD-1234"
        assertTrue(StorageHotplugPolicy.pathBelongsToVolume("/storage/ABCD-1234/Music/a.mp3", root))
        assertTrue(StorageHotplugPolicy.pathBelongsToVolume("/storage/ABCD-1234", root))
        assertTrue(StorageHotplugPolicy.pathBelongsToVolume("/storage/ABCD-1234/", root))
        assertFalse(StorageHotplugPolicy.pathBelongsToVolume("/storage/ABCD-12345/a.mp3", root))
        assertFalse(StorageHotplugPolicy.pathBelongsToVolume("/storage/other/a.mp3", root))
        assertFalse(StorageHotplugPolicy.pathBelongsToVolume(null, root))
        assertFalse(StorageHotplugPolicy.pathBelongsToVolume("/storage/ABCD-1234/a.mp3", ""))
    }

    @Test
    fun songOnVolume_prefersPathThenFileUri() {
        val root = "/storage/ABCD-1234"
        assertTrue(
            StorageHotplugPolicy.songOnVolume(
                songPath = "/storage/ABCD-1234/x.mp3",
                songUriString = "content://media/external/audio/media/9",
                volumeRoot = root,
            ),
        )
        assertTrue(
            StorageHotplugPolicy.songOnVolume(
                songPath = null,
                songUriString = "file:///storage/ABCD-1234/y.mp3",
                volumeRoot = root,
            ),
        )
        // MediaStore content:// 无法从字符串判卷
        assertFalse(
            StorageHotplugPolicy.songOnVolume(
                songPath = null,
                songUriString = "content://media/external/audio/media/9",
                volumeRoot = root,
            ),
        )
        assertFalse(
            StorageHotplugPolicy.songOnVolume(
                songPath = "/storage/OTHER/z.mp3",
                songUriString = null,
                volumeRoot = root,
            ),
        )
    }

    @Test
    fun shouldRequestRescan_throttleAndClockSkew() {
        assertTrue(StorageHotplugPolicy.shouldRequestRescan(0L, 1000L, 3000L))
        assertFalse(StorageHotplugPolicy.shouldRequestRescan(1000L, 2000L, 3000L))
        assertTrue(StorageHotplugPolicy.shouldRequestRescan(1000L, 4000L, 3000L))
        // 时钟回拨放行
        assertTrue(StorageHotplugPolicy.shouldRequestRescan(5000L, 1000L, 3000L))
    }

    @Test
    fun decideUnmountPlayback_alignsErrorSkip() {
        assertEquals(
            StorageHotplugPolicy.UnmountPlaybackAction.None,
            StorageHotplugPolicy.decideUnmountPlayback(false, 5),
        )
        assertEquals(
            StorageHotplugPolicy.UnmountPlaybackAction.SkipNext,
            StorageHotplugPolicy.decideUnmountPlayback(true, 3),
        )
        assertEquals(
            StorageHotplugPolicy.UnmountPlaybackAction.StopAndNotify,
            StorageHotplugPolicy.decideUnmountPlayback(true, 1),
        )
        assertEquals(
            StorageHotplugPolicy.UnmountPlaybackAction.StopAndNotify,
            StorageHotplugPolicy.decideUnmountPlayback(true, 0),
        )
    }
}
