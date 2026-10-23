package com.luckylearning.windtone.data.aliyun

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
     * 列目录的排序：文件夹在前、名称不区分大小写 —— 与百度网盘页保持同一套观感。
     */
    fun sorted(entries: List<AliyunEntry>): List<AliyunEntry> =
        entries.sortedWith(compareByDescending<AliyunEntry> { it.isDir }.thenBy { it.name.lowercase() })

    /**
     * 「用哪个盘」的兜底顺序（纯函数，单测覆盖）。
     *
     * 阿里云盘把空间拆成三个盘：**资源库**（resource，用户上传的文件多在这里）、
     * **备份盘**（backup，相册/文件备份）、以及接口给的默认盘（default）。
     * OpenList 的默认值是 `resource`；但老账号往往只有 default。所以顺序取
     * default → resource → backup：default 不可用时再退到资源库，最后才是备份盘。
     * 三个都拿不到说明账号异常，返回 null 交给界面报错。
     */
    fun pickDrive(defaultId: String?, resourceId: String?, backupId: String?): String? =
        listOf(defaultId, resourceId, backupId)
            .firstOrNull { !it.isNullOrBlank() }
}

/**
 * 阿里云盘 OpenAPI 客户端 —— 在线播放（只读）。
 *
 * 只做三件事：取用户信息（拿盘 id 与昵称）、列目录（marker 分页）、取下载直链。
 * 上传/删除/重命名一律不做 —— 这个应用对网盘是**只读播放器**，与百度网盘页同一原则：
 * 能力越小越不容易被风控，也越好解释权限。
 *
 * 端点与参数照 OpenList 的 `aliyundrive_open` 驱动逐字对齐（2026-10 核对源码）：
 *  · 基址 `https://openapi.alipan.com/adrive/v1.0`；
 *  · 列目录 `openFile/list`（limit 200、marker 分页）；
 *  · 直链 `openFile/getDownloadUrl`，body `{drive_id, file_id, expire_sec}`，响应字段 `url`。
 *
 * 与百度的关键差异：
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
) {

    /** 常规构造：token 从授权层按需取（快过期会自动刷新，见 AliyunAuth.accessToken） */
    constructor(auth: AliyunAuth) : this({ auth.accessToken() }, { auth.forceRefresh() })

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
        /** 默认进哪个盘（default → resource → backup） */
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
                }
                val json = api("$BASE/openFile/list", body)
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
            val json = api("$BASE/openFile/getDownloadUrl", body)
            val url = json.optString("url")
            if (!url.startsWith("http")) {
                error("接口没有返回可播放的地址（文件可能已被删除或被屏蔽）")
            }
            url
        }
    }

    /**
     * 把文件条目转成播放队列里的歌。
     *
     * URI 的 fragment 带 `wt=<fileId>`：直链每次签名都不同，拿整个 URI 当缓存键
     * 等于永不命中 —— 与百度页同一机制（fragment 不进 HTTP 请求，只给缓存层当
     * 稳定键，见 OnlineMediaCache.panCacheKey）。
     */
    fun toSong(entry: AliyunEntry, url: String): Song = Song(
        // 与百度页一致：id 取负数避开本地曲库的正数 id 空间。
        // hashCode 本身可能是负数，直接再取负会变正 —— 先压成非负（丢掉符号位），
        // 同一个 fileId 的映射结果稳定不变。
        id = -(entry.fileId.hashCode() and 0x7FFFFFFF).toLong(),
        uri = Uri.parse(url).buildUpon().fragment("wt=${entry.fileId}").build(),
        title = AliyunRules.displayTitle(entry.name),
        artist = null,
        album = "阿里云盘",
        durationMs = 0L,
        sizeBytes = entry.sizeBytes,
        displayName = entry.name,
        source = SongSource.REMOTE,
    )

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
        if (code == 401) error("登录状态已失效，请重新登录")
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
    }
}
