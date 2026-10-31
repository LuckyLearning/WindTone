package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import com.luckylearning.windtone.data.model.Song
import org.json.JSONObject

/**
 * 流派 / 年代 Prefs 侧缓存（G1）。
 *
 * Room 无 genre/year 列且 MusicDb 使用 fallbackToDestructiveMigration：
 * 不能为浏览视图升 schema。扫描读到的字段写入本缓存，冷启动再盖回 [Song]。
 *
 * 存储：`genre_year_json` = `{"<stableKey>":{"g":"Rock","y":2020}, ...}`
 * 挂在 `music_player_prefs`（与书签等同仓）。
 */
class GenreYearCache(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    data class Entry(val genre: String? = null, val year: Int? = null)

    /** 解析结果驻留内存；save / clear 时失效。避免 applyLibrary 连读 6 次 JSON。 */
    @Volatile
    private var memo: Map<String, Entry>? = null

    /** 用缓存里的 genre/year 覆盖 Song（缓存无记录则原样返回）。 */
    fun overlay(song: Song): Song {
        val e = load()[song.stableKey] ?: return song
        val g = normalizeGenre(e.genre)
        val y = e.year?.let { parseYearInt(it) }
        if (g == null && y == null && song.genre == null && song.year == null) return song
        return song.copy(
            genre = g ?: song.genre,
            year = y ?: song.year,
        )
    }

    fun overlay(songs: List<Song>): List<Song> {
        val map = load()
        if (map.isEmpty()) return songs
        return songs.map { song ->
            val e = map[song.stableKey] ?: return@map song
            val g = normalizeGenre(e.genre)
            val y = e.year?.let { parseYearInt(it) }
            if (g == null && y == null) song
            else song.copy(genre = g ?: song.genre, year = y ?: song.year)
        }
    }

    /**
     * 把扫描得到的 genre/year 合并进缓存。
     * 仅写入非空字段；已有值可被新扫描覆盖。
     */
    fun putFromSongs(songs: List<Song>) {
        val useful = songs.filter { normalizeGenre(it.genre) != null || it.year?.let { y -> parseYearInt(y) } != null }
        if (useful.isEmpty()) return
        val all = load().toMutableMap()
        var changed = false
        for (s in useful) {
            val g = normalizeGenre(s.genre)
            val y = s.year?.let { parseYearInt(it) }
            val key = s.stableKey
            if (key.isBlank()) continue
            val prev = all[key]
            val next = Entry(
                genre = g ?: prev?.genre,
                year = y ?: prev?.year,
            )
            if (prev != next) {
                all[key] = next
                changed = true
            }
        }
        if (changed) save(all)
    }

    /**
     * 曲库落地后调用：写入扫描字段，并只保留当前库的键（+ [MetadataPrefsPolicy.MAX_ENTRIES]）。
     * 须在 IO 调度器调用。
     */
    fun syncFromLibrary(songs: List<Song>) {
        putFromSongs(songs)
        retainOnly(songs.mapNotNullTo(HashSet(songs.size.coerceAtLeast(16))) {
            it.stableKey.takeIf { k -> k.isNotBlank() }
        })
    }

    /** 丢掉已不在曲库的键；超上限再截断。 */
    fun retainOnly(keep: Set<String>) {
        val all = load().toMutableMap()
        if (!MetadataPrefsPolicy.retainAndCap(all, keep)) return
        save(all)
    }

    private fun load(): Map<String, Entry> {
        memo?.let { return it }
        val raw = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
        if (raw == null) {
            memo = emptyMap()
            return emptyMap()
        }
        val parsed = runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = root.optJSONObject(k) ?: continue
                    val g = o.optString("g", "").takeIf { it.isNotBlank() }
                    val y = if (o.has("y")) o.optInt("y") else null
                    put(k, Entry(genre = g, year = y))
                }
            }
        }.getOrDefault(emptyMap())
        memo = parsed
        return parsed
    }

    private fun save(map: Map<String, Entry>) {
        val root = JSONObject()
        for ((k, e) in map) {
            val o = JSONObject()
            if (e.genre != null) o.put("g", e.genre)
            if (e.year != null) o.put("y", e.year)
            root.put(k, o)
        }
        prefs.edit().putString(KEY, root.toString()).apply()
        memo = map
    }

    companion object {
        const val KEY = "genre_year_json"
        const val PREFS_NAME = "music_player_prefs"
    }
}
