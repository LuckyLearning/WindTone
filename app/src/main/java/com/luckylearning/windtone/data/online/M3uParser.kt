package com.luckylearning.windtone.data.online

import androidx.compose.runtime.Immutable
import java.net.URLDecoder

/** 播放列表的文件格式（决定解析路径，也用于界面上的小标签） */
enum class PlaylistFormat {
    M3U,
    PLS,
    UNKNOWN,
}

/**
 * 播放形态。
 *
 * - [TRACKS]：一串可直接播放的条目（远程 mp3 / aac / ogg 直链，或 HLS 分片列表里的逐个节目）
 * - [HLS]：单个 HLS 流。**必须单独成一档**：HLS 的 m3u8 里没有「一首歌」的概念，
 *   它是分片清单，把里面的 `#EXTINF` 当歌曲列表读会得到一堆无法播放的相对路径。
 *   识别出来之后整条流当一个「电台」播放，而不是逐条入队。
 * - [UNSUPPORTED]：内容不像任何已知格式（例如误填了网页地址，拿回来的是 HTML）。
 */
enum class PlaylistKind {
    TRACKS,
    HLS,
    UNSUPPORTED,
}

/** 解析出来的一条可播放项 */
@Immutable
data class PlaylistEntry(
    val title: String,
    val artist: String?,
    val url: String,
    /** 清单里声明的时长；直播 / 未知时是 0 */
    val durationMs: Long = 0L,
)

/**
 * 解析结果。
 *
 * [skipped] 与 [note] 不是装饰：在线列表的脏数据非常普遍（注释、相对路径、非 http scheme、
 * 失效条目），静默丢掉一部分条目会让用户以为「导入少了一半」却无从判断。
 */
data class ParsedPlaylist(
    val format: PlaylistFormat,
    val kind: PlaylistKind,
    val entries: List<PlaylistEntry>,
    val skipped: Int = 0,
    val note: String = "",
)

/**
 * M3U / M3U8 / PLS 解析（纯函数，无 Android 依赖，可完整单测）。
 *
 * 设计取舍：
 *  1. **只认 http(s)**。清单里的 `file:` / `icy:` / 相对路径之外的 scheme 一律计入 [ParsedPlaylist.skipped]，
 *     因为这些地址在本机播放不了，混进队列只会在切歌时闪一下「无法播放」。
 *  2. **相对路径按清单所在 URL 解析**（RFC 3986 的常见三种形态：`/a`、`//host/a`、`a/b`）。
 *     大量自建列表用相对路径，不解析的话整份清单都是坏地址。
 *  3. **HLS 先判**。`#EXT-X-*` 出现即视为流，不再尝试逐条解析 —— 见 [PlaylistKind.HLS]。
 *  4. 不认识的格式返回 [PlaylistKind.UNSUPPORTED] 而不是抛异常：调用方拿到的永远是可用对象。
 */
object M3uParser {

    private const val TAG_EXTINF = "#EXTINF:"
    private const val TAG_PLS_HEADER = "[playlist]"

    fun parse(text: String, baseUrl: String): ParsedPlaylist {
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.split('\n').map { it.trim().trimEnd('\r') }
        if (lines.isEmpty() || clean.isBlank()) {
            return ParsedPlaylist(PlaylistFormat.UNKNOWN, PlaylistKind.UNSUPPORTED, emptyList())
        }
        // HLS 判定必须早于逐条解析：#EXT-X-STREAM-INF 变体清单里的 URL 也是子清单，
        // 按「歌曲」理解会得到几十个播不动的地址。
        if (lines.any { it.startsWith("#EXT-X-", ignoreCase = true) }) {
            val name = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:", true) }
                ?.let { attr(it, "NAME") }
                ?: baseLabel(baseUrl)
            return ParsedPlaylist(
                format = PlaylistFormat.M3U,
                kind = PlaylistKind.HLS,
                entries = listOf(PlaylistEntry(title = name, artist = "直播流", url = baseUrl)),
                // 界面已经会显示「HLS 直播流」，这里只补充「为什么只有一项」
                note = "整条流作为一项播放",
            )
        }
        if (lines.any { it.equals(TAG_PLS_HEADER, ignoreCase = true) } ||
            lines.any { it.startsWith("File1=", ignoreCase = true) }
        ) {
            return parsePls(lines, baseUrl)
        }
        if (lines.none { it.startsWith("#EXTINF:", ignoreCase = true) } &&
            lines.none { it.startsWith("http://", true) || it.startsWith("https://", true) }
        ) {
            return ParsedPlaylist(
                PlaylistFormat.UNKNOWN,
                PlaylistKind.UNSUPPORTED,
                emptyList(),
                note = "内容里没有任何可播放地址（可能需要登录、或这个地址返回的是网页）",
            )
        }
        return parseM3u(lines, baseUrl)
    }

