package com.luckylearning.windtone.playback

/**
 * ReplayGain 增益模式（N6）。
 *
 * 音量平衡开着时，决定优先用曲目增益还是专辑增益。
 * 选择与回退规则见 [ReplayGainTags.Gains.preferredDb]。
 */
enum class ReplayGainMode {
    /** 曲目增益：各曲独立拉平，适合「歌单混搭」 */
    TRACK,
    /** 专辑增益：同专内相对响度保留，适合「整专连听」 */
    ALBUM,
    ;

    companion object {
        fun fromStored(raw: String?): ReplayGainMode =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: TRACK
    }
}
