package com.anylyric.lyrics.online

/**
 * 在线歌词的来源（0.2.27）。
 *
 * 为什么要有这个枚举：以前三家写死在 [OnlineLyricsFetcher] 的调用顺序里，
 * 用户既不能指定「只用这一家」，也不能改顺序。而真机上各家的强弱很不一样 ——
 * 有的歌只有某一家有正确版本，有的家常年返回翻唱。让用户自己挑，比我们猜准。
 *
 * 两个约束：
 *  · **id 落盘**（SharedPreferences 里存的是 id 字符串），所以 id 一旦发布就不能改；
 *  · 枚举顺序 = 出厂自动顺序，与 [DEFAULT_ORDER] 分开写，改出厂顺序不影响枚举值。
 */
enum class OnlineSource(val id: String) {

    /** 酷狗：搜索 → 歌词搜索 → 下载 LRC，唯一一条「两跳」链路，命中率最高。 */
    KUGOU("kugou"),

    /** 网易云：搜索 → 按 id 取 `lrc.lyric`。翻唱多，靠逐个候选试兜住。 */
    NETEASE("netease"),

    /** LRCLIB：开放 REST API，无需密钥，搜索响应里**直接带** syncedLyrics。 */
    LRCLIB("lrclib"),

    /** lyrics.ovh：开放 REST API，只有纯文本（无时间轴），按 2 秒一句铺开。 */
    LYRICS_OVH("lyricsovh"),

    /** 汽水音乐：Cardash 的第三个源。匿名请求常年返回空体，所以出厂排最后。 */
    QISHUI("qishui");

    companion object {

        /**
         * 出厂自动顺序。
         *
         * 汽水排最后是实测结论（`luna/pc/search/track` 匿名 200 + 空体），
         * 它多跑一次就多两次空请求；两家开放 API 排在中间，作为商业接口之外的兜底。
         */
        val DEFAULT_ORDER: List<OnlineSource> =
            listOf(KUGOU, NETEASE, LRCLIB, LYRICS_OVH, QISHUI)

        /** 「自动」在落盘里的取值。空串比存 "auto" 更省事：老版本读不到这个键就是空串。 */
        const val AUTO_ID = ""

        fun of(id: String?): OnlineSource? {
            if (id.isNullOrBlank()) return null
            return values().firstOrNull { it.id == id }
        }

        /**
         * 解析落盘的顺序串（`,` 分隔）。
         *
         * 容错规则：认不出的 id 直接丢（将来下线某一家时老配置还能用），
         * 列表里缺的源按 [DEFAULT_ORDER] 补到末尾 —— 新版本新增的源不会因为
         * 用户有旧配置就永远用不到。
         */
        fun parseOrder(stored: String?): List<OnlineSource> {
            val out = ArrayList<OnlineSource>(values().size)
            if (!stored.isNullOrBlank()) {
                for (part in stored.split(',')) {
                    val source = of(part.trim()) ?: continue
                    if (!out.contains(source)) out += source
                }
            }
            for (source in DEFAULT_ORDER) if (!out.contains(source)) out += source
            return out
        }

        fun encodeOrder(order: List<OnlineSource>): String =
            order.distinct().joinToString(",") { it.id }

        /** 把一个源上移/下移，越界时原样返回（顺序对话框里的箭头用）。 */
        fun move(order: List<OnlineSource>, source: OnlineSource, delta: Int): List<OnlineSource> {
            val from = order.indexOf(source)
            if (from < 0) return order
            val to = from + delta
            if (to < 0 || to > order.lastIndex) return order
            val out = order.toMutableList()
            out.removeAt(from)
            out.add(to, source)
            return out
        }
    }
}
