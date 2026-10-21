package com.luckylearning.windtone.data.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百度网盘的纯规则（v1.1.39）。
 *
 * 这些判断看着琐碎，但每一条都对应一个「用户看得见、却很难解释」的失败：
 * 目录拼错会打开空列表、格式判断放宽会「点了播放没反应」、UA 判断漏掉会「取到直链但静音」。
 */
class BaiduRulesTest {

    private val TOKEN = "12.abcdef.86400.1292922000-2346678-124328"

    @Test
    fun `只把播放器能解码的后缀算作可播放`() {
        listOf("a.mp3", "b.FLAC", "c.m4a", "d.ogg", "e.wav", "f.opus").forEach {
            assertTrue(it, BaiduRules.isPlayableAudio(it))
        }
        // 这几种没有对应解码器，列成可播放只会让用户点了没反应
        listOf("a.wma", "b.ape", "c.dsf", "d.txt", "e").forEach {
            assertFalse(it, BaiduRules.isPlayableAudio(it))
        }
    }

    @Test
    fun `音频大类包含不可播放格式用于提示`() {
        assertTrue(BaiduRules.isAudioLike("x.ape"))
        assertFalse(BaiduRules.isAudioLike("x.jpg"))
    }

    @Test
    fun `拼子路径不产生双斜杠`() {
        assertEquals("/Music", BaiduRules.childPath("/", "Music"))
        assertEquals("/apps/x/Music", BaiduRules.childPath("/apps/x", "Music"))
        assertEquals("/apps/x/Music", BaiduRules.childPath("/apps/x/", "Music"))
    }

    @Test
    fun `上级目录在根目录返回空`() {
        assertNull(BaiduRules.parentPath("/"))
        assertEquals("/", BaiduRules.parentPath("/apps"))
        assertEquals("/apps", BaiduRules.parentPath("/apps/x"))
        assertEquals("/apps", BaiduRules.parentPath("/apps/x/"))
    }

    @Test
    fun `不允许从应用目录再往上走`() {
        val root = "/apps/windtone"
        // 在应用目录本身：没有「返回上级」（上去就是 /apps，全是别的应用/私人目录）
        assertFalse(BaiduRules.canGoUp(root, root))
        // 子目录可以回应用目录
        assertTrue(BaiduRules.canGoUp("/apps/windtone/Music", root))
        // 没找到应用目录时退回整个网盘，此时仍可逐级向上
        assertTrue(BaiduRules.canGoUp("/apps", "/"))
    }

    @Test
    fun `路径显示成用户在网盘里看到的名字`() {
        val root = "/apps/windtone"
        assertEquals("我的应用数据/windtone", BaiduRules.displayPath(root, root))
        assertEquals(
            "我的应用数据/windtone/Music/2024",
            BaiduRules.displayPath("/apps/windtone/Music/2024", root),
        )
        // root 未知（退回 /）时原样显示
        assertEquals("/", BaiduRules.displayPath("/", "/"))
        // 不在应用目录下的路径不该被乱改
        assertEquals("/other", BaiduRules.displayPath("/other", root))
    }

    @Test
    fun `展示标题去掉扩展名`() {
        assertEquals("夜航", BaiduRules.displayTitle("夜航.flac"))
        assertEquals("无扩展名", BaiduRules.displayTitle("无扩展名"))
    }

    @Test
    fun `网盘域名按边界匹配`() {
        assertTrue(BaiduRules.needsPanUserAgent("d.pcs.baidu.com"))
        assertTrue(BaiduRules.needsPanUserAgent("pan.baidu.com"))
        assertTrue(BaiduRules.needsPanUserAgent("bj.bcebos.com"))
        assertFalse(BaiduRules.needsPanUserAgent("baidu.com.evil.example"))
        // 裸 endsWith 会把这种域名也算进去，这里必须为 false
        assertFalse(BaiduRules.needsPanUserAgent("evilbaidu.com"))
        assertFalse(BaiduRules.needsPanUserAgent(null))
    }

