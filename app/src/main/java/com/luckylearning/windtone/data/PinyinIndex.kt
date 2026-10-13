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

    /** 单字 -> 拼音，空串表示「该字没有拼音」（避免重复查询） */
    private val charCache = ConcurrentHashMap<Char, String>(2048)

    /** 整串 -> 首字母，用于分组排序与索引条 */
    private val initialCache = ConcurrentHashMap<String, Char>(2048)

    private const val CACHE_LIMIT = 8192

    fun build(songs: List<Song>) {
        val map = HashMap<String, Pair<String, String>>(songs.size * 2)
        for (song in songs) {
            val text = "${song.title} ${song.artistOrUnknown} ${song.albumOrUnknown}"
            map[song.stableKey] = convert(text)
        }
        synchronized(index) { index.clear(); index.putAll(map) }
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
