package com.anylyric.lyrics

/**
 * 「车机直连通道」推来的整段歌词文本 → 行表。
 *
 * 这条通道的存在理由：`NotificationListenerService` 的授权页在部分车厂 ROM 上被
 * 整页拦掉（DiLink5 报「多媒体不支持」），而通知使用权是 `MediaSessionManager`
 * 唯一合法的入口。BYD 自己的媒体中心另有一个面向第三方应用的 Binder 服务，
 * **不需要任何系统权限**就能拿到歌名 / 歌手 / 时长 / 歌词 —— 见
 * `service/BydMediaController.kt`。授权拿不到时，这是唯一还能出歌词的路。
 *
 * 解析规则刻意保守：
 *  1. 带时间标签的整段 LRC → 走 [LrcParser]，行推进交给时钟；
 *  2. 多行纯文本 → 逐行流（`timeMs = null`），按行序推进；
 *  3. 单行文本不当歌词（多半是「暂无歌词」这类占位），返回空表。
 *
 * 不做「猜歌名」「猜歌手」——这条通道给的字段已经很明确了，猜错一次就是屏上挂着
 * 上一首的歌词，比空着更糟。
 */
object ExternalLyrics {

    /** 纯文本形态至少要有两行才当歌词：单行无法判断是歌词还是提示语。 */
    private const val MIN_PLAIN_LINES = 2

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        if (LrcParser.looksLikeLrc(text)) {
            val lines = LrcParser.parse(text)
            // 带标签但解析不出行（全被过滤成空）时继续走纯文本分支：
            // 有些车机在整段歌词外面套一层 `[ti:]` `[ar:]` 之类的头，正文却是纯文本。
            if (lines.isNotEmpty()) return lines
        }

        val parts = ArrayList<String>(32)
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) parts += trimmed
        }
        if (parts.size < MIN_PLAIN_LINES) return emptyList()
        return parts.map { LyricLine(null, it) }
    }

    /**
     * 「这段文本像不像歌词」——给**不知道键名**的兜底通道用。
     *
     * 车厂 SDK 把歌词塞在哪个键里没有公开约定（不同 DiLink 版本还不一样），
     * 与其猜键名，不如按内容形状认：带时间轴的行集，或每行都不长的多行文本。
     *
     * @param minLines 纯文本形态至少几行才算。扫描整包时要更严（默认 4 行）：
     *   专辑简介、榜单文案也常是多行短句，2 行就认会把它们当歌词画到屏上。
     */
    fun looksLikeLyrics(raw: String?, minLines: Int = 4): Boolean {
        if (raw.isNullOrBlank()) return false
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (LrcParser.looksLikeLrc(text)) return parse(text).size >= 2
        var lines = 0
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // 歌词行不会太长；长句多半是简介或 URL。
            if (trimmed.length > 80) return false
            lines++
            if (lines >= minLines) return true
        }
        return false
    }
}
