package com.luckylearning.windtone.playback

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B1：本地小缓冲 / 远程大缓冲的 scheme 分流必须钉死——误把 http 当本地会卡顿。
 */
@RunWith(RobolectricTestRunner::class)
class LoadControlScenePolicyTest {

    @Test
    fun `明显本地 scheme 走小缓冲`() {
        for (scheme in listOf(
            "file", "content", "FILE", "Content",
            "android.resource", "rawresource", "asset",
            "assetfiledescriptor", "filedescriptor",
        )) {
            assertFalse("scheme=$scheme 应本地", LoadControlScenePolicy.useRemoteBuffers(scheme))
        }
        assertFalse(LoadControlScenePolicy.useRemoteBuffers(Uri.parse("file:///sdcard/a.flac")))
        assertFalse(LoadControlScenePolicy.useRemoteBuffers(Uri.parse("content://media/external/audio/1")))
    }

    @Test
    fun `http_s 与空_未知走远程大缓冲`() {
        assertTrue(LoadControlScenePolicy.useRemoteBuffers("http"))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers("HTTPS"))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers(null as String?))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers(""))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers("   "))
        // 自定义 / 拼写异常：宁可多垫，不要卡
        assertTrue(LoadControlScenePolicy.useRemoteBuffers("rtsp"))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers("windtone"))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers("fil"))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers(Uri.parse("https://example.com/a.flac")))
        assertTrue(LoadControlScenePolicy.useRemoteBuffers(null as Uri?))
    }

    @Test
    fun `缓冲时长常量满足 DefaultLoadControl 约束`() {
        fun check(min: Int, max: Int, play: Int, rebuffer: Int, label: String) {
            assertTrue("$label play>=0", play >= 0)
            assertTrue("$label rebuffer>=0", rebuffer >= 0)
            assertTrue("$label min>=play", min >= play)
            assertTrue("$label min>=rebuffer", min >= rebuffer)
            assertTrue("$label max>=min", max >= min)
        }
        check(
            LoadControlScenePolicy.REMOTE_MIN_BUFFER_MS,
            LoadControlScenePolicy.REMOTE_MAX_BUFFER_MS,
            LoadControlScenePolicy.REMOTE_BUFFER_FOR_PLAYBACK_MS,
            LoadControlScenePolicy.REMOTE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            "remote",
        )
        check(
            LoadControlScenePolicy.LOCAL_MIN_BUFFER_MS,
            LoadControlScenePolicy.LOCAL_MAX_BUFFER_MS,
            LoadControlScenePolicy.LOCAL_BUFFER_FOR_PLAYBACK_MS,
            LoadControlScenePolicy.LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            "local",
        )
        // 远程仍是改前量级；本地明显更小
        assertTrue(LoadControlScenePolicy.LOCAL_MAX_BUFFER_MS < LoadControlScenePolicy.REMOTE_MIN_BUFFER_MS)
    }
}
