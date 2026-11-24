package com.luckylearning.windtone.data.online

import android.content.Context
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 在线曲目的**源内原始信息**存档（`filesDir/online_tracks.json`）。
 *
 * 为什么必须有它：洛雪脚本取直链（`musicUrl`）需要 search 返回的**整条 musicInfo**
 * （songmid / hash / albumId / 各类签名参数），只给一个 id 是取不到的。
 * 若不存 raw，从曲库点一首上次搜到的歌就只能重新搜一遍 —— 那等于「在线歌不能出搜索结果页」，
 * 与「在线与本地同级」直接矛盾。
 *
 * 为什么不用 Room：它是一条以稳定键为键、可能整体失效（音源被删）的**派生缓存**，
 * 与 `GenreYearCache` / `AlbumArtistCache` 同类。为它加表要动 schema，收益不成比例。
 * 曲库身份、收藏、歌单仍然在 Room 里 —— 那些才是用户数据。
 *
 * 容量：上限 [MAX_ENTRIES]，超出按插入顺序淘汰最旧的。一首歌的 raw 一般 0.5–2 KB，
 * 3000 条约 1.5–6 MB，属于可接受范围；真到几万首在线收藏时，这个上限本身就是保护。
 */
class OnlineTrackStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()

    /** 只在 [mutex] 内访问；LinkedHashMap 保持插入顺序用于淘汰 */
    private var loaded = false
    private val map = LinkedHashMap<String, OnlineTrack>()

    suspend fun put(track: OnlineTrack) = mutate { map[track.stableKey] = track }

    suspend fun putAll(tracks: List<OnlineTrack>) = mutate {
        tracks.forEach { map[it.stableKey] = it }
    }

    suspend fun get(stableKey: String): OnlineTrack? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            map[stableKey]
        }
    }

    suspend fun remove(stableKey: String) = mutate { map.remove(stableKey) }

    suspend fun removeAll(stableKeys: Collection<String>) = mutate {
        stableKeys.forEach { map.remove(it) }
    }

    /**
     * 只保留 [keep] 里的条目。音源被删除或曲目被移出曲库后调用，
     * 防止失效条目无限堆积（用户看不见它们，但会一直占文件）。
     */
    suspend fun prune(keep: Set<String>) = mutate {
        val it = map.keys.iterator()
        while (it.hasNext()) if (it.next() !in keep) it.remove()
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            map.size
        }
    }

    suspend fun clear() = mutate { map.clear() }

    // ---------------------------------------------------------------- 内部

    private suspend fun mutate(block: () -> Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                block()
                while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
                persist()
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val array = runCatching { JSONObject(text).optJSONArray(KEY_TRACKS) }.getOrNull() ?: return
        for (i in 0 until array.length()) {
            val row = runCatching { array.getJSONObject(i) }.getOrNull() ?: continue
            val track = runCatching { read(row) }.getOrNull() ?: continue
            map[track.stableKey] = track
        }
    }

    /** 先写临时文件再改名：写到一半被杀进程不会留下半个 JSON（下次启动直接读不出来） */
    private fun persist() {
        runCatching {
            val array = JSONArray()
            map.values.forEach { array.put(write(it)) }
            val root = JSONObject().put(KEY_VERSION, 1).put(KEY_TRACKS, array)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    private fun write(t: OnlineTrack): JSONObject = JSONObject().apply {
        put("s", t.sourceId)
        put("i", t.songId)
        put("t", t.title)
        put("a", t.artist)
        put("al", t.album ?: "")
        put("d", t.durationMs)
        put("q", JSONArray().apply { t.qualities.forEach { put(it.id) } })
        put("vip", t.vip)
        put("raw", t.raw)
    }

    private fun read(o: JSONObject): OnlineTrack {
        val qualities = o.optJSONArray("q")?.let { arr ->
            (0 until arr.length()).mapNotNull { Quality.of(arr.optString(it)) }
        }.orEmpty()
        return OnlineTrack(
            sourceId = o.getString("s"),
            songId = o.getString("i"),
            title = o.optString("t"),
            artist = o.optString("a"),
            album = o.optString("al").takeIf { it.isNotBlank() },
            durationMs = o.optLong("d"),
            qualities = qualities,
            raw = o.optString("raw"),
            vip = o.optBoolean("vip"),
        )
    }

    private companion object {
        const val FILE_NAME = "online_tracks.json"
        const val KEY_VERSION = "v"
        const val KEY_TRACKS = "tracks"
        const val MAX_ENTRIES = 3000
    }
}
