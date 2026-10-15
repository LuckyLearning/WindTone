package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song

/**
 * 重复歌曲识别（纯函数）。
 *
 * 为什么单独一层：打分规则一旦写错，用户点一次「清理」就会把想留的歌移出曲库，
 * 属于不可逆的破坏性操作。抽成纯函数后，规则可以在 JVM 单测里逐条钉死。
 *
 * 指纹口径刻意**保守**：标题 + 歌手 + 时长（四舍五入到秒）三者一致才算重复。
 * 宁肯漏判（留给用户手动处理），也不要把《加州旅馆》的现场版和录音室版当成一首。
 */
data class DuplicateGroup(
    /** 建议保留的那一首 */
    val keep: Song,
    /** 建议移出曲库的（同一首的其它副本） */
    val duplicates: List<Song>,
    /**
     * 展示用标签（`标题 · 歌手 · 秒数`），取自 [keep] 的**原始**文案 ——
     * 同一组的副本可能标签质量不一，统一挂在用户要留的那一份上，
     * 确认框里才对得上列表里看到的那一行。
     */
    val label: String,
) {
    /** 本组一共几首（含保留的） */
    val size: Int get() = duplicates.size + 1
}

/**
 * 找出重复分组。
 *
 * @param songs 曲库当前可见的歌（已排除的歌不应传进来）
 * @return 只有 size ≥ 2 的组，按"可清理的副本数"从多到少排序
 */
fun findDuplicateGroups(songs: List<Song>): List<DuplicateGroup> {
    // ⚠️ 按 `fingerprintOf(...).first`（键）分组，**不能**直接
    // `groupBy { fingerprintOf(it) }`。
    //
    // Pair 的第二项是给人看的标签，而标签用的是**原始**标题 / 歌手。
    // 归一化正是为了让「  Hello  / ADELE」与「hello / adele」算同一首歌 ——
    // 但这两份的标签并不相同，拿整个 Pair 当组键会把它们分进两组，
    // 归一化等于白做（而且是静默的：界面只是"没发现重复"，不会报错）。
    val grouped = songs.groupBy { fingerprintOf(it).first }
    return grouped.entries
        .mapNotNull { (_, list) ->
            if (list.size < 2) return@mapNotNull null
            val ordered = list.sortedWith(KEEP_FIRST)
            DuplicateGroup(
                keep = ordered.first(),
                duplicates = ordered.drop(1),
                // 标签取自**保留的那一首**：它才是列表里真正会出现、用户要核对的那份
                label = fingerprintOf(ordered.first()).second,
            )
        }
        .sortedWith(compareByDescending<DuplicateGroup> { it.duplicates.size }.thenBy { it.label })
}

/** 指纹：标题 + 歌手 + 时长（秒）；返回 (键, 展示用标签) */
internal fun fingerprintOf(song: Song): Pair<String, String> {
    val title = song.title.trim().lowercase()
    val artist = song.artistOrUnknown.trim().lowercase()
    val seconds = song.durationMs / 1000
    return "$title|$artist|$seconds" to "${song.title} · ${song.artistOrUnknown} · ${seconds} 秒"
}

/**
 * 「哪一首更值得留」的比较器。
 *
 * 打分从高到低：
 *  1. **文件更大**（在同时长下基本等价于码率更高，是无损/高码率与转码副本的可靠区分）；
 *  2. **更早入库**（`dateAdded` 小）——保留用户最早导入的那份，
 *     它的收藏 / 歌单关联通常更多，且不会因为一次清理就打乱既有整理；
 *  3. URI 字典序：纯粹为了让结果**稳定可复现**（同样的输入永远给同样的建议）。
 */
private val KEEP_FIRST: Comparator<Song> = Comparator { a, b ->
    var r = b.sizeBytes.compareTo(a.sizeBytes)
    if (r == 0) r = a.dateAdded.compareTo(b.dateAdded)
    if (r == 0) r = a.stableKey.compareTo(b.stableKey)
    r
}