    private fun parseM3u(lines: List<String>, baseUrl: String): ParsedPlaylist {
        val entries = ArrayList<PlaylistEntry>()
        var skipped = 0
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingDuration = 0L

        for (raw in lines) {
            if (raw.isEmpty()) continue
            if (raw.startsWith("#")) {
                if (!raw.startsWith(TAG_EXTINF, ignoreCase = true)) continue
                val body = raw.substring(TAG_EXTINF.length)
                // `#EXTINF:<秒数>[ 属性...],<显示名>`。属性与显示名之间的逗号是唯一分隔符，
                // 所以先按**第一个**逗号切开，右侧整段都算标题（标题里本身可能有逗号）。
                val comma = body.indexOf(',')
                val head = if (comma >= 0) body.substring(0, comma) else body
                val display = if (comma >= 0) body.substring(comma + 1).trim() else ""
                pendingDuration = secondsOf(head)
                val tvg = attr(head, "tvg-name")
                val rawTitle = display.ifBlank { tvg.orEmpty() }
                val (title, artist) = splitArtistTitle(decodeEntities(rawTitle))
                pendingTitle = title.ifBlank { null }
                pendingArtist = artist
                continue
            }
            val resolved = resolveUrl(baseUrl, raw)
            if (resolved == null) {
                skipped++
                continue
            }
            entries += PlaylistEntry(
                title = pendingTitle ?: guessTitle(resolved),
                artist = pendingArtist,
                url = resolved,
                durationMs = pendingDuration,
            )
            pendingTitle = null
            pendingArtist = null
            pendingDuration = 0L
        }
        return ParsedPlaylist(
            format = PlaylistFormat.M3U,
            kind = if (entries.isEmpty()) PlaylistKind.UNSUPPORTED else PlaylistKind.TRACKS,
            entries = entries,
            skipped = skipped,
            note = buildNote(entries.size, skipped),
        )
    }

    /** PLS 是 INI 风格：`File1=…` / `Title1=…` / `Length1=…`，序号把三行绑在一起 */
    private fun parsePls(lines: List<String>, baseUrl: String): ParsedPlaylist {
        val files = LinkedHashMap<Int, String>()
        val titles = HashMap<Int, String>()
        val lengths = HashMap<Int, Long>()
        for (raw in lines) {
            val eq = raw.indexOf('=')
            if (eq <= 0) continue
            val key = raw.substring(0, eq).trim()
            val value = raw.substring(eq + 1).trim()
            val idx = key.dropWhile { !it.isDigit() }.toIntOrNull() ?: continue
            when {
                key.startsWith("File", true) -> files[idx] = value
                key.startsWith("Title", true) -> titles[idx] = value
                key.startsWith("Length", true) -> lengths[idx] = value.toLongOrNull()?.times(1000) ?: 0L
            }
        }
        val entries = ArrayList<PlaylistEntry>()
        var skipped = 0
        for ((idx, file) in files.entries.sortedBy { it.key }) {
            val resolved = resolveUrl(baseUrl, file)
            if (resolved == null) {
                skipped++
                continue
            }
            val (title, artist) = splitArtistTitle(decodeEntities(titles[idx].orEmpty()))
            entries += PlaylistEntry(
                title = title.ifBlank { guessTitle(resolved) },
                artist = artist,
                url = resolved,
                durationMs = lengths[idx] ?: 0L,
            )
        }
        return ParsedPlaylist(
            format = PlaylistFormat.PLS,
            kind = if (entries.isEmpty()) PlaylistKind.UNSUPPORTED else PlaylistKind.TRACKS,
            entries = entries,
            skipped = skipped,
            note = buildNote(entries.size, skipped),
        )
    }

