package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import com.luckylearning.windtone.data.model.Song
import org.json.JSONObject

/**
 * 多碟 discNumber Prefs 侧缓存（G3）。
 *
 * MediaStore 曲目多半可从 TRACK 高 16 位即时解码，不必依赖本缓存；
 * 本缓存主要服务文件夹 / 深扫经 MMR 读到的 DISC_NUMBER（Room 无列）。
 *
 * 存储：disc_number_json = {"<stableKey>":2, ...}，挂在 music_player_prefs。
 */
class DiscNumberCache(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun overlay(song: Song): Song {
        val d = normalizeDiscNumber(load()[song.stableKey]) ?: return song
        if (song.discNumber == d) return song
        return song.copy(discNumber = d)
    }

    fun overlay(songs: List<Song>): List<Song> {
        val map = load()
        if (map.isEmpty()) return songs
        return songs.map { song ->
            val d = normalizeDiscNumber(map[song.stableKey]) ?: return@map song
            if (song.discNumber == d) song else song.copy(discNumber = d)
        }
    }

    fun putFromSongs(songs: List<Song>) {
        val useful = songs.filter { normalizeDiscNumber(it.discNumber) != null }
        if (useful.isEmpty()) return
        val all = load().toMutableMap()
        var changed = false
        for (s in useful) {
            val d = normalizeDiscNumber(s.discNumber) ?: continue
            val key = s.stableKey
            if (key.isBlank()) continue
            if (all[key] != d) {
                all[key] = d
                changed = true
            }
        }
        if (changed) save(all)
    }

    private fun load(): Map<String, Int> {
        val raw = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!root.has(k)) continue
                    val v = root.optInt(k, 0)
                    val d = normalizeDiscNumber(v) ?: continue
                    put(k, d)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(map: Map<String, Int>) {
        val root = JSONObject()
        for ((k, v) in map) root.put(k, v)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    companion object {
        const val KEY = "disc_number_json"
        const val PREFS_NAME = "music_player_prefs"
    }
}
