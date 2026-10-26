package com.luckylearning.windtone.data.aliyun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权层的纯 JVM 用例（不碰 android.* 类）。
 *
 * 端点拼装是「按 OpenList 源码逐字对齐」的契约：参数名写错不会编译失败，
 * 只会在真机上表现为「粘贴令牌后一直失败」，所以这里逐条钉住。
 * 依赖 android.net.Uri / org.json 的用例在 [AliyunAndroidTest]（Robolectric）。
 */
class AliyunAuthTest {

    @Test
    fun `OpenList 在线接口的 URL 参数与源码一致`() {
        val url = AliyunEndpoints.renewApiUrl("abc.def-123", AliyunTokenSource.OA, "https://api.oplist.org")
        assertTrue(url.startsWith("https://api.oplist.org/alicloud/renewapi?"))
        assertTrue("refresh_ui 是 OpenList 的入口参数名，不能改", url.contains("refresh_ui=abc.def-123"))
        assertTrue("server_use=true 表示由服务端代持密钥", url.contains("server_use=true"))
        assertTrue("driver_txt 决定驱动分派，扫码授权档是 alicloud_qr", url.contains("driver_txt=alicloud_qr"))
    }

    @Test
    fun `special chars 会被 URL 编码`() {
        val url = AliyunEndpoints.renewApiUrl("a+b/c=d", AliyunTokenSource.OA, "https://api.oplist.org")
        assertFalse("原始 + 号必须编码，否则服务端会解成空格", url.contains("a+b"))
        assertTrue(url.contains("a%2Bb%2Fc%3Dd"))
    }

    @Test
    fun `直链下载要伪装成网页，否则数据节点会掐连接`() {
        val headers = AliyunEndpoints.downloadHeaders()
        assertEquals("https://www.aliyundrive.com/", headers["Referer"])
        assertTrue(headers.getValue("User-Agent").contains("Mozilla"))
        assertTrue(AliyunEndpoints.isDownloadHost("cn-beijing.aliyundrive.net"))
        assertTrue(AliyunEndpoints.isDownloadHost("cdn.alicloudccp.com"))
        assertFalse(AliyunEndpoints.isDownloadHost("d.pcs.baidu.com"))
        assertFalse(AliyunEndpoints.isDownloadHost(null))
    }

    @Test
    fun `端点常量指向改名后的正式域名`() {
        // 阿里云盘已改名，正式域名是 alipan.com；aliyundrive.com 是旧域名
        assertTrue(AliyunEndpoints.API_BASE.startsWith("https://openapi.alipan.com/"))
        assertTrue(AliyunEndpoints.TOKEN_URL.startsWith("https://openapi.alipan.com/"))
        assertTrue(AliyunEndpoints.TOKEN_URL.endsWith("/oauth/access_token"))
        // 令牌获取页同样国内站优先（与 AliyunEndpoints.BASES 一致）
        assertEquals("https://api.oplist.org.cn", AliyunEndpoints.TOKEN_TOOL_URL)
    }

    @Test
    fun `错误码翻成人话`() {
        assertEquals("登录状态已过期。下一步：到设置 → 在线服务重新扫码授权", AliyunErrors.hint("AccessTokenExpired"))
        assertEquals("刷新令牌无效或已失效。下一步：到设置 → 在线服务重新扫码授权", AliyunErrors.hint("invalid_grant"))
        assertEquals("应用凭据无效（ClientId / ClientSecret）。下一步：核对自建应用配置后重试", AliyunErrors.hint("invalid_client"))
        assertEquals("", AliyunErrors.hint(""))
        assertTrue(AliyunErrors.hint("SomeNewCode").contains("SomeNewCode"))
    }

    @Test
    fun `可重试的错误码与 OpenList 判据一致`() {
        // OpenList 在 requestReturnErrResp 里对这三个码刷新一次再重试
        assertTrue(AliyunErrors.isRetryable("AccessTokenInvalid"))
        assertTrue(AliyunErrors.isRetryable("AccessTokenExpired"))
        assertTrue(AliyunErrors.isRetryable("I400JD"))
        assertFalse(AliyunErrors.isRetryable("InvalidParameter"))
        assertFalse(AliyunErrors.isRetryable(""))
    }

    @Test
    fun `token 快过期判据留五分钟余量`() {
        val now = 1_000_000_000L
        assertFalse(AliyunToken("a", "r", now + 10 * 60_000L).isExpiring(now))
        assertTrue(AliyunToken("a", "r", now + 4 * 60_000L).isExpiring(now))
    }
}
