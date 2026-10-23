package com.luckylearning.windtone.data.aliyun

import android.content.Context
import com.luckylearning.windtone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 令牌来源 —— 决定了**刷新要打哪个端点**（两者不能混用，见 [AliyunEndpoints.renewApiUrl]）。
 *
 * - [OA]：OpenList 旧扫码 / 开放平台应用授权出来的令牌（`driver_txt=alicloud_qr`）；
 * - [CS]：网页版扫码登录（`alicloud_cs`）拿到的令牌 —— 应用内扫码授权、
 *   以及在 api.oplist.org 网页上「扫码登录」拿到的刷新令牌都属于这一档。
 */
enum class AliyunTokenSource(val stored: String) {
    OA("oa"),
    CS("cs"),
    ;

    companion object {
        fun parse(raw: String?): AliyunTokenSource =
            if (raw == CS.stored) CS else OA // 缺省按 OA（1.1.56 之前存档没有这个键）
    }
}

/**
 * 刷新串行化的判定规则（纯函数，单测覆盖）。
 *
 * 为什么需要它：阿里云盘的 refresh_token 是**轮换**的。并发场景下，第一个协程刷新
 * 成功后新令牌已经落盘，后面排队的协程手里还是过期快照；若照旧再刷一次，
 * 服务端会再轮换一轮，而两个协程的保存顺序不确定 —— 最终可能留下一个服务端已作废的值，
 * 从此每次请求都 401（用户看到「播放一次后一直提示授权失效」）。
 */
internal object RefreshPolicy {

    /**
     * 进锁后复查：能否直接复用存储里的新令牌。
     *
     * @param stale 调用方进锁前读到的快照
     * @param fresh 进锁后重新读到的存储值
     * @param force true = 调用方明确要求换一次（遇到 401 / 令牌被判无效）。
     *              此时只要令牌**确实换过**就算达成，不必再刷一次 ——
     *              服务端已经认新令牌，再刷反而多一次轮换风险。
     */
    fun canReuse(stale: AliyunToken, fresh: AliyunToken?, force: Boolean, now: Long): Boolean {
        if (fresh == null) return false
        // access 没变 = 没有别人刷过
        if (fresh.accessToken == stale.accessToken) return false
        return force || !fresh.isExpiring(now)
    }
}

