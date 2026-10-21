package com.luckylearning.windtone.data.online

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
 */
class OnlinePlaylists(context: Context) {

    private val store = OnlinePlaylistStore(context)

    fun all(): List<OnlinePlaylist> = store.all().sortedByDescending { it.updatedAt }

    fun find(id: String): OnlinePlaylist? = store.all().firstOrNull { it.id == id }

    /** 新增：先抓一遍，抓不到就不入库（避免存一堆打不开的死地址） */
    suspend fun add(name: String, rawUrl: String): Result<OnlinePlaylist> = runCatching {
        val url = normalizeUrl(rawUrl)
        val parsed = fetch(url)
        if (parsed.kind == PlaylistKind.UNSUPPORTED) {
            error(parsed.note.ifBlank { "这个地址里没有可播放的内容" })
        }
        val playlist = OnlinePlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { defaultName(url) },
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
            mimeType = mimeOf(e.url),
            displayName = e.title,
            source = SongSource.REMOTE,
            dateAdded = p.updatedAt,
        )
    }

    // ------------------------------------------------------------------ 抓取

    private suspend fun fetch(url: String): ParsedPlaylist = withContext(Dispatchers.IO) {
        try {
            fetchOnce(url)
        } catch (e: java.io.IOException) {
            // 直接把异常 message 抛给界面会出现「connection closed」「Connection refused」这种
            // 用户读不懂的英文；这里换成能指导动作的说法（换网 / 检查地址 / 对方没响应）。
            error(friendly(e))
        }
    }

    private fun friendly(e: java.io.IOException): String = when (e) {
        is java.net.UnknownHostException ->
            "解析不到这个域名（网络不可用，或地址写错了）"
        is java.net.SocketTimeoutException ->
            "连接超时：对方没有响应，或当前网络太慢"
        is javax.net.ssl.SSLException ->
            "HTTPS 握手失败：对方的证书不受信任"
        else ->
            "连不上这个地址（${e.message ?: e.javaClass.simpleName}）"
    }

    private fun fetchOnce(url: String): ParsedPlaylist {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            // 部分电台按 Accept-Encoding 返回 gzip，交给连接自己解；声明出来只是为了不被拒
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("服务器返回 HTTP $code")
            val bytes = conn.inputStream.use { readCapped(it) }
            if (bytes.isEmpty()) error("这个地址返回了空内容")
            val text = decode(bytes)
            // 抓回来是网页时给出明确提示：用户最常犯的错就是把播放页地址贴进来
            if (looksLikeHtml(text)) {
                error("这个地址返回的是网页而不是播放列表，请填 m3u / m3u8 / pls 的直链")
            }
            return M3uParser.parse(text, baseUrl = conn.url.toString())
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 读响应体，超过上限即停。
     *
     * 上限 4MB 是防呆：有人会把整个 IPTV 频道表（几万行）当音乐列表填进来，
     * 不设限的话解析和落盘都会把车机拖住。
     */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_BYTES) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 编码判定：先按 UTF-8 严格解码，失败再按 GB18030。
     *
     * 中文电台列表里有大量 GBK 文件，按 UTF-8 硬解会得到一片问号，
     * 而标题乱码比「没导入」更难排查（用户只会说「词都不认识」）。
     */
    private fun decode(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        val gb = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
        return gb ?: String(bytes, Charsets.UTF_8)
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") ||
            (head.contains("<body") && !head.contains("#extm3u"))
    }

    /**
     * 没填名称时的默认名。
     *
     * `guessTitle` 取的是路径末段，但大量清单的文件名就叫 `list.m3u` / `index.m3u` ——
     * 直接用它会让列表里出现好几个「list」，没法分辨。这类通用名退化成域名。
     */
    private fun defaultName(url: String): String {
        val base = M3uParser.guessTitle(url)
        val generic = base.equals("list", true) || base.equals("index", true) ||
            base.equals("playlist", true) || base.equals("default", true)
        if (!generic) return base
        return M3uParser.originOf(url)?.substringAfter("://")?.ifBlank { null } ?: base
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) error("地址不能为空")
        val withScheme = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            // 用户从浏览器复制常丢掉协议头，补 https 而不是直接拒绝
            else -> "https://$t"
        }
        val parsed = runCatching { URL(withScheme) }.getOrNull() ?: error("地址格式不对")
        if (parsed.host.isNullOrBlank()) error("地址里缺少域名")
        return withScheme
    }

    private fun mimeOf(url: String): String? = when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "m3u8" -> "application/x-mpegURL"
        else -> null
    }

    private companion object {
        const val USER_AGENT = "WindTone/1.1 (Android; online-playlist)"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
