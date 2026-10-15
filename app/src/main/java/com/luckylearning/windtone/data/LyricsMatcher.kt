package com.luckylearning.windtone.data

/**
 * 同目录歌词文件匹配规则。
 * 支持：歌名.lrc / 歌名 - 歌手.lrc / 歌名.zh.lrc / 歌名.sc.lrc / 歌名.txt
 */
object LyricsMatcher {

    private val LYRIC_EXT = setOf("lrc", "txt", "krc", "yrc")

    fun isLyricFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in LYRIC_EXT
    }

    fun matches(fileName: String, baseName: String): Boolean {
        if (!isLyricFile(fileName)) return false
        val stem = fileName.substringBeforeLast('.').lowercase()
        val base = baseName.lowercase().trim()
        if (base.isEmpty()) return false
        if (stem == base) return true
        if (stem.startsWith("$base.") && stem.length <= base.length + 8) return true
        if (stem.startsWith("$base-")) return true
        if (stem.startsWith("$base -") && stem.length <= base.length + 32) return true
        if (stem.startsWith("${base}_") && stem.length <= base.length + 8) return true
        return false
    }

    /** 匹配优先级：完全同名 > .lrc > .txt */
    fun score(fileName: String, baseName: String): Int {
        if (!matches(fileName, baseName)) return -1
        val stem = fileName.substringBeforeLast('.').lowercase()
        val base = baseName.lowercase().trim()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        var s = if (stem == base) 100 else 50
        s += when (ext) {
            "lrc" -> 10
            "krc", "yrc" -> 15
            else -> 0
        }
        return s
    }
}
