package com.luckylearning.windtone.data.online

import android.content.Context
import org.json.JSONObject

/**
 * 「哪个在线歌单收进了哪个本地列表」的映射。
 *
 * 为什么要单独存一份：`playlists` 表里只有 id / name / createdAt，没有来源字段。
 * 想实现两件事都绕不开它：
 *  1. 详情页要显示「已在我的列表」，否则用户点完看不出生效，会再点一次 → 收到两条重复列表；
 *  2. 再点一次不该新建，而要更新同一份列表。
 *
 * 不往数据库加列的原因：本工程的迁移只有 `fallbackToDestructiveMigration()`，
 * 加一列等于清空用户的曲库、收藏与列表 —— 为一条来源标记付这个代价不值得。
 *
 * 用独立 SharedPreferences 而不是 [com.luckylearning.windtone.data.Prefs]：
 * 这是内部簿记，不该出现在设置项 / 数据备份的语义里。
 */
class SavedSheetStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 歌单键（`lx:songlist:kw:<id>`）→ 本地列表 id */
    fun get(sheetKey: String): Long? = load()[sheetKey]

    fun put(sheetKey: String, playlistId: Long) {
        val next = load().toMutableMap()
        next[sheetKey] = playlistId
        save(next)
    }

    /** 列表被删 / 被合并走这里：把指向它的歌单键一起摘掉或改指 [into]。 */
    fun repoint(fromPlaylistId: Long, intoPlaylistId: Long?) {
        val current = load()
        val next = LinkedHashMap<String, Long>()
        current.forEach { (key, pid) ->
            when {
                pid != fromPlaylistId -> next[key] = pid
                intoPlaylistId != null && intoPlaylistId > 0L -> next[key] = intoPlaylistId
                // into == null：列表没了，映射一起丢
            }
        }
        save(next)
    }

    fun all(): Map<String, Long> = load()

    private fun load(): Map<String, Long> {
        val raw = sp.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val out = LinkedHashMap<String, Long>()
            json.keys().forEach { key ->
                val v = json.optLong(key, 0L)
                if (v > 0L) out[key] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun save(map: Map<String, Long>) {
        // 空表直接清掉，避免留下 "{}" 这种垃圾值
        if (map.isEmpty()) {
            sp.edit().remove(KEY).apply()
            return
        }
        val json = JSONObject()
        map.forEach { (key, pid) -> json.put(key, pid) }
        sp.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val FILE = "online_saved_sheets"
        const val KEY = "sheet_to_playlist"
    }
}
