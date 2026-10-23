package com.luckylearning.windtone.data.aliyun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扫码授权接口的**响应解析**与 URL 拼装（Robolectric：解析用到 org.json）。
 *
 * 接口形态与 OpenList-APIPages 前端 `qr.ts` 对齐（2026-10 核对源码）：
 *  · `GET /alicloud2/generate_qr` → `{success, session_id, qr_code_url, expires_in, error}`
 *  · `GET /alicloud2/check_login?session_id=…` → `{success, status, error}`
 *  · `GET /alicloud2/get_user_info?session_id=…` → `{success, user_info:{nick_name}, access_token, refresh_token, error}`
 *
 * 状态机的字符串→枚举映射、终态判定、指纹生成在纯 JVM 的 [AliyunScanRulesTest]；
 * 这里管 JSON 解析与错误分流。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AliyunQrLoginApiTest {

    @Test
    fun parseGenerateExtractsSessionAndQrContent() {
        val session = AliyunQrLogin.parseGenerate(
            org.json.JSONObject(
                """{"success":true,"session_id":"sid-1",""" +
                    """"qr_code_url":"https://passport.alipan.com/qr?x=1","expires_in":120}""",
            ),
        )
        assertEquals("sid-1", session.sessionId)
        assertEquals("https://passport.alipan.com/qr?x=1", session.qrContent)
        assertEquals(120L, session.expiresInSec)
    }

    @Test
    fun parseGenerateFailsWithServerMessage() {
        try {
            AliyunQrLogin.parseGenerate(org.json.JSONObject("""{"success":false,"error":"请求过于频繁"}"""))
            throw AssertionError("success=false 应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertEquals("请求过于频繁", e.message)
        }
    }

    @Test
    fun parseGenerateRejectsIncompleteResponse() {
        try {
            AliyunQrLogin.parseGenerate(
                org.json.JSONObject("""{"success":true,"session_id":"sid-1"}"""), // 缺 qr_code_url
            )
            throw AssertionError("缺二维码内容应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertTrue(e.message!!.contains("响应异常"))
        }
    }

    @Test
    fun parseResultExtractsTokensAndNickname() {
        val result = AliyunQrLogin.parseResult(
            org.json.JSONObject(
                """{"success":true,"access_token":"at-1","refresh_token":"rt-1",""" +
                    """"user_info":{"nick_name":"阿明","user_id":"u1"},"expires_in":7200}""",
            ),
        )
        assertEquals("at-1", result.accessToken)
        assertEquals("rt-1", result.refreshToken)
        assertEquals("阿明", result.nickname)
        assertEquals(7200L, result.expiresInSec)
    }

    @Test
    fun parseResultRequiresBothTokens() {
        // 缺 refresh_token：绝不能存半截登录态（refresh_token 是轮换的，丢了就没了）
        try {
            AliyunQrLogin.parseResult(
                org.json.JSONObject("""{"success":true,"access_token":"at-1"}"""),
            )
            throw AssertionError("缺 refresh_token 应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertTrue(e.message!!.contains("手动粘贴"))
        }
    }

    @Test
    fun parseResultFailsWithServerMessage() {
        try {
            AliyunQrLogin.parseResult(
                org.json.JSONObject("""{"success":false,"error":"会话不存在"}"""),
            )
            throw AssertionError("success=false 应当抛异常")
        } catch (e: AliyunQrLogin.QrLoginException) {
            assertEquals("会话不存在", e.message)
        }
    }

    @Test
    fun urlsCarrySessionIdAndSelectedBase() {
        assertEquals(
            "https://api.oplist.org.cn/alicloud2/generate_qr",
            AliyunQrLogin.generateUrl("https://api.oplist.org.cn"),
        )
        assertEquals(
            "https://api.oplist.org.cn/alicloud2/check_login?session_id=sid%2F1",
            // sessionId 里有特殊字符也要被正确转义
            AliyunQrLogin.checkUrl("https://api.oplist.org.cn", "sid/1"),
        )
        assertEquals(
            "https://api.oplist.org.cn/alicloud2/get_user_info?session_id=sid-2",
            AliyunQrLogin.userInfoUrl("https://api.oplist.org.cn", "sid-2"),
        )
    }

    @Test
    fun mainlandSiteIsTriedFirst() {
        // 国内移动网络下 .cn（EdgeOne）0.6s，.org（Cloudflare）6s+ 甚至连不上
        assertEquals("https://api.oplist.org.cn", AliyunQrLogin.BASES.first())
        assertEquals(2, AliyunQrLogin.BASES.size)
    }
}
