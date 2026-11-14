package com.anylyric.lyrics

import java.security.MessageDigest
import kotlin.math.abs

/**
 * 歌名 / 歌手 / 时长三者的匹配规则（全部纯函数，可直接单测）。
 *
 * 为什么要有这一层：在线歌词库的搜索是**关键词**搜索，返回的是「含这些字的一堆歌」，
 * 而不是「这首歌」。取第一条的实现看起来能用，实际命中率很低 ——
 * 「不悟兰因 - AkI阿杰」这种把歌手塞进标题的写法、歌名里的 `(Live)` `【伴奏】`、
 * 同名不同人、副歌剪辑版，都会把第一条顶成别人的歌。而**匹配错**比**没匹配**更糟：
 * 屏上会滚一段不属于当前这首歌的词，用户只会认为整个功能不可靠。
 *
 * 所以规则是：先洗（[coreTitle] / [normalize]），再打分（[score]），够 [SCORE_FLOOR]
 * 才采用，否则宁可判「没找到」，交给下一次重试。
 */
object LyricMatch {

    /** 打分下限。低于它一律视为「不是这首歌」。 */
    const val SCORE_FLOOR = 55

    private const val TITLE_EXACT = 62
    private const val TITLE_CONTAINS = 44
    private const val ARTIST_EXACT = 24
    private const val ARTIST_CONTAINS = 14
    private const val ARTIST_UNKNOWN = 10

