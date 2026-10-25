package com.luckylearning.windtone.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.luckylearning.windtone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 公共分发仓库中某个版本的信息 */
data class UpdateInfo(
    /** 版本号（已去掉 tag 的 v 前缀），如 1.1.29 */
    val version: String,
    /** 原始 tag 名，如 v1.1.29 */
    val tagName: String,
    /** 展示标题，如「听风 WindTone 1.1.29」 */
    val title: String,
    /** 更新说明（已整理为纯文本，可直接展示） */
    val notes: String,
    /** APK 下载直链（首选：本机 ABI 对应的那个包） */
    val apkUrl: String,
    /** APK 文件名，如 WindTone-v1.1.29-arm64-v8a.apk */
    val apkName: String,
    /**
     * 同一版本其它候选包的直链，按「本机首选 ABI → 其它 ABI → universal」排序。
     * 首选 [apkUrl] 失败时依次尝试 —— 某个 ABI 的包可能没上传全。
     */
    val apkUrlFallbacks: List<String> = emptyList(),
    /** APK 字节数，0 表示未知 */
    val apkSizeBytes: Long,
    /** 发布时间（ISO 字符串） */
    val publishedAt: String,
    /** 该版本在公共仓库里的目录地址（浏览器可打开） */
    val releaseUrl: String,
    /** APK 的 SHA-256（小写十六进制），空串表示清单里没给 */
    val apkSha256: String = "",
) {
    /** 去重后的全部候选直链（首选在最前） */
    val allApkUrls: List<String> get() = (listOf(apkUrl) + apkUrlFallbacks).distinct()
}

sealed interface UpdateCheckResult {
    /** 有新版本可用 */
    data class Available(val info: UpdateInfo) : UpdateCheckResult

    /** 已是最新 */
    data class UpToDate(
        val latestVersion: String,
        /**
         * 这个结论来自镜像线路（直连失败后的兜底）。镜像有 CDN 缓存，
         * 刚发布的版本可能还没同步 —— 界面据此提示「可能有延迟」。
         */
        val viaMirror: Boolean = false,
    ) : UpdateCheckResult

    /** 检查失败（离线 / 未发布等），[reason] 可直接展示给用户 */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * 应用内更新。
 *
 * ## 数据从哪来（v1.1.30 起）
 *
 * 源码仓库是**私有**的，匿名用户既读不到它的 Release 列表、也下不到资产，
 * 所以更新链路改为读**公共分发仓库** `LuckyLearning/Release` 里的静态文件：
 *
 * ```
 * https://raw.githubusercontent.com/LuckyLearning/Release/main/WindTone/latest.json
 * https://raw.githubusercontent.com/LuckyLearning/Release/main/WindTone/v1.1.30/WindTone-v1.1.30-arm64-v8a.apk
 * ```
 *
 * `latest.json` 由发布流水线生成（`scripts/publish-release-repo.sh`）。它同时给出
 * **每个包的大小与 SHA-256**，所以下载校验从「大小粗校验」升级成「大小 + 摘要」双校验。
 *
 * 好处：① 不需要任何鉴权；② `raw.githubusercontent.com` 是 CDN，不计入 REST API
 * 每小时 60 次的配额（以前用 Atom + API 就是为了躲这个限制，现在整条链路都不碰 API）。
 *
 * ## 关于「镜像 / 代理」（重要）
 *
 * **检查更新与下载安装包共用同一套线路**（[DownloadRoute]，默认自动）：
 * 先直连 GitHub 官方地址（`raw.githubusercontent.com`），直连不通（超时 / 被阻断）时
 * 依次退到 ghproxy.net → gh-proxy.com → jsDelivr。
 *
 * 为什么检查更新也要兜底：`raw.githubusercontent.com` 在国内经常整段连不通，
 * 只走一条线时「检查更新」必然报「连接超时」——功能等于不可用（2026-10-19 用户反馈）。
 *
 * 镜像有 CDN 缓存，刚发布的版本可能稍有滞后：只有**直连失败**才用镜像，且
 * 经镜像得到的「已是最新」结论会在界面注明「可能有延迟」，不会把新版本悄悄报成最新。
 *
 * 全流程只在用户点击「检查更新 / 下载」或启动时自动检查（可关闭）时发生，
 * 不上报任何设备信息，也不访问除 GitHub 与其加速镜像之外的任何域名。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** 是否输出调试日志（Release 包默认关闭，避免刷 logcat） */
    private var verbose = false

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    /** 本应用在公共仓库里的目录（`LuckyLearning/Release` 下的 `WindTone/`） */
    private val appBase: String
        get() = "https://raw.githubusercontent.com/${BuildConfig.RELEASE_OWNER}/${BuildConfig.RELEASE_REPO}/${BuildConfig.RELEASE_BRANCH}/${BuildConfig.RELEASE_APP_DIR}"

