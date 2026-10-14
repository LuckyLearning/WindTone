package com.luckylearning.windtone.data.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/** 歌曲来源：系统媒体库 / SAF 导入的文件夹 / 直接文件路径（深度扫描） */
enum class SongSource { MEDIA_STORE, FOLDER, FILE }

/**
 * 标注 @Immutable：本类所有字段都是 val，且 `Uri` 本身不可变，
 * 更新一律走 `copy`（整体替换）。不加这个注解 Compose 会把它判定为不稳定类型，
 * 导致列表项在父级重组时无法跳过——这是滚动掉帧的主要来源之一。
 */
@Immutable
data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long = 0L,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val displayName: String = "",
    /** 有绝对路径时才有值（深度扫描 / Android 10 及以下） */
    val path: String? = null,
    val folderPath: String? = null,
    val source: SongSource = SongSource.MEDIA_STORE,
    val trackNo: Int = 0,
    /** 已定位到的同名 .lrc / .txt 歌词文件（若有） */
    val lrcUri: Uri? = null,
    val dateAdded: Long = 0L,
    /**
     * 曲库主键（Room `songs.id`）。
     *
     * UI 层不关心它——[stableKey] 才是界面查找 / 列表 key / 播放媒体项 id 的依托；
     * 它只在持久化边界使用（收藏、歌词偏移、队列、封面磁盘缓存文件名的换算）。
     * 扫描过程中直接构造的 Song 还是 0，落库后由 ViewModel 回填。
     */
    val dbId: Long = 0L,
) {
    val artistOrUnknown: String
        get() = artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知歌手"

    val albumOrUnknown: String
        get() = album?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知专辑"

    val baseName: String
        get() = displayName.substringBeforeLast('.', displayName)

    val stableKey: String get() = uri.toString()

    val hasSidecarLyric: Boolean get() = lrcUri != null
}
