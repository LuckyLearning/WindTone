package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.GroupInfo

/**
 * Album Artist（合辑艺人）浏览的纯函数口径（G2）。
 *
 * Room SongEntity **没有** albumArtist 列；本版用 Prefs 侧缓存（见 [AlbumArtistCache]）
 * 挂在 [Song] 内存字段上，避免升 schema 触发 destructive 清库。
 *
 * 归组优先 [Song.albumArtist]，空则回退 [Song.artist]，使 Various Artists / 合辑
 * 与「按歌手浏览」分开：合辑曲目归到合辑艺人名下，而不被拆进各演唱者。
 */

const val UNKNOWN_ALBUM_ARTIST_LABEL = "未知合辑艺人"

/** 去掉空白与常见「未知」占位；无效得 null。保留 Various Artists / 合辑 / 群星 等有效标签。 */
fun normalizeAlbumArtist(raw: String?): String? {
    if (raw == null) return null
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t == "<unknown>" || t.equals("unknown", ignoreCase = true) ||
        t == "未知" || t == "未知歌手" || t == "未知合辑艺人"
    ) return null
    return t
}

/**
 * 有效合辑艺人：优先 albumArtist，回退 artist。
 * 两者皆无则 null（调用方再套「未知合辑艺人」）。
 */
fun Song.effectiveAlbumArtist(): String? =
    normalizeAlbumArtist(albumArtist)
        ?: normalizeAlbumArtist(artist)

fun Song.albumArtistOrUnknown(): String =
    effectiveAlbumArtist() ?: UNKNOWN_ALBUM_ARTIST_LABEL

/** 按合辑艺人聚合；组标题=艺人名，副标题=曲数。 */
fun buildAlbumArtistGroups(songs: List<Song>): List<GroupInfo> {
    if (songs.isEmpty()) return emptyList()
    return songs.groupBy { it.albumArtistOrUnknown() }.map { (k, list) ->
        GroupInfo(k, k, "${list.size} 首", list.size, list.first())
    }
}
