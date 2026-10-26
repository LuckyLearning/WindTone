package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * 更新检查器单元测试（纯 JVM，无需设备与网络）。
 *
 * v1.1.30 起更新源改为**公共分发仓库**的 `latest.json`，测试重点随之变成：
 *  1. 清单解析与版本判定（新版本 / 已最新 / 坏数据 / 没有资产）；
 *  2. 资产挑选顺序（本机 ABI → 其它 → universal）—— 挑错会让用户下到装不上的包；
 *  3. 下载线路（raw 直链 / jsDelivr / ghproxy）与「手动指定线路不许偷换」；
 *  4. 说明清洗（Markdown / HTML 都收敛成纯文本）；
 *  5. SHA-256 校验（镜像只该转发字节，摘要不符必须拒绝安装）。
 *
 * ⚠️ 必须挂 [RobolectricTestRunner]：清单解析用 `org.json`，而**纯 JVM 单测**用的是
 * android.jar 的 mockable 版本 —— 所有方法返回 `null`/`0`，`optString` 会直接 NPE、
 * `JSONArray.length()` 恒为 0。这类失败看着像「解析逻辑写错了」，其实是测试环境问题。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private fun manifest(
        version: String = "1.1.30",
        tag: String = "v$version",
        notes: String = "**听风** 1.1.30\n\n- 修了一处\n",
        assets: String = DEFAULT_ASSETS,
    ): String = """
        {
          "schema": 1,
          "app": "WindTone",
          "displayName": "听风 WindTone",
          "version": "$version",
          "versionCode": 40,
          "tag": "$tag",
          "publishedAt": "2026-10-19T04:10:00Z",
          "minSdk": 24,
          "notes": ${org.json.JSONObject.quote(notes)},
          "assets": [${assets.trim()}]
        }
    """.trimIndent()

    private val DEFAULT_ASSETS = """
        {"abi":"arm64-v8a","file":"WindTone-v1.1.30-arm64-v8a.apk","size":3023626,"sha256":"aaa"},
        {"abi":"armeabi-v7a","file":"WindTone-v1.1.30-armeabi-v7a.apk","size":3020784,"sha256":"bbb"},
        {"abi":"x86_64","file":"WindTone-v1.1.30-x86_64.apk","size":3024287,"sha256":"ccc"},
        {"abi":"universal","file":"WindTone-v1.1.30-universal.apk","size":3073699,"sha256":"ddd"}
    """

    // ------------------------------------------------------------ 清单解析

    @Test
    fun manifestWithNewerVersionIsAvailable() {
        val r = UpdateChecker.parseManifest(manifest(), current = "1.1.29", abis = emptyList())

        assertTrue("应判定为有新版本", r is UpdateCheckResult.Available)
        val info = (r as UpdateCheckResult.Available).info
        assertEquals("1.1.30", info.version)
        assertEquals("v1.1.30", info.tagName)
        assertEquals("听风 WindTone 1.1.30", info.title)
        // 单测环境取不到设备 ABI → 退到 universal
        assertEquals("WindTone-v1.1.30-universal.apk", info.apkName)
        assertTrue(info.apkUrl.endsWith("/WindTone/v1.1.30/WindTone-v1.1.30-universal.apk"))
        assertTrue("直链必须是可匿名访问的 raw 地址", info.apkUrl.startsWith("https://raw.githubusercontent.com/"))
        assertEquals(3073699L, info.apkSizeBytes)
        assertEquals("ddd", info.apkSha256)
        // 版本目录页（浏览器打开）
        assertEquals(
            "https://github.com/LuckyLearning/Release/tree/main/WindTone/v1.1.30",
            info.releaseUrl,
        )
        // 说明：Markdown 的 ** 与 - 都被清洗掉
        assertEquals("听风 1.1.30\n• 修了一处", info.notes)
    }

    @Test
    fun sameVersionIsUpToDate() {
        val r = UpdateChecker.parseManifest(manifest(), current = "1.1.30", abis = emptyList())
        assertTrue(r is UpdateCheckResult.UpToDate)
        assertEquals("1.1.30", (r as UpdateCheckResult.UpToDate).latestVersion)
    }

    @Test
    fun olderManifestIsUpToDate() {
        // 本地跑的是 1.1.31（比如测试包），清单还是 1.1.30 → 不该提示更新
        val r = UpdateChecker.parseManifest(manifest(), current = "1.1.31", abis = emptyList())
        assertTrue(r is UpdateCheckResult.UpToDate)
    }

    @Test
    fun brokenManifestFails() {
        assertTrue(
            UpdateChecker.parseManifest("{ not json", "1.0.0", emptyList()) is UpdateCheckResult.Failed,
        )
    }

    @Test
    fun manifestWithoutVersionFails() {
        val r = UpdateChecker.parseManifest("""{"schema":1,"assets":[]}""", "1.0.0", emptyList())
        assertTrue(r is UpdateCheckResult.Failed)
        assertTrue((r as UpdateCheckResult.Failed).reason.contains("版本号"))
    }

    @Test
    fun manifestWithoutAssetsFails() {
        val r = UpdateChecker.parseManifest(manifest(assets = ""), "1.0.0", emptyList())
        assertTrue(r is UpdateCheckResult.Failed)
        assertTrue((r as UpdateCheckResult.Failed).reason.contains("安装包"))
    }

    // ------------------------------------------------------------ 资产挑选

    @Test
    fun deviceAbiWinsOverUniversal() {
        val assets = org.json.JSONArray(manifestAssetsJson())
        val ordered = UpdateChecker.orderAssets(assets, abis = listOf("arm64-v8a"))
        assertEquals("WindTone-v1.1.30-arm64-v8a.apk", ordered.first().first)
        assertEquals(3023626L, ordered.first().second)
        assertEquals("aaa", ordered.first().third)
    }

    @Test
    fun universalIsPreferredWhenDeviceAbiMissing() {
        val assets = org.json.JSONArray(manifestAssetsJson())
        val ordered = UpdateChecker.orderAssets(assets, abis = listOf("riscv64"))
        assertEquals("WindTone-v1.1.30-universal.apk", ordered.first().first)
    }

    @Test
    fun otherAbisComeAfterUniversal() {
        val assets = org.json.JSONArray(manifestAssetsJson())
        val ordered = UpdateChecker.orderAssets(assets, abis = listOf("riscv64"))
        assertEquals(
            listOf(
                "WindTone-v1.1.30-universal.apk",
                "WindTone-v1.1.30-arm64-v8a.apk",
                "WindTone-v1.1.30-armeabi-v7a.apk",
                "WindTone-v1.1.30-x86_64.apk",
            ),
            ordered.map { it.first },
        )
    }

    @Test
    fun secondDeviceAbiIsUsedWhenFirstAbsent() {
        // 32 位设备只报 armeabi-v7a；清单里没有它时按设备顺序继续往下找
        val assets = org.json.JSONArray(manifestAssetsJson())
        val ordered = UpdateChecker.orderAssets(assets, abis = listOf("x86_64", "armeabi-v7a"))
        assertEquals("WindTone-v1.1.30-x86_64.apk", ordered.first().first)
    }

    private fun manifestAssetsJson(): String = "[$DEFAULT_ASSETS]"

    // ------------------------------------------------------------ 下载线路

    private val rawUrl =
        "https://raw.githubusercontent.com/LuckyLearning/Release/main/WindTone/v1.1.30/WindTone-v1.1.30-arm64-v8a.apk"

    @Test
    fun autoRoutePrefersJsdelivrThenMirrorsThenOfficial() {
        val list = UpdateChecker.downloadCandidates(rawUrl)
        assertTrue(
            "AUTO 首条应为国内可达的 fastly.jsdelivr",
            list.first().startsWith("https://fastly.jsdelivr.net/gh/LuckyLearning/Release@main/"),
        )
        assertTrue(
            "jsDelivr 必须按 owner/repo@branch/path 重排（不能简单拼前缀）",
            "https://fastly.jsdelivr.net/gh/LuckyLearning/Release@main/WindTone/v1.1.30/WindTone-v1.1.30-arm64-v8a.apk" in list,
        )
        assertTrue(list.any { it.startsWith("https://gcore.jsdelivr.net/gh/") })
        assertTrue(list.any { it.startsWith("https://gh-proxy.com/https://raw.githubusercontent.com/") })
        assertTrue(list.any { it.startsWith("https://ghproxy.net/https://raw.githubusercontent.com/") })
        assertEquals("官方直连应排在最后兜底", rawUrl, list.last())
    }

    @Test
    fun jsdelivrRouteUsesOnlyFastlyHost() {
        val list = UpdateChecker.downloadCandidates(rawUrl, DownloadRoute.JSDELIVR)
        assertEquals(1, list.size)
        assertEquals(
            "https://fastly.jsdelivr.net/gh/LuckyLearning/Release@main/WindTone/v1.1.30/WindTone-v1.1.30-arm64-v8a.apk",
            list.first(),
        )
    }

    @Test
    fun directRouteNeverUsesMirror() {
        val list = UpdateChecker.downloadCandidates(rawUrl, DownloadRoute.DIRECT)
        assertEquals(listOf(rawUrl), list)
    }

    @Test
    fun manualMirrorRouteUsesOnlyThatMirror() {
        val list = UpdateChecker.downloadCandidates(rawUrl, DownloadRoute.GHPROXY_NET)
        assertEquals(1, list.size)
        assertEquals("https://ghproxy.net/$rawUrl", list.first())
    }

    @Test
    fun nonGithubUrlHasNoMirror() {
        val url = "https://example.com/WindTone-v1.1.30.apk"
        assertEquals(listOf(url), UpdateChecker.downloadCandidates(url))
    }

    @Test
    fun candidatesCoverEveryPackageThenEveryLine() {
        val a = "https://raw.githubusercontent.com/o/r/m/WindTone/v1/WindTone-v1-arm64-v8a.apk"
        val b = "https://raw.githubusercontent.com/o/r/m/WindTone/v1/WindTone-v1-universal.apk"
        val list = UpdateChecker.downloadCandidates(listOf(a, b), DownloadRoute.DIRECT)
        assertEquals(listOf(a, b), list)
    }

    @Test
    fun manualRouteFailureMentionsRouteAndWhereToChange() {
        val msg = UpdateChecker.describeDownloadFailure(
            DownloadRoute.GH_PROXY_COM,
            java.io.IOException("HTTP 502"),
        )
        assertTrue(msg.contains("gh-proxy.com"))
        assertTrue(msg.contains("下载线路"))
    }

    @Test
    fun autoRouteFailureSuggestsBrowserFallback() {
        val msg = UpdateChecker.describeDownloadFailure(DownloadRoute.AUTO, java.io.IOException("超时"))
        assertTrue(msg.contains("下载线路"))
        assertTrue(msg.contains("浏览器"))
    }

    // ------------------------------------------------------------ 检查更新线路

    @Test
    fun checkCandidatesPreferChinaReachableThenOfficialFallback() {
        val list = UpdateChecker.checkCandidates(DownloadRoute.AUTO)
        assertTrue(
            "AUTO 首条应为 fastly.jsdelivr（国内可达性优先）",
            list.first().startsWith("https://fastly.jsdelivr.net/gh/"),
        )
        assertTrue(list.any { it.startsWith("https://gcore.jsdelivr.net/gh/") })
        assertTrue(list.any { it.startsWith("https://gh-proxy.com/") })
        assertTrue(list.any { it.startsWith("https://ghproxy.net/") })
        assertTrue(
            "官方 raw 必须保留作兜底（最后一条）",
            list.last().startsWith("https://raw.githubusercontent.com/") &&
                list.last().endsWith("/WindTone/latest.json"),
        )
    }

    @Test
    fun checkCandidatesJsdelivrRouteUsesOnlyFastly() {
        val list = UpdateChecker.checkCandidates(DownloadRoute.JSDELIVR)
        assertEquals(1, list.size)
        assertTrue(list.first().startsWith("https://fastly.jsdelivr.net/gh/"))
        assertTrue(list.first().endsWith("/WindTone/latest.json"))
    }

    @Test
    fun checkCandidatesDirectRouteNeverUsesMirror() {
        val list = UpdateChecker.checkCandidates(DownloadRoute.DIRECT)
        assertEquals(1, list.size)
        assertTrue(list.first().startsWith("https://raw.githubusercontent.com/"))
    }

    @Test
    fun checkCandidatesManualMirrorUsesOnlyThatMirror() {
        val list = UpdateChecker.checkCandidates(DownloadRoute.GH_PROXY_COM)
        assertEquals(1, list.size)
        assertTrue(list.first().startsWith("https://gh-proxy.com/https://raw.githubusercontent.com/"))
        assertTrue(list.first().endsWith("/WindTone/latest.json"))
    }

    @Test
    fun mirrorConclusionIsFlaggedSoUiCanWarnAboutStaleCache() {
        val body = manifest(version = "1.1.30")
        val direct = UpdateChecker.parseManifest(body, "1.1.30", emptyList(), viaMirror = false)
        val mirror = UpdateChecker.parseManifest(body, "1.1.30", emptyList(), viaMirror = true)
        assertEquals(false, (direct as UpdateCheckResult.UpToDate).viaMirror)
        assertEquals(true, (mirror as UpdateCheckResult.UpToDate).viaMirror)
    }

    // ------------------------------------------------------------ 摘要校验

    @Test
    fun sha256MatchesKnownContent() {
        val f = File.createTempFile("windtone-sha", ".bin")
        try {
            Files.write(f.toPath(), "abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateChecker.sha256Of(f),
            )
        } finally {
            f.delete()
        }
    }

    // ------------------------------------------------------------ 版本比较

    @Test
    fun versionCompareHandlesMultiDigitSegments() {
        assertTrue(UpdateChecker.isNewer("1.1.30", "1.1.29"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.1.29", "1.1.29"))
        assertFalse(UpdateChecker.isNewer("1.1.29", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("v1.2.0", "v1.1.9"))
    }

    // ------------------------------------------------------------ 说明清洗

    @Test
    fun notesCollapseMarkdownToPlainText() {
        val raw = """
            **听风 WindTone 1.1.30** —— 本地音乐播放器

            ## 手机版 1.1.30

            - 修了一处问题
            - 另一处

            | 安装包 | 适用设备 |
            | --- | --- |
            | `arm64` | 手机 |
        """.trimIndent()
        val out = UpdateChecker.plainNotes(raw)
        assertFalse("不能残留 Markdown 标记", out.contains("**"))
        assertFalse(out.contains("| ---"))
        assertTrue(out.contains("听风 WindTone 1.1.30"))
        assertTrue(out.contains("• 修了一处问题"))
        assertTrue("表格应折成「• 单元格 · 单元格」", out.contains("• 安装包 · 适用设备"))
    }

    @Test
    fun notesStripHtmlTagsToo() {
        // 历史上传过 HTML 形态的说明（GitHub 渲染后的 Atom 内容），清洗必须两种都吃得下
        val out = UpdateChecker.plainNotes("<p>第一段</p><ul><li>条目</li></ul>")
        assertFalse(out.contains("<p>"))
        assertTrue(out.contains("第一段"))
        assertTrue(out.contains("• 条目"))
    }

    @Test
    fun blankNotesStayBlank() {
        assertEquals("", UpdateChecker.plainNotes("   \n  "))
    }

    // ------------------------------------------------------------ Range 续传辅助

    @Test
    fun parseContentRangeTotalReadsSlashTail() {
        assertEquals(1234L, UpdateChecker.parseContentRangeTotal("bytes 100-199/1234"))
        assertEquals(null, UpdateChecker.parseContentRangeTotal("bytes 100-199/*"))
        assertEquals(null, UpdateChecker.parseContentRangeTotal(null))
        assertEquals(null, UpdateChecker.parseContentRangeTotal(""))
    }

    @Test
    fun isOfficialRawDetectsGithubRawOnly() {
        assertTrue(UpdateChecker.isOfficialRaw(
            "https://raw.githubusercontent.com/LuckyLearning/Release/main/WindTone/latest.json",
        ))
        assertFalse(UpdateChecker.isOfficialRaw(
            "https://fastly.jsdelivr.net/gh/LuckyLearning/Release@main/WindTone/latest.json",
        ))
    }
}
