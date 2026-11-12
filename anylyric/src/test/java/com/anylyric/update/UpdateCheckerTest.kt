package com.anylyric.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新链路的纯逻辑测试。
 *
 * 这些断言守的是「客户端怎么判断该不该升级」—— 判错了用户要么升不了、要么被降级，
 * 而这条链路在车机上很难现场调试，所以宁可在单测里把边界钉死。
 */
class UpdateCheckerTest {

    private fun manifest(
        version: String = "0.1.1",
        code: Int = 2,
        tag: String = "v$version",
        assets: String = """[{"abi":"universal","file":"AnyLyric-v$version-universal.apk","size":152000,"sha256":"abc"}]""",
        notes: String = "## 修复\n- 通知使用权跳转",
    ): String = """
        {
          "schema": 1,
          "app": "AnyLyric",
          "version": "$version",
          "versionCode": $code,
          "tag": "$tag",
          "minSdk": 31,
          "publishedAt": "2026-11-12T10:00:00Z",
          "notes": ${org.json.JSONObject.quote(notes)},
          "assets": $assets
        }
    """.trimIndent()

    // ---------------------------------------------------------------- 版本比较

    @Test
    fun `版本号按数字段比较而不是按字符串`() {
        assertTrue(UpdateChecker.compareVersions("0.1.1", "0.1.0") > 0)
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.1.0", "0.1.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("0.1", "0.1.0"))
    }

    @Test
    fun `版本号容忍 v 前缀与预发布后缀`() {
        assertEquals(0, UpdateChecker.compareVersions("v0.1.1", "0.1.1"))
        assertEquals(0, UpdateChecker.compareVersions("0.1.1-beta", "0.1.1+build7"))
    }

    // ---------------------------------------------------------------- 清单解析

    @Test
    fun `清单里的 versionCode 更高时判定有新版本`() {
        val result = UpdateChecker.parseManifest(manifest(), currentName = "0.1.0", currentCode = 1)
        val available = result as CheckResult.Available
        assertEquals("0.1.1", available.release.version)
        assertEquals(2, available.release.versionCode)
        assertEquals("v0.1.1", available.release.tag)
        assertEquals("AnyLyric-v0.1.1-universal.apk", available.release.apkName)
        assertEquals(152000L, available.release.sizeBytes)
        assertTrue(available.release.apkUrl.endsWith("/v0.1.1/AnyLyric-v0.1.1-universal.apk"))
        assertEquals(UpdateChecker.releasePageUrl, available.release.pageUrl)
    }

    @Test
    fun `版本名更高但 versionCode 不更高时不算更新`() {
        // 反例守的是「装过更高版本名」的历史包袱：只看名字会误报可升级，
        // 实际装上去 versionCode 更低，覆盖安装后反而查不到后续版本。
        val result = UpdateChecker.parseManifest(manifest(version = "9.9.9", code = 1), "0.1.0", 2)
        assertTrue(result is CheckResult.UpToDate)
    }

    @Test
    fun `versionCode 相同时即便版本名更高也算最新`() {
        val result = UpdateChecker.parseManifest(manifest(version = "0.2.0", code = 2), "0.1.0", 2)
        assertEquals("0.2.0", (result as CheckResult.UpToDate).latestVersion)
    }

    @Test
    fun `清单缺 versionCode 时退回版本名比较`() {
        val body = """{"version":"0.2.0","assets":[{"file":"AnyLyric-v0.2.0-universal.apk"}]}"""
        assertTrue(UpdateChecker.parseManifest(body, "0.1.0", 9) is CheckResult.Available)
    }

    @Test
    fun `坏 JSON 折成 BAD_MANIFEST 而不是抛异常`() {
        val result = UpdateChecker.parseManifest("<html>404</html>", "0.1.0", 1)
        assertEquals(FailureKind.BAD_MANIFEST, (result as CheckResult.Failed).kind)
    }

    @Test
    fun `清单没有 assets 时报 NO_APK`() {
        val body = """{"version":"0.1.1","versionCode":2,"assets":[]}"""
        assertEquals(FailureKind.NO_APK, (UpdateChecker.parseManifest(body, "0.1.0", 1) as CheckResult.Failed).kind)
    }

    @Test
    fun `多架构时优先挑 universal 包`() {
        val assets = """
            [
              {"abi":"arm64-v8a","file":"AnyLyric-v0.1.1-arm64-v8a.apk","size":10},
              {"abi":"universal","file":"AnyLyric-v0.1.1-universal.apk","size":20}
            ]
        """.trimIndent()
        val result = UpdateChecker.parseManifest(manifest(assets = assets), "0.1.0", 1)
        assertEquals("AnyLyric-v0.1.1-universal.apk", (result as CheckResult.Available).release.apkName)
    }

    // ---------------------------------------------------------------- 线路

    @Test
    fun `清单线路镜像在前官方兜底且带破缓存时间戳`() {
        val candidates = UpdateChecker.manifestCandidates()
        // 每条都带 query：镜像对 @main 有缓存，刚发版时会返回上一版清单，
        // 客户端就会说「已是最新」—— 现场反馈的「检查不到」正是这一条。
        assertTrue(candidates.all { it.contains("?t=") })
        assertTrue(candidates.last().startsWith(UpdateChecker.manifestUrl))
        assertTrue(candidates.first().contains("jsdelivr.net"))
        assertTrue(candidates.any { it.contains("ghproxy.net/") })
        // 3 条 jsDelivr（fastly/gcore/cdn）+ 4 条加速镜像 + 官方 raw
        assertEquals(8, candidates.size)
        assertEquals(candidates.size, candidates.distinct().size)
    }

    /**
     * 指定线路时**只给那一条**。
     *
     * 守的是「线路选择必须可解释」：用户手动选了「直连 GitHub」，
     * 程序却偷偷退回镜像，那这颗开关就是骗人的。
     */
    @Test
    fun `指定线路只给一条候选`() {
        val direct = UpdateChecker.manifestCandidates(DownloadRoute.DIRECT)
        assertEquals(1, direct.size)
        assertTrue(direct[0].startsWith(UpdateChecker.manifestUrl))

        val jsdelivr = UpdateChecker.manifestCandidates(DownloadRoute.JSDELIVR)
        assertEquals(1, jsdelivr.size)
        assertTrue(jsdelivr[0].startsWith("https://fastly.jsdelivr.net/gh/"))

        val proxy = UpdateChecker.manifestCandidates(DownloadRoute.GH_PROXY_COM)
        assertEquals(1, proxy.size)
        assertTrue(proxy[0].startsWith("https://gh-proxy.com/https://raw.githubusercontent.com/"))
    }

    @Test
    fun `线路落盘按名字解析且未知值退回自动`() {
        assertEquals(DownloadRoute.GH_FAST, DownloadRoute.of("GH_FAST"))
        assertEquals(DownloadRoute.AUTO, DownloadRoute.of(null))
        assertEquals(DownloadRoute.AUTO, DownloadRoute.of(""))
        // 枚举改顺序不该影响已落盘的选择：存的是名字不是序号
        assertEquals(DownloadRoute.DIRECT, DownloadRoute.of(DownloadRoute.DIRECT.name))
    }

    @Test
    fun `清单版本比本机低时显示本机版本`() {
        // 镜像的旧缓存：别让 0.2.2 的设备看到「已是最新（0.2.1）」
        val result = UpdateChecker.parseManifest(
            manifest("0.2.1", 3),
            "0.2.2",
            4
        )
        assertEquals(CheckResult.UpToDate("0.2.2"), result)
    }

    @Test
    fun `安装包线路同样镜像在前且去重`() {
        val url = "https://raw.githubusercontent.com/LuckyLearning/Release/main/AnyLyric/anylyric-v0.1.1/x.apk"
        val candidates = UpdateChecker.downloadCandidates(url)
        assertEquals(url, candidates.last())
        assertTrue(candidates.size >= 3)
        assertEquals(candidates.size, candidates.distinct().size)
    }

    /** 下载侧与检查侧共用同一套线路，否则「换了线路还是下不动」会非常难解释。 */
    @Test
    fun `下载线路与检查线路同源`() {
        val url = "https://raw.githubusercontent.com/LuckyLearning/Release/main/AnyLyric/x.apk"
        assertEquals(
            UpdateChecker.manifestCandidates(DownloadRoute.GHPROXY_NET).size,
            UpdateChecker.downloadCandidates(url, DownloadRoute.GHPROXY_NET).size
        )
        assertEquals(
            listOf("https://hub.gitmirror.com/$url"),
            UpdateChecker.downloadCandidates(url, DownloadRoute.GIT_MIRROR)
        )
    }

    @Test
    fun `非 GitHub 直链不套镜像`() {
        val url = "https://example.com/x.apk"
        assertEquals(listOf(url), UpdateChecker.downloadCandidates(url))
        assertEquals(listOf(url), UpdateChecker.downloadCandidates(url, DownloadRoute.GH_PROXY_COM))
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `更新说明去掉 markdown 记号`() {
        val plain = UpdateChecker.plainNotes(
            """
            ## 本次更新
            - **通知使用权**：修掉详情页静默失败
            - 图标换成 `自适应图标`
            | 项 | 值 |
            |---|---|
            | 版本 | 0.1.1 |
            """.trimIndent()
        )
        assertFalse(plain.contains("##"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("|---"))
        assertTrue(plain.contains("本次更新"))
        assertTrue(plain.contains("• 通知使用权：修掉详情页静默失败"))
        assertTrue(plain.contains("• 版本 · 0.1.1"))
    }

    @Test
    fun `大小格式化为空串表示未知`() {
        assertEquals("", UpdateChecker.formatSize(0))
        assertTrue(UpdateChecker.formatSize(2L * 1024 * 1024).startsWith("2.00"))
    }
}
