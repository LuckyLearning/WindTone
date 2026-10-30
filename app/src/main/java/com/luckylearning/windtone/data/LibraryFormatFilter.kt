package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song

/**
 * 曲库「仅无损 / 按扩展名」筛选（H5）。
 *
 * 两维取**交集**：开启仅无损且选了扩展名时，只保留扩展名在选中集合内的无损曲。
 * [extensions] 为空表示不限扩展名；与 [losslessOnly]=false 合起来即 [NONE]（不过滤）。
 *
 * 扩展名一律小写、经 [normalizeAudioExtension]；口径与格式统计页 [LOSSLESS_AUDIO_EXTS] 一致
 * （m4a / mp4 不算无损）。
 */
data class LibraryFormatFilter(
    val losslessOnly: Boolean = false,
    val extensions: Set<String> = emptySet(),
) {
    /** 是否有任一维生效（顶栏角标 / 活动芯片用） */
    val isActive: Boolean get() = losslessOnly || extensions.isNotEmpty()

    companion object {
        val NONE = LibraryFormatFilter()
    }
}

/**
 * 筛选片里常亮的扩展名芯片（不依赖当前库里有没有）。
 * 含无损与常见有损；实际过滤仍走 [formatOf] + [isLosslessAudioExt]。
 */
val LIBRARY_FORMAT_FILTER_EXT_CHIPS: List<String> = listOf(
    "flac", "wav", "aiff", "ape", "dsf", "dff", "alac", "wv", "tta", "tak",
    "mp3", "m4a", "aac", "ogg", "opus", "wma",
)

/** 先格式筛选，再交给搜索；空筛选原样返回。 */
fun applyLibraryFormatFilter(
    songs: List<Song>,
    filter: LibraryFormatFilter,
): List<Song> {
    if (!filter.isActive) return songs
    val wantExts = filter.extensions.map { normalizeAudioExtension(it) }.filter { it.isNotEmpty() }.toSet()
    return songs.filter { song ->
        val ext = formatOf(song)
        val losslessOk = !filter.losslessOnly || isLosslessAudioExt(ext)
        val extOk = wantExts.isEmpty() || ext in wantExts
        losslessOk && extOk
    }
}
