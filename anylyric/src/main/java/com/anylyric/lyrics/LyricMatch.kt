package com.anylyric.lyrics

import java.security.MessageDigest
import kotlin.math.abs

/**
 * 歌名 / 歌手 / 时长的匹配规则（全部纯函数，可直接单测）。
 *
 * 0.2.24 起**按 Cardash 的判据重写**（`g/R3.b`）：它的取词之所以稳，不是接口选得好，
 * 而是「先筛后排」的关系反过来 ——
 *
 *  · **先筛**：标题归一后必须相等或互相包含，否则这条候选根本不进入评比。
 *    这一条是硬门槛，也是防止滚别人歌词的唯一阀门。
 *  · **再排**：标题 +5 / 包含 +2、歌手命中 +3、时长 <4s +3 / <10s +1 / 其它 -3，
 *    取最高分。**没有总分门槛** —— 只要标题对得上就算有词。
 *
 * 旧实现是「七十分制 + 55 分及格」，时长差 60 秒直接 -45。真机结果是「明明有词却
 * 一直显示暂无歌词」：车机播放器报的标题常带 `- 歌手`、时长偶尔缺省，几条一叠加
 * 就掉到线下。Cardash 的做法是只要标题对就用，宁可时间轴略有偏差也不给空屏。
 *
 * 归一化（去括号说明、砍 `feat.`、按 ` - ` 拆两半、只留字母数字）保留 AnyLyric 原有的
 * 清洗能力 —— 这一层比 Cardash 的「只去空白与标点」更强，不与它的判据冲突。
 */
object LyricMatch {

    /**
     * 标题命中即采用。
     *
     * 判定看的是「非 0」而不是某个分数线：0 只有一个含义 —— 标题对不上。
     * 分数可能是负数（标题包含 +2 配时长 -3），那仍然是一首**标题对得上**的歌，
     * 只是版本时长不同，照样给词（Cardash 同款取舍）。
     */
    fun acceptable(score: Int): Boolean = score != 0

    private const val TITLE_EXACT = 5
    private const val TITLE_CONTAINS = 2
    private const val ARTIST_HIT = 3

    /** 时长三档（毫秒差按秒折算后比较）。 */
    private const val DUR_CLOSE_SEC = 4L
    private const val DUR_NEAR_SEC = 10L
    private const val DUR_CLOSE = 3
    private const val DUR_NEAR = 1
    private const val DUR_FAR = -3

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
     * 候选打分（Cardash 口径）。
     *
     * 0 分 = **标题对不上，直接淘汰**。非 0 的最小值是 2（标题被包含），
     * 最大值 11（标题一致 + 歌手一致 + 时长差 4 秒内）。
     *
     * 时长在这里只影响排序，不做否决：同名同歌手的现场版与录音室版时长能差半分钟，
     * Cardash 的选择是「照样给词」，因为「时间轴略偏」比「没有词」体验好。
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
        if (titleBest == 0) return 0
        // 括号里是版本标记（`(治愈版)` `(Live)` `(伴奏)`）时降一分：洗掉括号是为了
        // **搜得到**，但排序时原版必须排在翻唱 / 剪辑前面。真机原案：网易第一条是
        // 「稻香(治愈版)」，洗成「稻香」后与原版同分并先出现，而它是一份空歌词，
        // 于是明明有词却一直显示「暂无歌词」。
        if (titleBest == TITLE_EXACT &&
            (title?.let { it != coreTitle(it) } == true ||
                candidateTitle?.let { it != coreTitle(it) } == true)
        ) {
            titleBest = TITLE_EXACT - 1
        }
        return titleBest + artistScore(artist, candidateArtist) +
            durationScore(durationSec, candidateDurationSec)
    }

    /**
     * 标题对不上就是 0 —— 这是**唯一的硬门槛**。
     *
     * 单字标题不参与包含判定：`稻` 会命中一大片歌名，靠歌手与时长也压不住。
     */
    private fun pairScore(a: String, b: String): Int {
        if (a == b) return TITLE_EXACT
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length >= 2 && longer.contains(shorter)) return TITLE_CONTAINS
        return 0
    }

    private fun artistScore(query: String?, candidate: String?): Int {
        val a = normalize(coreTitle(query))
        if (a.isEmpty()) return 0
        val b = normalize(coreTitle(candidate))
        if (b.isEmpty()) return 0
        if (a == b || a.contains(b) || b.contains(a)) return ARTIST_HIT
        return 0
    }

    private fun durationScore(targetSec: Long, candidateSec: Long): Int {
        if (targetSec <= 0L || candidateSec <= 0L) return 0
        val diff = abs(targetSec - candidateSec)
        return when {
            diff < DUR_CLOSE_SEC -> DUR_CLOSE
            diff < DUR_NEAR_SEC -> DUR_NEAR
            else -> DUR_FAR
        }
    }
}
