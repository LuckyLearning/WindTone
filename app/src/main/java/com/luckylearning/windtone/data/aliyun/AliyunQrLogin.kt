package com.luckylearning.windtone.data.aliyun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 阿里云盘**扫码授权** —— 直连阿里官方 passport 接口，全程 app 内完成，不跳外部页面，
 * 也不经过任何第三方中转服务。
 *
 * ## 为什么不再走 OpenList-APIPages（1.1.64 的教训）
 *
 * 1.1.58~1.1.63 复刻的是 api.oplist.org 网页工具的三步接口（generate_qr / check_login /
 * get_user_info）。读它的服务端源码（`src/driver/alicloud_cs.ts`）发现：那个服务的
 * 「扫码会话」存在 **Worker 进程的内存 Map 里**（`const loginSessions = new Map()`），
 * 生成二维码和轮询状态的请求一旦落到不同实例，`loginSessions.get(sessionId)` 取不到，
 * 轮询就报 403「会话验证失败」。真机上的表现正是：用户扫码确认后 app 永远停在二维码页
 * （轮询错误被静默吞掉），重新生成的码自然「过期」。
 *
 * 而顺着它的实现往下看会发现：**那三步只是把阿里云官方 passport 接口包了一层**，
 * 服务端没有持有任何密钥。所以 app 完全可以自己直连 —— 会话（ck/t）就在本地，
 * 没有跨实例问题，也没有第三方可用性风险。
 *
 * ## 官方三步（与 OpenList `alicloud_cs` 驱动一致，2026-10 核对源码 + 实测）
 *
 *  1. `GET /newlogin/qrcode/generate.do?appName=aliyun_drive&fromSite=52&…`
 *     → `content.data.codeContent`（要画成二维码的内容）、`content.data.ck`、`content.data.t`
 *  2. `POST /newlogin/qrcode/query.do`（表单：appName、fromSite、ck、t，约 2 秒一次）
 *     → `content.data.qrCodeStatus` ∈ NEW / SCANED / CONFIRMED / EXPIRED；
 *     CONFIRMED 时 `content.data.bizExt`（Base64 的 JSON）里有 `pds_login_result`：
 *     accessToken / refreshToken / expiresIn
 *  3. 拿到 refresh_token 走 [AliyunAuth] 的既有存储与刷新链路（来源标 CS）
 *
 * passport 对请求形态有要求（照抄驱动）：浏览器 UA、`Referer/Origin` 指向
 * passport.alipan.com、`X-Requested-With: XMLHttpRequest`。
 */
object AliyunQrLogin {

    /** 阿里云盘登录护照基址（alipan.com 是改名后的正式域名） */
    const val PASSPORT = "https://passport.alipan.com"

    /**
     * passport 只按浏览器形态放行（驱动里就是 Chrome UA + Referer/Origin），
     * 用 app 自己的 UA 会被当成异常流量。
     */
    const val PASSPORT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 固定参数（与服务端驱动逐字一致）：csrf 令牌与 hsiz 是阿里网页端写死在页面里的
     * 值，服务端驱动硬编码它们照样能生成二维码（实测 200 OK），app 端原样沿用。
     */
    private const val CSRF_TOKEN = "MuSysYVxW5AMGblcOTSKb3"
    private const val HSIZ = "115d9f5f2cf2f87850a93a793aaaecb4"

    /** 一次扫码会话：会话数据（ck/t）由本机持有，不再有「跨实例丢会话」的问题 */
    data class QrSession(
        /** 要渲染成二维码的内容（阿里云盘 App 扫它） */
        val qrContent: String,
        /** 轮询凭据 */
        val ck: String,
        /** 轮询凭据（生成时间戳） */
        val t: String,
    )

    /** 扫码状态（官方 qrCodeStatus：NEW / SCANED / CONFIRMED / EXPIRED） */
    enum class ScanStatus { WAITING, SCANED, CONFIRMED, EXPIRED }

    /** 第 2 步的一次轮询结果；CONFIRMED 时带双令牌 */
    data class QrPoll(
        val status: ScanStatus,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        /** access_token 有效期（秒），0 = 官方没给（按 2 小时兜底） */
        val expiresInSec: Long = 0,
    )

    /** 请求失败的人话原因 */
    class QrLoginException(message: String) : Exception(message)

    // ---------------------------------------------------------------- 端点 URL

    /** 第 1 步：生成二维码（[umidToken] 每次随机，32 位十六进制） */
    fun generateUrl(umidToken: String): String = PASSPORT + "/newlogin/qrcode/generate.do?" +
        listOf(
            "appName" to "aliyun_drive",
            "fromSite" to "52",
            "appEntrance" to "web_default",
            "_csrf_token" to CSRF_TOKEN,
            "umidToken" to umidToken,
            "hsiz" to HSIZ,
            "bizParams" to "taobaoBizLoginFrom=web_default&renderRefer=https://auth.alipan.com/",
            "mainPage" to "false",
            "isMobile" to "false",
            "lang" to "zh_CN",
            "returnUrl" to "",
            "umidTag" to "SERVER",
        ).joinToString("&") { (k, v) -> k + "=" + AliyunEndpoints.enc(v) }

    /** 第 2 步：轮询扫码状态 */
    fun queryUrl(): String = "$PASSPORT/newlogin/qrcode/query.do"

    /** passport 请求的公共头（照抄服务端驱动） */
    fun passportHeaders(): Map<String, String> = mapOf(
        "Referer" to "$PASSPORT/",
        "Origin" to PASSPORT,
        "X-Requested-With" to "XMLHttpRequest",
    )

    // ---------------------------------------------------------------- 两步请求

