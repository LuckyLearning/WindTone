package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import kotlin.math.roundToInt

/**
 * 曲库「格式与容量」统计的**纯计算**部分。
 *
 * 为什么单拎出来：这里每个数字都是用户会当真的结论（"MP3 占了多少 G"），
 * 而输入全是脏的 —— 文件名可能没有扩展名、老设备 `DISPLAY_NAME` 为空、
 * SAF 拿到的 `length()` 可能是 0、同一格式有 `wave` / `wav` 两种写法。
 * 把这些判定收进一个不碰 Android API 的函数里，就能用 JVM 单测逐条钉死，
 * 而不是靠"在车机上看着差不多"。
 *
 * 另一个刻意的选择：**统计只读内存里的曲库**（[Song] 列表），不查数据库、
 * 不碰文件系统。曲库已经是"当前可见的歌"的唯一真相（软删 / 移出曲库的歌不在里面），
 * 再查一遍 DB 只会得到另一份可能对不上的数字，还得额外解释差异。
 */

/** 一个音频格式的汇总 */
data class FormatStat(
    /** 规范化后的扩展名（小写）：`mp3` / `flac` / `m4a` …；认不出时为空串 */
    val ext: String,
    val songs: Int,
    val bytes: Long,
    val durationMs: Long,
) {
    /** 列表里显示的名字：`MP3` / `FLAC`；认不出时写「未知格式」 */
    val label: String get() = if (ext.isEmpty()) "未知格式" else ext.uppercase()

    /** 是否无损容器（用于「无损 / 有损」拆分） */
    val lossless: Boolean get() = ext in LOSSLESS_AUDIO_EXTS

    /** 平均码率（kbps，取整）；时长未知时返回 0 —— 0 表示"算不出来"，不是"0 kbps" */
    val averageKbps: Int get() = if (durationMs <= 0L || bytes <= 0L) {
        0
    } else {
        (bytes * 8.0 / (durationMs / 1000.0) / 1000.0).roundToInt()
    }

    /** 平均每首占用 */
    val averageBytes: Long get() = if (songs <= 0) 0L else bytes / songs
}

