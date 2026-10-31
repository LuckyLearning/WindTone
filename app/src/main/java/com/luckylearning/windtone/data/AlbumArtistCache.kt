package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import com.luckylearning.windtone.data.model.Song
import org.json.JSONObject

/**
 * Album Artist Prefs 侧缓存（G2）。
 *
 * Room 无 albumArtist 列且 MusicDb 使用 fallbackToDestructiveMigration：
 * 不能为浏览视图升 schema。扫描读到的字段写入本缓存，冷启动再盖回 [Song]。
 *
 * 存储：`album_artist_json` = `{"<stableKey>":"Various Artists", ...}`
 * 挂在 `music_player_prefs`（与流派/年代、书签等同仓）。
 */
class AlbumArtistCache(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    @Volatile
    private var memo: Map<String, String>? = null

    /** 用缓存里的 albumArtist 覆盖 Song（缓存无记录则原样返回）。 */
    fun overlay(song: Song): Song {
        val aa = normalizeAlbumArtist(load()[song.stableKey]) ?: return song
        if (song.albumArtist == aa) return song
        return song.copy(albumArtist = aa)
    }

    fun overlay(songs: List<Song>): List<Song> {
        val map = load()
        if (map.isEmpty()) return songs
        return songs.map { song ->
            val aa = normalizeAlbumArtist(map[song.stableKey]) ?: return@map song
            if (song.albumArtist == aa) song else song.copy(albumArtist = aa)
        }
    }

    /**
     * 把扫描得到的 albumArtist 合并进缓存。
     * 仅写入非空字段；已有值可被新扫描覆盖。
     */
    fun putFromSongs(songs: List<Song>) {
        val useful = songs.filter { normalizeAlbumArtist(it.albumArtist) != null }
        if (useful.isEmpty()) return
        val all = load().toMutableMap()
        var changed = false
        for (s in useful) {
            val aa = normalizeAlbumArtist(s.albumArtist) ?: continue
            val key = s.stableKey
            if (key.isBlank()) continue
            if (all[key] != aa) {
                all[key] = aa
                changed = true
            }
        }
        if (changed) save(all)
    }

    fun syncFromLibrary(songs: List<Song>) {
        putFromSongs(songs)
        retainOnly(songs.mapNotNullTo(HashSet(songs.size.coerceAtLeast(16))) {
            it.stableKey.takeIf { k -> k.isNotBlank() }
        })
    }

    fun retainOnly(keep: Set<String>) {
        val all = load().toMutableMap()
        if (!MetadataPrefsPolicy.retainAndCap(all, keep)) return
        save(all)
    }

    private fun load(): Map<String, String> {
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
                    val v = root.optString(k, "").takeIf { it.isNotBlank() } ?: continue
                    put(k, v)
                }
            }
        }.getOrDefault(emptyMap())
        memo = parsed
        return parsed
    }

    private fun save(map: Map<String, String>) {
        val root = JSONObject()
        for ((k, v) in map) root.put(k, v)
        prefs.edit().putString(KEY, root.toString()).apply()
        memo = map
    }

    companion object {
        const val KEY = "album_artist_json"
        const val PREFS_NAME = "music_player_prefs"
    }
}
