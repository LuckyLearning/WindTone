package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.util.concurrent.ConcurrentHashMap

/**
 * 拼音索引：为搜索（全拼 / 首字母）与字母索引导航提供支持。
 * 库加载后调用 [build]，在 IO 线程完成全部汉字转换，之后查表纯内存。
 *
 * 性能说明：`initialOf()` 会被分组排序（O(n log n) 次比较）与字母索引导航反复调用，
 * 每次都直接访问 pinyin4j 开销很大，因此这里对「单字拼音」和「整串首字母」都加了缓存。
 */
object PinyinIndex {

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /** stableKey -> (全拼, 首字母串) */
    private val index = HashMap<String, Pair<String, String>>(1024)

    /** stableKey -> 生成索引时用的原文；原文没变就复用上次的转换结果 */
    private val textByKey = HashMap<String, String>(1024)

    /** 单字 -> 拼音，空串表示「该字没有拼音」（避免重复查询） */
    private val charCache = ConcurrentHashMap<Char, String>(2048)

    /** 整串 -> 首字母，用于分组排序与索引条 */
    private val initialCache = ConcurrentHashMap<String, Char>(2048)

    private const val CACHE_LIMIT = 8192

    /**
     * 重建索引（**增量**）。
     *
     * 每首歌的索引文本是「标题 + 歌手 + 专辑」；只有这一段文本变化的歌曲才会重新做
     * 汉字转拼音，其余复用上次结果。重新扫描后绝大多数歌是不变的，这一改动把
     * 「扫描完成」这一步从全量转换（几千首）降到只处理新增 / 改名的那些。
     */
    fun build(songs: List<Song>) {
        val newIndex = HashMap<String, Pair<String, String>>(songs.size * 2)
        val newText = HashMap<String, String>(songs.size * 2)
        synchronized(index) {
            for (song in songs) {
                val key = song.stableKey
                val text = "${song.title} ${song.artistOrUnknown} ${song.albumOrUnknown}"
                newText[key] = text
                newIndex[key] = if (textByKey[key] == text) index[key] ?: convert(text) else convert(text)
            }
            index.clear()
            index.putAll(newIndex)
            textByKey.clear()
            textByKey.putAll(newText)
        }
    }

    fun full(key: String): String = synchronized(index) { index[key]?.first }.orEmpty()

    fun initials(key: String): String = synchronized(index) { index[key]?.second }.orEmpty()

    /** 取文本首字符的拼音首字母（非字母返回 '#'） */
    fun initialOf(text: String): Char {
        if (text.isEmpty()) return '#'
        initialCache[text]?.let { return it }
        val c = text.trim().firstOrNull() ?: return '#'
        val result = when {
            c in 'A'..'Z' -> c
            c in 'a'..'z' -> c.uppercaseChar()
            c.code < 128 -> '#'
            else -> pinyinOf(c)?.firstOrNull()?.uppercaseChar() ?: '#'
        }
        if (initialCache.size < CACHE_LIMIT) initialCache[text] = result
        return result
    }

    /** 单字符转全拼（小写），无拼音返回 null */
    fun pinyinOf(c: Char): String? {
        val cached = charCache[c]
        if (cached != null) return cached.ifEmpty { null }
        val py = try {
            PinyinHelper.toHanyuPinyinStringArray(c, format)?.firstOrNull()
        } catch (_: Throwable) {
            null
        }
        if (charCache.size < CACHE_LIMIT) charCache[c] = py.orEmpty()
        return py
    }

    private fun convert(text: String): Pair<String, String> {
        val full = StringBuilder(text.length * 4)
        val ini = StringBuilder(text.length)
        for (c in text) {
            if (c.isWhitespace()) {
                full.append(' ')
                continue
            }
            val py = if (c.code < 128) null else pinyinOf(c)
            if (py != null) {
                full.append(py)
                ini.append(py.firstOrNull() ?: c)
            } else {
                full.append(c.lowercaseChar())
                if (c.isLetterOrDigit()) ini.append(c.lowercaseChar())
            }
        }
        return full.toString() to ini.toString()
    }
}