    /**
     * 把清单里的地址解析成绝对 http(s) 地址；解析不出来返回 null（调用方计入 skipped）。
     *
     * 只支持 http/https 是**有意为之**：车机上没有 `file:` 之外的权限，`icy://` 这类
     * 非标准 scheme 只有部分播放器认。放进来只会在切歌时闪错。
     */
    fun resolveUrl(baseUrl: String, candidate: String): String? {
        val c = candidate.trim()
        if (c.isEmpty()) return null
        val lower = c.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return c
        // 带其它 scheme 的一律拒绝（file: / icy: / ftp: …）。
        // 这一步必须在「相对路径」分支之前：否则 `file:///x.mp3` 会被拼成
        // `https://host/file:///x.mp3`，看着像个合法地址，播放时才失败。
        val schemeEnd = c.indexOf(':')
        if (schemeEnd in 1..12 && c.take(schemeEnd).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            return null
        }
        if (lower.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https")
            return "$scheme:$c"
        }
        if (c.startsWith("/")) {
            val origin = originOf(baseUrl) ?: return null
            return origin + c
        }
        val dir = baseUrl.substringBeforeLast('/', "")
        if (dir.isEmpty() || !dir.startsWith("http", true)) return null
        return "$dir/$c"
    }

    /** `https://host:port/path?q` → `https://host:port` */
    fun originOf(url: String): String? {
        val idx = url.indexOf("://")
        if (idx <= 0) return null
        val scheme = url.substring(0, idx)
        val rest = url.substring(idx + 3)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' }
        val authority = if (hostEnd < 0) rest else rest.substring(0, hostEnd)
        return if (authority.isBlank()) null else "$scheme://$authority"
    }

    /**
     * `Artist - Title` 拆分。
     *
     * 只在**第一个** " - " 处拆，且两侧都非空。有些清单写 `Title - Artist`，
     * 但那没法可靠判断，按行业惯例（EXTINF 的通用写法）取「左歌手、右标题」。
     * 带 URL 特征（含 `://` 或 `/`）的一律不拆，否则电台名里的斜杠会被误判。
     */
    fun splitArtistTitle(raw: String): Pair<String, String?> {
        val s = raw.trim()
        if (s.isEmpty()) return "" to null
        if (s.contains("://")) return s to null
        val sep = s.indexOf(" - ")
        if (sep <= 0 || sep >= s.length - 3) return s to null
        val left = s.substring(0, sep).trim()
        val right = s.substring(sep + 3).trim()
        if (left.isEmpty() || right.isEmpty()) return s to null
        return right to left
    }

    /** `#EXTINF:` 的头部里取属性值，如 `tvg-name="XXX"` */
    fun attr(head: String, name: String): String? {
        val key = "$name=\""
        val start = head.indexOf(key, ignoreCase = true)
        if (start < 0) return null
        val from = start + key.length
        val end = head.indexOf('"', from)
        if (end <= from) return null
        return head.substring(from, end)
    }

    /** 头部里第一个 token 是秒数；`-1` 表示直播 / 未知 */
    private fun secondsOf(head: String): Long {
        val token = head.trim().substringBefore(' ')
        val sec = token.toDoubleOrNull() ?: return 0L
        if (sec <= 0) return 0L
        return (sec * 1000).toLong()
    }

    /** 没有标题时用地址末段兜底：`https://x/a/%E5%A4%9C%E8%88%AA.mp3` → 「夜航」 */
    fun guessTitle(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val last = noQuery.trimEnd('/').substringAfterLast('/')
        if (last.isBlank()) return baseLabel(url)
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return decoded.substringBeforeLast('.', decoded).ifBlank { decoded }
    }

    private fun baseLabel(url: String): String {
        val host = originOf(url)?.substringAfter("://").orEmpty()
        return host.ifBlank { "在线歌单" }
    }

    /**
     * 只在「有东西被丢掉」时才写备注。
     *
     * 早先是「N 条」也写进 note，界面又把条数单独显示一次，于是卡片上出现过
     * 「2 条 · 2 条」这种重复（2026-10-21 模拟器实测）。note 的职责是解释差异，不是复述数量。
     */
    private fun buildNote(ok: Int, skipped: Int): String =
        if (skipped > 0) "另有 $skipped 条非 http 地址未导入" else ""

    /** 少数清单里带 HTML 实体（多见于从网页复制的列表） */
    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()
}