    /** 最新版本清单（唯一的检查入口） */
    private val manifestUrl: String
        get() = "$appBase/latest.json"

    /** 当前安装版本（来自 BuildConfig，与 build.gradle.kts 的 versionName 一致） */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    // ------------------------------------------------------------ 架构

    /**
     * 本机 CPU 架构（主 ABI 在最前）。
     *
     * 用 [android.os.Build.SUPPORTED_ABIS] 而不是 `Build.CPU_ABI`：前者是完整的优先级列表，
     * 64 位设备上第一个就是 arm64-v8a，能直接对上清单里的 `abi` 字段。
     * 单元测试环境下取不到（返回 null），此时给空列表，由 [orderAssets] 退到 universal。
     */
    internal fun deviceAbis(): List<String> =
        runCatching { android.os.Build.SUPPORTED_ABIS?.toList().orEmpty() }
            .getOrDefault(emptyList())

    /** 页面展示用的架构标签，如 `arm64-v8a`；取不到时返回空串 */
    fun primaryAbiLabel(): String = deviceAbis().firstOrNull().orEmpty()

    /** 公共仓库里本软件的目录页面（检查失败时可引导用户手动打开） */
    fun releasePageUrl(): String = BuildConfig.RELEASE_PAGE_URL

    // ------------------------------------------------------------ 查询

    /**
     * 检查更新的候选线路（按尝试优先级）。
     *
     * - [DownloadRoute.AUTO]：官方直连 → ghproxy.net → gh-proxy.com → jsDelivr。
     *   镜像顺序是按「实时性」排的：两个 ghproxy 都是整串转发、基本不缓存，jsDelivr 走 CDN
     *   有小时级缓存，所以放最后 —— 它只在直连和 ghproxy 全挂时才用得上。
     * - [DownloadRoute.DIRECT]：只走官方直连（用户明确选的）。
     * - 指定镜像：只走那一条，与下载侧同语义 —— 既然手动选了线路，就不该偷偷换线。
     */
    internal fun checkCandidates(route: DownloadRoute = DownloadRoute.AUTO): List<String> {
        val base = manifestUrl
        val prefix = route.prefix ?: return buildList {
            add(base)
            MIRROR_PREFIXES.forEach { add(it + base) }
            jsdelivr(base)?.let { add(it) }
        }
        return listOf(if (prefix.isEmpty()) base else prefix + base)
    }

