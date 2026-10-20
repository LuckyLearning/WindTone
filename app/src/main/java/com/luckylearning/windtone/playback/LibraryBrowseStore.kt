package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.db.SongStateEntity

/**
 * 车机浏览树的**只读查询门面**。
 *
 * 与 [PlaybackStore] 同思路：`LibraryBrowser` 原先直接持有 [MusicDb] 并散落调用
 * 4 个 DAO，于是「浏览树的组装逻辑」和「表结构 / SQL」绑在一起。收口到这里后：
 *  - `LibraryBrowser` 只表达「我需要收藏 id / 最近 id / 歌单行 / 歌曲行」；
 *  - 改 `Entities.kt` / `Daos.kt` 时只需要动本文件；
 *  - 单测可以给 `LibraryBrowser` 传一个内存库构造的门面，不必关心 DAO 细节。
 *
 * 纯查询、零缓存——浏览是低频操作，每次直接读 Room 才能保证车机看到的数据与手机一致。
 */
internal class LibraryBrowseStore(private val db: MusicDb) {

    // ---------------------------------------------------------------- 虚拟歌单

    /** 「我的收藏」：最近听过的排前面（与 App 内虚拟歌单一致） */
    suspend fun favoriteIdsByRecent(): List<Long> = db.songStateDao().favoriteIdsByRecent()

    /** 「最近播放」：按最后播放时间倒序 */
    suspend fun recentIds(limit: Int): List<Long> = db.songStateDao().recentIds(limit)

    /** 「最常播放」：按播放次数倒序 */
    suspend fun mostPlayedIds(limit: Int): List<Long> = db.songStateDao().mostPlayedIds(limit)

    // ---------------------------------------------------------------- 用户歌单

    suspend fun playlist(id: Long): PlaylistEntity? = db.playlistDao().byId(id)

    suspend fun allPlaylists(): List<PlaylistEntity> = db.playlistDao().all()

    /** 歌单内的歌曲主键（按 position 升序） */
    suspend fun playlistSongIds(playlistId: Long): List<Long> =
        db.playlistDao().songsOf(playlistId).map { it.songId }

    suspend fun playlistSongCount(playlistId: Long): Int =
        db.playlistDao().songsOf(playlistId).size

    /** 每个歌单的曲目数（一条 SQL + 内存分组，避免逐个歌单 COUNT） */
    suspend fun playlistLinkCounts(): Map<Long, Int> =
        db.playlistDao().allSongs().groupingBy { it.playlistId }.eachCount()

    // ---------------------------------------------------------------- 歌曲行

    /** 按主键批量取歌曲行（不保证顺序，调用方按 id 列表还原） */
    suspend fun songRowsByIds(ids: List<Long>): List<SongEntity> = db.songDao().byIds(ids)

    /** 按主键批量取每曲状态行（标签覆盖 / 收藏标记用） */
    suspend fun songStatesByIds(ids: List<Long>): List<SongStateEntity> =
        db.songStateDao().bySongIds(ids)
}
