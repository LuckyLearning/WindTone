package com.luckylearning.windtone.data.aliyun

import android.content.Context
import com.luckylearning.windtone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
 *
 * 一条硬约束：两个接口返回的 refresh_token 都是**轮换**的（旧的随即失效），
 * 成功后必须立刻保存新的。
 */
class AliyunAuth(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("aliyundrive_auth", Context.MODE_PRIVATE)

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

    fun isLoggedIn(): Boolean = currentToken() != null

    fun signOut() {
        sp.edit().clear().apply()
    }

    /**
     * 粘贴 refresh_token 登录。
     *
     * 不直接存粘贴值：先用它换一次新 token，成功才算登录 —— 这样能当场发现
     * 「粘贴错了 / 令牌属于别的应用 / 已失效」，而不是等到播放时才撞 401。
     */
    suspend fun applyRefreshToken(pasted: String): AliyunExchange = withContext(Dispatchers.IO) {
        val trimmed = pasted.trim()
        if (trimmed.isBlank()) return@withContext AliyunExchange.Failed("粘贴的内容是空的")
        refresh(trimmed)
    }

    /**
     * 取一个可用 token：快过期就换新的。
     *
     * 返回 null = 没登录（调用方据此把界面切回未登录态）。
     * 刷新失败时保留旧 token 返回：让上层拿到一次明确的 401 后再引导重登，
     * 直接清空会把「网络抖一下」误判成「被登出」，用户要重新粘贴令牌，代价太大。
     */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext null
        if (!token.isExpiring()) return@withContext token.accessToken
        when (refresh(token.refreshToken)) {
            is AliyunExchange.Ready -> currentToken()?.accessToken ?: token.accessToken
            is AliyunExchange.Failed -> token.accessToken
        }
    }

    /**
     * 强制换一次 token（接口回 AccessTokenInvalid 时用）。
     *
     * 不判断过期时间 —— 服务端说了无效就是无效（多机同时用、令牌被顶掉等）。
     */
    suspend fun forceRefresh(): Boolean = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext false
        refresh(token.refreshToken) is AliyunExchange.Ready
    }

    fun save(token: AliyunToken) {
        sp.edit()
            .putString(KEY_ACCESS, token.accessToken)
            .putString(KEY_REFRESH, token.refreshToken)
            .putLong(KEY_EXPIRES_AT, token.expiresAt)
            .apply()
    }

    private fun refresh(refreshToken: String): AliyunExchange {
        if (refreshToken.isBlank()) return AliyunExchange.Failed("缺少刷新令牌，请重新获取")
        // 优先用自己凭据直连；没有凭据、或直连失败（令牌不属于本应用）时走 OpenList 在线接口。
        val direct = if (hasClientCredentials()) refreshViaOfficial(refreshToken) else null
        val result = when {
            direct is AliyunExchange.Ready -> direct
            else -> refreshViaOpenList(refreshToken)
        }
        if (result is AliyunExchange.Ready) save(result.token)
        // 两边都失败时把两句话拼起来：用户才知道「是令牌不行还是网络不行」
        return if (result is AliyunExchange.Failed && direct is AliyunExchange.Failed) {
            AliyunExchange.Failed("${result.reason}；直连官方接口也失败：${direct.reason}")
        } else {
            result
        }
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

    /** 路径二：OpenList 在线接口（不需要自己的凭据） */
    private fun refreshViaOpenList(refreshToken: String): AliyunExchange {
        val json = runCatching {
            AliyunHttp.getJson(AliyunEndpoints.renewApiUrl(refreshToken), AliyunEndpoints.UA)
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

    /** OpenList 在线刷新接口（代持 client_secret，只要 refresh_token） */
    const val RENEW_API = "https://api.oplist.org/alicloud/renewapi"

    /** OpenList 的令牌获取页（浏览器打开，扫码授权后显示 refresh_token） */
    const val TOKEN_TOOL_URL = "https://api.oplist.org"

    /** 刷新令牌的官方地址 */
    fun tokenUrl(): String = TOKEN_URL

    /**
     * OpenList 在线接口的完整 URL。
     *
     * 三个查询参数与 OpenList 源码逐字一致（`refresh_ui` / `server_use` / `driver_txt`）——
     * 名字怪但改不得：服务端按它们分派驱动。`driver_txt=alicloud_qr` 表示
     * 「阿里云盘扫码授权」那一档；TV 授权是 `alicloud_tv`，本应用不涉及。
     */
    fun renewApiUrl(refreshToken: String): String = buildString {
        append(RENEW_API)
        append("?refresh_ui=").append(enc(refreshToken))
        append("&server_use=true")
        append("&driver_txt=alicloud_qr")
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    const val UA = "WindTone/1.1 (Android; aliyundrive)"
}

/** HTTP 小工具：两种方法、零依赖，与工程其他网络层同一风格 */
internal object AliyunHttp {

    fun postJson(url: String, body: JSONObject, ua: String): JSONObject =
        read(url, ua, method = "POST", body = body)

    fun getJson(url: String, ua: String): JSONObject =
        read(url, ua, method = "GET", body = null)

    /** 带额外请求头的 GET（扫码授权需要 `X-Client-Fingerprint`，见 AliyunQrLogin） */
    fun getJson(url: String, ua: String, extraHeaders: Map<String, String>): JSONObject =
        read(url, ua, method = "GET", body = null, extraHeaders = extraHeaders)

    private fun read(
        url: String,
        ua: String,
        method: String,
        body: JSONObject?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = method
            setRequestProperty("User-Agent", ua)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("空响应（HTTP $code）")
            return JSONObject(text)
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
