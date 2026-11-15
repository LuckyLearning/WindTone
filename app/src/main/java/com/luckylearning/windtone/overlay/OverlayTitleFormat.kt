package com.luckylearning.windtone.overlay

/**
 * 悬浮歌词标题文案的组装（纯函数，可单测）。
 *
 * 与 [LyricOverlayService.applyTitle] 解耦：标题内容只取决于「模式 + 歌名 + 歌手」，
 * 不碰 TextView / 资源 / 生命周期。模式 3 是「描边浮层」样式专属的单行形态。
 */
object OverlayTitleFormat {

    /** 标题模式：0 歌名+歌手（竖排两行）/ 1 只歌名 / 2 只歌手 / 3 单行「歌名 - 歌手」 */
    const val MODE_TITLE_ARTIST = 0
    const val MODE_TITLE_ONLY = 1
    const val MODE_ARTIST_ONLY = 2
    const val MODE_SINGLE_LINE = 3

    /**
     * 组装标题文案。
     *
     * - [MODE_TITLE_ARTIST]：歌名、歌手各一行（竖排），任一为空则只显示另一行；
     * - [MODE_TITLE_ONLY] / [MODE_ARTIST_ONLY]：只取其一；
     * - [MODE_SINGLE_LINE]：单行「歌名 - 歌手」；当 **歌名长度 + 歌手长度 > 10** 时
     *   只显示歌名（满足「歌名加歌手超过 10 个字就只显示歌名」）。歌手为空直接返回歌名。
     */
    fun format(mode: Int, title: String, artist: String): String {
        return when (mode) {
            MODE_TITLE_ONLY -> title
            MODE_ARTIST_ONLY -> artist
            MODE_SINGLE_LINE -> formatSingleLine(title, artist)
            else -> listOf(title, artist).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    private fun formatSingleLine(title: String, artist: String): String {
        if (artist.isBlank()) return title
        val combined = "$title - $artist"
        // 歌名加歌手的字符数（不含分隔符）超过 10 字 → 仅歌名
        return if (title.length + artist.length > 10) title else combined
    }
}
