package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.GroupInfo

/**
 * 流派 / 年代浏览的纯函数口径（G1）。
 *
 * Room SongEntity **没有** genre / year 列；本版用 Prefs 侧缓存（见 [GenreYearCache]）
 * 挂在 [Song] 内存字段上，避免升 schema 触发 destructive 清库。
 */

const val UNKNOWN_GENRE_LABEL = "未知流派"
const val UNKNOWN_YEAR_LABEL = "未知年代"

/** 去掉空白与常见「未知」占位；无效得 null。 */
fun normalizeGenre(raw: String?): String? {
    if (raw == null) return null
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t == "<unknown>" || t.equals("unknown", ignoreCase = true) ||
        t == "未知" || t == "未知流派"
    ) return null
    return t
}

fun parseYearLabel(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val m = YEAR_IN_TEXT.find(raw.trim()) ?: return null
    return m.value.toIntOrNull()?.takeIf { it in YEAR_MIN..YEAR_MAX }
}

/** MediaStore / MMR 给出的整型年份；越界视为无效。 */
fun parseYearInt(year: Int): Int? = year.takeIf { it in YEAR_MIN..YEAR_MAX }

fun Song.genreOrUnknown(): String = normalizeGenre(genre) ?: UNKNOWN_GENRE_LABEL

fun Song.yearOrUnknown(): String =
    year?.takeIf { it in YEAR_MIN..YEAR_MAX }?.toString() ?: UNKNOWN_YEAR_LABEL

/** 按流派聚合；组标题=流派名，副标题=曲数。 */
fun buildGenreGroups(songs: List<Song>): List<GroupInfo> {
    if (songs.isEmpty()) return emptyList()
    return songs.groupBy { it.genreOrUnknown() }.map { (k, list) ->
        GroupInfo(k, k, "${list.size} 首", list.size, list.first())
    }
}

/**
 * 按年代聚合。
 * 组键用年份字符串（或「未知年代」）；默认排序由调用方按数值降序处理。
 */
fun buildYearGroups(songs: List<Song>): List<GroupInfo> {
    if (songs.isEmpty()) return emptyList()
    return songs.groupBy { it.yearOrUnknown() }.map { (k, list) ->
        GroupInfo(k, k, "${list.size} 首", list.size, list.first())
    }
}

/** 年代组：有数字的按年降序，未知垫底。 */
fun sortYearGroups(groups: List<GroupInfo>): List<GroupInfo> =
    groups.sortedWith(
        compareByDescending<GroupInfo> { it.key.toIntOrNull() ?: Int.MIN_VALUE }
            .thenBy { it.key },
    )

private val YEAR_IN_TEXT = Regex("""(19\d{2}|20\d{2}|2100)""")
private const val YEAR_MIN = 1000
private const val YEAR_MAX = 2100