    /**
     * 查询最新版本。永不抛异常，失败一律折成 [UpdateCheckResult.Failed]。
     *
     * 按 [route] 依次尝试候选线路（见 [checkCandidates]）：直连超时 / 被阻断时自动退到镜像，
     * 总耗时受 [CHECK_BUDGET_MS] 约束 —— 国内网络下「一路干等到十几秒再报错」是最糟的体验。
     *
     * 采用第一个**解析成功**的响应：某条线路返回坏数据（代理错误页、半截 JSON）时继续换线，
     * 而不是把「格式不正确」直接甩给用户。
     */
    suspend fun checkLatest(
        current: String = currentVersion,
        route: DownloadRoute = DownloadRoute.AUTO,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        // v1.1.97：非官方签名构建（改包 / 二次分发）不再提供更新链路 ——
        // 这是签名校验唯一"卡住"的地方，播放等核心功能不受影响，避免误伤自编译用户。
        if (!AppIntegrity.isOfficial) {
            return@withContext UpdateCheckResult.Failed(
                "当前安装包不是官方签名构建（${AppIntegrity.describe()}），已停用应用内更新",
            )
        }
        val candidates = checkCandidates(route)
        val deadline = System.currentTimeMillis() + CHECK_BUDGET_MS
        var lastError: Throwable? = null
        var lastCode = 0

        for ((index, url) in candidates.withIndex()) {
            // 第一条之外的线路先看预算：剩余时间不够就别开新连接了，早点给出可操作的提示
            if (index > 0 && deadline - System.currentTimeMillis() < MIN_LINE_BUDGET_MS) break
            try {
                val resp = httpGet(url, accept = "application/json")
                lastCode = resp.code
                if (resp.code == 200) {
                    val parsed = parseManifest(resp.body, current, viaMirror = index > 0)
                    if (parsed !is UpdateCheckResult.Failed) return@withContext parsed
                    if (verbose) Log.w(TAG, "线路返回的清单不可用，换下一条：$url")
                    continue
                }
                if (verbose) Log.w(TAG, "线路返回 HTTP ${resp.code}：$url")
            } catch (t: Throwable) {
                lastError = t
                if (verbose) Log.w(TAG, "线路失败：$url", t)
            }
        }

        when {
            // 404 是确定性结论：文件在仓库里就是不存在，换线路也变不出来
            lastCode == 404 -> UpdateCheckResult.Failed("公共仓库还没有发布版本")
            lastCode != 0 -> UpdateCheckResult.Failed("检查更新失败（HTTP $lastCode）")
            else -> UpdateCheckResult.Failed(describe(lastError ?: IOException("网络异常")))
        }
    }

    /**
     * 解析 `latest.json` 并做出结论。纯函数、可单测。
     *
     * 清单结构（由发布脚本生成，`schema` 目前是 1）：
     * ```json
     * { "schema":1, "version":"1.1.30", "versionCode":40, "tag":"v1.1.30",
     *   "publishedAt":"2026-10-19T04:10:00Z", "notes":"…",
     *   "assets":[{"abi":"arm64-v8a","file":"WindTone-v1.1.30-arm64-v8a.apk",
     *              "size":3023626,"sha256":"…"}] }
     * ```
     *
     * ⚠️ `schema` 只做「忽略未知字段」的前向兼容：这是分发渠道，客户端散落在旧版本上，
     * **新字段必须可选、旧端不能被新清单弄崩**。
     */
    internal fun parseManifest(
        body: String,
        current: String,
        abis: List<String> = deviceAbis(),
        viaMirror: Boolean = false,
    ): UpdateCheckResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return UpdateCheckResult.Failed("版本清单格式不正确")
        val version = normalizeVersion(json.optString("version"))
        if (version.isBlank()) return UpdateCheckResult.Failed("版本清单里没有版本号")
        if (!isNewer(version, current)) return UpdateCheckResult.UpToDate(version, viaMirror)

        val tag = json.optString("tag").ifBlank { "v$version" }
        val versionDir = "$appBase/$tag"
        val ordered = orderAssets(json.optJSONArray("assets"), abis)
        if (ordered.isEmpty()) return UpdateCheckResult.Failed("新版本没有附带 APK 安装包")

