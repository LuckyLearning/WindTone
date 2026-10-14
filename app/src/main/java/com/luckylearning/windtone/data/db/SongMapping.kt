package com.luckylearning.windtone.data.db

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource

/**
 * `SongEntity` ↔ `Song` 映射。
 *
 * 约定：
 *  - [Song.dbId] 是数据库主键，UI 层不关心它，持久化边界才会用到；
 *  - [Song.stableKey]（= uri）保持不变，UI 的查找 / 列表 key / 播放媒体项 id 全部沿用它；
 *  - 本地标签覆盖在这里合并——调用方拿到的就是"最终该显示的样子"，
 *    不再像旧实现那样在每次装载曲库时另外查一遍覆盖表。
 */

/** 曲库行 → 领域模型；来源无法识别的行（历史脏数据）返回 null 由调用方丢弃 */
internal fun SongEntity.toSong(state: SongStateEntity? = null): Song? {
    val src = runCatching { SongSource.valueOf(source) }.getOrNull() ?: return null
    return Song(
        id = mediaStoreId,
        dbId = id,
        uri = Uri.parse(uri),
        title = state?.overrideTitle?.takeIf { it.isNotBlank() } ?: title,
        // override 为 null = 不覆盖；为空串 = 用户主动清空（展示成「未知」）
        artist = if (state?.overrideArtist != null) state.overrideArtist else artist,
        album = if (state?.overrideAlbum != null) state.overrideAlbum else album,
        albumId = albumId,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        displayName = displayName,
        path = path,
        folderPath = folderPath,
        source = src,
        trackNo = trackNo,
        lrcUri = lrcUri?.let { Uri.parse(it) },
        dateAdded = dateAdded,
    )
}

/**
 * 领域模型 → 曲库行。
 *
 * @param existingId 已存在的行主键（0 = 新行）。传入它才能**保住 ID 不变**，
 *   否则一旦被当成新行插入，指向它的收藏 / 歌单 / 队列会因为主键变化而失联。
 * @param folderId 所属导入目录（由 folderPath 匹配 folders.docId 得来）
 */
internal fun Song.toEntity(existingId: Long, folderId: Long?): SongEntity = SongEntity(
    id = existingId,
    uri = stableKey,
    source = source.name,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    displayName = displayName,
    path = path,
    folderId = folderId,
    folderPath = folderPath,
    mediaStoreId = id,
    trackNo = trackNo,
    lrcUri = lrcUri?.toString(),
    dateAdded = dateAdded,
    lastSeenAt = 0L,
    missingSince = 0L,
)
