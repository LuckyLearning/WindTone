package com.luckylearning.windtone.data

/**
 * 播放页中部的三个横滑页（v1.2.55）。
 *
 * 顺序即左右滑动的顺序：信息 → 图片 → 歌词。首尾两页再往外滑就是切上一首 /
 * 下一首，所以「页」和「曲」在同一根横轴上，用户不需要记两套手势。
 *
 * [index] 与 `SettingsDefaults.PLAYER_PANE` 是同一套编号（落盘用的是 Int），
 * 两者一致由 `PlayerSwipeTest` 对质 —— 这里刻意不把常量本身放进枚举，
 * 因为 `const val` 不能引用 enum 的非常量属性。
 */
enum class PlayerPane(val index: Int) {
    /** 歌曲信息：标签 / 文件参数 / 所在歌单 / 播放统计 */
    INFO(0),

    /** 图片：大封面 + 当前一句歌词 */
    ART(1),

    /** 歌词：整页滚动歌词（车机在这一页是「左歌词 + 右唱片」双栏） */
    LYRICS(2);

    /** 下一张「页」；已是最后一页时为 null（此时该切歌）。 */
    fun next(): PlayerPane? = entries.getOrNull(ordinal + 1)

    /** 上一张「页」；已是第一页时为 null（此时该切上一首）。 */
    fun previous(): PlayerPane? = entries.getOrNull(ordinal - 1)

    /** 相对本页偏移 [delta] 页；越界时返回 null（用于边界判定）。 */
    fun shift(delta: Int): PlayerPane? = entries.getOrNull(ordinal + delta)

    companion object {
        /** 进播放页的出厂默认页：图片页有主体也有歌词，信息密度最平衡。 */
        val DEFAULT: PlayerPane = ART

        /** 落盘 Int → 枚举；越界一律退回 [DEFAULT]，不抛异常。 */
        fun of(index: Int): PlayerPane = entries.getOrNull(index) ?: DEFAULT
    }
}
