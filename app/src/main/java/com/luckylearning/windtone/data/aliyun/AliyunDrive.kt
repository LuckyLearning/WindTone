package com.luckylearning.windtone.data.aliyun

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import com.luckylearning.windtone.data.model.TrackFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 阿里云盘里的一个条目（文件或文件夹） */
@Immutable
data class AliyunEntry(
    /** 文件 id（开放平台是字符串，与百度的数值 fsId 不同） */
    val fileId: String,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long,
) {
    /** 能被播放器解码的音频（wma / ape / dsf 等没有解码器，列出来只会点了没反应） */
    val isPlayableAudio: Boolean get() = !isDir && AliyunRules.isPlayableAudio(name)
}

/** 一次目录列表的分页结果 */
data class AliyunListPage(
    val entries: List<AliyunEntry>,
    /** 下一页标记；空串 = 已到末尾（界面据此决定是否显示「加载更多」） */
    val nextMarker: String,
)

/**
 * 路径与文件类型的纯规则（单测覆盖）。
 *
 * 阿里云盘没有「路径」概念（列目录全靠 parent_file_id），所以这里的规则只有
 * 文件类型、展示名与「用哪个盘」的选择。
 */
object AliyunRules {

    /** 播放器能解的音频后缀。wma/ape/dsf 不在内：本工程没接 ffmpeg 解码器 */
    private val PLAYABLE = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")

