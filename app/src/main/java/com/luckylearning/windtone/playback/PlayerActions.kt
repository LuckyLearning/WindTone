package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.ThemeMode
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics

/**
 * Playback actions facade for UI / ViewModel (`by` delegation).
 */
interface PlayerActions : OverlayActions {
    fun restoreQueueIfNeeded()

    /** 开机后台起播：不经界面恢复上次队列并直接开播（v1.1.24） */
    fun startPlaybackInBackground()
    fun playQueue(queue: List<Song>, startIndex: Int)

    /** 随机播放一整批歌（先切成「随机 + 列表循环」再起播），见 [PlaybackEngine.playShuffled] */
    fun playShuffled(songs: List<Song>)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(ms: Long)

    /**
     * N2：相对当前位置快进 / 快退（毫秒，可负）。
     * 边界夹到 0～duration，走与 [seekTo] 同一路径。
     */
    fun seekBy(deltaMs: Long)
    fun cyclePlayMode()
    fun playNext(song: Song)
    fun enqueue(songs: List<Song>)
    fun refreshQueue()
    fun jumpToQueue(index: Int)
    fun removeQueueItem(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    /** A3：清空播放队列 */
    fun clearQueue()
    /** A4：打乱队列物理顺序（非随机播放模式） */
    fun shuffleQueueOrder()

    // ---- 多播放队列快照（竞品借鉴：Musicolet / Symfonium） ----

    /** 把当前队列（顺序 + 当前曲 + 进度）存成命名快照；同名覆盖 */
    fun saveQueueSnapshot(name: String)

    /** 恢复命名快照：按主键回表（自动跳过已删除的歌）并起播到保存的位置 */
    fun restoreQueueSnapshot(name: String)

    fun deleteQueueSnapshot(name: String)
    fun setSpeed(speed: Float)

    /** 变调：半音（-12..+12，0 = 原调），见 `playback/Pitch` */
    fun setPitchSemitones(semitones: Int)
    fun sleepAfter(minutes: Int)
    fun sleepEndOfSong()

    /** 播完当前专辑（当前曲剩余 + 队列里后续同专辑曲目） */
    fun sleepEndOfAlbum()

    /** 播完当前队列（当前曲剩余 + 队列里当前索引之后全部曲目） */
    fun sleepEndOfQueue()

    /** 再播 N 首后暂停（当前正在播的算第 1 首；N 经 clampSongCount） */
    fun sleepAfterSongs(count: Int)
    fun cancelSleep()
    fun setSkipSilence(enabled: Boolean)
    /** H2：片头固定秒（0=关，上限 120） */
    fun setSkipIntroSeconds(seconds: Int)
    /** H2：片尾固定秒（0=关，上限 120） */
    fun setSkipOutroSeconds(seconds: Int)

    /** 记 A-B 循环 A 点为当前进度（C2） */
    fun markAbLoopA()

    /** 记 A-B 循环 B 点为当前进度（C2）；需已设 A 且当前位置 > A */
    fun markAbLoopB()

    /** 清除 A-B 循环（C2 / D1） */
    fun clearAbLoop()

    /**
     * D1 逐句循环：把一句歌词的起止灌入同一 [AbLoopState] 引擎（[AbLoopState.fromInterval]）。
     * 切歌会清除；与手动 A-B、变速共用 seek 路径。
     */
    fun loopLyricLine(startMs: Long, endMs: Long)

    /** 「打开应用继续上次播放」：冷启动恢复队列后是否自动起播（v1.1.16） */
    fun setAutoResume(enabled: Boolean)

    /** 开机后台起播（默认关） */
    fun setAutoPlayOnBoot(enabled: Boolean)
    fun openPlayer()
    fun closePlayer()
    fun setLyricOffset(deltaMs: Int)
    fun resetLyricOffset()
    fun setLyricOffsetAbsolute(offsetMs: Int)
    fun setLyricScale(scale: Float)

    /** 点歌词行是否跳转（默认关） */
    /** 播放页左侧竖排是否显示「无障碍状态」按钮（v1.1.92） */
    fun setPlayerA11yAction(enabled: Boolean)

    fun setLyricTapSeeks(enabled: Boolean)

    /**
     * 双语歌词显示模式：0 中英都显示 / 1 只显示中文 / 2 只显示英文（v1.1.18）。
     * 只影响显示；歌词文件与导出不受影响。
     */

    /** 未经「双语歌词」过滤的原始歌词（导出用：译文要一起写进 LRC） */
    fun lyricsForExport(): Lyrics
    fun setLyricsPaneVisible(visible: Boolean)
    fun setCarMode(enabled: Boolean)

    /**
     * 界面明暗模式：跟随系统 / 浅色 / 深色（v1.2.5）。
     *
     * 语义是「是否允许越过系统」——选浅色或深色时，即使系统是另一种模式也照用户的选择画。
     * 当前值经 [com.luckylearning.windtone.playback.PlaybackEngine.themeMode] 流式暴露给
     * `MainActivity`，改完当场换肤，不要求重启。
     */
    fun setThemeMode(mode: ThemeMode)
    fun inspectAudio(song: Song)
    fun clearAudioInfo()
}
