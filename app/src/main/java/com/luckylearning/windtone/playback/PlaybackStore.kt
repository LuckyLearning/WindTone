package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.db.ListenLogEntity
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.db.QueueSongEntity
import com.luckylearning.windtone.data.db.toSong
import com.luckylearning.windtone.data.model.Song

/**
 * 播放域的持久化门面（Repository）。
 *
 * 为什么需要它：`PlaybackEngine` 原先直接持有 [MusicDb] 并调用 8 个 DAO
 * （`songStateDao` / `listenLogDao` / `queueDao` / `playerStateDao` / `songDao`）。
 * 于是「播放域」与「数据库 schema」紧耦合——改 `Entities.kt` / `Daos.kt` 会直接
 * 冲击这个 1800 行的引擎，且引擎无法在单测里被替换成假实现。
 *
 * 现在引擎只认识本接口的语义化方法（记一次播放 / 结算收听时长 / 存队列与播放态 /
 * 读曲库快照），数据层的表结构与 SQL 全部收口在这里。原则与 `data/MusicRepository`
 * 一致：**播放域不直接碰 DAO**。
 *
 * 线程：所有方法都是 `suspend`，由调用方的 IO 调度器执行（引擎里仍保留
 * `withContext(Dispatchers.IO)` 包装，行为与改造前一致）。
 */
class PlaybackStore(private val db: MusicDb) {

    /** 记一次播放：先保证行存在，再加播放次数（历史 bug 见 [SongStateDao.ensure]） */
    suspend fun recordPlay(songId: Long, now: Long) {
        val dao = db.songStateDao()
        dao.ensure(songId, now)
        dao.bump(songId, now)
    }

    /**
     * 结算一段收听时长：[ms] 累加进每曲统计，同时写进收听报告的小时桶，
     * 并顺手裁掉保留期之外的桶（表大小恒定有界）。
     */
    suspend fun addListenTime(songId: Long, ms: Long, hour: Long, now: Long) {
        val dao = db.songStateDao()
        dao.ensure(songId, now)
        dao.addPlayedMs(songId, ms, now)
        val log = db.listenLogDao()
        log.add(hour, ms)
        log.prune(hour - ListenLogEntity.RETENTION_DAYS * 24L)
    }

    /** 队列与播放态一起落库（整体重写队列 + 单行 upsert 播放态） */
    suspend fun saveQueueAndState(rows: List<QueueSongEntity>, state: PlayerStateEntity) {
        db.queueDao().replaceAll(rows)
        db.playerStateDao().put(state)
    }

    /** 只写播放态（队列没变、只是切了循环 / 随机模式时用） */
    suspend fun saveState(state: PlayerStateEntity) {
        db.playerStateDao().put(state)
    }

    /**
     * 按主键批量取歌（队列快照恢复用）：返回顺序**严格按 [ids]**，
     * 且已自动剔除「已移出曲库 / 文件丢失」的歌（DAO 的 byIds 带过滤条件）。
     */
    suspend fun loadSongsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = db.songDao().byIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toSong() }
    }

    /** 只推进播放进度（进度计时器每几秒一次，单行 UPDATE） */
    suspend fun updatePosition(positionMs: Long, now: Long) {
        db.playerStateDao().updatePosition(positionMs, now)
    }

    /** 读一次曲库快照（开机后台起播时界面还没建，需要自己读库） */
    suspend fun loadVisibleSongs(): List<Song> {
        val states = db.songStateDao().all().associateBy { it.songId }
        return db.songDao().visible().mapNotNull { it.toSong(states[it.id]) }
    }

    /** 读上次的播放态（当前曲 / 进度 / 循环模式） */
    suspend fun loadPlayerState(): PlayerStateEntity? = db.playerStateDao().get()

    /** 读上次的队列顺序（曲目主键列表） */
    suspend fun loadQueueSongIds(): List<Long> = db.queueDao().songIds()

    /** 逐曲歌词偏移落库（旧实现是 Prefs 里按 hashCode 拼的散列键） */
    suspend fun setLyricOffset(songId: Long, offsetMs: Int, now: Long) {
        val dao = db.songStateDao()
        dao.ensure(songId, now)
        dao.setLyricOffset(songId, offsetMs, now)
    }
}
