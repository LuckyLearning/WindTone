package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song

/**
 * 多碟专辑 discNumber 纯函数口径（G3）。
 *
 * Room SongEntity **没有** discNumber 列；MediaStore 的 TRACK 常把碟号编进高 16 位
 * （(disc << 16) | track），本版优先从已有 [Song.trackNo] 解码，避免升 schema。
 * 文件夹 / 深扫走 MMR DISC_NUMBER 时写入 [Song.discNumber]，并由 [DiscNumberCache]
 * Prefs 侧缓存以便冷启动盖回。
 */

/** MediaStore TRACK：低 16 位曲序，高 16 位碟号（碟号 >0 时有效）。 */
fun decodeMediaStoreTrack(raw: Int): Pair<Int?, Int> {
    if (raw <= 0) return null to 0
    val disc = (raw ushr 16) and 0xFFFF
    val track = raw and 0xFFFF
    return if (disc > 0) disc to track else null to track
}

/** 「2」「2/3」「Disc 2」等 → 正整数碟号；无效 null。 */
fun parseDiscNumber(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val t = raw.trim()
    val m = DISC_IN_TEXT.find(t) ?: return null
    // 「100」会被 \d{1,2} 截成 10；匹配后若仍有多余数字则整段无效
    val after = t.substring(m.range.last + 1)
    if (after.isNotEmpty() && after[0].isDigit()) return null
    val beforeIdx = m.range.first - 1
    if (beforeIdx >= 0 && t[beforeIdx].isDigit()) return null
    return m.groupValues[1].toIntOrNull()?.takeIf { it in 1..99 }
}

fun normalizeDiscNumber(n: Int?): Int? = n?.takeIf { it in 1..99 }

/**
 * 有效碟号：显式 [Song.discNumber] 优先，否则从 MediaStore 编码的 [Song.trackNo] 解码。
 * 皆无则 null（单碟 / 无碟信息）。
 */
fun Song.effectiveDiscNumber(): Int? =
    normalizeDiscNumber(discNumber)
        ?: decodeMediaStoreTrack(trackNo).first

/** 曲序：有碟编码时取低 16 位，否则用原始 trackNo（兼容未编码的单碟）。 */
fun Song.effectiveTrackNumber(): Int {
    val (disc, track) = decodeMediaStoreTrack(trackNo)
    return if (disc != null) track else trackNo.coerceAtLeast(0)
}

/** 分组用碟号：无信息时视为第 1 碟（仅在已判定「需要显示碟分区」时使用）。 */
fun Song.discForGrouping(): Int = effectiveDiscNumber() ?: 1

/** 专辑内是否应按碟分区展示（多于一个有效碟号，或存在 >1 的碟号）。 */
fun shouldShowDiscHeaders(songs: List<Song>): Boolean {
    val discs = songs.mapNotNull { it.effectiveDiscNumber() }.toSet()
    if (discs.isEmpty()) return false
    return discs.size > 1 || discs.any { it > 1 }
}

/**
 * 专辑详情排序：有碟信息时 disc → track → title；无碟信息时与旧行为一致（trackNo → title）。
 */
fun sortAlbumSongs(songs: List<Song>): List<Song> {
    val anyDisc = songs.any { it.effectiveDiscNumber() != null }
    return if (!anyDisc) {
        songs.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
    } else {
        songs.sortedWith(
            compareBy(
                { it.discForGrouping() },
                { it.effectiveTrackNumber() },
                { it.title.lowercase() },
            ),
        )
    }
}

/**
 * 艺人 / 合辑艺人组内：专辑名 →（有碟则 disc→track，否则 trackNo）→ title。
 */
fun sortArtistAlbumSongs(songs: List<Song>): List<Song> {
    val anyDisc = songs.any { it.effectiveDiscNumber() != null }
    return if (!anyDisc) {
        songs.sortedWith(
            compareBy(
                { it.albumOrUnknown.lowercase() },
                { it.trackNo },
                { it.title.lowercase() },
            ),
        )
    } else {
        songs.sortedWith(
            compareBy(
                { it.albumOrUnknown.lowercase() },
                { it.discForGrouping() },
                { it.effectiveTrackNumber() },
                { it.title.lowercase() },
            ),
        )
    }
}

private val DISC_IN_TEXT = Regex("""(?i)(?:disc|disk|cd|碟)?\s*(\d{1,2})(?:\s*/\s*\d{1,2})?""")
