package com.luckylearning.windtone.data.aliyun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 阿里云盘**扫码授权**（app 内完成，不跳外部页面）。
 *
 * 背景：OpenList 的阿里驱动只消费已有的 refresh_token，**扫码授权不在驱动里**，
 * 它是 OpenList-APIPages（api.oplist.org）在线服务提供的：后端代持 client 密钥，
 * 前端页面通过三个接口完成「生成二维码 → 轮询扫码状态 → 取回令牌」。
 * 这三个接口是公开 HTTP 端点，本文件在 app 内**原生复刻**同一条流程：
 *
 *  1. `GET /alicloud2/generate_qr`
 *     → `{success, session_id, qr_code_url, expires_in, error}`
 *     `qr_code_url` 是要画成二维码的内容，用户用阿里云盘 App 扫它。
 *  2. `GET /alicloud2/check_login?session_id=<sid>`（约 2 秒一次）
 *     → `{success, status, error}`，status ∈ WAITING / SCANED / CONFIRMED / EXPIRED
 *  3. `GET /alicloud2/get_user_info?session_id=<sid>`（CONFIRMED 后调一次）
 *     → `{success, user_info:{nick_name, user_id}, access_token, refresh_token, error}`
 *
 * 请求需带 `X-Client-Fingerprint` 头（服务端用它把生成会话与轮询客户端绑定）；
 * 网页端用 canvas 指纹的 base36 串，app 端生成一个稳定随机串即可，格式不限。
 *
 * 拿到 refresh_token 后走 [AliyunAuth] 的既有存储与刷新链路（含轮换语义）。
 */
object AliyunQrLogin {

    /** 扫码会话的一次生成结果 */
    data class QrSession(
        val sessionId: String,
        /** 要渲染成二维码的内容（阿里云盘 App 扫它） */
        val qrContent: String,
        /** 会话有效期（秒），0 = 服务端没给 */
        val expiresInSec: Long,
        /** 生成它的服务基址 —— 后续轮询 / 取令牌必须打同一个站（见 [BASES]） */
        val base: String = BASE,
    )

    /** 扫码状态（与服务端字符串一一对应，详见 [ScanStatus]） */
    enum class ScanStatus { WAITING, SCANED, CONFIRMED, EXPIRED }

    /** 第 3 步的返回：昵称 + 双令牌 */
    data class QrResult(
        val nickname: String,
        val accessToken: String,
        val refreshToken: String,
        /** access_token 有效期（秒），0 = 服务端没给（按 2 小时兜底） */
        val expiresInSec: Long,
    )

    /** 请求失败的人话原因 */
    class QrLoginException(message: String) : Exception(message)

    // ---------------------------------------------------------------- 端点 URL

    /**
     * 授权服务基址候选，**国内站优先**。
     *
     * OpenList 同时部署了两个站：`api.oplist.org.cn`（腾讯 EdgeOne 中国大陆节点）
     * 与 `api.oplist.org`（全球，Cloudflare）。实测后者在国内移动网络下经常要 6 秒以上、
     * 甚至握不上手 —— 用户看到的就是「正在生成二维码…」一直转。所以先试 `.cn`，
     * 失败再退到全球站。
     *
     * ⚠️ 三步（生成 / 轮询 / 取令牌）必须打**同一个域名**：`session_id` 存在服务端
     * 会话存储里，跨站查不到。所以选中的 base 会随 [QrSession] 一起带给后续两步。
     */
    val BASES = listOf("https://api.oplist.org.cn", "https://api.oplist.org")

    /** 默认基址（全球站）：仅作兼容引用，实际请求走 [BASES] 逐个尝试 */
    const val BASE = "https://api.oplist.org"

    fun generateUrl(base: String): String = "$base/alicloud2/generate_qr"

    fun checkUrl(base: String, sessionId: String): String =
        "$base/alicloud2/check_login?session_id=${AliyunEndpoints.enc(sessionId)}"

    fun userInfoUrl(base: String, sessionId: String): String =
        "$base/alicloud2/get_user_info?session_id=${AliyunEndpoints.enc(sessionId)}"

    /** 服务端要求的会话绑定头 */
    const val HEADER_FINGERPRINT = "X-Client-Fingerprint"

    // ---------------------------------------------------------------- 三步请求

