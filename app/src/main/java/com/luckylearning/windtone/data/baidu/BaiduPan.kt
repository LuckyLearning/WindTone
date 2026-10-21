package com.luckylearning.windtone.data.baidu

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 一次批量取直链的结果。
 *
 * 之所以不只返回 `Map<fsId, dlink>`：**「拿到 0 条直链」是最难向用户解释的失败**。
 * 接口 `errno` 常常是 0（请求本身成功），只是没有 dlink —— 可能是文件不在应用目录、
 * 被判违规屏蔽、或参数没被接受。把「请求了几个 / 接口返回几条记录 / 成功几个」都带出来，
 * 界面才能给出可操作的提示，而不是一句「没有取到直链」让用户无处下手。
 */
data class DlinkBatch(
    val links: Map<Long, String>,
    /** 请求了多少个 fsid */
    val requested: Int,
    /** 接口实际返回的记录数（含没有 dlink 的） */
    val records: Int,
    /** 拿不全时的人话原因；全部成功时为空串 */
    val note: String,
) {
    val isEmpty: Boolean get() = links.isEmpty()
}

/**
 * 直链响应解析（v1.1.40）。
 *
 * ⚠️ 这里踩过一个「功能整体不可用」的坑：`/xpan/multimedia?method=filemetas` 的
 * 成功响应把记录放在 **`list`** 里，而更老的 `/xpan/file?method=filemetas` 放在 `info` 里。
 * 第一版只读 `info`，结果「目录能列出来、点播放永远提示没有直链」—— `errno` 是 0，
 * 所以没有任何错误日志可看。两种形态都兼容，也把 `info` 是对象映射的形态兜住。
 */
object BaiduLinkParse {

    fun links(json: JSONObject): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        readArray(json.optJSONArray("list"), out)
        readArray(json.optJSONArray("info"), out)
        json.optJSONObject("info")?.let { map ->
            for (key in map.keys()) {
                val item = map.optJSONObject(key) ?: continue
                val fsId = item.optLong("fs_id", key.toLongOrNull() ?: 0L)
                val link = item.optString("dlink")
                if (fsId != 0L && isHttp(link)) out.putIfAbsent(fsId, link)
            }
        }
        return out
    }

    /** 接口返回了多少条记录（用来区分「没数据」和「有数据但没直链」） */
    fun recordCount(json: JSONObject): Int =
        (json.optJSONArray("list")?.length() ?: 0) +
            (json.optJSONArray("info")?.length() ?: 0) +
            (json.optJSONObject("info")?.length() ?: 0)

    /** 拿不全直链时给出可操作的说明 */
    fun note(requested: Int, links: Map<Long, String>, records: Int): String = when {
        requested == 0 || links.size >= requested -> ""
        records == 0 ->
            "网盘没有返回这些文件的资料（接口 0 条记录）。请确认音乐在「我的应用数据/<应用名>」目录下，" +
                "或先把文件在网盘 App 里点开一次再回来重试。"
        else ->
            "有 ${requested - links.size} 个文件没拿到播放地址（可能已被移出、被屏蔽，或需要网盘转码）。"
    }

    private fun readArray(arr: JSONArray?, out: MutableMap<Long, String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val fsId = item.optLong("fs_id", 0L)
            val link = item.optString("dlink")
            if (fsId != 0L && isHttp(link)) out[fsId] = link
        }
    }

    /** 只认真正的 http(s) 地址：接口失败时会返回空串或错误短语，不能当成可播地址 */
    private fun isHttp(link: String): Boolean =
        link.startsWith("http://", true) || link.startsWith("https://", true)
}

/** 网盘里的一个条目（文件或文件夹） */
@Immutable
data class BaiduEntry(
    val fsId: Long,
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val modifiedAtSec: Long,
) {
    /** 能被播放器解码的音频（wma / ape / dsf 等没有解码器，列出来只会点了没反应） */
    val isPlayableAudio: Boolean get() = !isDir && BaiduRules.isPlayableAudio(name)
}

