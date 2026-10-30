package com.luckylearning.windtone.data

/**
 * 存储热插拔：广播分类、路径归属、重扫节流、卸载时播放决策（纯 JVM 可测）。
 *
 * 车机歌多在 U 盘 / SD：插上不重扫就看不到新歌；拔掉后正在播的文件会失效。
 * 本对象不碰 Android Framework，方便普通单测锁住口径。
 */
object StorageHotplugPolicy {

    /**
     * 挂载/卸载触发的重扫最小间隔。
     * 比冷启动 10 分钟节流短很多：插拔是明确外部事件，必须尽快扫；
     * 但仍要挡住「同一根 U 盘连跳多次挂载广播」刷屏。
     */
    const val RESCAN_MIN_INTERVAL_MS = 3_000L

    enum class BroadcastKind {
        /** MEDIA_MOUNTED：新卷可用 → 请求重扫 */
        MOUNTED,
        /** UNMOUNTED / EJECT / REMOVED / BAD_REMOVAL：卷不可用 → 停播策略 + 重扫软删 */
        UNMOUNTED,
        IGNORE,
    }

    sealed class UnmountPlaybackAction {
        data object None : UnmountPlaybackAction()
        /** 队列还有别的歌：与播放失败跳下一首同口径 */
        data object SkipNext : UnmountPlaybackAction()
        /** 队列只剩这一首（或空）：暂停并提示，等同播放失败停住 */
        data object StopAndNotify : UnmountPlaybackAction()
    }

    fun classifyAction(action: String?): BroadcastKind = when (action) {
        "android.intent.action.MEDIA_MOUNTED" -> BroadcastKind.MOUNTED
        "android.intent.action.MEDIA_UNMOUNTED",
        "android.intent.action.MEDIA_EJECT",
        "android.intent.action.MEDIA_REMOVED",
        "android.intent.action.MEDIA_BAD_REMOVAL",
        -> BroadcastKind.UNMOUNTED
        else -> BroadcastKind.IGNORE
    }

    /**
     * 从广播 Intent.data（通常 file:///storage/XXXX-XXXX）抽出卷根路径。
     * 空 / 非 file / 无 path → null（仍可触发重扫，但无法做「当前曲是否在该卷」判定）。
     */
    fun volumePathFromData(dataString: String?): String? {
        val raw = dataString?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val path = when {
            raw.startsWith("file://", ignoreCase = true) -> {
                // 不依赖 android.net.Uri，便于纯 JVM 测
                val after = raw.substring(7) // 去掉 file://
                when {
                    after.startsWith("/") -> after
                    after.startsWith("localhost/", ignoreCase = true) ->
                        after.substring("localhost".length)
                    else -> "/$after"
                }
            }
            raw.startsWith("/") -> raw
            else -> return null
        }
        val normalized = path.trimEnd('/')
        return normalized.takeIf { it.isNotEmpty() && it != "/" }
    }

    /**
     * [candidatePath] 是否落在 [volumeRoot] 下（含恰好等于卷根）。
     * 两边都会去掉尾部 `/`；匹配区分大小写（与 Linux/车机常见 FS 一致）。
     */
    fun pathBelongsToVolume(candidatePath: String?, volumeRoot: String): Boolean {
        val path = candidatePath?.trim().orEmpty().trimEnd('/')
        val root = volumeRoot.trim().trimEnd('/')
        if (path.isEmpty() || root.isEmpty()) return false
        if (path == root) return true
        return path.startsWith("$root/")
    }

    /**
     * 当前曲是否属于已卸卷。
     * 优先看绝对路径；没有 path 时再看 file:// uri（MediaStore content:// 无法从字符串判断卷）。
     */
    fun songOnVolume(songPath: String?, songUriString: String?, volumeRoot: String): Boolean {
        if (volumeRoot.isBlank()) return false
        if (pathBelongsToVolume(songPath, volumeRoot)) return true
        val uriPath = volumePathFromData(songUriString) ?: return false
        return pathBelongsToVolume(uriPath, volumeRoot)
    }

    /**
     * 挂载/卸载后是否再发一次重扫请求。
     * 时钟回拨时放行，避免永久锁死。
     */
    fun shouldRequestRescan(
        lastRequestAtMs: Long,
        nowMs: Long,
        minIntervalMs: Long = RESCAN_MIN_INTERVAL_MS,
    ): Boolean {
        if (lastRequestAtMs <= 0L) return true
        val delta = nowMs - lastRequestAtMs
        if (delta < 0L) return true
        return delta >= minIntervalMs
    }

    /**
     * 卸载时对「正在播」的决策，与播放失败跳下一首同口径：
     * 当前曲不在该卷 → 不动；在该卷且队列 > 1 → 跳下一首；否则停住并提示。
     */
    fun decideUnmountPlayback(
        currentOnGoneVolume: Boolean,
        queueSize: Int,
    ): UnmountPlaybackAction {
        if (!currentOnGoneVolume) return UnmountPlaybackAction.None
        return if (queueSize > 1) UnmountPlaybackAction.SkipNext
        else UnmountPlaybackAction.StopAndNotify
    }
}
