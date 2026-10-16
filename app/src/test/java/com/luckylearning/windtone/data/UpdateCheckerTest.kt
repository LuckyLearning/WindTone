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
        // 单测环境拿不到设备 ABI（Build.SUPPORTED_ABIS 为默认值），
        // 候选顺序应退化为 universal → 历史单包命名
        assertEquals("WindTone-v1.0.3-universal.apk", info.apkName)
        assertEquals(
            "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.3/WindTone-v1.0.3-universal.apk",
            info.apkUrl,
        )
        // 历史命名必须仍在候选里，否则老版本用户升级会 404
        assertTrue(
            "兼容 1.0.6 及以前命名的候选不能丢",
            info.apkUrlFallbacks.any { it.endsWith("/WindTone-v1.0.3.apk") },
        )
        assertEquals(
            "https://github.com/LuckyLearning/WindTone/releases/tag/v1.0.3",
            info.releaseUrl,
        )
        // 说明会被清洗成纯文本再上屏：Atom 的 <content> 是 **HTML**，
        // 原先这里只断言「反转义成功」（允许残留 <h2>），于是真机上弹窗里
        // 直接显示了一整屏标签（v1.0 ~ v1.1.11 的线上表现）。
        assertEquals("听风 WindTone v1.0.3\n安装包：WindTone-v1.0.3.apk", info.notes)
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

    // ------------------------------------------------------------ ABI 选包

    @Test
    fun apkNameCandidatesPutsDeviceAbiFirstThenUniversalThenLegacy() {
        val names = UpdateChecker.apkNameCandidates("1.0.7", listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(
            listOf(
                "WindTone-v1.0.7-arm64-v8a.apk",
                "WindTone-v1.0.7-armeabi-v7a.apk",
                "WindTone-v1.0.7-universal.apk",
                "WindTone-v1.0.7.apk", // 1.0.6 及以前的历史命名，必须保留在最后
            ),
            names,
        )
    }

    @Test
    fun apkNameCandidatesIgnoresUnknownAbi() {
        // riscv64 之类没有发布包的架构 → 直接退到 universal，不生成无意义的候选名
        val names = UpdateChecker.apkNameCandidates("1.0.7", listOf("riscv64"))
        assertEquals(
            listOf("WindTone-v1.0.7-universal.apk", "WindTone-v1.0.7.apk"),
            names,
        )
    }

    @Test
    fun atomUsesDeviceAbiAssetNameFirst() {
        val body = atomBody("v1.0.7" to "听风 WindTone v1.0.7")
        // 直接验证候选顺序：Atom 通道拿不到 assets，只能按命名规则推导
        val names = UpdateChecker.apkNameCandidates("1.0.7", listOf("armeabi-v7a"))
        assertEquals("WindTone-v1.0.7-armeabi-v7a.apk", names.first())
    }

    @Test
    fun downloadCandidatesCoverAllAssetNamesThenLines() {
        val urls = listOf(
            "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.7/WindTone-v1.0.7-arm64-v8a.apk",
            "https://github.com/LuckyLearning/WindTone/releases/download/v1.0.7/WindTone-v1.0.7-universal.apk",
        )
        // 自动：每个包名先直链、再两个镜像
        val auto = UpdateChecker.downloadCandidates(urls, DownloadRoute.AUTO)
        assertEquals(6, auto.size)
        assertEquals(urls[0], auto[0])
        assertEquals("https://ghproxy.net/${urls[0]}", auto[1])
        assertEquals("https://gh-proxy.com/${urls[0]}", auto[2])
        assertEquals(urls[1], auto[3])

        // 手动指定：每个包名各一条，共 2 条
        val manual = UpdateChecker.downloadCandidates(urls, DownloadRoute.GHPROXY_NET)
        assertEquals(listOf("https://ghproxy.net/${urls[0]}", "https://ghproxy.net/${urls[1]}"), manual)
    }

    // ------------------------------------------------------------ 更新说明的清洗
    //
    // 由来：Atom 通道（主通道）的 <content type="html"> 是 GitHub **渲染后的 HTML**，
    // REST API 的 body 是 **Markdown 原文**。原先只清理 Markdown，
    // 于是真机上弹窗显示成了一整屏 `<p><strong>听风 WindTone…`（v1.0 ~ v1.1.11）。
    // 这里钉两件事：① 两条通道必须收敛到同一份文本；② 结果里不留任何标签与实体。

    @Test
    fun notesDoNotDependOnChannel() {
        // 两份都是 v1.1.11 的真实响应：一份取自 releases.atom，一份取自 REST API
        val fromAtom = UpdateChecker.plainNotes(REAL_ATOM_CONTENT)
        val fromApi = UpdateChecker.plainNotes(REAL_API_BODY)

        assertEquals("同一份说明在两条通道下必须显示成同一份文本", fromApi, fromAtom)
        assertTrue("正文首行：${fromAtom.take(40)}", fromAtom.startsWith("听风 WindTone 1.1.11 ——"))
        assertTrue("列表项应折成 • 行", fromAtom.contains("• 本版包含若干功能调整与问题修复。"))
        assertTrue("标题应去掉 # 保留文字", fromAtom.contains("手机版 / 车机版 1.1.11"))
        assertTrue(
            "表格行应折成 • 行（两条通道的表格写法完全不同）",
            fromAtom.contains("• WindTone-v1.1.11-arm64-v8a.apk · 绝大多数手机 / 车机"),
        )
        assertTrue("表格对齐行应整行丢掉", !fromAtom.contains("--- | ---"))
        assertTrue("段间不留空行（弹窗高度有限）", !fromAtom.contains("\n\n"))
    }

    @Test
    fun notesNeverShowMarkup() {
        listOf(REAL_ATOM_CONTENT, REAL_API_BODY).forEach { raw ->
            val notes = UpdateChecker.plainNotes(raw)
            val tag = Regex("<[a-zA-Z/][^>]*>").find(notes)?.value
            val entity = Regex("&[a-zA-Z#][a-zA-Z0-9]*;").find(notes)?.value
            assertTrue("不应残留标签，却发现：$tag", tag == null)
            assertTrue("不应残留实体，却发现：$entity", entity == null)
        }
    }

    @Test
    fun notesDecodeEntities() {
        // Atom 的 content 是 XML 转义的，转发义后正文里仍可能带 HTML 实体
        assertEquals("风格 A & B", UpdateChecker.plainNotes("<p>风格 A &amp; B</p>"))
        assertEquals("破折号—与中点·", UpdateChecker.plainNotes("<p>破折号&#8212;与中点&middot;</p>"))
        // 认不出的实体原样保留：宁可显示 &foo;，也不要把内容吞掉
        assertEquals("保留 &foo; 原样", UpdateChecker.plainNotes("<p>保留 &foo; 原样</p>"))
        // 实体里出现的尖括号必须是「先剥标签、后解实体」，否则 &lt;b&gt; 会被当成标签剥掉
        assertEquals("<b> 是标签写法", UpdateChecker.plainNotes("<p>&lt;b&gt; 是标签写法</p>"))
    }

    @Test
    fun notesHandleHeadingListAndInlineMarkup() {
        assertEquals("标题\n• 一条\n• 二条", UpdateChecker.plainNotes("## 标题\n\n- 一条\n- 二条\n"))
        assertEquals("看文档 · 现在", UpdateChecker.plainNotes("看[文档](https://example.com) · `现在`"))
    }

    @Test
    fun notesOnBlankInputIsEmpty() {
        assertEquals("", UpdateChecker.plainNotes(""))
        assertEquals("", UpdateChecker.plainNotes("   \n\n"))
    }

    /** v1.1.11 的真实 Atom 正文（`<content type="html">`，已按 [android] xmlText 反转义、换行统一为 \n） */
    private val REAL_ATOM_CONTENT = """
<p><strong>听风 WindTone 1.1.11</strong> —— 本地音乐播放器：无广告、无账号，曲库、歌单、收藏、播放统计与歌词全部只保存在本机。</p>
<h1>手机版 / 车机版 1.1.11</h1>
<ul>
<li>本版包含若干功能调整与问题修复。</li>
<li>覆盖升级后歌单、收藏、播放统计、歌词设置全部保留，曲库缓存自动复用，无需重新扫描。</li>
</ul>
<p><strong>适用设备</strong>：Android 7.0（API 24）及以上；手机与车机通用（车机横屏界面自动切换）。</p>
<p><strong>安装</strong>：按设备架构下载对应的包，直接覆盖安装即可：</p>
<table>
<thead>
<tr>
<th>安装包</th>
<th>适用设备</th>
</tr>
</thead>
<tbody>
<tr>
<td><code>WindTone-v1.1.11-arm64-v8a.apk</code></td>
<td>绝大多数手机 / 车机</td>
</tr>
<tr>
<td><code>WindTone-v1.1.11-armeabi-v7a.apk</code></td>
<td>较老的 32 位设备</td>
</tr>
<tr>
<td><code>WindTone-v1.1.11-x86_64.apk</code></td>
<td>模拟器 / ChromeOS</td>
</tr>
<tr>
<td><code>WindTone-v1.1.11-universal.apk</code></td>
<td>不确定架构时选它</td>
</tr>
</tbody>
</table>
<p><strong>升级</strong>：也可以在应用内「设置 → 更新 → 检查更新」一键升级（检查更新只访问 GitHub 官方地址）。</p>
<p><strong>隐私</strong>：音频与歌词只在本机读写；联网只有一处——检查/下载更新（GitHub Releases）。不采集、不上传任何信息。</p>
<p><sub>1.1.11（versionCode 21）· 构建于 2026-10-16</sub></p>
""".trimIndent()
    /** v1.1.11 的真实 REST API `body`（Markdown 原文，含 Markdown 表格） */
    private val REAL_API_BODY = """
**听风 WindTone 1.1.11** —— 本地音乐播放器：无广告、无账号，曲库、歌单、收藏、播放统计与歌词全部只保存在本机。

# 手机版 / 车机版 1.1.11

- 本版包含若干功能调整与问题修复。
- 覆盖升级后歌单、收藏、播放统计、歌词设置全部保留，曲库缓存自动复用，无需重新扫描。

**适用设备**：Android 7.0（API 24）及以上；手机与车机通用（车机横屏界面自动切换）。

**安装**：按设备架构下载对应的包，直接覆盖安装即可：

| 安装包 | 适用设备 |
| --- | --- |
| `WindTone-v1.1.11-arm64-v8a.apk` | 绝大多数手机 / 车机 |
| `WindTone-v1.1.11-armeabi-v7a.apk` | 较老的 32 位设备 |
| `WindTone-v1.1.11-x86_64.apk` | 模拟器 / ChromeOS |
| `WindTone-v1.1.11-universal.apk` | 不确定架构时选它 |

**升级**：也可以在应用内「设置 → 更新 → 检查更新」一键升级（检查更新只访问 GitHub 官方地址）。

**隐私**：音频与歌词只在本机读写；联网只有一处——检查/下载更新（GitHub Releases）。不采集、不上传任何信息。

<sub>1.1.11（versionCode 21）· 构建于 2026-10-16</sub>
""".trimIndent()

}