/**
 * 百度网盘接口的错误码 → 人话（纯函数，可单测）。
 *
 * 网盘的错误码是这套接口最容易踩的地方：
 *  - `-7` 出现频率最高，含义不是「token 无效」而是「你无权访问这个目录」——
 *    平台只允许第三方应用访问 `/apps/{应用名}`，用户把音乐放在别处就会撞上它。
 *    如果统一说成「登录失效」，用户会反复扫码，永远解决不了问题。
 *  - `31066` 是风控，通常几分钟后自愈，不该引导用户去改任何设置。
 */
object BaiduErrors {

    fun hint(errno: Int): String = when (errno) {
        0 -> ""
        -6 -> "登录状态失效，请重新扫码授权"
        -7 -> "没有权限访问这个目录：网盘只允许应用访问「我的应用数据/<应用名>」，请把音乐放到那里"
        -9, 2 -> "文件或目录不存在（可能已被移动或删除）"
        31066 -> "请求过于频繁，被网盘限流了，等几分钟再试"
        31023 -> "请求参数错误"
        31064 -> "该文件不存在或已过期"
        31045 -> "登录用户与文件不匹配，请重新登录"
        else -> "网盘返回错误码 $errno"
    }

    /** OAuth 的 error 字符串 → 人话（设备码轮询用） */
    fun hint(error: String): String = when (error) {
        "authorization_pending", "authorization_waiting" -> "等待扫码确认"
        "slow_down" -> "轮询太快，已自动放慢"
        "expired_token" -> "二维码已过期，请重新生成"
        "access_denied" -> "已拒绝授权"
        "invalid_client_id" -> "应用凭据无效（AppKey）"
        "invalid_client_secret" -> "应用凭据无效（SecretKey）"
        "" -> ""
        else -> "授权失败：$error"
    }
}

/** 路径与文件类型的纯规则（单测覆盖） */
object BaiduRules {

    /** 播放器能解的音频后缀。wma/ape/dsf 不在内：本工程没接 ffmpeg 解码器 */
    private val PLAYABLE = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")

