package com.luckylearning.windtone.playback

import android.net.Uri

/**
 * LoadControl 分场景规则（资源极致 B1）。
 *
 * 本地 `file` / `content`（及明显本地 scheme）用小缓冲，省堆与预读；
 * `http(s)` / 网盘直链保持大缓冲，弱网不卡。
 *
 * **稳妥优先**：scheme 认不出或为空时按**远程**处理——误判成本是多占一点内存；
 * 反方向（远程当本地）会直接卡顿，车机上更糟。
 */
object LoadControlScenePolicy {

    /** 远程：与改前 PlaybackService 固定值一致 */
    const val REMOTE_MIN_BUFFER_MS = 30_000
    const val REMOTE_MAX_BUFFER_MS = 120_000
    const val REMOTE_BUFFER_FOR_PLAYBACK_MS = 4_000
    const val REMOTE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 8_000

    /**
     * 本地：USB/内置盘随机读够快，不必垫 2 分钟。
     * min 仍 ≥ playback / afterRebuffer，满足 DefaultLoadControl 约束。
     */
    const val LOCAL_MIN_BUFFER_MS = 8_000
    const val LOCAL_MAX_BUFFER_MS = 24_000
    const val LOCAL_BUFFER_FOR_PLAYBACK_MS = 1_500
    const val LOCAL_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    /** 当前曲应按「远程大缓冲」对待吗。 */
    fun useRemoteBuffers(scheme: String?): Boolean {
        if (scheme.isNullOrBlank()) return true
        return when (scheme.lowercase()) {
            "file",
            "content",
            "android.resource",
            "rawresource",
            "asset",
            "assetfiledescriptor",
            "filedescriptor",
            -> false
            "http", "https" -> true
            // 其余（rtsp / 自定义网盘 scheme / 拼写异常）一律远程，避免卡顿
            else -> true
        }
    }

    fun useRemoteBuffers(uri: Uri?): Boolean = useRemoteBuffers(uri?.scheme)
}
