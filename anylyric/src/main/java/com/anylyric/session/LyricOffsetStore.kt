package com.anylyric.session

import com.anylyric.lyrics.LyricMatch

/**
 * 歌词时间偏移的编解码与键规则（纯逻辑，可 JVM 单测）。
 *
 * **为什么按「播放器 + 歌名 + 歌手」而不是只按歌名**（用户 2026-11-16 拍板）：
 * 同一首歌在不同播放器里，上报的 `PlaybackState.position` 与音频实际播出位置的
 * 偏差并不一样 —— 各家解码器与进度上报策略不同，几十到几百毫秒的差异很常见。
 * 只按歌名存，会出现「在酷我调好了，切到 QQ 音乐又歪回原来的样子」。
 *
 * 存储形态：一条字符串，条目用 `\u001E`(RS) 分隔、字段用 `\u001F`(US) 分隔。
 * 这两个控制字符不可能出现在歌名里，比 `|` / `,` 安全（歌名里真的有竖线）。
 */
object LyricOffsetStore {

    /** 偏移上下限：±3 秒。超过这个范围就不是「对齐」，是「换了一版时间轴」。 */
    const val MIN_MS = -3000
    const val MAX_MS = 3000

    /** 一次调整的步进。0.2 秒是「能听出来、又不至于要按十次」的折中。 */
    const val STEP_MS = 200

    /** 大跨度按钮的步进。整首差一拍时不用连点五次。 */
    const val BIG_STEP_MS = 1000

    /**
     * 条目上限。
     *
     * 按 400 条算：一条约 60 字节，总量不到 25KB，对 SharedPreferences 无压力；
     * 同时足够覆盖「常听的几百首」。超限按**最久未调整**淘汰（见 [put]）。
     */
    const val MAX_ENTRIES = 400

    private const val RS = '\u001E'
    private const val GS = '\u001D'
    private const val US = '\u001F'

    /** 把毫秒钳到合法区间，并对齐到 10ms —— 避免存进 `137` 这种没有意义的精度。 */
    fun clamp(ms: Int): Int = (ms.coerceIn(MIN_MS, MAX_MS) / 10) * 10

    /**
     * 条目键。
     *
     * 歌名走 [LyricMatch.coreTitle] + [LyricMatch.normalize]，理由：同一首歌在
     * 不同播放器里的标题写法五花八门（`稻香` / `稻香 - 周杰伦` / `稻香 (Live)`），
     * 归一后才能命中同一条偏移。包名只转小写不归一化 —— 包名本身就是稳定标识。
     */
    fun keyOf(pkg: String?, title: String?, artist: String?): String {
        val p = (pkg ?: "").trim().lowercase()
        val artistNorm = LyricMatch.normalize(artist)
        // 有的播放器把歌手写进标题（「稻香 - 周杰伦」）：后半段**确实等于歌手**时剥掉，
        // 否则同一首歌会因为标题写法不同而各自记一条偏移。
        // 不能无条件按 " - " 拆 ——「A - B」本身也可能是一个歌名。
        val rawTitle = run {
            if (artistNorm.isEmpty()) return@run title
            val parts = title?.split(" - ", limit = 2) ?: return@run title
            if (parts.size == 2 && LyricMatch.normalize(parts[1]) == artistNorm) parts[0] else title
        }
        val t = LyricMatch.normalize(LyricMatch.coreTitle(rawTitle))
        val a = artistNorm
        if (t.isEmpty()) return ""
        return "$p$US$t$US$a"
    }

    fun decode(raw: String?): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (raw.isNullOrEmpty()) return out
        for (entry in raw.split(RS)) {
            if (entry.isEmpty()) continue
            // key 与毫秒用 GS 分开（key 内部用的是 US，不能拿它再切一次）。
            val cut = entry.lastIndexOf(GS)
            if (cut <= 0 || cut == entry.lastIndex) continue
            val key = entry.substring(0, cut)
            val ms = entry.substring(cut + 1).toIntOrNull() ?: continue
            out[key] = clamp(ms)
        }
        return out
    }

    fun encode(entries: Map<String, Int>): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        for ((key, ms) in entries) {
            if (key.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(RS)
            sb.append(key).append(GS).append(clamp(ms))
        }
        return sb.toString()
    }

    /**
     * 写入一条并返回新串。
     *
     * 已存在的键先移除再追加到末尾 —— 末尾即「最近调整」，淘汰时先丢最前面的。
     * 值为 0 表示「归零」，**保留条目而不是删除**：用户把偏移调回 0 也是一种
     * 明确的选择，删掉会让「这首歌从没调过」和「调回 0」变得无法区分。
     */
    fun put(raw: String?, key: String, ms: Int, maxEntries: Int = MAX_ENTRIES): String {
        if (key.isEmpty()) return raw.orEmpty()
        val entries = decode(raw)
        entries.remove(key)
        entries[key] = clamp(ms)
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        return encode(entries)
    }

    /** 把某首歌的偏移抹掉（回到「从没调过」）。 */
    fun remove(raw: String?, key: String): String {
        if (key.isEmpty()) return raw.orEmpty()
        val entries = decode(raw)
        entries.remove(key)
        return encode(entries)
    }

    /** 给界面用的带符号文本：`+0.4s` / `-0.2s` / `0.0s`。 */
    fun label(ms: Int): String {
        if (ms == 0) return "0.0s"
        val sign = if (ms > 0) "+" else "-"
        val abs = kotlin.math.abs(ms)
        val whole = abs / 1000
        val tenth = (abs % 1000) / 100
        return "$sign$whole.$tenth" + "s"
    }
}
