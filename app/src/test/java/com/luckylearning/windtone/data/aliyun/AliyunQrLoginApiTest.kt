package com.luckylearning.windtone.data.aliyun

import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 阿里官方 passport 扫码接口的**响应解析**与 URL 拼装（Robolectric：解析用到
 * org.json 与 android.util.Base64）。
 *
 * 接口形态与 OpenList `alicloud_cs` 驱动逐字对齐（2026-10 核对源码 + curl 实测）：
 *  · `GET /newlogin/qrcode/generate.do?…`
 *     → `{hasError:false, content:{success:true, data:{codeContent, ck, t, resultCode}}}`
 *  · `POST /newlogin/qrcode/query.do`（表单 ck/t）
 *     → `{content:{success:true, data:{qrCodeStatus, bizExt}}}`；
 *     CONFIRMED 时 bizExt（Base64 JSON）的 `pds_login_result` 里有双令牌。
 *
 * 状态字符串映射、终态判定、umidToken 生成的纯函数用例在 [AliyunScanRulesTest]；
 * 这里管 JSON 解析、错误分流与 bizExt 解码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AliyunQrLoginApiTest {

    // ---------------------------------------------------------------- generate.do

    @Test
    fun parseGenerateExtractsQrContentAndPollingCredentials() {
        val session = AliyunQrLogin.parseGenerate(
            JSONObject(
                """{"hasError":false,"content":{"success":true,"data":{""" +
                    """"codeContent":"https://passport.aliyundrive.com/qrcodeCheck.htm?lgToken=abc",""" +
                    """"ck":"ck-123","t":1792745743543,"resultCode":200,"processFinished":false}}}""",
            ),
        )
        assertEquals("https://passport.aliyundrive.com/qrcodeCheck.htm?lgToken=abc", session.qrContent)
        assertEquals("ck-123", session.ck)
        assertEquals("1792745743543", session.t) // t 是数字，也要原样带回表单
    }

    @Test
    fun parseGenerateFailsOnHasError() {
        try {
            AliyunQrLogin.parseGenerate(
                JSONObject("""{"hasError":true,"content":{"success":false,"error":"请求异常"}}"""),
            )
            throw AssertionError("hasError=true 应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertEquals("阿里云盘：请求异常", e.message)
        }
    }

    @Test
    fun parseGenerateRejectsMissingCodeContent() {
        try {
            AliyunQrLogin.parseGenerate(
                JSONObject("""{"hasError":false,"content":{"success":true,"data":{"ck":"ck-1"}}}"""),
            )
            throw AssertionError("缺 codeContent 应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertTrue(e.message!!.contains("二维码内容"))
        }
    }

    // ---------------------------------------------------------------- query.do

    @Test
    fun parseQueryMapsWaitingAndScaned() {
        val waiting = AliyunQrLogin.parseQuery(
            JSONObject(
                """{"hasError":false,"content":{"success":true,""" +
                    """"data":{"qrCodeStatus":"NEW","resultCode":100}}}""",
            ),
        )
        assertEquals(AliyunQrLogin.ScanStatus.WAITING, waiting.status)
        assertNull(waiting.accessToken)

        val scaned = AliyunQrLogin.parseQuery(
            JSONObject(
                """{"hasError":false,"content":{"success":true,""" +
                    """"data":{"qrCodeStatus":"SCANED","resultCode":200}}}""",
            ),
        )
        assertEquals(AliyunQrLogin.ScanStatus.SCANED, scaned.status)
    }

    @Test
    fun parseQueryExtractsTokensFromBizExtOnConfirmed() {
        // bizExt = base64({"pds_login_result":{"accessToken":..,"refreshToken":..,"expiresIn":..}})
        val bizExt = Base64.encodeToString(
            """{"pds_login_result":{"accessToken":"at-1","refreshToken":"rt-1","expiresIn":7200}}"""
                .toByteArray(),
            Base64.NO_WRAP,
        )
        val poll = AliyunQrLogin.parseQuery(
            JSONObject(
                """{"hasError":false,"content":{"success":true,"data":{""" +
                    """"qrCodeStatus":"CONFIRMED","resultCode":300,"bizExt":"$bizExt"}}}""",
            ),
        )
        assertEquals(AliyunQrLogin.ScanStatus.CONFIRMED, poll.status)
        assertEquals("at-1", poll.accessToken)
        assertEquals("rt-1", poll.refreshToken)
        assertEquals(7200L, poll.expiresInSec)
    }

    @Test
    fun parseQueryFailsWhenConfirmedWithoutTokens() {
        // 确认成功却取不出令牌：绝不能当「登录完成」存半截状态 —— 必须报错让人重新扫
        try {
            AliyunQrLogin.parseQuery(
                JSONObject(
                    """{"hasError":false,"content":{"success":true,""" +
                        """"data":{"qrCodeStatus":"CONFIRMED"}}}""",
                ),
            )
            throw AssertionError("CONFIRMED 但没有 bizExt 应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertTrue(e.message!!.contains("重新扫码"))
        }
    }

    @Test
    fun parseQueryToleratesExpiredStatus() {
        val poll = AliyunQrLogin.parseQuery(
            JSONObject(
                """{"hasError":false,"content":{"success":true,""" +
                    """"data":{"qrCodeStatus":"EXPIRED","resultCode":0}}}""",
            ),
        )
        assertEquals(AliyunQrLogin.ScanStatus.EXPIRED, poll.status)
        assertNull(poll.accessToken)
    }

    // ---------------------------------------------------------------- bizExt 解码

    @Test
    fun decodeBizExtHandlesPaddedAndWhitespaceInput() {
        val json = """{"pds_login_result":{"accessToken":"a","refreshToken":"r"}}"""
        val padded = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
        assertEquals("a", AliyunQrLogin.decodeBizExtTokens(padded)!!.first)
        assertEquals("r", AliyunQrLogin.decodeBizExtTokens(padded)!!.second)
        // DEFAULT 会自动处理换行
        assertEquals("a", AliyunQrLogin.decodeBizExtTokens(padded + "\n")!!.first)
    }

    @Test
    fun decodeBizExtReturnsNullOnGarbageOrMissingTokens() {
        assertNull(AliyunQrLogin.decodeBizExtTokens(""))
        assertNull(AliyunQrLogin.decodeBizExtTokens("not-base64!!!"))
        val noTokens = Base64.encodeToString("""{"pds_login_result":{}}""".toByteArray(), Base64.NO_WRAP)
        assertNull(AliyunQrLogin.decodeBizExtTokens(noTokens))
        val noLogin = Base64.encodeToString("""{"foo":1}""".toByteArray(), Base64.NO_WRAP)
        assertNull(AliyunQrLogin.decodeBizExtTokens(noLogin))
    }

    @Test
    fun parsePdsLoginDefaultsExpiryToTwoHours() {
        val t = AliyunQrLogin.parsePdsLogin(
            """{"pds_login_result":{"accessToken":"a","refreshToken":"r"}}""",
        )!!
        assertEquals(7_200L, t.third)
    }

    // ---------------------------------------------------------------- URL 拼装

    @Test
    fun generateUrlCarriesFixedParamsAndEscapesBizParams() {
        val url = AliyunQrLogin.generateUrl("umid-1")
        assertTrue(url.startsWith("https://passport.alipan.com/newlogin/qrcode/generate.do?"))
        assertTrue(url.contains("appName=aliyun_drive"))
        assertTrue(url.contains("fromSite=52"))
        assertTrue(url.contains("umidToken=umid-1"))
        // bizParams 里的 & 与 = 必须被转义，否则会被当成独立的查询参数
        assertTrue(url.contains("bizParams=taobaoBizLoginFrom%3Dweb_default%26renderRefer%3Dhttps%3A%2F%2Fauth.alipan.com%2F"))
        assertTrue(url.contains("umidTag=SERVER"))
    }

    @Test
    fun queryUrlIsPassportQueryEndpoint() {
        assertEquals("https://passport.alipan.com/newlogin/qrcode/query.do", AliyunQrLogin.queryUrl())
    }

    @Test
    fun passportRequestsCarryBrowserFormHeaders() {
        val h = AliyunQrLogin.passportHeaders()
        assertEquals("https://passport.alipan.com/", h["Referer"])
        assertEquals("https://passport.alipan.com", h["Origin"])
        assertEquals("XMLHttpRequest", h["X-Requested-With"])
        // passport 按浏览器形态放行，UA 不能是 app 自己的
        assertTrue(AliyunQrLogin.PASSPORT_UA.startsWith("Mozilla/5.0"))
    }
}
