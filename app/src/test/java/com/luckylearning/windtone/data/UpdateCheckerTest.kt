package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查器单元测试（纯 JVM，无需设备与网络）。
 *
 * 重点锁住两件事：
 *  1. Atom 源解析（版本号 / tag / 下载直链 / 说明反转义）—— 主通道，不受 API 限流；
 *  2. 403/429 的文案 —— 带 Reset 头时给出准确恢复时间，避免用户无意义地反复重试。
 */
class UpdateCheckerTest {

    /** 真实 `releases.atom` 的结构（截取自 v1.0.2 发布后的响应） */
    private fun atomBody(vararg releases: Pair<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\">\n")
        releases.forEach { (tag, title) ->
            append(
                """
                <entry>
                  <id>tag:github.com,2008:Repository/1416587416/$tag</id>
                  <updated>2026-10-13T11:53:41Z</updated>
                  <link rel="alternate" type="text/html" href="https://github.com/LuckyLearning/WindTone/releases/tag/$tag"/>
                  <title>$title</title>
                  <content type="html">&lt;h2&gt;$title&lt;/h2&gt;
                &lt;p&gt;安装包：&lt;code&gt;WindTone-$tag.apk&lt;/code&gt;&lt;/p&gt;</content>
                </entry>
                """.trimIndent()
            )
            append('\n')
        }
        append("</feed>")
    }

    @Test
    fun atomParsesNewerRelease() {
        val body = atomBody("v1.0.3" to "听风 WindTone v1.0.3")
        val r = UpdateChecker.parseAtom(body, current = "1.0.2")

        assertTrue("应判定为有新版本", r is UpdateCheckResult.Available)
        val info = (r as UpdateCheckResult.Available).info
        assertEquals("1.0.3", info.version)
        assertEquals("v1.0.3", info.tagName)
        assertEquals("听风 WindTone v1.0.3", info.title)
        assertEquals(
            "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.3/WindTone-v1.0.3.apk",
            info.apkUrl,
        )
        assertEquals("WindTone-v1.0.3.apk", info.apkName)
        assertEquals(
            "https://github.com/LuckyLearning/WindTone/releases/tag/v1.0.3",
            info.releaseUrl,
        )
        // XML 实体必须已反转义，否则 UI 上会显示 &lt;h2&gt;
        assertTrue("说明应已反转义", info.notes.contains("<h2>") || info.notes.contains("安装包"))
        assertTrue("说明不应残留实体", !info.notes.contains("&lt;"))
    }

    @Test
    fun atomReportsUpToDateWhenSameVersion() {
        val body = atomBody("v1.0.2" to "听风 WindTone v1.0.2")
        val r = UpdateChecker.parseAtom(body, current = "1.0.2")
        assertTrue("同版本应为已是最新", r is UpdateCheckResult.UpToDate)
        assertEquals("1.0.2", (r as UpdateCheckResult.UpToDate).latestVersion)
    }

    @Test
    fun atomPicksFirstEntryAsLatest() {
        // 真实响应是按发布时间倒序的，第一条必须被当作最新
        val body = atomBody(
            "v1.1.0" to "听风 WindTone v1.1.0",
            "v1.0.2" to "听风 WindTone v1.0.2",
            "v1.0.1" to "听风 WindTone v1.0.1",
        )
        val r = UpdateChecker.parseAtom(body, current = "1.0.2")
        assertEquals("1.1.0", (r as UpdateCheckResult.Available).info.version)
    }

    @Test
    fun atomEmptyFeedFails() {
        val r = UpdateChecker.parseAtom("<feed></feed>", current = "1.0.2")
        assertTrue(r is UpdateCheckResult.Failed)
        assertTrue((r as UpdateCheckResult.Failed).reason.contains("还没有发布"))
    }

    // ------------------------------------------------------------ 限流文案

    @Test
    fun rateLimitWithResetHeaderGivesMinutes() {
        val now = 1_700_000_000_000L
        // Reset 在 5 分钟后
        val resetAtSec = (now / 1000L) + 300L
        val msg = UpdateChecker.rateLimitMessage(remaining = 0, resetAtEpochSec = resetAtSec, nowMs = now)
        assertTrue("应给出恢复时间，实际：$msg", msg.contains("5 分钟后恢复"))
    }

    @Test
    fun rateLimitRoundsUpPartialMinute() {
        val now = 1_700_000_000_000L
        val resetAtSec = (now / 1000L) + 30L // 30 秒 → 至少算 1 分钟
        val msg = UpdateChecker.rateLimitMessage(remaining = 0, resetAtEpochSec = resetAtSec, nowMs = now)
        assertTrue("不足一分钟应向上取整，实际：$msg", msg.contains("1 分钟后恢复"))
    }

    @Test
    fun rateLimitWithoutResetHeaderFallsBack() {
        val msg = UpdateChecker.rateLimitMessage(remaining = 0, resetAtEpochSec = null, nowMs = 0L)
        assertTrue("无 Reset 头应给出通用文案，实际：$msg", msg.contains("请稍后再试"))
    }

    @Test
    fun rateLimitIgnoresAbsurdResetHeader() {
        // Reset 值超出 1 天视为不可信（设备时钟偏差等），退回通用文案
        val msg = UpdateChecker.rateLimitMessage(
            remaining = 0,
            resetAtEpochSec = 9_999_999_999L,
            nowMs = 0L,
        )
        assertTrue("异常 Reset 值不应显示，实际：$msg", !msg.contains("分钟后恢复"))
    }

    // ------------------------------------------------------------ 多线路下载

    @Test
    fun downloadCandidatesPutDirectLinkFirst() {
        val url = "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.3/WindTone-v1.0.3.apk"
        val candidates = UpdateChecker.downloadCandidates(url)
        assertEquals(url, candidates[0])
        assertTrue(candidates.size >= 3)
        assertTrue(candidates[1].startsWith("https://ghproxy.net/https://github.com/"))
        assertTrue(candidates[2].startsWith("https://gh-proxy.com/https://github.com/"))
    }

    @Test
    fun downloadCandidatesSkipMirrorForNonGithubUrl() {
        val url = "https://example.com/self-hosted.apk"
        assertEquals(listOf(url), UpdateChecker.downloadCandidates(url))
        // 任何线路下都不给非 GitHub 地址套前缀
        DownloadRoute.values().forEach { route ->
            assertEquals(listOf(url), UpdateChecker.downloadCandidates(url, route))
        }
    }

    @Test
    fun directRouteNeverUsesMirror() {
        val url = "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.6/WindTone-v1.0.6.apk"
        assertEquals(listOf(url), UpdateChecker.downloadCandidates(url, DownloadRoute.DIRECT))
    }

    @Test
    fun manualMirrorRouteUsesOnlyThatMirror() {
        val url = "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.6/WindTone-v1.0.6.apk"

        val ghproxy = UpdateChecker.downloadCandidates(url, DownloadRoute.GHPROXY_NET)
        assertEquals(listOf("https://ghproxy.net/$url"), ghproxy)

        val ghProxyCom = UpdateChecker.downloadCandidates(url, DownloadRoute.GH_PROXY_COM)
        assertEquals(listOf("https://gh-proxy.com/$url"), ghProxyCom)
    }

    @Test
    fun autoRouteTriesDirectThenMirrors() {
        val url = "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.6/WindTone-v1.0.6.apk"
        val candidates = UpdateChecker.downloadCandidates(url, DownloadRoute.AUTO)
        assertEquals(listOf(url, "https://ghproxy.net/$url", "https://gh-proxy.com/$url"), candidates)
    }

    // ------------------------------------------------------------ 下载失败文案

    @Test
    fun manualRouteFailureMentionsRouteAndWhereToChange() {
        val msg = UpdateChecker.describeDownloadFailure(
            DownloadRoute.GHPROXY_NET,
            java.io.IOException("下载失败（HTTP 502）"),
        )
        assertTrue("应点明是哪条线路，实际：$msg", msg.contains("ghproxy.net"))
        assertTrue("应指引去设置换线路，实际：$msg", msg.contains("下载线路"))
    }

    @Test
    fun autoRouteFailureSuggestsBrowserFallback() {
        val msg = UpdateChecker.describeDownloadFailure(DownloadRoute.AUTO, null)
        assertTrue("应给出兜底建议，实际：$msg", msg.contains("浏览器"))
    }
}