    fun isPlayableAudio(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in PLAYABLE

    /** 展示用文件名（去掉扩展名） */
    fun displayTitle(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

    /**
     * 从文件名拆出歌手和歌名。
     *
     * 先认带空格的分隔（` - `、破折号、` _ `），再认只出现一次的 `_` 或 `-`。
     * 拆不开时歌名用去扩展名的文件名，歌手为空。
     */
    fun splitDisplayName(fileName: String): Pair<String, String?> {
        val stem = displayTitle(fileName)
        val spaced = listOf(" - ", " – ", " — ", " _ ")
        for (sep in spaced) {
            val cut = splitOnce(stem, sep) ?: continue
            return cut
        }
        for (sep in listOf("_", "-")) {
            if (stem.count { it == sep[0] } != 1) continue
            val cut = splitOnce(stem, sep) ?: continue
            return cut
        }
        return stem to null
    }

    /** 专辑名用所在文件夹。空文件夹不拿「阿里云盘」充数。 */
    fun albumName(folderName: String?): String? = folderName?.trim()?.takeIf { it.isNotEmpty() }

    private fun splitOnce(stem: String, sep: String): Pair<String, String?>? {
        val at = stem.indexOf(sep)
        if (at <= 0 || at + sep.length >= stem.length) return null
        val artist = stem.substring(0, at).trim()
        val title = stem.substring(at + sep.length).trim()
        if (artist.isEmpty() || title.isEmpty()) return null
        return title to artist
    }

    /**
     * 列目录的排序：文件夹在前、名称不区分大小写 —— 与百度网盘页保持同一套观感。
     */
    fun sorted(entries: List<AliyunEntry>): List<AliyunEntry> =
        entries.sortedWith(compareByDescending<AliyunEntry> { it.isDir }.thenBy { it.name.lowercase() })

    /**
     * 「用哪个盘」的兜底顺序（纯函数，单测覆盖）。
     *
     * 网页「全部文件」是**资源库**。截图里的「备份文件」是备份盘的根，不是它下面的子目录，
     * 所以在备份盘根上没有文件夹上级可返回。`default_drive_id` 又经常就等于备份盘，
     * 先选 default 会一登录就进备份文件。
     *
     * 有资源库就进资源库；没有资源库时再用 default（老账号往往只有它）；
     * default 自己就是备份盘、又没有资源库时，才落在备份盘。
     */
    fun pickDrive(defaultId: String?, resourceId: String?, backupId: String?): String? {
        val resource = resourceId?.takeIf { it.isNotBlank() }
        val default = defaultId?.takeIf { it.isNotBlank() }
        val backup = backupId?.takeIf { it.isNotBlank() }
        if (resource != null) return resource
        if (default != null && default != backup) return default
        return default ?: backup
    }

    /**
     * 备份盘根目录在网页上的上级是「全部文件」（资源库）。
     *
     * 已经在某个子目录里时返回 null，调用方走普通的目录栈弹出。
     * 当前不是备份盘、或没有另一份资源库时，也返回 null（没有可回的上级）。
     */
    fun backupRootParent(
        currentDriveId: String,
        drives: List<Pair<String, String>>,
        atRoot: Boolean,
    ): String? {
        if (!atRoot) return null
        val resource = drives.firstOrNull { it.first == "资源库" }?.second ?: return null
        val backup = drives.firstOrNull { it.first == "备份盘" }?.second ?: return null
        if (currentDriveId == backup && resource != currentDriveId) return resource
        return null
    }
}

/**
 * 阿里云盘 OpenAPI 客户端 —— 在线播放（只读）。
 *
 * 只做三件事：取用户信息（拿盘 id 与昵称）、列目录（marker 分页）、取下载直链。
 * 上传/删除/重命名一律不做 —— 这个应用对网盘是**只读播放器**，与百度网盘页同一原则：
 * 能力越小越不容易被风控，也越好解释权限。
 *
 * 端点与参数**按令牌来源分家族**（2026-10 实锤，见 [AliyunEndpoints.WEB_BASE] 注释）：
 *  · **网页扫码线（CS）**→ 网页版 API `api.alipan.com`（`/v2/file/list`、
 *    `/v2/file/get_download_url` 等，照 AList 网页版驱动对齐）；
 *  · **开放平台线（OA，自备密钥）**→ OpenAPI `openapi.alipan.com/adrive/v1.0`（照
 *    OpenList `aliyundrive_open` 驱动对齐）。
 *  · 与阿里云盘的关键差异：
 *  · 全部 JSON POST + `Authorization: Bearer` 头（百度是 GET + query 上的 token）；
 *  · 列目录**没有路径**，靠 parent_file_id（根目录固定叫 `root`）；
 *  · 直链**没有批量接口**，一个文件一次请求，调用方按需取；
 *  · 直链本身公开可播（带 4 小时有效期签名），不需要 UA 伪装、不需要拼 token ——
 *    所以播放/缓存层（OnlineMediaCache / CachePrefetcher）对它零改动，
 *    稳定缓存键复用 fragment `wt=<fileId>` 机制（见 panCacheKey）。
 */
class AliyunDrive(
    private val tokenProvider: suspend () -> String?,
    /**
     * 强制刷新 token 的回调（接口回 AccessTokenInvalid 时重试一次用）。
     * 默认返回 false（纯测试构造下不重试）。
     */
    private val forceRefresher: suspend () -> Boolean = { false },
    /** 当前令牌来源（决定用哪套 API 家族；纯测试构造下默认网页线） */
    private val sourceProvider: suspend () -> AliyunTokenSource = { AliyunTokenSource.CS },
) {

    /** 常规构造：token 与来源都从授权层按需取 */
    constructor(auth: AliyunAuth) : this(
        { auth.accessToken() },
        { auth.forceRefresh() },
        { auth.tokenSource() },
    )

    /** 网页线（CS）走网页 API；开放平台线（OA）走 OpenAPI */
    private suspend fun isWebFamily(): Boolean = sourceProvider() == AliyunTokenSource.CS

    /**
     * 用户信息：昵称 + 三个盘 id。
     *
     * 阿里云盘现在把空间拆成 资源库 / 备份盘 两块，getDriveInfo 会分别给出
     * `resource_drive_id` / `backup_drive_id` / `default_drive_id`。展示哪个盘
     * 由调用方决定（见 [AliyunRules.pickDrive]），这里把三个都带出来。
     */
    data class DriveInfo(
        val nickname: String,
        val defaultDriveId: String,
        val resourceDriveId: String,
        val backupDriveId: String,
    ) {
        /** 默认进哪个盘：有资源库进资源库（网页「全部文件」），否则见 [AliyunRules.pickDrive] */
        fun preferredDriveId(): String? =
            AliyunRules.pickDrive(defaultDriveId, resourceDriveId, backupDriveId)

        /**
         * 可切换的盘：Pair(显示名, drive_id)。
         *
         * **默认盘也要列出来**：接口给的 default_drive_id 有时既不等于备份盘也不等于
         * 资源库，只列后两个的话，用户切走之后就没有入口切回来了。
         * 按 id 去重（default 常常与 backup 是同一个），顺序固定：备份盘 / 资源库 / 默认盘。
         * 数量 ≤ 1 时界面不显示切换 —— 只有一个盘没什么可切的。
         */
        fun availableDrives(): List<Pair<String, String>> {
            val candidates = buildList {
                if (backupDriveId.isNotBlank()) add("备份盘" to backupDriveId)
                if (resourceDriveId.isNotBlank()) add("资源库" to resourceDriveId)
                if (defaultDriveId.isNotBlank()) add("默认盘" to defaultDriveId)
            }
            val seen = HashSet<String>()
            return candidates.filter { seen.add(it.second) }
        }
    }

    suspend fun driveInfo(): Result<DriveInfo> = withContext(Dispatchers.IO) {
        runCatching {
            if (isWebFamily()) {
                return@runCatching webDriveInfo()
            }
            val json = api("$BASE/user/getDriveInfo", JSONObject())
            DriveInfo(
                nickname = json.optString("user_name").ifBlank { "阿里云盘用户" },
                defaultDriveId = json.optString("default_drive_id"),
                resourceDriveId = json.optString("resource_drive_id"),
                backupDriveId = json.optString("backup_drive_id"),
            ).also {
                if (it.preferredDriveId() == null) {
                    error("账号没有可用的盘（响应里没有任何 drive_id）")
                }
            }
        }
    }

    /**
     * 网页线的账号信息。
     *
     * 盘 id 的正源是 `/v2/user/get` 根上的 `default_drive_id`（以及
     * `resource_drive_id` / `backup_drive_id`）。`get_personal_info` 在现行接口里
     * 通常只有容量，没有盘 id；只解析它就会在登录成功后报「没有任何 drive_id」。
     * `api.alipan.com` 这份用户信息里没有可用盘 id 时，再问一次
     * `user.aliyundrive.com/v2/user/get`（小雅 / aligo 读资源库、备份盘的那台）。
     * 两个用户接口都失败时，把接口错误抛出去，不用「没有盘」盖掉 401 这类真失败。
     */
    private suspend fun webDriveInfo(): DriveInfo {
        val apiUser = runCatching { api(AliyunEndpoints.WEB_USER_URL, JSONObject()) }
        val personal = runCatching { api(AliyunEndpoints.WEB_PERSONAL_INFO_URL, JSONObject()) }
        val personalJson = personal.getOrNull() ?: JSONObject()
        val firstUser = apiUser.getOrNull()
        val first = runCatching { parseWebPersonalInfo(personalJson, firstUser) }.getOrNull()
        // api.alipan.com 这份常常只有 default_drive_id，而且它就是备份盘。
        // 资源库 id 在 user.aliyundrive.com 上。缺了资源库就再问一次，否则进不了「全部文件」。
        if (first != null && first.resourceDriveId.isNotBlank()) return first

        val hostUser = runCatching { api(AliyunEndpoints.WEB_USER_DRIVE_URL, JSONObject()) }
        val merged = mergeUserJson(firstUser, hostUser.getOrNull())
        if (merged == null && personal.getOrNull() == null && first == null) {
            throw apiUser.exceptionOrNull()
                ?: hostUser.exceptionOrNull()
                ?: personal.exceptionOrNull()
                ?: error("账号没有可用的盘（响应里没有任何 drive_id）")
        }
        return try {
            parseWebPersonalInfo(personalJson, merged)
        } catch (e: IllegalStateException) {
            if (first != null) return first
            if (firstUser == null && hostUser.getOrNull() == null) {
                throw apiUser.exceptionOrNull()
                    ?: hostUser.exceptionOrNull()
                    ?: personal.exceptionOrNull()
                    ?: e
            }
            throw e
        }
    }

    /**
     * 列目录（marker 分页）。
     *
     * 每页 200 条（OpenList 用的也是这个值，接口上限就是 200）；目录很大的话调用方拿
     * [AliyunListPage.nextMarker] 追加下一页，而不是一次拉全 —— 车机屏幕一次也就
     * 显示十几行，先出先看。
     */
    suspend fun list(driveId: String, parentFileId: String, marker: String = ""): Result<AliyunListPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("drive_id", driveId)
                    put("parent_file_id", parentFileId.ifBlank { ROOT_ID })
                    put("limit", PAGE_LIMIT)
                    put("marker", marker)
                    put("order_by", "name")
                    put("order_direction", "ASC")
                    // 网页 API 额外要求这两个字段（照 AList 网页驱动；OpenAPI 多给也不报错）
                    put("fields", "*")
                    put("url_expire_sec", LINK_EXPIRATION_SEC)
                }
                val url = if (isWebFamily()) {
                    AliyunEndpoints.WEB_FILE_LIST_URL
                } else {
                    "$BASE/openFile/list"
                }
                val json = api(url, body)
                val arr = json.optJSONArray("items") ?: JSONArray()
                val entries = (0 until arr.length()).mapNotNull { i ->
                    runCatching { parseEntry(arr.getJSONObject(i)) }.getOrNull()
                }
                AliyunListPage(
                    entries = AliyunRules.sorted(entries),
                    nextMarker = json.optString("next_marker").orEmpty(),
                )
            }
        }

    /**
     * 取单个文件的下载直链。
     *
     * 有效期按 4 小时申请（与 OpenList 一致）。直链**每次播放前现取**：虽然 4 小时内
     * 可复用，但「队列里的歌明天还在不在、用户有没有重新登录」都不可控，现取的
     * 复杂度最低，与百度页同一策略。
     */
    suspend fun downloadUrl(driveId: String, fileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("drive_id", driveId)
                put("file_id", fileId)
                put("expire_sec", LINK_EXPIRATION_SEC)
            }
            val url = if (isWebFamily()) {
                AliyunEndpoints.WEB_DOWNLOAD_URL
            } else {
                "$BASE/openFile/getDownloadUrl"
            }
            val json = api(url, body)
            val link = json.optString("url")
            if (!link.startsWith("http")) {
                error("接口没有返回可播放的地址（文件可能已被删除或被屏蔽）")
            }
            link
        }
    }

    /**
     * 递归扫描一份歌单用的「列目录」实现：处理分页，把整目录全部条目拉完。
     *
     * 扫描编排（层深 / 排序 / 跳过）在 [AliyunScanRules.scan]，这里只负责把
     * 一个目录的所有页拉平；单页失败直接抛给上层按「跳过该目录」处理。
     */
    private suspend fun listAll(driveId: String, parentFileId: String): List<AliyunEntry> {
        val all = mutableListOf<AliyunEntry>()
        var marker = ""
        do {
            val page = list(driveId, parentFileId, marker).getOrThrow()
            all += page.entries
            marker = page.nextMarker
        } while (marker.isNotBlank())
        return all
    }

    /**
     * 把 [rootId] 目录（含下钻 [AliyunScanRules.SCAN_DEPTH] 层子目录）扫描成一份歌单。
     *
     * 只收可播放音频；列失败的目录跳过并在结果里带原因；协程取消即中断。
     * 进度通过 [onProgress] 回调（已扫目录数 / 已收文件数），界面显示「扫描中」用。
     */
    suspend fun scanPlaylist(
        driveId: String,
        rootId: String,
        onProgress: suspend (dirsDone: Int, filesFound: Int) -> Unit = { _, _ -> },
    ): AliyunScanRules.ScanOutcome = withContext(Dispatchers.IO) {
        AliyunScanRules.scan(
            rootId = rootId,
            listDir = { listAll(driveId, it) },
            onProgress = onProgress,
        )
    }

    /**
     * 把文件条目转成播放队列里的歌。
     *
     * 身份是盘编号加文件编号，放在 fragment（`ay=`）里，不进 HTTP。
     * [saved] 里已有的标题、歌手、专辑、时长盖过文件名和文件夹。
     */
    fun toSong(
        entry: AliyunEntry,
        url: String,
        driveId: String,
        folderName: String? = null,
        saved: AliyunTrackRecord? = null,
    ): Song {
        val (parsedTitle, parsedArtist) = AliyunRules.splitDisplayName(entry.name)
        val title = saved?.title?.takeIf { it.isNotBlank() } ?: parsedTitle
        val artist = saved?.artist?.takeIf { it.isNotBlank() } ?: parsedArtist
        val album = saved?.album?.takeIf { it.isNotBlank() } ?: AliyunRules.albumName(folderName)
        val identity = "$driveId/${entry.fileId}"
        return Song(
            id = -(identity.hashCode() and 0x7FFFFFFF).toLong(),
            uri = Uri.parse(url).buildUpon().fragment(TrackFragment.of(driveId, entry.fileId)).build(),
            title = title,
            artist = artist,
            album = album,
            durationMs = saved?.durationMs ?: 0L,
            sizeBytes = entry.sizeBytes,
            displayName = entry.name,
            source = SongSource.REMOTE,
        )
    }

    /** 先占一个位置进队列，直链等这首歌滑进窗口再取。 */
    fun pendingSong(record: AliyunTrackRecord): Song {
        val name = record.fileName.ifBlank {
            if (record.title.isBlank()) "未命名.mp3" else "${record.title}.mp3"
        }
        return pendingSong(
            AliyunEntry(record.fileId, name, false, 0L),
            record.driveId,
            record.folderName.takeIf { it.isNotBlank() },
            record,
        )
    }

    fun pendingSong(
        entry: AliyunEntry,
        driveId: String,
        folderName: String? = null,
        saved: AliyunTrackRecord? = null,
    ): Song = toSong(
        entry,
        "${AliyunPlaybackWindow.PENDING_SCHEME}://track",
        driveId,
        folderName,
        saved,
    )

    /**
     * 只给窗口里还没有直链的歌取地址。窗口外的歌保持原样。
     * 已经是 http(s) 的不重复取。某一首失败就留在占位，调用方可以改播窗口里下一首。
     */
    suspend fun fillWindow(songs: List<Song>, current: Int): List<Song> {
        if (songs.isEmpty()) return songs
        val index = current.coerceIn(0, songs.lastIndex)
        val span = AliyunPlaybackWindow.range(index, songs.size)
        if (span.isEmpty()) return songs
        val out = songs.toMutableList()
        for (i in span) {
            coroutineContext.ensureActive()
            val song = out[i]
            if (!AliyunPlaybackWindow.isPending(song.uri.scheme)) continue
            val id = TrackFragment.parse(song.uri.fragment) ?: continue
            val url = downloadUrl(id.first, id.second).getOrNull() ?: continue
            val refreshed = toSong(
                AliyunEntry(id.second, song.displayName.ifBlank { song.title }, false, song.sizeBytes),
                url,
                id.first,
                song.album,
                AliyunTrackRecord(
                    driveId = id.first,
                    fileId = id.second,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                ),
            )
            out[i] = refreshed.copy(displayName = song.displayName.ifBlank { refreshed.displayName })
        }
        return out
    }

    private fun parseEntry(o: JSONObject): AliyunEntry? {
        val id = o.optString("file_id")
        if (id.isBlank()) return null
        val type = o.optString("type")
        return AliyunEntry(
            fileId = id,
            name = o.optString("name"),
            // 文件夹的 type 是 "folder"；回收站等条目 type 可能是其它值，按非文件处理
            isDir = type == "folder",
            sizeBytes = o.optLong("size", 0L),
        )
    }

    /**
     * 一次 API 调用：POST JSON + Bearer 头，错误统一翻成人话。
     *
     * 两条重试规则：
     *  · `AccessTokenInvalid / AccessTokenExpired / I400JD` → 强制刷新一次 token 再重试
     *    （与 OpenList 的判据一致：本地时钟漂移、多设备同时用都可能让 token 提前失效）；
     *  · 401 兜底同上 —— 走到这里还 401 说明 refresh_token 也失效了，必须让界面回未登录态。
     */
    private suspend fun api(url: String, body: JSONObject, retried: Boolean = false): JSONObject {
        val token = requireToken()
        val (code, text) = post(token, url, body)
        // HTTP 401：access_token 被服务端判无效（过期判定偏差 / 多端登录被顶 / 令牌轮换后旧值）
        // —— 这里必须也走一次刷新重试。以前直接报「登录状态已失效」，
        // 让一次本可以自愈的 401 变成必须用户重新授权。
        if (code == 401) {
            if (!retried && forceRefresher()) return api(url, body, retried = true)
            error("登录状态已失效，请重新扫码授权")
        }
        if (text.isBlank()) error("阿里云盘返回空响应（HTTP $code）")
        val json = JSONObject(text)
        if (code !in 200..299) {
            val errCode = json.optString("code")
            if (!retried && AliyunErrors.isRetryable(errCode) && forceRefresher()) {
                return api(url, body, retried = true)
            }
            error(AliyunErrors.hint(json).ifBlank { "请求失败（HTTP $code）" })
        }
        return json
    }

    private fun post(token: String, url: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", AliyunEndpoints.UA)
            // 网页 API 校验请求来源形态（照 AList 网页驱动：Origin/Referer 指向网页版）
            setRequestProperty("Referer", "https://alipan.com/")
            setRequestProperty("Origin", "https://www.alipan.com")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun requireToken(): String =
        // provider 返回 null = 未登录（AliyunAuth.accessToken 的约定）
        runBlocking { tokenProvider() } ?: error("尚未登录阿里云盘")

    companion object {
        private const val BASE = AliyunEndpoints.API_BASE

        const val ROOT_ID = "root"

        /** 列目录每页条数（接口上限 200，OpenList 也用 200） */
        const val PAGE_LIMIT = 200

        /** 直链有效期（秒）：4 小时，与 OpenList 一致 */
        const val LINK_EXPIRATION_SEC = 14_400

        /**
         * 网页线的盘信息解析（纯函数，单测覆盖）。
         *
         * 盘 id 优先取 [userJson]（`/v2/user/get` 的根字段 `default_drive_id` /
         * `resource_drive_id` / `backup_drive_id`）。`get_personal_info` 里的
         * `personal_drive_info` 以及响应根上的同名字段只作旧形态兜底。
         * 字段值是 JSON null 时 `optString` 会得到字面量 `"null"`，那不是盘 id。
         */
        internal fun parseWebPersonalInfo(
            personalInfo: JSONObject,
            userJson: JSONObject? = null,
        ): DriveInfo {
            val personal = personalInfo.optJSONObject("personal_drive_info") ?: JSONObject()
            fun pick(key: String): String = listOf(
                userJson?.optString(key),
                personal.optString(key),
                personalInfo.optString(key),
            ).firstOrNull { usable(it) }.orEmpty()
            val nickname = listOf("user_name", "nick_name", "nickname", "name")
                .firstNotNullOfOrNull { key -> userJson?.optString(key)?.takeIf { usable(it) } }
                ?: "阿里云盘用户"
            return DriveInfo(
                nickname = nickname,
                defaultDriveId = pick("default_drive_id").ifBlank { pick("drive_id") },
                resourceDriveId = pick("resource_drive_id"),
                backupDriveId = pick("backup_drive_id"),
            ).also {
                if (it.preferredDriveId() == null) {
                    error("账号没有可用的盘（响应里没有任何 drive_id）")
                }
            }
        }

        /** 把两份 user/get 合成一份：先到的非空字段保留，空的用另一份补 */
        internal fun mergeUserJson(primary: JSONObject?, extra: JSONObject?): JSONObject? {
            if (primary == null) return extra
            if (extra == null) return primary
            val out = JSONObject()
            for (key in USER_KEYS) {
                val value = listOf(primary.optString(key), extra.optString(key))
                    .firstOrNull { usable(it) }
                if (value != null) out.put(key, value)
            }
            return out
        }

        private fun usable(value: String?): Boolean =
            !value.isNullOrBlank() && value != "null"

        private val USER_KEYS = listOf(
            "default_drive_id",
            "resource_drive_id",
            "backup_drive_id",
            "drive_id",
            "user_name",
            "nick_name",
            "nickname",
            "name",
        )
    }
}
