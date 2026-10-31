package com.luckylearning.windtone.data

/**
 * G1/G2/G3 Prefs JSON 侧缓存的共用纪律（资源优先）。
 *
 * Room 无 genre/year/albumArtist/discNumber 列，字段落在 `music_player_prefs` 的
 * 大 JSON 上。若不裁剪，删歌后键永不消失，曲库上万时 SharedPreferences 与每次
 * `JSONObject` 全量解析都会 silently 膨胀——这不是功能缺口，是长稳内存/IO 债。
 *
 * 调用方在曲库 `applyLibrary` 后 [retainAndCap]：只留当前库里的 stableKey，
 * 并硬卡 [MAX_ENTRIES]。
 */
object MetadataPrefsPolicy {

    /** 单份侧缓存最大条目；远高于正常车机曲库，只挡异常/损坏 JSON */
    const val MAX_ENTRIES = 20_000

    /**
     * 就地：先丢掉不在 [keep] 里的键，若仍超 [MAX_ENTRIES] 按键名字典序截断。
     * @return 是否发生过删除
     */
    fun <V> retainAndCap(map: MutableMap<String, V>, keep: Set<String>): Boolean {
        var changed = false
        val it = map.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in keep) {
                it.remove()
                changed = true
            }
        }
        if (map.size > MAX_ENTRIES) {
            val sorted = map.keys.sorted()
            for (i in MAX_ENTRIES until sorted.size) {
                map.remove(sorted[i])
                changed = true
            }
        }
        return changed
    }
}
