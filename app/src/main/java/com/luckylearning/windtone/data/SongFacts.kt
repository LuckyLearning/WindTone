package com.luckylearning.windtone.data

/**
 * 播放页「歌曲信息」页需要、但不在 [com.luckylearning.windtone.data.model.Song] 里的派生数据（v1.2.55）。
 *
 * 为什么不塞进 Song：这三类数据的来源与生命周期都不同 ——
 * 歌单归属来自 `playlist_songs`（随歌单编辑变化）、播放统计来自 `song_state`
 * （每次播放都在变）、技术参数要开文件解析。Song 是「扫描产出、列表复用」的
 * 只读快照，往里面塞这些会逼着每次播放都重算一遍曲库。
 *
 * 每次进入信息页拉一次即可，页内不订阅变化。
 */
data class SongFacts(
    /** 收录了这首歌的**用户歌单**名（虚拟歌单由界面按收藏状态另行显示） */
    val playlistNames: List<String> = emptyList(),
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    /** 累计收听时长（毫秒） */
    val playedMs: Long = 0L,
    /** 音频技术参数；文件不可读 / 远程曲时为 null */
    val audio: AudioInspector.Info? = null,
    // ---- 标签：这几个字段不入 Room（见 Song 的字段注释），从 Prefs 侧缓存盖回来。
    //      Song 快照里可能已经是 null（比如从数据库直接构造的队列项），
    //      信息页要显示完整标签就只能在这里补一次。
    val genre: String? = null,
    val year: Int? = null,
    val albumArtist: String? = null,
    val discNumber: Int? = null,
)