        val first = ordered.first()
        val urls = ordered.map { "$versionDir/${it.first}" }
        return UpdateCheckResult.Available(
            UpdateInfo(
                version = version,
                tagName = tag,
                title = json.optString("displayName").ifBlank { "WindTone" } + " $version",
                notes = plainNotes(json.optString("notes")),
                apkUrl = urls.first(),
                apkName = first.first,
                apkUrlFallbacks = urls.drop(1),
                apkSizeBytes = first.second,
                publishedAt = json.optString("publishedAt"),
                releaseUrl = "$versionDir".replace(
                    "https://raw.githubusercontent.com/",
                    "https://github.com/",
                ).replace(
                    "/${BuildConfig.RELEASE_BRANCH}/",
                    "/tree/${BuildConfig.RELEASE_BRANCH}/",
                ),
                apkSha256 = first.third,
            )
        )
    }

    /**
     * 按「本机 ABI 优先顺序 → universal → 其它」排列清单里的包，返回 (文件名, 大小, sha256)。
     *
     * 不能只取第一个：清单是按 ABI 顺序生成的，而设备该装哪个由**设备自己**说了算。
     * universal 放最后兜底，其它 ABI 的包排在 universal 之后（装了也能跑，只是不是最优）。
     */
    internal fun orderAssets(
        assets: org.json.JSONArray?,
        abis: List<String>,
    ): List<Triple<String, Long, String>> {
        if (assets == null) return emptyList()
        data class Item(val abi: String, val file: String, val size: Long, val sha: String)

        val items = (0 until assets.length()).mapNotNull { i ->
            val o = assets.optJSONObject(i) ?: return@mapNotNull null
            val file = o.optString("file").trim()
            if (file.isBlank()) return@mapNotNull null
            Item(o.optString("abi").trim(), file, o.optLong("size", 0L), o.optString("sha256").trim())
        }
        if (items.isEmpty()) return emptyList()

        val known = abis.filter { it.isNotBlank() }
        val score: (Item) -> Int = { item ->
            val idx = known.indexOf(item.abi)
            when {
                idx >= 0 -> idx              // 本机 ABI，按优先级
                item.abi == "universal" -> 100
                else -> 200                  // 别的 ABI，最后尝试
            }
        }
        return items.sortedBy(score).map { Triple(it.file, it.size, it.sha) }
    }

    // ------------------------------------------------------------ 下载

    /**
     * 下载安装包的候选线路（按尝试优先级排列）。
     *
     * 两个维度相乘：
     *  - **包名维度**（[urls]）：本机 ABI 包 → 其它包 → universal，见 [orderAssets]；
     *  - **线路维度**（[route]）：由用户在「设置 → 更新 → 下载线路」里选择，默认 [DownloadRoute.AUTO]：
     *    · [DownloadRoute.AUTO]：直链优先，某条线路**报错**（连不上 / HTTP 错误）时落到下一条；
     *    · [DownloadRoute.DIRECT]：只走 GitHub 官方直链；
     *    · 指定镜像：**只走这一条**（用户既然手动选了，就不该偷偷换线，失败时界面会提示去哪改）。
     *
     * v1.1.74 起**不再做速度自动切换**（旧版起步慢就中断换线）：下载中途换线会让进度
     * 反复清零，用户困惑；慢线路由用户在下载弹窗里点「切换线路」手动解决。
     *
     * 镜像只做字节转发，不改动文件内容，且下载后仍有 [verifyApk] 兜底校验。
     */
    internal fun downloadCandidates(
        urls: List<String>,
        route: DownloadRoute = DownloadRoute.AUTO,
    ): List<String> {
        val out = LinkedHashSet<String>()
        urls.forEach { url ->
            val mirrors = mirrorUrls(url)
            if (mirrors.isEmpty()) {
                out.add(url)
                return@forEach
            }
            val prefix = route.prefix
            if (prefix == null) {
                out.add(url)
                mirrors.forEach { out.add(it) }
            } else {
                // 手动指定：只给这一条（空前缀 = 直链）
                out.add(if (prefix.isEmpty()) url else prefix + url)
            }
        }
        return out.toList().ifEmpty { urls }
    }

    /** 单 URL 版本（测试与简单场景用） */
    internal fun downloadCandidates(
        apkUrl: String,
        route: DownloadRoute = DownloadRoute.AUTO,
    ): List<String> = downloadCandidates(listOf(apkUrl), route)

    /**
     * 一个直链的可用镜像。非 GitHub 资源（含自定义直链）返回空列表 = 不套镜像。
     *
     * jsDelivr 走**仓库文件**通道（`cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/<path>`），
     * 对 raw 直链来说前缀不能简单拼接，必须重排成 `owner/repo@branch/path`；
     * ghproxy 一类是「整串 URL 当路径」，直接加前缀即可。
     */
    private fun mirrorUrls(url: String): List<String> {
        if (!url.startsWith("https://", ignoreCase = true)) return emptyList()
        val list = mutableListOf<String>()
        jsdelivr(url)?.let { list.add(it) }
        if (url.contains("githubusercontent.com/") || url.contains("github.com/")) {
            MIRROR_PREFIXES.forEach { list.add(it + url) }
        }
        return list
    }

    /** raw 直链 → jsDelivr 仓库文件链接；不是 raw 直链时返回 null */
    private fun jsdelivr(url: String): String? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!url.startsWith(prefix, ignoreCase = true)) return null
        val parts = url.substring(prefix.length).split('/', limit = 4)
        if (parts.size < 4) return null
        val (owner, repo, branch, path) = parts
        if (owner.isBlank() || repo.isBlank() || branch.isBlank() || path.isBlank()) return null
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    /**
     * 下载 APK 到 `cacheDir/updates/`。
     * 下载前会清空该目录，避免历史版本堆积；先写 `.part` 再做完整性校验后才改名。
     *
     * **线路由 [route] 决定**（见 [downloadCandidates]）：自动模式下按顺序逐条尝试，
     * 但**只在线路真正报错**（连不上 / HTTP 错误 / 校验失败）时才换下一条 ——
     * v1.1.74 起不再做速度自动切换（旧版起步 [STALL 已移除] 起步慢就中断换线，
     * 会让进度反复清零），慢线路由用户在下载弹窗里点「切换线路」手动解决。
     * 手动指定线路时只用那一条，失败即报错。
     * 全部线路失败时抛最后一次的错误，由界面引导用户浏览器手动下载。
     *
     * @param route 下载线路（设置页可选，默认自动降级）
     * @param onProgress 参数为 (已下载字节, 总字节)，总字节为 0 时表示长度未知
     * @param onLineFallback 某条线路**报错**即将切换时回调 (即将尝试的线路序号, 候选总数)，
     *   供界面提示「正在换线路」；进度条会从头重新计数
     * @param checkCancelled 下载中由调用方轮询的取消探针（v1.1.74，供「切换线路」
     *   中止旧下载用）：返回 true 时尽快抛出、让出下载通道。每个 64KB 分块查一次。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        route: DownloadRoute = DownloadRoute.AUTO,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onLineFallback: (Int, Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Boolean = { false },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val safeName = info.apkName
            .ifBlank { "WindTone-v${info.version}.apk" }
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val target = File(dir, safeName)
        val part = File(dir, "$safeName.part")

        val candidates = downloadCandidates(info.allApkUrls, route)
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            if (checkCancelled()) throw IOException(DOWNLOAD_ABORTED_MSG)
            try {
                val responseTotal = downloadOnce(candidate, part, info, onProgress, checkCancelled)
                verifyApk(part, info.apkSizeBytes, responseTotal, info.apkSha256)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                return@withContext target
            } catch (t: Throwable) {
                lastError = t
                runCatching { part.delete() }
                if (checkCancelled()) throw IOException(DOWNLOAD_ABORTED_MSG)
                if (verbose) Log.w(TAG, "下载线路失败，切换下一条: $candidate", t)
                val next = index + 1
                if (next < candidates.size) onLineFallback(next + 1, candidates.size)
            }
        }
        throw lastError ?: IOException("所有下载线路均失败")
    }

    /**
     * 下载单个候选线路到 [part]，返回响应声明的总字节数（0 = 未知）。
     *
     * 断点续传刻意不做：安装包只有 3MB，而 `.part` 与「换线路重来」已经够用；
     * 续传要处理 206/416、ETag 变化与代理返回错误页，投入产出不划算。
     */
    private fun downloadOnce(
        url: String,
        part: File,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit,
        checkCancelled: () -> Boolean,
    ): Long {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WindTone-Android/$currentVersion")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            var downloaded = 0L
            part.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n = input.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                        // v1.1.74 起不再做速度自动切换（起步慢就中断换线会让进度反复清零），
                        // 只响应调用方的取消探针 —— 慢线路由用户点「切换线路」手动解决。
                        if (checkCancelled()) throw IOException(DOWNLOAD_ABORTED_MSG)
                        n = input.read(buf)
                    }
                    out.flush()
                }
            }
            return total
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 校验下载到的文件。
     *
     * 三层，从便宜到贵：
     *  1. APK 是 zip，头 2 字节应为 `PK`；且不能小于 100KB（防代理返回错误页）；
     *  2. 大小：优先用清单声明的字节数（[expectedSize]），否则用响应头；
     *  3. **SHA-256**：清单给了摘要就逐字节比 —— 这是唯一能发现「内容被中间环节替换」的检查，
     *     镜像线路越多越需要它（镜像只该转发字节）。
     */
    private fun verifyApk(file: File, expectedSize: Long, responseSize: Long, expectedSha256: String) {
        if (!file.exists() || file.length() < 100 * 1024) {
            throw IOException("下载的安装包不完整")
        }
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) }
        val isZip = head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        if (!isZip) throw IOException("下载的文件不是有效的 APK")

        // 有任何一个可信的大小基准时，做严格比对；不一致说明下载被截断或代理返回了错误页
        val basis = when {
            expectedSize > 0 -> expectedSize
            responseSize > 0 -> responseSize
            else -> return
        }
        if (file.length() != basis) {
            throw IOException("安装包大小不符（${file.length()} / $basis），请重新下载")
        }
        if (expectedSha256.isNotBlank()) {
            val actual = sha256Of(file)
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                throw IOException("安装包校验失败（SHA-256 不一致），可能被中间环节改动，请换线路重试")
            }
        }
    }

    /** 文件 SHA-256（小写十六进制） */
    internal fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 安装

    /** Android 8.0+ 需要用户先允许「安装未知应用」 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }

    /** 交给系统安装器。失败返回带原因的 [Result] */
    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // clipData 不是装饰：部分 ROM（车机居多）的安装器只认 clipData 里的
            // grant，单靠 FLAG_GRANT_READ_URI_PERMISSION 会读不到文件，
            // 表现为「安装器一闪而过 / 一直转圈」，用户只能杀掉应用重来。
            clipData = android.content.ClipData.newRawUri("apk", uri)
        }
        context.startActivity(intent)
    }.onFailure { if (verbose) Log.w(TAG, "拉起安装器失败", it) }

    /** 跳转到「安装未知应用」授权页 */
    fun unknownSourcesIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }

    // ------------------------------------------------------------ 工具

    /** 一次 GET 的结果：状态码、响应头、正文、连接（供 finally 断开） */
    private class HttpResp(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val connection: HttpURLConnection,
    )

    /**
     * 测一条线路的延迟（v1.1.74，「下载线路」弹窗的「测试延迟」用）。
     *
     * 对该线路的**首个候选 URL**（即 `latest.json`，只有几 KB）发一次带 Range 的小请求，
     * 计时到拿到响应首包为止 —— 只衡量「这条线通不通、快不快」，不比下载大文件的速度。
     * 失败 / 超时 / 非 2xx 一律返回 `null`（界面显示「超时」）。
     */
    fun measureLatency(route: DownloadRoute): Long? {
        val url = checkCandidates(route).first()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PING_TIMEOUT_MS
                readTimeout = PING_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WindTone-Android/$currentVersion")
                setRequestProperty("Range", "bytes=0-1023") // 只要一小段，拿到首包就断开
            }
            val startedAt = System.currentTimeMillis()
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - startedAt
            if (code in 200..299 || code == 206) elapsed else null
        } catch (t: Throwable) {
            if (verbose) Log.w(TAG, "测延迟失败：${route.label}", t)
            null
        } finally {
            runCatching { conn?.inputStream?.close() }
            runCatching { conn?.errorStream?.close() }
            conn?.disconnect()
        }
    }

    /**
     * 统一的 GET 请求：不抛状态码异常，404 也照常返回，由调用方决定文案；
     * 真正的 IO 异常仍然抛出，由调用方翻译。
     */
    private fun httpGet(
        url: String,
        accept: String,
        configure: (HttpURLConnection) -> Unit = {},
    ): HttpResp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // 清单只有几 KB：超时给短一点，多出来的时间留给后续镜像线路。
            // 旧版 10s/15s 且只有一条线，国内直连被阻断时用户要干等 25 秒才看到「超时」。
            connectTimeout = CHECK_CONNECT_TIMEOUT_MS
            readTimeout = CHECK_READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "WindTone-Android/$currentVersion")
            configure(this)
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResp(code, conn.headerFields.orEmpty(), body, conn)
        } catch (t: Throwable) {
            conn.disconnect()
            throw t
        } finally {
            // 连接由调用方在读完之后断开；这里只兜住异常路径，避免重复 disconnect
        }
    }

    /** 语义化版本比较：3.10.0 > 3.9.2。非数字段按 0 处理 */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    private fun parseVersion(raw: String): List<Int> =
        normalizeVersion(raw)
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.trim().toIntOrNull() ?: 0 }

    fun plainNotes(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.replace("\r\n", "\n")

        // ---- 表格：HTML 与 Markdown 都折成「• 单元格 · 单元格」 ----
        // HTML 的 <tr>：表头（th）与数据（td）同等对待，否则两条通道对不上
        s = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).replace(s) { row ->
            val cells = Regex("<t[hd][^>]*>(.*?)</t[hd]>", RegexOption.DOT_MATCHES_ALL)
                .findAll(row.groupValues[1])
                .map { stripTags(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (cells.isEmpty()) "" else "\n• " + cells.joinToString(" · ")
        }
        // Markdown 的 | a | b |：对齐行（| --- | --- |）整行丢掉，其余折成同一种形式
        // 行首行尾一律用 `[ \t]` 而不是 `\s`：`\s` 会吃掉换行，把两行粘成一行
        s = Regex("(?m)^[ \\t]*\\|[ \\t:|-]*\\|[ \\t]*$").replace(s, "")
        s = Regex("(?m)^[ \\t]*\\|(.+)\\|[ \\t]*$").replace(s) { row ->
            val cells = row.groupValues[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.isEmpty()) "" else "• " + cells.joinToString(" · ")
        }

        // ---- HTML → 文本 ----
        s = Regex("(?i)<br[^>]*>").replace(s, "\n")
        s = Regex("(?i)</(p|div|h[1-6]|ul|ol|li|table|thead|tbody|tr|blockquote|pre)>").replace(s, "\n")
        s = Regex("(?i)<li[^>]*>").replace(s, "• ")
        // 先剥标签再解实体：反过来的话，正文里被转义的 &lt; 会变成尖括号被吃掉
        s = stripTags(s)
        s = decodeHtmlEntities(s)

        // ---- Markdown → 文本 ----
        s = Regex("(?m)^[ \\t]*#{1,6}[ \\t]*").replace(s, "")
        s = Regex("(?m)^[ \\t]*[-*+][ \\t]+").replace(s, "• ")
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s) { it.groupValues[1] }
        s = Regex("__(.+?)__").replace(s) { it.groupValues[1] }
        s = Regex("`([^`]+)`").replace(s) { it.groupValues[1] }
        s = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(s) { it.groupValues[1] }

        // ---- 收尾：去行首尾空白，连续空行压成一条 ----
        s = Regex("[ \\t]+\\n").replace(s, "\n")
        s = Regex("(?m)^[ \\t]+").replace(s, "")
        s = Regex("\\n{2,}").replace(s, "\n")
        return s.trim()
    }

    /** 去掉 HTML 标签（单个 `<...>` 视为标签；Release 说明里不存在裸的 `<`） */
    private fun stripTags(s: String): String = Regex("<[^>]*>").replace(s, "")

    /**
     * 解码 HTML 实体。认不出的实体**原样保留** —— 宁可显示 `&foo;`，
     * 也不要因为不认识就直接吞掉（信息丢了更难查）。
     */
    private fun decodeHtmlEntities(s: String): String =
        Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);").replace(s) { m ->
            val body = m.groupValues[1]
            val decoded = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.drop(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull())
                else -> ENTITIES[body.lowercase()]
            }
            decoded ?: m.value
        }

    private fun codePoint(code: Int?): String? =
        if (code == null || code <= 0) null
        else runCatching { String(Character.toChars(code)) }.getOrNull()

    /** 常用命名实体；不追求全覆盖，认不出的会原样保留 */
    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "middot" to "·", "hellip" to "…", "mdash" to "—",
        "ndash" to "–", "times" to "×", "copy" to "©", "reg" to "®",
        "trade" to "™", "laquo" to "«", "raquo" to "»", "deg" to "°",
    )

    /**
     * 把下载失败翻译成用户可操作的一句话。
     * 手动指定线路时点明是哪条线、并告诉用户去哪里换。
     */
    internal fun describeDownloadFailure(route: DownloadRoute, error: Throwable?): String {
        val base = error?.message?.ifBlank { null } ?: "网络异常"
        return if (route == DownloadRoute.AUTO) {
            "下载失败：$base\n可到「设置 → 更新 → 下载线路」换一条线路，或点「在浏览器中查看发布页」手动下载。"
        } else {
            "「${route.label}」线路下载失败：$base\n" +
                "可到「设置 → 更新 → 下载线路」换成「自动」或其它线路后重试。"
        }
    }

    /** 把异常翻译成用户能看懂、且**能照着做**的一句话 */
    private fun describe(t: Throwable): String = when (t) {
        // DNS 污染在国内很常见，表现就是「网络明明通着却解析不了」，
        // 直接说「网络不可用」会让用户去白折腾路由器。
        is java.net.UnknownHostException -> "无法解析更新服务器地址，请检查网络后重试"
        is java.net.SocketTimeoutException ->
            "连接更新服务器超时，请稍后重试，或点「打开发布页面」手动下载"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络环境"
        is IOException -> "网络异常：${t.message ?: "请稍后重试"}"
        else -> "检查更新失败：${t.message ?: t.javaClass.simpleName}"
    }

    /**
     * 把字节数格式化成 MB，用于界面展示。
     * 大小未知时返回空串 —— 调用点都是「… · ${formatSize(…)}」的拼接形式，
     * 返回空串能让分隔符一并消失，不会显示成「· 未知大小」。
     */
    fun formatSize(bytes: Long): String =
        if (bytes <= 0) "" else "%.2f MB".format(bytes / 1024.0 / 1024.0)

    private const val DIR_NAME = "updates"

    /** GitHub 加速镜像（给原始 URL 加前缀即生效；均为公开代理，只转发字节，仅用于下载安装包） */
    private val MIRROR_PREFIXES = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
    )

    /** 检查更新：单条线路的连接 / 读取超时。清单只有几 KB，连不上就尽快换线 */
    private const val CHECK_CONNECT_TIMEOUT_MS = 6_000
    private const val CHECK_READ_TIMEOUT_MS = 8_000

    /** 检查更新的总预算：用完就停，别让用户盯着「正在检查更新…」干等 */
    private const val CHECK_BUDGET_MS = 18_000L

    /** 剩余预算低于这个值就不再开新连接（开了也来不及读完） */
    private const val MIN_LINE_BUDGET_MS = 3_000L

    /** 测延迟：连接 / 读取超时（结果只要个大概，超时即视为线路不通） */
    private const val PING_TIMEOUT_MS = 5_000

    /** 「切换线路」中止旧下载时抛出的标记文案（调用方据此不把它当失败展示） */
    internal const val DOWNLOAD_ABORTED_MSG = "下载已取消（切换线路）"
}
