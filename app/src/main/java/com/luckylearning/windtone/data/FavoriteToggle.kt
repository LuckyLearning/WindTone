package com.luckylearning.windtone.data

import android.content.Context
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.TrackFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 收藏切换的**唯一落盘入口**（应用内红心、快捷磁贴共用）。
 *
 * 本地曲走 [MusicDb.songStateDao]；阿里云盘曲走 [AliyunTrackStore]——与
 * 原先 `LibraryCore.toggleFavorite` 的分支完全一致，只是把「写库 + 内存索引 +
 * 提示 + 变更广播」收拢到一处，避免磁贴另造一套。
 *
 * [changes] 供曲库域订阅：界面的 `favoriteUris` 与「我的收藏」虚拟歌单据此对齐，
 * 不要求调用方自己再改一份 StateFlow。
 */
data class FavoriteChange(
    val stableKey: String,
    val favorite: Boolean,
    val title: String? = null,
)

class FavoriteToggle(
    private val app: Context,
    private val db: MusicDb,
    private val index: SongIndex,
    private val messages: MessageBus,
) {

    private val _changes = MutableSharedFlow<FavoriteChange>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<FavoriteChange> = _changes.asSharedFlow()

    /**
     * 按播放项 mediaId（即 [Song.stableKey]）切换收藏。
     * @return 切换后的收藏态；无法解析身份时返回 null（在线直链、未入库临时曲等）。
     */
    suspend fun toggleByMediaId(mediaId: String, titleHint: String? = null): Boolean? {
        if (mediaId.isBlank()) return null
        if (TrackFragment.cacheKeyOfMediaId(mediaId)) {
            return toggleAliyun(mediaId, titleHint)
        }
        return toggleLocal(mediaId, titleHint)
    }

    /** 应用内已有 [Song] 时走这条，避免再查一次 uri→id。 */
    suspend fun toggleSong(song: Song): Boolean? {
        val cloud = TrackFragment.parse(song.uri.fragment)
        if (cloud != null) {
            val newFav = withContext(Dispatchers.IO) {
                AliyunTrackStore(app).toggleFavorite(cloud.first, cloud.second)
            }
            emit(song.stableKey, newFav, song.title)
            return newFav
        }
        val songId = index.idOf(song) ?: return null
        val newFav = persistLocal(songId)
        index.bump(songId) { it.copy(favorite = newFav) }
        emit(song.stableKey, newFav, song.title)
        return newFav
    }

    /** 磁贴刷新用：当前曲是否已收藏；无法识别身份时返回 null。 */
    suspend fun isFavorite(mediaId: String): Boolean? {
        if (mediaId.isBlank()) return null
        if (TrackFragment.cacheKeyOfMediaId(mediaId)) {
            val fragment = mediaId.removePrefix("aliyun:")
            val pair = TrackFragment.parse(fragment) ?: return null
            return withContext(Dispatchers.IO) {
                AliyunTrackStore(app).get(pair.first, pair.second)?.favorite == true
            }
        }
        val songId = index.idOf(mediaId)
            ?: withContext(Dispatchers.IO) { db.songDao().idOf(mediaId) }
            ?: return null
        index.stateOf(songId)?.favorite?.let { return it }
        return withContext(Dispatchers.IO) { db.songStateDao().favoriteOf(songId) ?: false }
    }

    private suspend fun toggleAliyun(mediaId: String, titleHint: String?): Boolean? {
        val fragment = mediaId.removePrefix("aliyun:")
        val pair = TrackFragment.parse(fragment) ?: return null
        val newFav = withContext(Dispatchers.IO) {
            AliyunTrackStore(app).toggleFavorite(pair.first, pair.second)
        }
        val key = TrackFragment.cacheKey(fragment) ?: mediaId
        emit(key, newFav, titleHint)
        return newFav
    }

    private suspend fun toggleLocal(mediaId: String, titleHint: String?): Boolean? {
        val songId = index.idOf(mediaId)
            ?: withContext(Dispatchers.IO) { db.songDao().idOf(mediaId) }
            ?: return null
        val newFav = persistLocal(songId)
        index.bump(songId) { it.copy(favorite = newFav) }
        emit(mediaId, newFav, titleHint)
        return newFav
    }

    private suspend fun persistLocal(songId: Long): Boolean = withContext(Dispatchers.IO) {
        val dao = db.songStateDao()
        val now = System.currentTimeMillis()
        dao.ensure(songId, now)
        val fav = !(dao.favoriteOf(songId) ?: false)
        dao.setFavorite(songId, fav, now)
        fav
    }

    private fun emit(stableKey: String, favorite: Boolean, title: String?) {
        _changes.tryEmit(FavoriteChange(stableKey, favorite, title))
        val label = title?.takeIf { it.isNotBlank() }
        messages.post(
            when {
                favorite && label != null ->
                    AppStrings.s(R.string.fav_on_titled, label).ifEmpty { "已收藏「$label」" }
                favorite -> AppStrings.s(R.string.fav_on).ifEmpty { "已收藏" }
                else -> AppStrings.s(R.string.fav_off).ifEmpty { "已取消收藏" }
            },
        )
    }
}
