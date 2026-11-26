package com.luckylearning.windtone.data.online

import android.content.Context
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.toSong
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 洛雪模式的「最近播放」。
 *
 * 只取在线曲（`songs.source = ONLINE`），按 `song_state.lastPlayedAt` 倒序；
 * 本地模式的「最近播放」会把在线曲排除，两边各自只记录自己场景的播放。
 *
 * 不加表、不加列：`song_state` 本来就给在线曲记了 `lastPlayedAt`，
 * 这里只补一条 JOIN `songs` 的来源过滤查询。
 */
object OnlineRecent {

    const val LIMIT = 100

    suspend fun songs(context: Context, limit: Int = LIMIT): List<Song> = withContext(Dispatchers.IO) {
        val db = MusicDb.get(context)
        val ids = db.songStateDao().recentIdsBySource(SongSource.ONLINE.name, limit)
        if (ids.isEmpty()) return@withContext emptyList()
        val byId = db.songDao().byIds(ids).associateBy { it.id }
        // 按查询返回的时间序还原，不能直接按 id 排
        ids.mapNotNull { byId[it]?.toSong() }
    }
}
