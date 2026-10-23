package com.luckylearning.windtone.data.aliyun

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 令牌来源与刷新端点的对应关系 —— **v1.1.58 之后最严重的一次线上问题**的回归。
 *
 * 症状：用户装上一版后「播放一次能用，之后一直提示授权失效」。
 * 根因：OpenList 的两条刷新线不能混用 ——
 *  · `/alicloud/renewapi`（`drivers/alicloud_oa`）刷新时带 **OpenList 服务端自己的**
 *    client_id / client_secret，只能刷「用那对凭据授权出来的」令牌；
 *  · `/alicloud2/renewapi`（`drivers/alicloud_cs`）body 只有 grant_type + refresh_token，
 *    对应网页扫码登录那一档。
 * 混用不会当场报错：新签发的 access_token 照常能用 2 小时，等过期要刷新时才永久失败。
 *
 * 这里钉三件事：
 *  1. 两种来源各自拼对端点（URL 逐字）；
 *  2. 存档读回来的来源解析（老存档无该键 → OA，随后靠双端点兜底自愈）；
 *  3. 并发刷新时「别人刚刷过就别再刷」的判定（否则轮换两次 → 永久失效）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AliyunAuthSourceTest {

    private fun auth() = AliyunAuth(ApplicationProvider.getApplicationContext<Context>())

    private val token = AliyunToken(accessToken = "at", refreshToken = "rt", expiresAt = 0L)

    @Test
    fun oaSourceUsesLegacyRenewEndpointWithDriverHint() {
        val url = AliyunEndpoints.renewApiUrl("rt-1", AliyunTokenSource.OA, "https://api.oplist.org")
        assertEquals(
            "https://api.oplist.org/alicloud/renewapi?refresh_ui=rt-1&server_use=true&driver_txt=alicloud_qr",
            url,
        )
    }

    @Test
    fun renewUrlPutsMainlandSiteFirst() {
        // 国内移动网络下 .cn（EdgeOne）明显快于 .org（Cloudflare），后者甚至可能连不上
        assertEquals("https://api.oplist.org.cn", AliyunEndpoints.BASES.first())
        assertTrue(AliyunEndpoints.BASES.contains("https://api.oplist.org"))
        assertTrue(
            AliyunEndpoints.renewApiUrl("rt", AliyunTokenSource.CS)
                .startsWith("https://api.oplist.org.cn/alicloud2/renewapi?"),
        )
    }

    @Test
    fun csSourceUsesQrLoginRenewEndpointWithoutDriverHint() {
        val url = AliyunEndpoints.renewApiUrl("rt-2", AliyunTokenSource.CS, "https://api.oplist.org")
        assertEquals("https://api.oplist.org/alicloud2/renewapi?refresh_ui=rt-2", url)
        // oa 端点的 driver_txt 与 server_use 不能出现在 cs 端点上（服务端按路径分派驱动，
        // 多余参数会让 alicloud_cs 的 genToken 行为与预期不符）
        assertFalse(url.contains("driver_txt"))
        assertFalse(url.contains("server_use"))
    }

    @Test
    fun refreshTokenIsUrlEncoded() {
        val url = AliyunEndpoints.renewApiUrl("a+b/c=", AliyunTokenSource.CS, "https://api.oplist.org")
        assertTrue(url.endsWith("refresh_ui=a%2Bb%2Fc%3D"))
    }

    @Test
    fun sourceParsingFallsBackToOaForOldSaves() {
        // 1.1.56/57 的存档里没有 token_source 键 —— 按 OA 读，
        // 升级后第一次刷新会先试 OA、失败再试 CS，成功后自动把来源纠正为 CS
        assertEquals(AliyunTokenSource.OA, AliyunTokenSource.parse(null))
        assertEquals(AliyunTokenSource.OA, AliyunTokenSource.parse(""))
        assertEquals(AliyunTokenSource.OA, AliyunTokenSource.parse("whatever"))
        assertEquals(AliyunTokenSource.CS, AliyunTokenSource.parse("cs"))
    }

    @Test
    fun saveRemembersSource() {
        val a = auth()
        a.signOut()
        a.save(token, AliyunTokenSource.CS)
        assertEquals(AliyunTokenSource.CS, a.tokenSource())
        assertEquals("at", a.currentToken()?.accessToken)

        a.save(token, AliyunTokenSource.OA)
        assertEquals(AliyunTokenSource.OA, a.tokenSource())
    }

    @Test
    fun qrLoginSavesAsCsSource() {
        val a = auth()
        a.signOut()
        a.saveFromQrLogin(accessToken = "at-qr", refreshToken = "rt-qr", expiresInSec = 7200)
        assertEquals(AliyunTokenSource.CS, a.tokenSource())
        val saved = a.currentToken()!!
        assertEquals("at-qr", saved.accessToken)
        assertEquals("rt-qr", saved.refreshToken)
        // 有效期按服务端给的时间算（留 10 分钟下限，避免服务端给 0 时立刻过期）
        assertTrue(saved.expiresAt > System.currentTimeMillis() + 60 * 60_000L)
    }

    @Test
    fun qrLoginClampsNonPositiveExpiry() {
        val a = auth()
        a.signOut()
        a.saveFromQrLogin(accessToken = "at", refreshToken = "rt", expiresInSec = 0)
        // 服务端没给有效期时不能存成「立刻过期」，否则下一句播放就要刷新
        assertTrue(a.currentToken()!!.expiresAt > System.currentTimeMillis() + 5 * 60_000L)
    }

    // ---------------------------------------------------------------- 并发刷新判定

    @Test
    fun reuseWhenSomeoneElseAlreadyRefreshed() {
        val stale = AliyunToken("old-at", "old-rt", expiresAt = 0L) // 已过期
        val fresh = AliyunToken("new-at", "new-rt", expiresAt = System.currentTimeMillis() + 7_200_000)
        val now = System.currentTimeMillis()
        assertTrue(
            "别人刚刷过且新令牌有效：必须直接复用，不能再轮换一次",
            RefreshPolicy.canReuse(stale, fresh, force = false, now = now),
        )
    }

    @Test
    fun noReuseWhenNothingChanged() {
        val now = System.currentTimeMillis()
        val same = AliyunToken("same-at", "same-rt", expiresAt = now + 7_200_000)
        assertFalse(
            "access 没变说明没人刷过，该刷还是要刷",
            RefreshPolicy.canReuse(same, same, force = false, now = now),
        )
        assertFalse("没有存储值时不能复用", RefreshPolicy.canReuse(same, null, force = false, now = now))
    }

    @Test
    fun forceReusesFreshTokenEvenIfExpiringSoon() {
        val now = System.currentTimeMillis()
        val stale = AliyunToken("old-at", "old-rt", expiresAt = 0L)
        // force 场景（401 后重试）：令牌确实换过就算达成，哪怕又快过期也不再多刷一轮
        val fresh = AliyunToken("new-at", "new-rt", expiresAt = now + 60_000)
        assertTrue(RefreshPolicy.canReuse(stale, fresh, force = true, now = now))
    }
}
