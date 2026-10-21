package com.luckylearning.windtone.data.baidu

import android.content.Context
import com.luckylearning.windtone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 设备码：一次扫码授权的会话凭据 */
data class BaiduDeviceCode(
    val deviceCode: String,
    val userCode: String,
    /** 百度返回的二维码图片地址（可能为空：那时只能显示 user_code + 授权网址） */
    val qrcodeUrl: String,
    val verificationUrl: String,
    /** 轮询间隔（秒），百度要求不低于 5 */
    val intervalSec: Int,
    val expiresInSec: Int,
)

/** 一次成功授权拿到的令牌 */
data class BaiduToken(
    val accessToken: String,
    val refreshToken: String,
    /** 绝对过期时间（毫秒） */
    val expiresAt: Long,
) {
    fun isExpiring(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt - 60_000L
}

/** 轮询结果。设备码模式下「用户还没扫」是正常状态，不是错误，必须单独成一档。 */
sealed interface DevicePoll {
    /** 用户还没确认，继续等 */
    data object Pending : DevicePoll
    /** 轮得太快，百度要求放慢 */
    data object SlowDown : DevicePoll
    /** 设备码过期，需要重新生成二维码 */
    data object Expired : DevicePoll
    /** 用户在手机上拒绝了授权 */
    data object Denied : DevicePoll
    data class Ready(val token: BaiduToken) : DevicePoll
}

/**
 * 百度网盘 OAuth（设备码模式）—— v1.1.39。
 *
 * 为什么选设备码而不是授权码模式：本应用没有自己的后端，SecretKey 只能放在客户端
 * （百度允许客户端持有 Secret），而授权码模式要求 redirect_uri 收 code。
 * 设备码模式正是为「输入受限的设备（车机、TV）」设计的：客户端请求 device_code，
 * 把 `qrcode_url` 画成二维码；用户用手机百度网盘 App 扫码确认；客户端轮询换 token。
 * 好处是**车机上不需要登录框、不需要键盘**，这恰好是本应用的主场景。
 *
 * 两条硬约束（都来自百度文档，写在这儿防止以后改坏）：
 *  1. 轮询间隔**不得低于 5 秒**，否则会被判 slow_down 甚至限流；
 *  2. 用户扫码确认后不要立刻切走 —— 授权状态要几秒才最终落库。
 */
class BaiduAuth(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("baidu_auth", Context.MODE_PRIVATE)

    /** 当前构建是否带了 AppKey / SecretKey（缺任何一个都没法发起授权） */
    fun isConfigured(): Boolean =
        BuildConfig.BAIDU_APP_KEY.isNotBlank() && BuildConfig.BAIDU_SECRET_KEY.isNotBlank()

    fun currentToken(): BaiduToken? {
        val at = sp.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return BaiduToken(
            accessToken = at,
            refreshToken = sp.getString(KEY_REFRESH, "").orEmpty(),
            expiresAt = sp.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    fun isLoggedIn(): Boolean = currentToken() != null

    fun signOut() {
        sp.edit().clear().apply()
    }

    /** 申请设备码 */
    suspend fun requestDeviceCode(): Result<BaiduDeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            require(isConfigured()) { "当前构建未配置百度网盘应用凭据" }
            val params = mapOf(
                "response_type" to "device_code",
                "client_id" to BuildConfig.BAIDU_APP_KEY,
                "scope" to "basic,netdisk",
            )
            val json = get(DEVICE_CODE_URL + "?" + params.toQuery())
            if (json.optInt("error_code", 0) != 0) {
                error("申请授权码失败：${json.optString("error_description").ifBlank { json.optString("error") }}")
            }
            val deviceCode = json.optString("device_code")
            if (deviceCode.isBlank()) error("授权码响应异常（缺少 device_code）")
            BaiduDeviceCode(
                deviceCode = deviceCode,
                userCode = json.optString("user_code"),
                qrcodeUrl = json.optString("qrcode_url"),
                verificationUrl = json.optString("verification_url"),
                // 百度文档要求 ≥5 秒；返回更小时按 5 秒兜底
                intervalSec = json.optInt("interval", 5).coerceAtLeast(5),
                expiresInSec = json.optInt("expires_in", 300),
            )
        }
    }

    /** 轮询一次（调用方负责按 [BaiduDeviceCode.intervalSec] 控制节奏） */
    suspend fun poll(deviceCode: String): DevicePoll = withContext(Dispatchers.IO) {
        val params = mapOf(
            "grant_type" to "device_token",
            "code" to deviceCode,
            "client_id" to BuildConfig.BAIDU_APP_KEY,
            "client_secret" to BuildConfig.BAIDU_SECRET_KEY,
        )
        val json = runCatching { get(TOKEN_URL + "?" + params.toQuery()) }.getOrElse {
            // 网络抖动不该让整次授权失败：当作「还没好」，下一轮接着轮
            return@withContext DevicePoll.Pending
        }
        val error = json.optString("error")
        when (error) {
            "" -> {
                val token = json.optString("access_token")
                if (token.isBlank()) return@withContext DevicePoll.Pending
                DevicePoll.Ready(
                    BaiduToken(
                        accessToken = token,
                        refreshToken = json.optString("refresh_token"),
                        expiresAt = System.currentTimeMillis() +
                            json.optLong("expires_in", 2_592_000L) * 1000L,
                    ),
                )
            }
            "authorization_pending", "authorization_waiting" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "expired_token" -> DevicePoll.Expired
            "access_denied" -> DevicePoll.Denied
            else -> DevicePoll.Pending
        }
    }

    fun save(token: BaiduToken) {
        sp.edit()
            .putString(KEY_ACCESS, token.accessToken)
            .putString(KEY_REFRESH, token.refreshToken)
            .putLong(KEY_EXPIRES_AT, token.expiresAt)
            .apply()
    }

    /**
     * 取一个可用 token：快过期就先用 refresh_token 换新的。
     *
     * 返回 null 表示「没登录 / 刷新也失败，需要重新扫码」—— 调用方据此把界面切回未登录态，
     * 而不是拿一个必然 401 的 token 去请求。
     */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext null
        if (!token.isExpiring()) return@withContext token.accessToken
        val refreshed = runCatching { refresh(token.refreshToken) }.getOrNull()
        if (refreshed != null) {
            save(refreshed)
            refreshed.accessToken
        } else {
            // 刷新失败：保留登录态但返回旧 token，让上层拿到一次明确的 401 后再引导重登；
            // 直接清空会把「网络抖一下」误判成「被登出」，用户要重新扫码，代价太大。
            token.accessToken
        }
    }

    private fun refresh(refreshToken: String): BaiduToken {
        require(refreshToken.isNotBlank()) { "缺少 refresh_token" }
        val params = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to BuildConfig.BAIDU_APP_KEY,
            "client_secret" to BuildConfig.BAIDU_SECRET_KEY,
        )
        val json = get(TOKEN_URL + "?" + params.toQuery())
        val at = json.optString("access_token")
        if (at.isBlank()) error("刷新令牌失败：${BaiduErrors.hint(json.optString("error"))}")
        return BaiduToken(
            accessToken = at,
            refreshToken = json.optString("refresh_token").ifBlank { refreshToken },
            expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 2_592_000L) * 1000L,
        )
    }

    private fun get(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) throw IOException("空响应（HTTP $code）")
            return JSONObject(text)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun Map<String, String>.toQuery(): String =
        entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private companion object {
        const val DEVICE_CODE_URL = "https://openapi.baidu.com/oauth/2.0/device/code"
        const val TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
        const val UA = "pan.baidu.com"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
    }
}
