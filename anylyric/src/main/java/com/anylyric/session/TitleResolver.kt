package com.anylyric.session

import com.anylyric.lyrics.LyricLine

/**
 * 歌名 / 歌手的取值与判定（纯函数，便于单测）。
 *
 * 真机上「时不时取不到歌名」有两个来源：
 *  · 只读 `TITLE` / `ARTIST`，而不少播放器只填 `DISPLAY_TITLE` / `ALBUM_ARTIST`；
 *  · 换歌或切前后台的一瞬间元数据为空，直接把上一次的值覆盖成了 null。
 */
object TitleResolver {

    /** 取第一个非空白值；null / 空串 / 纯空白都跳过。 */
    fun firstNonBlank(vararg values: CharSequence?): String? {
        for (value in values) {
            if (value == null) continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /**
     * 标题是否与某一条歌词完全相同。
     *
     * 这是「伪歌名」的强信号：逐行推歌词的播放器会把当前歌词写进 TITLE。
     * 判定只看**原始 TITLE**，不能看粘住后的展示值（真实歌名不会一行一变）。
     */
    fun isLyricTitle(title: String?, lines: List<LyricLine>): Boolean {
        val candidate = title?.trim() ?: return false
        if (candidate.isEmpty()) return false
        for (line in lines) {
            if (line.text.trim() == candidate) return true
        }
        return false
    }
}
