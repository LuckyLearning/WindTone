package com.luckylearning.windtone.data.online

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一份已保存的在线播放列表。
 *
 * 只存**来源地址 + 上次解析出的条目**，不缓存音频：车机弱网下缓存是另一个议题
 * （见 docs/架构评审-联网播放可行性.md 的 E.5），这一步只做「能加上、能播、能刷新」。
 */
@Immutable
data class OnlinePlaylist(
    val id: String,
    val name: String,
    val url: String,
    val format: PlaylistFormat,
    val kind: PlaylistKind,
    val entries: List<PlaylistEntry>,
    val updatedAt: Long,
    val note: String = "",
) {
    val isStream: Boolean get() = kind == PlaylistKind.HLS
}

/**
 * 在线播放列表的本地存档（SharedPreferences + JSON）。
 *
 * 为什么不用 Room：这里存的是「几条地址」而不是曲库实体，规模是个位数；
 * 为它加表和迁移，风险和收益不成比例。真要按「曲库一等公民」处理（在线歌进收藏 / 歌单），
 * 那是后续把远程 Song 入库的活，届时再迁。
 */
internal class OnlinePlaylistStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("online_playlists", Context.MODE_PRIVATE)

    fun all(): List<OnlinePlaylist> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { read(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    fun replace(list: List<OnlinePlaylist>) {
        val arr = JSONArray()
        list.forEach { arr.put(write(it)) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun write(p: OnlinePlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("url", p.url)
        put("format", p.format.name)
        put("kind", p.kind.name)
        put("updatedAt", p.updatedAt)
        put("note", p.note)
        put("entries", JSONArray().apply {
            p.entries.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.title)
                    put("a", e.artist)
                    put("u", e.url)
                    put("d", e.durationMs)
                })
            }
        })
    }

    private fun read(o: JSONObject): OnlinePlaylist {
        val entriesJson = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until entriesJson.length()).mapNotNull { i ->
            runCatching {
                val e = entriesJson.getJSONObject(i)
                PlaylistEntry(
                    title = e.optString("t"),
                    artist = e.optString("a").takeIf { it.isNotBlank() && it != "null" },
                    url = e.optString("u"),
                    durationMs = e.optLong("d"),
                )
            }.getOrNull()
        }.filter { it.url.isNotBlank() }
        return OnlinePlaylist(
            id = o.optString("id"),
            name = o.optString("name"),
            url = o.optString("url"),
            format = runCatching { PlaylistFormat.valueOf(o.optString("format")) }
                .getOrDefault(PlaylistFormat.UNKNOWN),
            kind = runCatching { PlaylistKind.valueOf(o.optString("kind")) }
                .getOrDefault(PlaylistKind.UNSUPPORTED),
            entries = entries,
            updatedAt = o.optLong("updatedAt"),
            note = o.optString("note"),
        )
    }

    private companion object {
        const val KEY = "playlists"
    }
}

/**
 * 在线播放列表的抓取与解析。
 *
 * 与播放内核的关系：**解析出来之后只是普通 `Song`（uri 是 http 地址）**，
 * 交给现有 `playQueue` 即可，播放链路零改造 —— 这是评审文档 B 章的结论，
 * 也是这里刻意不碰播放器的原因。
 *
 * 本类只管编排与持久化；纯规则在 `OnlinePlaylistRules`，网络抓取在 `NetworkPlaylistFetcher`。
 */
class OnlinePlaylists(
    context: Context,
    /**
     * 抓取实现，默认走网络（[NetworkPlaylistFetcher]）。
     *
     * 留这个缝是为了单测：下面 `add` / `refresh` 的编排规则（抓不到就不入库、
     * 刷新失败保留旧条目、UNSUPPORTED 不入库）都是真行为，但不该靠连真网来验。
     */
    private val fetcher: suspend (String) -> ParsedPlaylist = NetworkPlaylistFetcher::fetch,
) {

    private val store = OnlinePlaylistStore(context)

    fun all(): List<OnlinePlaylist> = store.all().sortedByDescending { it.updatedAt }

    fun find(id: String): OnlinePlaylist? = store.all().firstOrNull { it.id == id }

    /** 新增：先抓一遍，抓不到就不入库（避免存一堆打不开的死地址） */
    suspend fun add(name: String, rawUrl: String): Result<OnlinePlaylist> = runCatching {
        val url = OnlinePlaylistRules.normalizeUrl(rawUrl)
        val parsed = fetch(url)
        if (parsed.kind == PlaylistKind.UNSUPPORTED) {
            error(parsed.note.ifBlank { "这个地址里没有可播放的内容" })
        }
        val playlist = OnlinePlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { OnlinePlaylistRules.defaultName(url) },
            url = url,
            format = parsed.format,
            kind = parsed.kind,
            entries = parsed.entries,
            updatedAt = System.currentTimeMillis(),
            note = parsed.note,
        )
        store.replace(store.all() + playlist)
        playlist
    }

    /** 刷新：地址没变，只重抓条目；失败时保留旧条目（弱网下旧数据比没有强） */
    suspend fun refresh(id: String): Result<OnlinePlaylist> = runCatching {
        val current = find(id) ?: error("列表不存在")
        val parsed = fetch(current.url)
        if (parsed.kind == PlaylistKind.UNSUPPORTED) {
            error(parsed.note.ifBlank { "这个地址里没有可播放的内容" })
        }
        val updated = current.copy(
            format = parsed.format,
            kind = parsed.kind,
            entries = parsed.entries,
            updatedAt = System.currentTimeMillis(),
            note = parsed.note,
        )
        store.replace(store.all().map { if (it.id == id) updated else it })
        updated
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        store.replace(store.all().map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    fun remove(id: String) {
        store.replace(store.all().filterNot { it.id == id })
    }

    /**
     * 转成播放队列。
     *
     * `id` 用负数：曲库里的 id 来自 MediaStore（正数），负数保证两者永不冲突 ——
     * 界面上「当前播放」与收藏查找都按 `stableKey`(=uri) 走，不依赖这个字段。
     */
    fun toSongs(p: OnlinePlaylist): List<Song> = p.entries.mapIndexed { index, e ->
        Song(
            id = -(index + 1L),
            uri = Uri.parse(e.url),
            title = e.title,
            artist = e.artist,
            album = p.name,
            durationMs = e.durationMs,
            mimeType = OnlinePlaylistRules.mimeOf(e.url),
            displayName = e.title,
            source = SongSource.REMOTE,
            dateAdded = p.updatedAt,
        )
    }

    // ------------------------------------------------------------------ 抓取

    private suspend fun fetch(url: String): ParsedPlaylist = fetcher(url)
}