    /** 第 1 步：生成扫码会话。失败抛 [QrLoginException]。 */
    suspend fun generateQr(): QrSession = withContext(Dispatchers.IO) {
        val json = http {
            AliyunHttp.getJson(
                generateUrl(randomUmidToken()),
                PASSPORT_UA,
                passportHeaders(),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 12_000,
            )
        }
        parseGenerate(json)
    }

    /** 第 2 步：轮询扫码状态。CONFIRMED 时结果里直接带双令牌。失败抛 [QrLoginException]。 */
    suspend fun checkLogin(session: QrSession): QrPoll = withContext(Dispatchers.IO) {
        val json = http {
            AliyunHttp.postForm(
                queryUrl(),
                mapOf(
                    "appName" to "aliyun_drive",
                    "fromSite" to "52",
                    "ck" to session.ck,
                    "t" to session.t,
                ),
                PASSPORT_UA,
                passportHeaders(),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 12_000,
            )
        }
        parseQuery(json)
    }

    /** [parseGenerate]：只做字段解析，单测可直接喏 JSON */
    fun parseGenerate(json: JSONObject): QrSession {
        val content = contentOrThrow(json, "生成二维码")
        val data = content.optJSONObject("data")
            ?: throw QrLoginException("阿里云盘没有返回二维码数据")
        val code = data.optString("codeContent")
        val ck = data.optString("ck")
        if (code.isBlank() || ck.isBlank()) {
            throw QrLoginException("阿里云盘没有返回二维码内容，请稍后重试")
        }
        return QrSession(
            qrContent = code,
            ck = ck,
            t = data.optString("t"),
        )
    }

    /** [parseQuery]：只做字段解析，单测可直接喏 JSON */
    fun parseQuery(json: JSONObject): QrPoll {
        val content = contentOrThrow(json, "查询扫码状态")
        val data = content.optJSONObject("data")
            ?: throw QrLoginException("阿里云盘没有返回扫码状态")
        val status = parseStatus(data.optString("qrCodeStatus", "NEW"))
        if (status != ScanStatus.CONFIRMED) return QrPoll(status)
        // 确认成功：bizExt（Base64 的 JSON）里才有令牌，错过这一次就要重新扫码
        val tokens = decodeBizExtTokens(data.optString("bizExt"))
            ?: throw QrLoginException("登录已确认，但没能取出令牌（响应异常），请重新扫码")
        return QrPoll(
            status = ScanStatus.CONFIRMED,
            accessToken = tokens.first,
            refreshToken = tokens.second,
            expiresInSec = tokens.third,
        )
    }

    // ---------------------------------------------------------------- 纯函数（单测用）

    /**
     * 官方状态字符串 → 枚举。
     *
     * 官方值是 `NEW / SCANED / CONFIRMED / EXPIRED`；未知值当 WAITING 处理
     * （官方加新状态不至于把轮询打断）。
     */
    fun parseStatus(raw: String): ScanStatus = when (raw.trim().uppercase()) {
        "SCANED" -> ScanStatus.SCANED
        "CONFIRMED" -> ScanStatus.CONFIRMED
        "EXPIRED" -> ScanStatus.EXPIRED
        else -> ScanStatus.WAITING // NEW 与未知值
    }

    /** 该状态是否已经出了结果（成功或过期），调用方可停止轮询 */
    fun isTerminal(status: ScanStatus): Boolean =
        status == ScanStatus.CONFIRMED || status == ScanStatus.EXPIRED

    /** 轮询间隔（毫秒）：网页端大约 1.5~2s，取 2s 别刷太勤 */
    const val POLL_INTERVAL_MS = 2_000L

    /**
     * bizExt（Base64 的 JSON）→ (accessToken, refreshToken, expiresInSec)。
     * 解不开 / 缺字段都返回 null，由调用方决定怎么报错。
     */
    fun decodeBizExtTokens(bizExt: String): Triple<String, String, Long>? {
        if (bizExt.isBlank()) return null
        val decoded = runCatching {
            String(
                android.util.Base64.decode(bizExt, android.util.Base64.DEFAULT),
                Charsets.UTF_8,
            )
        }.getOrNull() ?: return null
        return parsePdsLogin(decoded)
    }

    /** 已解码的 pds_login_result JSON 文本 → 三元组；缺任一令牌都算失败 */
    fun parsePdsLogin(jsonText: String): Triple<String, String, Long>? {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val login = root.optJSONObject("pds_login_result") ?: return null
        val at = login.optString("accessToken")
        val rt = login.optString("refreshToken")
        if (at.isBlank() || rt.isBlank()) return null
        return Triple(at, rt, login.optLong("expiresIn", 7_200L))
    }

    /** 每次生成都换一个 umidToken（驱动里就是随机 UUID 去横线） */
    fun randomUmidToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    // ---------------------------------------------------------------- 内部

    /** 统一拆 content：hasError / content.success=false 都转成人话异常 */
    private fun contentOrThrow(json: JSONObject, what: String): JSONObject {
        val content = json.optJSONObject("content")
        val ok = !json.optBoolean("hasError", false) && content?.optBoolean("success", false) == true
        if (!ok) {
            val e = content?.optString("error").orEmpty()
            throw QrLoginException(
                if (e.isNotBlank()) "阿里云盘：$e" else "${what}失败（阿里云盘返回异常）",
            )
        }
        return content
    }

    private inline fun http(block: () -> JSONObject): JSONObject =
        try {
            block()
        } catch (e: QrLoginException) {
            throw e
        } catch (e: Exception) {
            throw QrLoginException(e.message ?: "网络请求失败")
        }
}