    @Test
    fun `直链请求头带网盘 UA`() {
        assertEquals("pan.baidu.com", BaiduRules.panHeaders()["User-Agent"])
    }

    @Test
    fun `直链必须补 access_token 且用正确的分隔符`() {
        val dlink = "https://d.pcs.baidu.com/file/abc?fid=1&sign=2"
        assertEquals(
            "$dlink&access_token=$TOKEN",
            BaiduRules.withAccessToken(dlink, TOKEN),
        )
        // 没有查询串时要用 ?，否则参数会粘在路径上
        assertEquals(
            "https://d.pcs.baidu.com/file/abc?access_token=$TOKEN",
            BaiduRules.withAccessToken("https://d.pcs.baidu.com/file/abc", TOKEN),
        )
    }

    @Test
    fun `直链带 fragment 时 access_token 必须插在问号后面而非拼进 fragment`() {
        // v1.1.43 的播放全失败就是这里：fragment 里的 `#wt=<fsId>` 被当成了普通尾巴，
        // token 拼到了 `#` 之后 —— fragment 不发往服务器，等于每个请求都没带 token → 全 403。
        val dlink = "https://d.pcs.baidu.com/file/abc?fid=1&sign=2#wt=414244021542671"
        assertEquals(
            "https://d.pcs.baidu.com/file/abc?fid=1&sign=2&access_token=$TOKEN#wt=414244021542671",
            BaiduRules.withAccessToken(dlink, TOKEN),
        )
        // 没有查询串时也一样：? 要在 # 之前
        assertEquals(
            "https://d.pcs.baidu.com/file/abc?access_token=$TOKEN#wt=42",
            BaiduRules.withAccessToken("https://d.pcs.baidu.com/file/abc#wt=42", TOKEN),
        )
        // 已经有 token 时不该再拼一次
        assertEquals(
            "https://d.pcs.baidu.com/f?a=1&access_token=old#wt=42",
            BaiduRules.withAccessToken("https://d.pcs.baidu.com/f?a=1&access_token=old#wt=42", TOKEN),
        )
    }

    @Test
    fun `已有 token 或没有 token 时保持原样`() {
        val signed = "https://d.pcs.baidu.com/f?a=1&access_token=old"
        assertEquals(signed, BaiduRules.withAccessToken(signed, TOKEN))
        assertEquals("https://d.pcs.baidu.com/f?a=1", BaiduRules.withAccessToken("https://d.pcs.baidu.com/f?a=1", null))
        assertEquals("https://d.pcs.baidu.com/f?a=1", BaiduRules.withAccessToken("https://d.pcs.baidu.com/f?a=1", "  "))
    }

    @Test
    fun `错误码翻成人话且不误导`() {
        assertEquals("", BaiduErrors.hint(0))
        // -7 是「无权访问目录」，不是登录失效 —— 说错会让用户反复扫码
        assertTrue(BaiduErrors.hint(-7).contains("权限"))
        assertTrue(BaiduErrors.hint(-6).contains("重新扫码"))
        assertTrue(BaiduErrors.hint(31066).contains("限流"))
        assertTrue(BaiduErrors.hint(99999).contains("99999"))
    }

    @Test
    fun `OAuth 错误串可读`() {
        assertTrue(BaiduErrors.hint("expired_token").contains("过期"))
        assertTrue(BaiduErrors.hint("access_denied").contains("拒绝"))
        assertEquals("", BaiduErrors.hint(""))
    }

    @Test
    fun `令牌临近过期才需要刷新`() {
        val now = 1_000_000L
        val fresh = BaiduToken("a", "r", expiresAt = now + 10 * 60_000L)
        assertFalse(fresh.isExpiring(now))
        val soon = BaiduToken("a", "r", expiresAt = now + 30_000L)
        assertTrue(soon.isExpiring(now))
    }
}