/** 一次刷新拿到的令牌 */
data class AliyunToken(
    val accessToken: String,
    val refreshToken: String,
    /** 绝对过期时间（毫秒）。阿里 access_token 有效期 2 小时 */
    val expiresAt: Long,
) {
    fun isExpiring(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt - 5 * 60_000L
}

/** 换 / 刷新令牌的结果（失败时带可读原因） */
sealed interface AliyunExchange {
    data class Ready(val token: AliyunToken) : AliyunExchange
    /** 失败，带人话原因 */
    data class Failed(val reason: String) : AliyunExchange
}

/**
 * 阿里云盘授权（**按 OpenList 的方案**）—— 在线播放的登录层。
 *
 * 阿里云盘开放平台只提供「授权码 + 回调」流程，客户端没有后端就收不到 code。
 * OpenList 的做法是把授权搬到它的网页工具上（`api.oplist.org`）：用户在那里
 * 扫码登录、授权，页面直接把 **refresh_token** 显示出来，粘贴进应用即可。
 * 本应用采用同一方案，登录只有一个动作：**粘贴刷新令牌**。
 *
 * 刷新令牌有两条路，按「有没有自己的应用凭据」自动选择，且互为兜底：
 *
 *  1. **直连官方**（BuildConfig 里有 ClientId + ClientSecret 时优先）：
 *     POST `https://openapi.alipan.com/oauth/access_token`
 *     body `{client_id, client_secret, grant_type:"refresh_token", refresh_token}`。
 *     令牌必须是**用同一个 client 授权出来的**，否则会 InvalidClient。
 *  2. **OpenList 在线接口**（没有自己凭据时用，也是直连失败后的兜底）：
 *     GET `https://api.oplist.org/alicloud/renewapi?refresh_ui=<token>&server_use=true&driver_txt=alicloud_qr`
 *     返回 `{access_token, refresh_token, text}`，`text` 非空即错误说明。
 *     这条路由 OpenList 的服务端代持它的 client_secret，我们只发 refresh_token。
 *     ⚠️ 它只能刷 OA 类令牌；CS 类（网页扫码登录）必须走 `/alicloud2/renewapi`，
 *     否则症状是「新令牌能用 2 小时，过期后永久提示授权失效」。
 *
 * 两条硬约束：
 *  · 两个接口返回的 refresh_token 都是**轮换**的（旧的随即失效），成功后必须立刻保存；
 *  · 刷新必须**串行**（[refreshMutex]）。并发拿同一个旧令牌去换，服务端会连着轮换两次，
 *    而我们只能保存其中一个 —— 另一个在服务端生效但本地没存，同样表现为永久失效。
 */
class AliyunAuth(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("aliyundrive_auth", Context.MODE_PRIVATE)

    /**
     * 刷新互斥锁：同一时刻只允许一个协程换令牌。
     *
     * 没有它的话，播放一首歌时并发的多个请求（取直链 / 预取 / 封面）会各自发现令牌快过期，
     * 同时拿同一个 refresh_token 去换 —— 阿里是轮换制，服务端连着轮换两次，
     * 我们只存得下最后一次响应，中间那个随即失效，于是下一次请求就永久 401。
     */
    private val refreshMutex = Mutex()

    /** 是否带了自己的应用凭据（有就能直连官方接口，没有就走 OpenList 在线接口） */
    fun hasClientCredentials(): Boolean =
        BuildConfig.ALIYUN_CLIENT_ID.isNotBlank() && BuildConfig.ALIYUN_CLIENT_SECRET.isNotBlank()

    fun currentToken(): AliyunToken? {
        val at = sp.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return AliyunToken(
            accessToken = at,
            refreshToken = sp.getString(KEY_REFRESH, "").orEmpty(),
            expiresAt = sp.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    /** 当前令牌的来源（决定刷新端点；老存档没有这个键时按 OA 处理） */
    fun tokenSource(): AliyunTokenSource =
        AliyunTokenSource.parse(sp.getString(KEY_SOURCE, null))

    fun isLoggedIn(): Boolean = currentToken() != null

    fun signOut() {
        sp.edit().clear().commit()
    }

    /**
     * 粘贴 refresh_token 登录。
     *
     * 不直接存粘贴值：先用它换一次新 token，成功才算登录 —— 这样能当场发现
     * 「粘贴错了 / 令牌属于别的应用 / 已失效」，而不是等到播放时才撞 401。
     * 来源未知：两种端点都会试（先 OA 后 CS），哪条先成功就记哪条。
     */
    suspend fun applyRefreshToken(pasted: String): AliyunExchange = withContext(Dispatchers.IO) {
        val trimmed = pasted.trim()
        if (trimmed.isBlank()) return@withContext AliyunExchange.Failed("粘贴的内容是空的")
        // 粘贴令牌大概率是网页扫码线（OpenList 网页工具发的就是网页令牌）：先走网页
        // 刷新，不行再试 OpenList 两条兜底；成功即按实际成功的那条记来源
        refreshAuto(trimmed, preferred = AliyunTokenSource.CS)
    }

    /** 扫码登录拿到令牌后保存（来源固定为 CS：网页版扫码登录） */
    fun saveFromQrLogin(accessToken: String, refreshToken: String, expiresInSec: Long) {
        save(
            AliyunToken(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = System.currentTimeMillis() + expiresInSec.coerceAtLeast(600L) * 1000L,
            ),
            AliyunTokenSource.CS,
        )
    }

    /**
     * 取一个可用 token：快过期就换新的。
     *
     * 返回 null = 没登录（调用方据此把界面切回未登录态）。
     * 刷新失败时保留旧 token 返回：让上层拿到一次明确的 401 后再引导重登，
     * 直接清空会把「网络抖一下」误判成「被登出」，用户要重新授权，代价太大。
     */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext null
        if (!token.isExpiring()) return@withContext token.accessToken
        refreshSerial(token, force = false) ?: token.accessToken
    }

    /**
     * 强制换一次 token（接口回 AccessTokenInvalid / HTTP 401 时用）。
     *
     * 不判断过期时间 —— 服务端说了无效就是无效（多机同时用、被顶掉等）。
     * 但如果进锁后发现别人刚刚已经换过了（access 与调用方看到的快照不同），
     * 直接算成功，不再白白多轮换一次。
     */
    suspend fun forceRefresh(): Boolean = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext false
        refreshSerial(token, force = true) != null
    }

    /**
     * 串行刷新（single-flight）。
     *
     * 进锁后**重新读一次存储**：并发场景里第一个协程刷完就已经把新令牌落盘了，
     * 后面的协程拿到的 `stale` 是过期快照，如果照旧去刷，会再轮换一次、
     * 而那个新值还可能被先写完的协程覆盖掉 —— 结果就是永久失效。
     *
     * @return 可用的 access_token；null = 刷新失败
     */
    private suspend fun refreshSerial(stale: AliyunToken, force: Boolean): String? =
        refreshMutex.withLock {
            val fresh = currentToken()
            if (RefreshPolicy.canReuse(stale, fresh, force, System.currentTimeMillis())) {
                // 别人刚刷过：不用再刷（force 场景也算已达成目的），直接用它
                return@withLock fresh?.accessToken
            }
            val refreshToken = fresh?.refreshToken?.takeIf { it.isNotBlank() } ?: stale.refreshToken
            when (val result = refreshAuto(refreshToken, preferred = tokenSource())) {
                is AliyunExchange.Ready -> result.token.accessToken
                is AliyunExchange.Failed -> null
            }
        }

    /** 落盘（commit 同步写：刷新成功但没来得及落盘就杀进程，同样会丢新令牌） */
    fun save(token: AliyunToken, source: AliyunTokenSource = tokenSource()) {
        sp.edit()
            .putString(KEY_ACCESS, token.accessToken)
            .putString(KEY_REFRESH, token.refreshToken)
            .putLong(KEY_EXPIRES_AT, token.expiresAt)
            .putString(KEY_SOURCE, source.stored)
            .commit()
    }

    /**
     * 依次尝试刷新：直连官方（有凭据时）→ 优先来源端点 → 另一端兜底。
     *
     * 两端互为兜底不是冗余：用户拿到的令牌可能来自任一渠道（网页工具新旧两版、
     * 应用内扫码），而我们只有「先成功的那条」才知道它属于哪条线。
     */
    private fun refreshAuto(refreshToken: String, preferred: AliyunTokenSource): AliyunExchange {
        if (refreshToken.isBlank()) return AliyunExchange.Failed("缺少刷新令牌，请重新获取")
        val failures = mutableListOf<String>()

        // 路径一：直连官方（需要自己的 client_id / client_secret）
        if (hasClientCredentials()) {
            when (val direct = refreshViaOfficial(refreshToken)) {
                is AliyunExchange.Ready -> {
                    save(direct.token, preferred)
                    return direct
                }
                is AliyunExchange.Failed -> failures += "直连官方接口：${direct.reason}"
            }
        }

        // 路径二：网页令牌的免凭据刷新端点（仅扫码登录线）。
        // 扫码登录拿到的就是网页版令牌，刷新只需 refresh_token 本身（无 client 凭据）。
        // 直连成功就完全不经过第三方；失败自动落到兜底链。
        if (preferred == AliyunTokenSource.CS) {
            when (val direct = refreshViaWeb(refreshToken)) {
                is AliyunExchange.Ready -> {
                    save(direct.token, AliyunTokenSource.CS)
                    return direct
                }
                is AliyunExchange.Failed -> failures += "直连网页令牌端点：${direct.reason}"
            }
        }

        // 路径三 / 四：OpenList 两条刷新线（先来源、再兜底）
        val order = listOf(preferred, if (preferred == AliyunTokenSource.OA) AliyunTokenSource.CS else AliyunTokenSource.OA)
        for (source in order) {
            for (base in AliyunEndpoints.BASES) {
                when (val r = refreshViaOpenList(refreshToken, source, base)) {
                    is AliyunExchange.Ready -> {
                        save(r.token, source)
                        return r
                    }
                    is AliyunExchange.Failed -> failures += (
                        (if (source == AliyunTokenSource.OA) "旧扫码线" else "扫码登录线") +
                            "(${base.substringAfter("://")})：" + r.reason
                        )
                }
            }
        }
        return AliyunExchange.Failed(
            failures.joinToString("；").ifBlank { "刷新令牌失败" },
        )
    }

    /** 路径一：直连官方（需要自己的 client_id / client_secret） */
    private fun refreshViaOfficial(refreshToken: String): AliyunExchange {
        val body = JSONObject().apply {
            put("client_id", BuildConfig.ALIYUN_CLIENT_ID)
            put("client_secret", BuildConfig.ALIYUN_CLIENT_SECRET)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val json = runCatching {
            AliyunHttp.postJson(AliyunEndpoints.tokenUrl(), body, AliyunEndpoints.UA)
        }.getOrElse { return AliyunExchange.Failed(it.message ?: "网络请求失败（官方接口）") }
        val token = parseToken(json, fallbackRefresh = refreshToken)
        return if (token != null) {
            AliyunExchange.Ready(token)
        } else {
            AliyunExchange.Failed(AliyunErrors.hint(json).ifBlank { "官方接口没有返回令牌" })
        }
    }

    /** 路径二：网页令牌免凭据刷新端点（auth.alipan.com，仅扫码登录线用） */
    private fun refreshViaWeb(refreshToken: String): AliyunExchange {
        val body = JSONObject().apply {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val json = runCatching {
            AliyunHttp.postJson(AliyunEndpoints.WEB_TOKEN_URL, body, AliyunEndpoints.UA)
        }.getOrElse { return AliyunExchange.Failed(it.message ?: "网络请求失败（网页令牌端点）") }
        val token = parseToken(json, fallbackRefresh = refreshToken)
        return if (token != null) {
            AliyunExchange.Ready(token)
        } else {
            AliyunExchange.Failed(AliyunErrors.hint(json).ifBlank { "网页令牌端点没有返回令牌" })
        }
    }

    /** 路径三 / 四：OpenList 在线接口（按令牌来源选端点、按站点选域名） */
    private fun refreshViaOpenList(
        refreshToken: String,
        source: AliyunTokenSource,
        base: String,
    ): AliyunExchange {
        val json = runCatching {
            AliyunHttp.getJson(
                AliyunEndpoints.renewApiUrl(refreshToken, source, base),
                AliyunEndpoints.UA,
            )
        }.getOrElse { return AliyunExchange.Failed(it.message ?: "网络请求失败（OpenList 在线接口）") }
        // 在线接口的错误放在 text 字段里（成功时为空）
        val text = json.optString("text")
        val token = parseToken(json, fallbackRefresh = refreshToken)
        if (token == null) {
            return AliyunExchange.Failed(
                text.ifBlank { "OpenList 在线接口没有返回令牌（令牌可能已失效）" },
            )
        }
        return AliyunExchange.Ready(token)
    }

    /** 两种响应形态统一解析：成功时必有 access_token + refresh_token */
    private fun parseToken(json: JSONObject, fallbackRefresh: String): AliyunToken? {
        val at = json.optString("access_token")
        if (at.isBlank()) return null
        val rt = json.optString("refresh_token").ifBlank { fallbackRefresh }
        return AliyunToken(
            accessToken = at,
            refreshToken = rt,
            // 官方接口给 expires_in；OpenList 在线接口不一定给，按 2 小时兜底
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 7_200L) * 1000L,
        )
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        /** 令牌来源（oa / cs），决定刷新端点 —— 见 [AliyunTokenSource] */
        const val KEY_SOURCE = "token_source"
    }
}

/**
 * 端点与 URL 拼装（纯函数，可 JVM 单测）。
 *
 * API 基址用 `openapi.alipan.com`（阿里云盘改名后的正式域名，OpenList 用的也是它；
 * `openapi.aliyundrive.com` 是旧域名，仍可解析但不保证长期可用）。
 */
object AliyunEndpoints {

    /** 开放平台 API 基址 */
    const val API_BASE = "https://openapi.alipan.com/adrive/v1.0"

    /** 官方 token 端点（刷新 / 换取共用） */
    const val TOKEN_URL = "https://openapi.alipan.com/oauth/access_token"

    /** OpenList 在线刷新接口 —— 旧扫码驱动（`alicloud_oa`，开放平台应用 client） */
    const val RENEW_API = "https://api.oplist.org/alicloud/renewapi"

    /**
     * OpenList 在线刷新接口 —— 扫码登录驱动（`alicloud_cs`，网页版登录 client）。
     *
     * **两条刷新线不能混用**（2026-10 核对 OpenList-APIPages 后端源码）：
     *  · `/alicloud/renewapi` 背后是 `drivers/alicloud_oa`，刷新时**带 OpenList 服务端自己的
     *    client_id/client_secret**——只能刷新「用那对凭据授权出来的」令牌；
     *  · `/alicloud2/renewapi` 背后是 `drivers/alicloud_cs`，刷新 body 只有
     *    grant_type + refresh_token（网页登录 client，不需要 secret）——
     *    只能刷新「网页扫码登录拿到的」令牌。
     * 混用的后果不是当场报错：**新签发的 access_token 照常能用 2 小时**，等它过期要
     * 刷新时才开始 permanent 失败——用户看到的就是「播放一次后一直提示授权失效」。
     * v1.1.56/57 就是踩了这个坑（扫码拿的令牌却打到 oa 端点）。
     */
    const val RENEW_API_CS = "https://api.oplist.org/alicloud2/renewapi"

    /**
     * OpenList 服务基址候选：**中国大陆节点优先**，全球站兜底。
     *
     * 实测（国内容移动网络）：`api.oplist.org.cn`（腾讯 EdgeOne）约 0.6 秒，
     * `api.oplist.org`（Cloudflare）要 6 秒以上、甚至是用户侧完全连不上的那一档。
     * 两者接口路径与行为一致。
     */
    val BASES = listOf("https://api.oplist.org.cn", "https://api.oplist.org")

    /**
     * OpenList 的令牌获取页（浏览器打开，扫码授权后显示 refresh_token）——
     * 国内站优先（与 [BASES] 一致）
     */
    val TOKEN_TOOL_URL: String get() = BASES.first()

    /**
     * **网页版 API** 基址（扫码登录线专用）。
     *
     * **2026-10 实锤的重大修正**：扫码登录拿到的是**网页版应用**的令牌，只能在
     * `api.alipan.com` 这套网页 API 上用；拿去打开放平台 OpenAPI（`openapi.alipan.com`）
     * 一律 401 `AccessTokenInvalid` —— 这就是 1.1.56~1.1.66 期间「登录成功但
     * 列目录/播放全挂」的总根因（OpenList 自己的网页驱动调的也是这套网页 API，
     * 见它的 `alicloud_cs.ts`：`user.aliyundrive.com/v2/user/get` 等）。
     * 端点与参数照 AList 网页版驱动（`drivers/aliyundrive`）逐字对齐。
     */
    const val WEB_BASE = "https://api.alipan.com"

    /**
     * 网页 API：用户信息（`/v2/user/get`，`{}` 空体）。
     *
     * **盘 id 在这里**，不在 [WEB_PERSONAL_INFO_URL]。OpenList 网页驱动取的就是
     * 响应根上的 `default_drive_id`（同时可能有 `resource_drive_id` / `backup_drive_id`）。
     * `get_personal_info` 多半只有容量和会员权益，没有盘 id —— 只盯它会误报
     * 「账号没有可用的盘」。
     */
    const val WEB_USER_URL = "$WEB_BASE/v2/user/get"

    /**
     * 同一份用户信息的另一台主机。
     *
     * 小雅 / aligo 从这里读 `resource_drive_id` 与 `backup_drive_id`。
     * `api.alipan.com/v2/user/get` 已经带了盘 id 时不会再打这一下；
     * 只有那边没有可用盘 id 时才兜底。
     */
    const val WEB_USER_DRIVE_URL = "https://user.aliyundrive.com/v2/user/get"

    /** 网页 API：容量与权益（通常没有盘 id，只作盘 id 的旧形态兜底） */
    const val WEB_PERSONAL_INFO_URL = "$WEB_BASE/v2/databox/get_personal_info"

    /** 网页 API：列目录（`/v2/file/list`，marker 分页，字段名与 OpenAPI 一致） */
    const val WEB_FILE_LIST_URL = "$WEB_BASE/v2/file/list"

    /** 网页 API：取下载直链（`/v2/file/get_download_url`，响应字段 `url`） */
    const val WEB_DOWNLOAD_URL = "$WEB_BASE/v2/file/get_download_url"

    /**
     * 网页令牌的**免凭据**刷新端点（AList 网页版驱动的刷新地址）。
     *
     * 网页扫码令牌刷新只需 `{grant_type: "refresh_token", refresh_token}`，
     * 不需要任何 client 凭据（实测：假 RT 回 `InvalidParameter.RefreshToken`，
     * 说明请求形态被完整接受、只是 RT 不存在）。
     * 注意别与开放平台的 `openapi.alipan.com/oauth/access_token` 混淆 ——
     * 那边实测必须带 client_id（缺它回 `BadRequest: client_id 不能为空`），
     * 1.1.64 曾误把那边当「免凭据端点」，实测打脸后改到这里。
     */
    const val WEB_TOKEN_URL = "https://auth.alipan.com/v2/account/token"

    /** 刷新令牌的官方地址 */
    fun tokenUrl(): String = TOKEN_URL

    /**
     * OpenList 在线接口的完整 URL（按令牌来源选端点）。
     *
     * 参数与 OpenList 源码逐字一致（`refresh_ui` / `server_use` / `driver_txt`）——
     * 名字怪但改不得：服务端按它们分派驱动。`driver_txt=alicloud_qr` 表示
     * 「旧扫码授权」那一档；扫码登录（网页侧）走 `/alicloud2` 且**不带** driver_txt。
     *
     * [base] 由调用方从 [BASES] 里选（通常先国内站，失败再换站）。
     */
    fun renewApiUrl(
        refreshToken: String,
        source: AliyunTokenSource,
        base: String = BASES.first(),
    ): String = when (source) {
        AliyunTokenSource.OA -> buildString {
            append(base).append("/alicloud/renewapi")
            append("?refresh_ui=").append(enc(refreshToken))
            append("&server_use=true")
            append("&driver_txt=alicloud_qr")
        }
        AliyunTokenSource.CS -> buildString {
            append(base).append("/alicloud2/renewapi")
            append("?refresh_ui=").append(enc(refreshToken))
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    const val UA = "WindTone/1.1 (Android; aliyundrive)"
}

/** HTTP 小工具：零依赖，与工程其他网络层同一风格 */
internal object AliyunHttp {

    fun postJson(url: String, body: JSONObject, ua: String): JSONObject =
        read(url, ua, method = "POST", body = body.toString(), contentType = "application/json; charset=utf-8")

    fun getJson(url: String, ua: String): JSONObject =
        read(url, ua, method = "GET", body = null, contentType = null)

    /** 带额外请求头 + 自定义体的请求（passport 扫码接口要求浏览器形态的头，见 AliyunQrLogin） */
    fun request(
        url: String,
        ua: String,
        method: String,
        body: String?,
        contentType: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): JSONObject = read(
        url,
        ua,
        method = method,
        body = body,
        contentType = contentType,
        extraHeaders = extraHeaders,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    /** 带额外请求头的 GET */
    fun getJson(
        url: String,
        ua: String,
        extraHeaders: Map<String, String>,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): JSONObject = read(
        url,
        ua,
        method = "GET",
        body = null,
        contentType = null,
        extraHeaders = extraHeaders,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    /** 表单 POST（`application/x-www-form-urlencoded`，passport query.do 用它） */
    fun postForm(
        url: String,
        form: Map<String, String>,
        ua: String,
        extraHeaders: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): JSONObject = read(
        url,
        ua,
        method = "POST",
        body = form.entries.joinToString("&") { (k, v) ->
            AliyunEndpoints.enc(k) + "=" + AliyunEndpoints.enc(v)
        },
        contentType = "application/x-www-form-urlencoded; charset=UTF-8",
        extraHeaders = extraHeaders,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    private fun read(
        url: String,
        ua: String,
        method: String,
        body: String?,
        contentType: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 20_000,
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", ua)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                if (contentType != null) setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("空响应（HTTP $code）")
            // 阿里 passport 出错时偶尔回 HTML 错误页，JSON 解析失败的报错要可读
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("响应不是 JSON（HTTP $code）")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}

/**
 * 阿里云盘 OpenAPI 错误 → 人话（纯函数，可单测）。
 *
 * 错误响应形如 `{"code":"AccessTokenExpired","message":"..."}`，与 token 端点的
 * 错误字段名不同（那边可能是 `error`/`error_description`），两种都认。
 */
object AliyunErrors {

    /** 需要「换一次 token 再重试」的错误码（与 OpenList 的判据一致，多一个 I400JD） */
    val RETRYABLE = setOf("AccessTokenInvalid", "AccessTokenExpired", "I400JD")

    fun isRetryable(code: String): Boolean = code in RETRYABLE

    fun hint(code: String): String = when (code) {
        "" -> ""
        "AccessTokenExpired", "AccessTokenInvalid", "InvalidAccessToken", "I400JD" ->
            "登录状态已过期，请重新登录"
        "InvalidGrant", "invalid_grant" -> "刷新令牌无效或已失效，请重新获取"
        "InvalidClient", "invalid_client" -> "应用凭据无效（ClientId / ClientSecret）"
        "InvalidParameter", "invalid_parameter" -> "请求参数错误"
        "BadRequest" -> "请求参数无效（多为 drive_id / file_id 为空或不匹配，不是登录问题）"
        "NotFound", "ResourceNotFound" -> "文件或目录不存在（可能已被移动或删除）"
        "AccessDenied", "PermissionDenied", "Forbidden" -> "没有权限访问这个资源"
        "QuotaExceeded", "TooManyRequests", "TooManyRequestsError" -> "请求过于频繁，被限流了，等几分钟再试"
        "ExceedCapacityForbidden" -> "网盘容量超限，播放被限制（需清理或扩容）"
        else -> "阿里云盘返回错误：$code"
    }

    /** API 响应（带 code 字段）→ 人话 */
    fun hint(json: JSONObject): String {
        val code = json.optString("code").ifBlank { json.optString("error") }
        if (code.isNotBlank()) return hint(code)
        return json.optString("message").ifBlank { "请求失败" }
    }
}