    fun isPlayableAudio(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in PLAYABLE

    fun isAudioLike(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in
            PLAYABLE + setOf("wma", "ape", "dsf", "dff", "m4a", "aiff", "aif")

    /** 拼子路径：父目录 `/apps/x` + 子项 `Music` → `/apps/x/Music`（根目录不产生双斜杠） */
    fun childPath(parent: String, name: String): String {
        val base = parent.trimEnd('/')
        return if (base.isEmpty()) "/$name" else "$base/$name"
    }

    /** 上一级；根目录返回 null（界面据此不显示「返回上级」） */
    fun parentPath(path: String): String? {
        val p = path.trimEnd('/')
        if (p.isEmpty() || p == "/") return null
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    /**
     * 「返回上级」是否可用。
     *
     * [root] 是应用有权限的目录（`/apps/<应用名>`）。到了这一层就该停住：
     * 再往上要么是 `/apps`，要么是 `/`，全是用户私人目录 —— 能看到、能点，
     * 但文件拿不到播放地址，纯属把功能做得像坏的。
     */
    fun canGoUp(path: String, root: String): Boolean {
        if (parentPath(path) == null) return false
        if (root.isBlank() || root == "/") return true
        return path.trimEnd('/') != root.trimEnd('/')
    }

    /**
     * 给人看的路径：把 `/apps/windtone` 显示成「我的应用数据/windtone」。
     *
     * 车机上显示的路径要能被用户对上手机里看到的目录名 —— 百度网盘 App 里
     * 那一级叫「我的应用数据」，不是 `apps`。
     */
    fun displayPath(path: String, root: String): String {
        if (path.isBlank()) return "…"
        if (root.isBlank() || root == "/") return path
        val base = root.trimEnd('/')
        val friendly = "我的应用数据/" + base.substringAfterLast('/')
        return when {
            path.trimEnd('/') == base -> friendly
            path.startsWith("$base/") -> friendly + path.removePrefix(base)
            else -> path
        }
    }

    /**
     * 播放直链时是否要给请求加「网盘客户端」UA。
     *
     * 百度对 dlink 的校验包含 UA：用应用自己的 UA 去拉会直接被拒（表现为「能拿到地址、
     * 播放器一声不响」）。dlink 的 host 形如 `d.pcs.baidu.com` / `bj.bcebos.com`，
     * 这里按域名后缀判断，避免把任何带 "baidu" 字样的普通网站也一起改 UA。
     */
    fun needsPanUserAgent(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        // 按「域名边界」判断，不用裸 endsWith：`evilbaidu.com` 也以 baidu.com 结尾，
        // 裸判断会把它一起认成百度域名
        fun on(domain: String) = h == domain || h.endsWith(".$domain")
        return on("baidu.com") || on("baidupcs.com") || on("bcebos.com")
    }

    /** 播放直链要带的请求头（UA 必须伪装成网盘客户端） */
    fun panHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "pan.baidu.com",
        "Referer" to "https://pan.baidu.com/",
    )

    /**
     * 给 dlink 拼上 `access_token`（v1.1.40）。
     *
     * 官方文档写得很死：**「使用 dlink 时，必须拼接 &access_token=xxx 参数」**，
     * 同时请求头 UA 必须是 `pan.baidu.com`。第一版只带了 UA，结果是「拿到地址但播放器
     * 一声不响」—— 403 不会进 UI，只有一行 ExoPlayer 的错误日志，非常难查。
     *
     * 这里用纯字符串拼接（不依赖 `Uri`），一是这个模块要保持可 JVM 单测，
     * 二是 dlink 自带的查询串里已经有 `sign=...&...`，必须用 `&` 而不是 `?`。
     * 已有 `access_token` 的直接返回：重定向后可能已经是带 token 的地址。
     */
    fun withAccessToken(dlink: String, token: String?): String {
        if (token.isNullOrBlank()) return dlink
        if (dlink.contains("access_token=")) return dlink
        val sep = if (dlink.contains('?')) "&" else "?"
        return dlink + sep + "access_token=" + URLEncoder.encode(token, "UTF-8")
    }

    /** 展示用文件名（去掉扩展名） */
    fun displayTitle(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
}

/**
 * 百度网盘 OpenAPI 客户端（v1.1.39）。
 *
 * 只做三件事：列目录、拿下载直链、取用户昵称。上传/删除/重命名一律不做 ——
 * 这个应用对网盘是**只读播放器**，能力越小越不容易被风控，也越好解释权限。
 *
 * 直链（dlink）是**临时**的：每次播放前现取，不能缓存复用（百度会给过期的 sign）。
 */
class BaiduPan(private val auth: BaiduAuth) {

    suspend fun nickname(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth.accessToken() ?: error("尚未登录")
            val json = request("$NAS_URL?method=uinfo&access_token=${enc(token)}")
            val errno = json.optInt("errno", 0)
            if (errno != 0) error(BaiduErrors.hint(errno))
            json.optString("baidu_name").ifBlank { "百度网盘用户" }
        }
    }

    /**
     * 找到应用**真正有权限**的目录，返回 `/apps/<应用名>`（找不到就退回 `/`）。
     *
     * 为什么必须由客户端自己找：`list` 用的是用户自己的 token，能列出整个网盘 ——
     * 车机屏幕上会直接铺开「2023 / A卡整合包 / XXX」这些私人目录。而 dlink 只对
     * `/apps` 下的文件发放，那些目录里的文件看着能点、点了必然失败。所以进页面
     * 先落到权限目录，用户不必知道 `/apps` 这层。
     *
     * 顺序：`/apps` 下匹配应用名的子目录 → 唯一的子目录 → 约定的 `/apps/windtone`
     * → 都没有才退回 `/`（界面会提示原因，而不是假装一切正常）。
     */
    suspend fun appRoot(): String = withContext(Dispatchers.IO) {
        val dirs = list("/apps").getOrNull().orEmpty().filter { it.isDir }
        val preferred = dirs.firstOrNull { it.name.contains("windtone", ignoreCase = true) }
            ?: dirs.singleOrNull()
            ?: dirs.firstOrNull()
        if (preferred != null) {
            return@withContext BaiduRules.childPath("/apps", preferred.name)
        }
        val fallback = "/apps/windtone"
        if (list(fallback).isSuccess) return@withContext fallback
        "/"
    }

    suspend fun list(dir: String): Result<List<BaiduEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = auth.accessToken() ?: error("尚未登录")
            val url = "$FILE_URL?method=list&access_token=${enc(token)}" +
                "&dir=${enc(dir)}&order=name&start=0&limit=1000&web=0"
            val json = request(url)
            val errno = json.optInt("errno", 0)
            if (errno != 0) error(BaiduErrors.hint(errno))
            val arr = json.optJSONArray("list") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseEntry(arr.getJSONObject(i)) }.getOrNull()
            }.sortedWith(compareByDescending<BaiduEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }
    }

    /**
     * 批量取直链。
     *
     * 接口一次最多 100 个 fsid，调用方按需切分。返回 [DlinkBatch]：
     * 单个文件失败（被删 / 无权限）不该让整批播放失败，所以只要有一部分成功就正常返回，
     * 缺的个数放在 [DlinkBatch.note] 里；**全部失败且接口明确报错**才抛异常。
     */
    suspend fun dlinks(fsIds: List<Long>): Result<DlinkBatch> = withContext(Dispatchers.IO) {
        runCatching {
            if (fsIds.isEmpty()) return@runCatching DlinkBatch(emptyMap(), 0, 0, "")
            val token = auth.accessToken() ?: error("尚未登录")
            val out = LinkedHashMap<Long, String>()
            var records = 0
            var firstError = ""
            fsIds.chunked(MAX_FSIDS_PER_CALL).forEach { chunk ->
                val ids = chunk.joinToString(",", prefix = "[", postfix = "]")
                val url = "$MULTIMEDIA_URL?method=filemetas&access_token=${enc(token)}" +
                    "&fsids=${enc(ids)}&dlink=1&thumb=0&extra=0"
                val json = request(url)
                val errno = json.optInt("errno", 0)
                if (errno != 0) {
                    if (firstError.isEmpty()) firstError = BaiduErrors.hint(errno)
                    return@forEach
                }
                records += BaiduLinkParse.recordCount(json)
                out.putAll(BaiduLinkParse.links(json))
            }
            if (out.isEmpty() && firstError.isNotEmpty()) error(firstError)
            DlinkBatch(
                links = out,
                requested = fsIds.size,
                records = records,
                note = BaiduLinkParse.note(fsIds.size, out, records),
            )
        }
    }

    /** 把选中的条目转成播放队列（没有直链的条目直接跳过） */
    fun toSongs(entries: List<BaiduEntry>, dlinks: Map<Long, String>): List<Song> =
        entries.mapNotNull { e ->
            val link = dlinks[e.fsId] ?: return@mapNotNull null
            Song(
                id = -(e.fsId),
                uri = Uri.parse(link),
                title = BaiduRules.displayTitle(e.name),
                artist = null,
                album = "百度网盘",
                durationMs = 0L,
                sizeBytes = e.sizeBytes,
                displayName = e.name,
                source = SongSource.REMOTE,
            )
        }

    private fun parseEntry(o: JSONObject): BaiduEntry = BaiduEntry(
        fsId = o.optLong("fs_id", 0L),
        name = o.optString("server_filename"),
        path = o.optString("path"),
        isDir = o.optInt("isdir", 0) == 1,
        sizeBytes = o.optLong("size", 0L),
        modifiedAtSec = o.optLong("server_mtime", 0L),
    )

    private fun request(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
            // 网盘接口对 UA 敏感：非网盘 UA 会被判成「第三方抓取」，直接 403 / errno -6
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("网盘返回空响应（HTTP $code）")
            return JSONObject(text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val FILE_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
        const val MULTIMEDIA_URL = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
        const val NAS_URL = "https://pan.baidu.com/rest/2.0/xpan/nas"
        const val UA = "pan.baidu.com"
        const val MAX_FSIDS_PER_CALL = 100
    }
}
