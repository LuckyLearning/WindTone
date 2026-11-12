package com.anylyric.lyrics

/**
 * 一次解析请求的上下文快照。
 *
 * 刻意**不引用任何 Android 类型**：元数据与通知字段在 Android 层先转成
 * `Map<String, Any?>` / `Map<String, String>`，这样整条责任链都能在 JVM 单测里跑。
 */
data class TrackContext(
    val packageName: String = "",
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0L,
    val mediaId: String? = null,
    /** MediaMetadata.keySet() -> getValue；值可能是 String / Long / Bitmap 等 */
    val metadata: Map<String, Any?> = emptyMap(),
    /** 媒体通知 extras 里的文本字段（android.title / android.text / tickerText …） */
    val notificationTexts: Map<String, String> = emptyMap(),
    /** 是否已判定为「伪歌名」（TITLE 被当作歌词逐行推送） */
    val pseudoTitle: Boolean = false,
    /** 最近一次 TITLE 原文，L1 用于把当前行纳入缓冲 */
    val titleLine: String? = null
)

sealed interface LyricResult {
    val sourceId: String
    val costMs: Long

    data class Hit(
        override val sourceId: String,
        val lines: List<LyricLine>,
        override val costMs: Long = 0L
    ) : LyricResult

    data class Miss(
        override val sourceId: String,
        /** 未命中原因，直接进探测报告，便于判断是「播放器没给」还是「我们没取到」 */
        val reason: String,
        override val costMs: Long = 0L
    ) : LyricResult
}

/**
 * 歌词来源。一期只有 L0 / L1 / L2 三个实现；
 * L3（本地 .lrc）与 L4（在线／无障碍）**只保留这个接口**，不写实现、不申请权限。
 */
interface LyricsSource {
    val id: String
    fun tryResolve(ctx: TrackContext): LyricResult
}