/** 整个曲库的格式与容量汇总 */
data class LibraryStats(
    val totalSongs: Int,
    /** 合计占用（只累加已知大小的歌） */
    val totalBytes: Long,
    /** 合计时长（只累加时长已知的歌） */
    val totalDurationMs: Long,
    /** 按**占用降序**排列；占用相同按首数降序、再按扩展名升序（保证顺序稳定、可单测） */
    val formats: List<FormatStat> = emptyList(),
    /** 大小为 0 / 未知的歌数（老设备、SAF 拿不到 length() 时会出现） */
    val unknownSizeSongs: Int = 0,
) {
    val formatCount: Int get() = formats.count { it.ext.isNotEmpty() }

    /** 占用最大的格式；曲库为空时为 null */
    val largest: FormatStat? get() = formats.firstOrNull()

    val averageBytes: Long get() = if (totalSongs <= 0) 0L else totalBytes / totalSongs

    /** 认不出格式（无扩展名、MIME 也不认识）的歌数 */
    val unknownFormatSongs: Int get() = formats.filter { it.ext.isEmpty() }.sumOf { it.songs }

    val losslessSongs: Int get() = formats.filter { it.lossless }.sumOf { it.songs }
    val losslessBytes: Long get() = formats.filter { it.lossless }.sumOf { it.bytes }

    /** 有损 = 认得出格式、但不在无损名单里（含 m4a —— 它可能是 ALAC，统计里按有损计） */
    val lossySongs: Int get() = formats.filter { it.ext.isNotEmpty() && !it.lossless }.sumOf { it.songs }
    val lossyBytes: Long get() = formats.filter { it.ext.isNotEmpty() && !it.lossless }.sumOf { it.bytes }

    /** 某个格式占总占用的比例（0f..1f）；合计未知时为 0 */
    fun shareOf(stat: FormatStat): Float =
        if (totalBytes <= 0L) 0f else (stat.bytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * 无损容器扩展名（与格式统计 / 曲库格式筛选共用）。
 * m4a / mp4 **不在此列**：它们既可能是 ALAC 也可能是 AAC，光看名字判不出来。
 */
val LOSSLESS_AUDIO_EXTS = setOf("flac", "wav", "aif", "aiff", "ape", "dsf", "dff", "tta", "tak", "wv", "alac")

/** 扩展名是否按「无损」口径计入（先 [normalizeAudioExtension]） */
fun isLosslessAudioExt(ext: String): Boolean =
    normalizeAudioExtension(ext) in LOSSLESS_AUDIO_EXTS

/**
 * 同一格式的多种写法归一到一处，否则「WAV / WAVE」「OGG / OGA」会被拆成两行，
 * 用户看到两个各占一半的条目，还要自己脑补它们是同一个东西。
 */
private val EXT_ALIASES = mapOf(
    "wave" to "wav",
    "oga" to "ogg",
    "m4b" to "m4a",
    "mpga" to "mp3",
    "aifc" to "aiff",
    "mpeg" to "mp3",
)

/** 文件名 / 路径都拿不到后缀时的兜底：MIME → 扩展名（只收扫描器认得的那些） */
private val MIME_EXT = mapOf(
    "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/x-mp3" to "mp3", "audio/mpeg3" to "mp3",
    "audio/flac" to "flac", "audio/x-flac" to "flac",
    "audio/wav" to "wav", "audio/wave" to "wav", "audio/x-wav" to "wav",
    "audio/mp4" to "m4a", "audio/m4a" to "m4a", "audio/x-m4a" to "m4a",
    "audio/aac" to "aac", "audio/aacp" to "aac",
    "audio/ogg" to "ogg", "audio/x-ogg" to "ogg", "audio/vorbis" to "ogg",
    "audio/opus" to "opus",
    "audio/amr" to "amr", "audio/3gpp" to "amr",
)

/** 把扩展名规范化：小写、去空白、套别名表；空串表示"没有扩展名" */
fun normalizeAudioExtension(raw: String): String {
    val e = raw.trim().lowercase()
    if (e.isEmpty()) return ""
    return EXT_ALIASES[e] ?: e
}

/**
 * 推断一首歌的格式扩展名。
 *
 * 顺序是**文件名 → 绝对路径 → MIME**，不能颠倒：media store 里 `MIME` 常年是
 * `audio/mpeg`（连 .m4a 都可能这么报），先信 MIME 会把一半 FLAC 写成 MP3。
 * 文件名才是最贴近用户认知的那个（"我看到的是 .flac"）。
 */
fun formatOf(song: Song): String {
    normalizeAudioExtension(song.displayName.substringAfterLast('.', "")).let { if (it.isNotEmpty()) return it }
    song.path?.let { p ->
        normalizeAudioExtension(p.substringAfterLast('.', "")).let { if (it.isNotEmpty()) return it }
    }
    val mime = song.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return MIME_EXT[mime].orEmpty()
}

/** 把曲库聚合成「格式 × 首数 × 占用」 */
fun buildLibraryStats(songs: List<Song>): LibraryStats {
    // 每个格式累加三样：首数 / 字节 / 时长。用 LongArray 而不是可变 data class，
    // 是为了让"累加"这一步不可能漏字段 —— 漏了编译期也不会报，只会算错。
    val acc = LinkedHashMap<String, LongArray>()
    var totalBytes = 0L
    var totalDuration = 0L
    var unknownSize = 0

    for (song in songs) {
        val ext = formatOf(song)
        val size = song.sizeBytes.coerceAtLeast(0L)
        val duration = song.durationMs.coerceAtLeast(0L)
        if (size <= 0L) unknownSize++
        val row = acc.getOrPut(ext) { LongArray(3) }
        row[0] += 1
        row[1] += size
        row[2] += duration
        totalBytes += size
        totalDuration += duration
    }

    val formats = acc.map { (ext, row) ->
        FormatStat(ext = ext, songs = row[0].toInt(), bytes = row[1], durationMs = row[2])
    }.sortedWith(
        compareByDescending<FormatStat> { it.bytes }
            .thenByDescending { it.songs }
            .thenBy { it.ext },
    )

    return LibraryStats(
        totalSongs = songs.size,
        totalBytes = totalBytes,
        totalDurationMs = totalDuration,
        formats = formats,
        unknownSizeSongs = unknownSize,
    )
}

/**
 * 容量文本：`1.2 GB` / `356 MB` / `18 KB`。
 *
 * 与 `DataMaintenance.Report` 里的私有实现不同，这里的 GB 档带一位小数 ——
 * 曲库总量大多在 GB 量级，只写整数会把 1.4 GB 和 2.3 GB 都显示成"2 GB"级别的粗糙数字。
 */
fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    bytes > 0L -> "$bytes B"
    else -> "0 B"
}