    /**
     * 第 1 步：生成扫码会话（网络请求，必须在 IO 线程）。失败抛 [QrLoginException]。
     *
     * 逐个试 [BASES]：一个域名连不上不能把整个授权堵死。每个请求用短超时
     * （连接 6s / 读 10s），失败就换下一个 —— 两个都失败才报错，错误里带上两边原因。
     */
    suspend fun generateQr(fingerprint: String): QrSession = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for (base in BASES) {
            try {
                val json = http {
                    AliyunHttp.getJson(
                        generateUrl(base),
                        AliyunEndpoints.UA,
                        mapOf(HEADER_FINGERPRINT to fingerprint),
                        connectTimeoutMs = 6_000,
                        readTimeoutMs = 10_000,
                    )
                }
                return@withContext parseGenerate(json).copy(base = base)
            } catch (e: QrLoginException) {
                failures += "${base.substringAfter("://")}：${e.message}"
            }
        }
        throw QrLoginException("连不上授权服务器（已试 ${BASES.size} 个站）：" + failures.joinToString("；"))
    }

    /** [parseGenerate]：只做字段解析，单测可直接喏 JSON */
    fun parseGenerate(json: JSONObject): QrSession {
        if (!json.optBoolean("success", false)) {
            throw QrLoginException(json.optString("error").ifBlank { "生成二维码失败" })
        }
        val sid = json.optString("session_id")
        val qr = json.optString("qr_code_url")
        if (sid.isBlank() || qr.isBlank()) {
            throw QrLoginException("服务端没有返回二维码（响应异常）")
        }
        return QrSession(
            sessionId = sid,
            qrContent = qr,
            expiresInSec = json.optLong("expires_in", 0L),
        )
    }

    /** 第 2 步：轮询扫码状态。失败抛 [QrLoginException]。 */
    suspend fun checkLogin(session: QrSession, fingerprint: String): ScanStatus =
        withContext(Dispatchers.IO) {
            val json = http {
                AliyunHttp.getJson(
                    checkUrl(session.base, session.sessionId),
                    AliyunEndpoints.UA,
                    mapOf(HEADER_FINGERPRINT to fingerprint),
                    connectTimeoutMs = 6_000,
                    readTimeoutMs = 10_000,
                )
            }
            if (!json.optBoolean("success", false)) {
                throw QrLoginException(json.optString("error").ifBlank { "查询扫码状态失败" })
            }
            parseStatus(json.optString("status"))
        }

    /** 第 3 步：取回令牌。失败抛 [QrLoginException]。 */
    suspend fun fetchResult(session: QrSession, fingerprint: String): QrResult =
        withContext(Dispatchers.IO) {
            val json = http {
                AliyunHttp.getJson(
                    userInfoUrl(session.base, session.sessionId),
                    AliyunEndpoints.UA,
                    mapOf(HEADER_FINGERPRINT to fingerprint),
                    connectTimeoutMs = 6_000,
                    readTimeoutMs = 15_000,
                )
            }
            parseResult(json)
        }

    /** [parseResult]：只做字段解析，单测可直接喏 JSON */
    fun parseResult(json: JSONObject): QrResult {
        if (!json.optBoolean("success", false)) {
            throw QrLoginException(json.optString("error").ifBlank { "获取授权结果失败" })
        }
        val at = json.optString("access_token")
        val rt = json.optString("refresh_token")
        if (at.isBlank() || rt.isBlank()) {
            throw QrLoginException("授权成功但没有拿到令牌（响应异常），请重试或改用手动粘贴")
        }
        val userInfo = json.optJSONObject("user_info")
        return QrResult(
            nickname = userInfo?.optString("nick_name").orEmpty(),
            accessToken = at,
            refreshToken = rt,
            expiresInSec = json.optLong("expires_in", 7_200L),
        )
    }

    // ---------------------------------------------------------------- 纯函数（单测用）

    /**
     * 状态字符串 → 枚举。
     *
     * 服务端大小写是 `WAITING/SCANED/CONFIRMED/EXPIRED`（见 OpenList-APIPages 前端
     * `qr.ts` 的类型注释），这里统一按**忽略大小写**解析，未知值当 WAITING 处理
     * （多一个新状态不至把轮询打断）。
     */
    fun parseStatus(raw: String): ScanStatus = when (raw.trim().uppercase()) {
        "SCANED" -> ScanStatus.SCANED
        "CONFIRMED" -> ScanStatus.CONFIRMED
        "EXPIRED" -> ScanStatus.EXPIRED
        else -> ScanStatus.WAITING
    }

    /** 该状态是否已经出了结果（成功或过期），调用方可停止轮询 */
    fun isTerminal(status: ScanStatus): Boolean =
        status == ScanStatus.CONFIRMED || status == ScanStatus.EXPIRED

    /** 轮询间隔（毫秒）：网页端大约 1.5~2s，取 2s 别刷太勤 */
    const val POLL_INTERVAL_MS = 2_000L

    /**
     * 生成客户端指纹（base36 随机串）。
     *
     * 网页端是 canvas 指纹哈希的 base36；服务端只要求「同一会话用同一个值」，
     * app 端用一个随机串就够 —— 调用方应**一次授权流程内固定同一个值**
     * （生成、轮询、取结果三步都用它），否则服务端可能拒绝轮询请求。
     */
    fun randomFingerprint(): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val bytes = ByteArray(12)
        java.security.SecureRandom().nextBytes(bytes)
        return buildString {
            for (b in bytes) append(alphabet[(b.toInt() and 0xFF) % alphabet.length])
        }
    }

    // ---------------------------------------------------------------- 内部

    private inline fun http(block: () -> JSONObject): JSONObject =
        try {
            block()
        } catch (e: QrLoginException) {
            throw e
        } catch (e: Exception) {
            throw QrLoginException(e.message ?: "网络请求失败")
        }
}
