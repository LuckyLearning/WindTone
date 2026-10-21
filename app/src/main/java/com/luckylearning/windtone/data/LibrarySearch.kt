package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song

/**
 * 曲库浏览的「搜索过滤」与「字母索引」（v1.1.37 抽出）。
 *
 * ## 为什么单独拎出来
 * 这两件事原先分别住在 [MusicRepository.matches] 与 `LibraryCore.searchSongs` /
 * `buildLetterIndex` 里，都是**纯函数**，却被埋在依赖 MediaStore、Room、协程作用域的大类中，
 * 只能靠 Robolectric 间接测。抽成独立 object 后，它们对运行时的依赖只剩两样：
 * [Song]（数据类）与 [PinyinIndex]（查表，纯 Java 的 pinyin4j，JVM 上就能跑）。
 *
 * ## 搜索是「文本 OR 全拼 OR 首字母」三路并集
 * 用户可能输「周杰伦」（文本）、「zhoujielun」（全拼）、「zjl」（首字母），三路都该命中。
 * 三路都是 `contains` 而非 `equals`：输「海」希望能捞到《海阔天空》。
 *
 * ## 拼音那两路依赖索引已被构建
 * [PinyinIndex.full] / [PinyinIndex.initials] 查的是内存表，**没建过索引就返回空串**，
 * 于是搜索自动退化成纯文本匹配（不报错，只是拼音那两路失效）。
 * 这不是缺陷而是兜底：曲库装载完会调 `PinyinIndex.build(sorted)`（见 `LibraryCore`），
 * 正常路径下索引一定在；万一没建，行为也与「没有拼音搜索」一致。
 * 单测里要验拼音那两路，必须先 `PinyinIndex.build(songs)`。
 */
object LibrarySearch {

    /**
     * 单曲文本匹配（标题 / 歌手 / 专辑 / 文件名），大小写不敏感。
     *
     * [queryLower] 要求**已是小写** —— 调用方（[search]）负责 trim + lowercase 一次，
     * 避免每首歌都对同一个查询重复做规范化。
     */
    fun matches(song: Song, queryLower: String): Boolean {
        if (queryLower.isEmpty()) return false
        return song.title.lowercase().contains(queryLower) ||
            song.artistOrUnknown.lowercase().contains(queryLower) ||
            song.albumOrUnknown.lowercase().contains(queryLower) ||
            song.displayName.lowercase().contains(queryLower)
    }

    /**
     * 过滤出可见歌曲。空查询（或全是空白）原样返回整个列表 —— 交给调用方自己决定要不要拷贝。
     */
    fun search(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            matches(song, q) ||
                PinyinIndex.full(song.stableKey).contains(q) ||
                PinyinIndex.initials(song.stableKey).contains(q)
        }
    }

    /**
     * 首字母 → 该字母在列表里的**首个**下标，用于 A-Z 索引导航。
     *
     * 用 [LinkedHashMap] 而不是 `HashMap`：索引条要按字母顺序显示，
     * 而「首次出现的顺序」在这里恰好就是字母表顺序（列表本身已按标题排过）。
     * 没有拼音的条目归到 `'#'`，由界面决定放最前还是最后。
     */
    fun letterIndex(list: List<Song>): Map<Char, Int> {
        if (list.isEmpty()) return emptyMap()
        val map = LinkedHashMap<Char, Int>()
        list.forEachIndexed { i, song ->
            val ch = PinyinIndex.initialOf(song.title)
            if (!map.containsKey(ch)) map[ch] = i
        }
        return map
    }
}