    /**
     * 去掉标题里的「噪声片段」，但保留正文。
     *
     * 处理三种车机上常见的写法：
     *  1. 括号包裹的补充说明：`(Live)`、`（伴奏）`、`【纯音乐】`、`(feat. 某人)`；
     *  2. 书名号只是装饰：`《稻香》` → `稻香`；
     *  3. 全角括号 → 半角，避免同一段内容出现两种写法。
     */
    fun coreTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        var depth = 0
        for (ch in raw) {
            when (ch) {
                '（' -> { depth++; continue }
                '(' -> { depth++; continue }
                '【' -> { depth++; continue }
                '[' -> { depth++; continue }
                '）', ')' -> { if (depth > 0) depth--; continue }
                '】', ']' -> { if (depth > 0) depth--; continue }
                '《', '》' -> continue
            }
            if (depth == 0) sb.append(ch)
        }
        return cutFeaturing(sb.toString()).trim()
    }

    /**
     * 砍掉 `feat.` / `ft.` 之后的合作歌手。
     *
     * 这类写法不带括号：`起风了 feat. 某人`。留着它会让整串既不等于「起风了」，
     * 也过不了包含判定的长度比，白白丢掉一条本来能命中的记录。
     */
    private fun cutFeaturing(text: String): String {
        val lower = text.lowercase()
        var cut = -1
        for (marker in FEAT_MARKERS) {
            val at = lower.indexOf(marker)
            if (at > 0 && (cut < 0 || at < cut)) cut = at
        }
        return if (cut > 0) text.substring(0, cut) else text
    }

    private val FEAT_MARKERS = arrayOf(" feat. ", " feat ", " ft. ", " ft ")

    /** 只留字母与数字（含中日韩汉字），用于比较。 */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lower = raw.lowercase()
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * 标题的几种归一形态：「整串」+「按 ` - ` 拆开的两半」。
     *
     * `周杰伦 - 稻香` 与 `稻香 - 周杰伦` 都要能和 `稻香` 匹配上，
     * 而只取整串时两边谁也命中不了谁。
     */
    fun titleForms(raw: String?): List<String> {
        val core = coreTitle(raw)
        if (core.isEmpty()) return emptyList()
        val out = ArrayList<String>(3)
        fun add(text: String) {
            val n = normalize(text)
            if (n.isNotEmpty() && !out.contains(n)) out += n
        }
        add(core)
        for (part in splitDashes(core)) add(part)
        return out
    }

    private fun splitDashes(text: String): List<String> {
        for (dash in DASHES) {
            val at = text.indexOf(dash)
            if (at <= 0) continue
            val left = text.substring(0, at).trim()
            val right = text.substring(at + dash.length).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) return listOf(left, right)
        }
        return emptyList()
    }

    private val DASHES = arrayOf(" - ", " – ", " — ", "－")

    /**
     * 缓存键：歌名 + 歌手 的 SHA-1。
     *
     * 用摘要而不是原文，是为了当文件名：歌名里出现 `/`、`:`、emoji 时不至于写不进去，
     * 也不会因为大小写或括号写法不同而重复存两份。
     */
    fun cacheKey(title: String?, artist: String?): String {
        val base = normalize(coreTitle(title))
        if (base.isEmpty()) return ""
        val seed = base + "|" + normalize(artist)
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                if (v < 16) hex.append('0')
                hex.append(Integer.toHexString(v))
            }
            hex.toString()
        }.getOrDefault(base.hashCode().toString())
    }

    /**
     * 依次尝试的搜索关键词。
     *
     * 第一版用「歌名 歌手」，因为多数曲库的排序会把唱得最像的那首顶上来；
     * 命中不了再退回纯歌名 —— 歌手字段在车机上经常是**播放器名字**（`Media Center`），
     * 带上它只会把结果推偏。
     */
    fun queries(title: String?, artist: String?): List<String> {
        val core = coreTitle(title)
        if (core.isEmpty()) return emptyList()
        val out = ArrayList<String>(3)
        val a = coreTitle(artist)
        if (a.isNotEmpty()) out += "$core $a"
        for (part in splitDashes(core)) {
            if (!out.contains(part)) out += part
        }
        if (!out.contains(core)) out += core
        return out
    }

    /**
     * 候选打分：标题为主（0–62），歌手加分（0–24），时长纠偏（-16–14）。
     *
     * 时长是**最可靠的一条独立证据**：同名同歌手还有「剪辑版 / 演唱会版 / 加速版」，
     * 差出半分钟以上的基本不是同一段音频。
     */
    fun score(
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationSec: Long,
        title: String?,
        artist: String?,
        durationSec: Long
    ): Int {
        val q = titleForms(title)
        val c = titleForms(candidateTitle)
        if (q.isEmpty() || c.isEmpty()) return 0
        var titleBest = 0
        for (a in q) {
            for (b in c) {
                val s = pairScore(a, b)
                if (s > titleBest) titleBest = s
            }
        }
        val total = titleBest + artistScore(artist, candidateArtist) +
            durationScore(durationSec, candidateDurationSec)
        return total.coerceIn(0, 100)
    }

    fun acceptable(score: Int): Boolean = score >= SCORE_FLOOR

    private fun pairScore(a: String, b: String): Int {
        if (a == b) return TITLE_EXACT
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 2 && longer.contains(shorter)) {
            // 「晴天」命中「晴天娃娃」不该和「晴天」一样高：长度差越大越可疑。
            val ratio = shorter.length.toFloat() / longer.length
            return (TITLE_CONTAINS * ratio).toInt().coerceAtLeast(18)
        }
        var hit = 0
        for (ch in shorter) {
            if (longer.contains(ch)) hit++
        }
        return hit * 30 / shorter.length.coerceAtLeast(1)
    }

    private fun artistScore(query: String?, candidate: String?): Int {
        val a = normalize(coreTitle(query))
        if (a.isEmpty()) return ARTIST_UNKNOWN
        val b = normalize(coreTitle(candidate))
        if (b.isEmpty()) return 0
        if (a == b) return ARTIST_EXACT
        if (a.length >= 2 && (a.contains(b) || b.contains(a))) return ARTIST_CONTAINS
        return 0
    }

    private fun durationScore(targetSec: Long, candidateSec: Long): Int {
        if (targetSec <= 0 || candidateSec <= 0) return 0
        val diff = abs(targetSec - candidateSec)
        return when {
            diff <= 3 -> 14
            diff <= 8 -> 9
            diff <= 15 -> 3
            diff <= 30 -> 0
            diff <= 60 -> -25
            // 差出一分钟以上基本是另一个录音（现场版 / 加长版 / DJ 版）。
            // 这种词的**时间轴对不上**，比没有词更糟，所以直接压到线下。
            else -> -45
        }
    }
}
