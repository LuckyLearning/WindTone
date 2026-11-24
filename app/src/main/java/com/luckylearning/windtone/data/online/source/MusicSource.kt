package com.luckylearning.windtone.data.online.source

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.OnlineKeys
import androidx.compose.runtime.Immutable
import android.net.Uri

/**
 * 音质档位。**id 必须与洛雪脚本协议里的字符串一致**（`128k` / `320k` / `flac` / `flac24bit`），
 * 因为自定义源会拿这个字符串直接去请求接口，写错一个字母就是「源支持但取不到」。
 */
enum class Quality(val id: String, val label: String) {
    Q128("128k", "128k"),
    Q320("320k", "320k"),
    FLAC("flac", "FLAC"),
    FLAC24("flac24bit", "FLAC 24bit");

    /** 用于日志与界面展示的短标签（低档位保持原样，无损档位补中文说明） */
    val shortLabel: String
        get() = when (this) {
            Q128, Q320 -> id
            FLAC -> "FLAC"
            FLAC24 -> "FLAC 24bit"
        }

    companion object {
        /** 由高到低 —— 降级链与「先给最好的」都按这个顺序 */
        val BY_QUALITY: List<Quality> = listOf(FLAC24, FLAC, Q320, Q128)

        fun of(id: String?): Quality? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * 音源能力集。
 *
 * 与洛雪的 `supportActions` 对齐：脚本 init 时声明自己支持哪些 action，宿主据此决定
 * UI 上哪些入口可用（不支持的就不显示，而不是点了报错）。
 */
enum class Capability {
    SEARCH,
    LEADERBOARD,
    PLAYLIST,
    LYRIC,
    PIC,
    ALBUM,
    ARTIST,
}

/**
 * 一条在线曲目的搜索结果。
 *
 * [raw] 是音源脚本返回的**原始信息**（一首歌的完整 musicInfo）。宿主不解释它，
 * 取直链时原样传回去 —— 洛雪的 `musicUrl` 需要 search 返回的全部字段（songmid / hash /
 * albumId / 各种签名参数），只靠一个 id 是取不到地址的。
 *
 * 这一点是本层最关键的设计：**只存 id 不存 raw，等于只能「当场搜当场播」**，
 * 一旦重启或从曲库点播就必须重新搜一遍。所以 [OnlineTrackStore] 会把 raw 一起持久化。
 */
@Immutable
data class OnlineTrack(
    val sourceId: String,
    val songId: String,
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationMs: Long = 0L,
    /** 该曲在源内可用的音质（空 = 用音源声明的全集） */
    val qualities: List<Quality> = emptyList(),
    /** 源内原始信息（JSON 字符串），取直链时原样回传 */
    val raw: String = "",
    /**
     * 搜索时就能拿到的封面地址。
     *
     * 各家能力不同：QQ / 咪咕 / 酷狗在搜索结果里直接给图，酷我要单独请求
     * `artistpicserver` 才能换到地址（见 [com.luckylearning.windtone.data.online.OnlineCover]）。
     * 所以本字段**允许为空**，空时由 [com.luckylearning.windtone.data.online.OnlineCover] 按源现取 ——
     * 有值就少一次网络往返。
     */
    val coverUrl: String? = null,
    /**
     * 需要会员 / 版权受限。
     *
     * 这类条目不隐藏（用户仍然能收藏、能看到），但取址必然失败 ——
     * 列表上置灰并标注「需会员」，比播放时弹一个看不懂的错误好。
     */
    val vip: Boolean = false,
) {
    val stableKey: String get() = OnlineKeys.of(sourceId, songId)

    val displayArtist: String get() = artist.ifBlank { "未知歌手" }

    /**
     * 转成曲库里的领域模型。
     *
     * 注意 [Song.uri] 存的是**稳定键**（`lx:kw:228908`）而不是占位地址：曲库里存的东西
     * 必须是「身份」，任何带有效期的东西都不能落盘（直链会过期，占位地址又没必要存）。
     * 真正喂给播放器的地址由 `LibraryBrowser.resolveOne` 现算。
     */
    fun toSong(dateAdded: Long = 0L, dbId: Long = 0L): Song = Song(
        id = 0L,
        uri = Uri.parse(stableKey),
        title = title,
        artist = artist.ifBlank { null },
        album = album?.takeIf { it.isNotBlank() },
        durationMs = durationMs,
        displayName = title,
        source = com.luckylearning.windtone.data.model.SongSource.ONLINE,
        dateAdded = dateAdded,
        dbId = dbId,
    )
}

/**
 * 一个音源的统一接口。
 *
 * 两族实现共用它（这也是本方案敢先只做一种的原因）：
 *  - `ScriptSource`：执行洛雪 v2 自定义源脚本（主路线）；
 *  - `DirectSource`：Kotlin 直连某家平台的接口（若将来需要「零配置开箱」）。
 *
 * 所有方法都是 `suspend` 且**允许抛异常**：调用方（[com.luckylearning.windtone.data.online.OnlineResolver]）
 * 统一兜住并降级，音源实现里不必写满 try/catch。
 */
interface MusicSource {

    /** 音源 id（与洛雪的 `kw`/`kg`/`tx`/`wy`/`mg` 同名，自定义源用 `custom:<uuid>`） */
    val id: String

    /**
     * 这个实现**属于哪一份脚本**（脚本源 = `LxScript.id`，直连源 = 自己的 id）。
     *
     * 为什么要它：同一个平台（如 `kw`）可以被多份脚本同时接管，注册表里因此是
     * 「一个平台 → 多个实现」。用户选「这个平台用哪份脚本」时记的就是这个值 ——
     * 记 `id` 没用，因为同平台的两份脚本 `id` 完全一样。
     */
    val ownerId: String get() = id

    /** 展示名 */
    val name: String

    /** 提供者短名（脚本源给脚本名）—— 音源管理里「酷我音乐 → fish 源」用 */
    val providerLabel: String get() = name

    val capabilities: Set<Capability>

    /** 本音源支持的全部音质（降级链的候选集） */
    val qualities: List<Quality>

    suspend fun search(keyword: String, limit: Int = DEFAULT_LIMIT): List<OnlineTrack>

    /** 取直链；取不到返回 null（返回非 http(s) 一律视为失败，由调用方判定） */
    suspend fun resolveUrl(track: OnlineTrack, quality: Quality): String?

    /** 取歌词（LRC 文本）；不支持或没有时返回 null */
    suspend fun lyric(track: OnlineTrack): String? = null

    /** 取封面地址；不支持或没有时返回 null */
    suspend fun cover(track: OnlineTrack): String? = null

    companion object {
        const val DEFAULT_LIMIT = 30
    }
}
