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

/** 从 GitHub Release 解析出来的新版本信息 */
data class UpdateInfo(
    /** 版本号（已去掉 tag 的 v 前缀），如 1.0.0 */
    val version: String,
    /** 原始 tag 名，如 v1.0.0 */
    val tagName: String,
    /** Release 标题 */
    val title: String,
    /** Release 说明（已做轻量 Markdown 清理，可直接展示） */
    val notes: String,
    /** APK 下载直链（首选：本机 ABI 对应的那个包） */
    val apkUrl: String,
    /** APK 文件名，如 WindTone-v1.0.0-arm64-v8a.apk */
    val apkName: String,
    /**
     * 同一版本其它候选包的直链，按「本机首选 ABI → 其它 ABI → universal → 历史单包命名」排序。
     * 首选 [apkUrl] 失败时依次尝试——Release 里的资产可能不齐全（例如只出了 universal）。
     */
    val apkUrlFallbacks: List<String> = emptyList(),
    /** APK 字节数，0 表示未知 */
    val apkSizeBytes: Long,
    /** 发布时间（原始 ISO 字符串） */
    val publishedAt: String,
    /** Release 网页地址 */
    val releaseUrl: String,
) {
    /** 去重后的全部候选直链（首选在最前） */
    val allApkUrls: List<String> get() = (listOf(apkUrl) + apkUrlFallbacks).distinct()
}

sealed interface UpdateCheckResult {
    /** 有新版本可用 */
    data class Available(val info: UpdateInfo) : UpdateCheckResult

    /** 已是最新 */
    data class UpToDate(val latestVersion: String) : UpdateCheckResult

    /** 检查失败（离线 / 限流 / 未发布等），[reason] 可直接展示给用户 */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * 应用内更新。
 *
 * 数据来源是公开的 GitHub Releases，因此**不需要任何鉴权**。
 *
 * ## 关于「接口访问过于频繁」（重要）
 *
 * GitHub REST API 对未认证请求按**出口 IP** 限制 60 次/小时。手机常处于运营商
 * NAT / 代理后面，同一出口 IP 被大量用户共享，配额极易被别人耗尽——此时
 * `api.github.com` 会返回 403 `rate limit exceeded`，与用户自己的使用频率无关。
 *
 * 因此这里**不再以 API 为主通道**，而是改用两个不受该配额限制的公开网页资源：
 *  1. `releases.atom`（Atom 源）—— 拿版本号、标题、说明、发布时间、Release 页地址；
 *  2. `releases/download/<tag>/<apk>`（资产直链）—— 按 CI 固定的命名规则拼出下载地址。
 * 两者都不计入 API 配额，也不会因为共享 IP 而失败。
 *
 * API 端点仅作为 Atom 不可用时的降级兜底（例如 Atom 源被中间设备改写）。
 *
 * ## 关于「镜像 / 代理」（重要）
 *
 * **检查更新永远只访问 GitHub 官方地址**（`releases.atom` → `api.github.com/releases/latest`）。
 * 曾经的实现让检查也走加速镜像，结果镜像缓存了旧内容：刚发布的 1.0.6 在镜像上还不存在，
 * 客户端就报「已是最新」。因此镜像只用于**下载安装包**，且线路由用户在
 * 「设置 → 更新 → 下载线路」中决定（[DownloadRoute]，默认自动降级）。
 *
 * 全流程只在用户点击「检查更新 / 下载」或启动时自动检查（可关闭）时发生，
 * 不上报任何设备信息，也不访问除 GitHub 之外的任何域名。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** 是否输出调试日志（Release 包默认关闭，避免刷 logcat） */
    private var verbose = false

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    private val apiBase: String
        get() = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"

    private val latestReleaseApi: String
        get() = "$apiBase/releases/latest"

    /** Atom 源：普通网页资源，不受 API 每小时 60 次的列表限制 */
    private val releasesAtomUrl: String
        get() = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases.atom"

    /** 当前安装版本（来自 BuildConfig，与 build.gradle.kts 的 versionName 一致） */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    // ------------------------------------------------------------ 架构与资产名

    /**
     * 本机 CPU 架构（主 ABI 在最前）。
     *
     * 用 [android.os.Build.SUPPORTED_ABIS] 而不是 `Build.CPU_ABI`：前者是完整的优先级列表，
     * 64 位设备上第一个就是 arm64-v8a，能直接对上 Release 里的资产名。
     * 单元测试环境下取不到（返回 null），此时给空列表，由 [apkNameCandidates] 退到 universal。
     */
    internal fun deviceAbis(): List<String> =
        runCatching { android.os.Build.SUPPORTED_ABIS?.toList().orEmpty() }
            .getOrDefault(emptyList())

    /**
     * 按优先级列出这个版本可能存在的 APK 文件名。
     *
     * 顺序（**这就是下载尝试顺序**）：
     *  1. 本机各 ABI：`WindTone-v{版本}-arm64-v8a.apk`（v1.0.7 起的 ABI 分包命名）；
     *  2. `WindTone-v{版本}-universal.apk`（通用包，任何设备都能装）；
     *  3. `WindTone-v{版本}.apk`（**历史命名**：1.0.6 及以前只发一个全 ABI 包）。
     *
     * 保留第 3 条是为了向下兼容——用户从旧版本升级时，Release 里可能只有老命名的包。
     * 与 gradle 的命名规则（app/build.gradle.kts 的 androidComponents）必须保持一致。
     */
    internal fun apkNameCandidates(version: String, abis: List<String> = deviceAbis()): List<String> {
        val known = abis.filter { it in KNOWN_ABIS }
        val names = buildList {
            known.forEach { add(BuildConfig.APK_ASSET_TEMPLATE_ABI.format(version, it)) }
            add(BuildConfig.APK_ASSET_TEMPLATE_UNIVERSAL.format(version))
            add(BuildConfig.APK_ASSET_TEMPLATE.format(version))
        }
        return names.distinct()
    }

    /** 把资产名换算成直链（同一个 tag 目录下） */
    private fun assetUrl(tag: String, name: String): String =
        "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}" +
            "/releases/download/$tag/$name"

    /** 页面展示用的架构标签，如 `arm64-v8a`；取不到时返回空串 */
    fun primaryAbiLabel(): String = deviceAbis().firstOrNull().orEmpty()

    /** Release 列表页，检查失败时可引导用户手动打开 */
    fun releasePageUrl(): String = BuildConfig.GITHUB_RELEASES_URL

    // ------------------------------------------------------------ 查询

    /**
     * 查询最新版本。永不抛异常，失败一律折成 [UpdateCheckResult.Failed]。
     *
     * **只走 GitHub 官方地址**（Atom 源 → REST API），不用任何镜像/代理：
     * 镜像有缓存，刚发布的版本在镜像上可能还不存在，会误判成「已是最新」。
     *
     * 先用 Atom 源（不受 API 限流），失败再退回 REST API。
     */
    suspend fun checkLatest(current: String = currentVersion): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            // ---------- 通道 1：Atom 源（GitHub 官方，无 API 配额） ----------
            val viaAtom = runCatching { fetchLatestViaAtom(releasesAtomUrl, current) }.getOrNull()
            when (val r = viaAtom) {
                is UpdateCheckResult.Available,
                is UpdateCheckResult.UpToDate -> return@withContext r

                is UpdateCheckResult.Failed -> {
                    // Atom 明确报「没有发布版本」时不浪费一次 API 配额
                    if (r.reason.contains("还没有发布")) return@withContext r
                }

                null -> if (verbose) Log.w(TAG, "Atom 源不可用（$releasesAtomUrl），退回 API")
            }

            // ---------- 通道 2：REST API（同样是 GitHub 官方地址） ----------
            fetchLatestViaApi(current)
        }

    // ------------------------------------------------------------ 通道 1：Atom

    /**
     * 解析 `releases.atom`。条目按时间倒序，第一条即最新发布。
     *
     * 注意：Atom 源**不包含** assets 列表，下载地址按 CI 的固定命名规则推导，
     * 若推导出的地址不可用，会回退到 REST API 拿真实资产链接。
     */
    private fun fetchLatestViaAtom(atomUrl: String, current: String): UpdateCheckResult {
        val body = httpGet(atomUrl, accept = "application/atom+xml").body
        return parseAtom(body, current)
    }

    /**
     * 解析 Atom 正文，纯函数、可单测。
     *
     * 条目按时间倒序，第一条即最新发布；Atom 源**不包含** assets 列表，
     * 下载地址按 CI 的固定命名规则推导。
     */
    internal fun parseAtom(body: String, current: String): UpdateCheckResult {
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
        if (entries.isEmpty()) return UpdateCheckResult.Failed("仓库还没有发布正式版本")

        val first = entries.first()
        val title = xmlText(first, "title")
        val version = normalizeVersion(atomTagOf(first)).ifBlank { normalizeVersion(title) }
        if (version.isBlank()) return UpdateCheckResult.Failed("无法解析最新版本号")
        if (!isNewer(version, current)) return UpdateCheckResult.UpToDate(version)

        val tag = atomTagOf(first).ifBlank { "v$version" }
        val names = apkNameCandidates(version)
        val urls = names.map { assetUrl(tag, it) }

        return UpdateCheckResult.Available(
            UpdateInfo(
                version = version,
                tagName = tag,
                title = title.ifBlank { "WindTone $version" },
                notes = plainNotes(xmlText(first, "content")),
                apkUrl = urls.first(),
                apkName = names.first(),
                apkUrlFallbacks = urls.drop(1),
                apkSizeBytes = 0L, // Atom 源没有资产大小，下载时以实际 Content-Length 为准
                publishedAt = xmlText(first, "updated"),
                releaseUrl = Regex("href=\"([^\"]+)\"")
                    .find(first.substringAfter("<link rel=\"alternate\"", ""))
                    ?.groupValues?.get(1)
                    ?: releasePageUrl(),
            )
        )
    }

    /** 从 Atom 条目的 `<id>` 里取出 tag，形如 `tag:github.com,2008:Repository/123/v1.0.2` */
    private fun atomTagOf(entry: String): String {
        val id = xmlText(entry, "id")
        return id.substringAfterLast('/').trim()
    }

    /** 取出 XML 标签文本并反转义（Atom 的 content 是 HTML 转义后的字符串） */
    private fun xmlText(xml: String, tag: String): String {
        val m = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: return ""
        return m.groupValues[1]
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&")
            .trim()
    }

    // ------------------------------------------------------------ 通道 2：REST API

    /** 原 API 通道，保留作为兜底；403/429 时给出准确的恢复时间 */
    private fun fetchLatestViaApi(current: String): UpdateCheckResult {
        var conn: HttpURLConnection? = null
        try {
            val resp = httpGet(latestReleaseApi, accept = "application/vnd.github+json") { c ->
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            conn = resp.connection
            when (resp.code) {
                200 -> Unit
                404 -> return UpdateCheckResult.Failed("仓库还没有发布正式版本")
                403, 429 -> return UpdateCheckResult.Failed(rateLimitMessage(resp.headers))
                else -> return UpdateCheckResult.Failed("检查更新失败（HTTP ${resp.code}）")
            }

            val json = JSONObject(resp.body)

            val tag = json.optString("tag_name").trim()
            val version = normalizeVersion(tag).ifBlank { normalizeVersion(json.optString("name")) }
            if (version.isBlank()) {
                return UpdateCheckResult.Failed("无法解析最新版本号")
            }

            if (!isNewer(version, current)) {
                return UpdateCheckResult.UpToDate(version)
            }

            val asset = pickApkAsset(json, version)
                ?: return UpdateCheckResult.Failed("新版本没有附带 APK 安装包")

            val allUrls = (listOf(asset.first) + asset.fourth).distinct()

            return UpdateCheckResult.Available(
                UpdateInfo(
                    version = version,
                    tagName = tag.ifBlank { "v$version" },
                    title = json.optString("name").ifBlank { "WindTone $version" },
                    notes = plainNotes(json.optString("body")),
                    apkUrl = asset.first,
                    apkName = asset.second,
                    apkUrlFallbacks = allUrls.drop(1),
                    apkSizeBytes = asset.third,
                    publishedAt = json.optString("published_at"),
                    releaseUrl = json.optString("html_url").ifBlank { releasePageUrl() },
                )
            )
        } catch (t: Throwable) {
            if (verbose) Log.w(TAG, "检查更新失败", t)
            return UpdateCheckResult.Failed(describe(t))
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 把 403/429 翻译成人话。若响应头带了 `X-RateLimit-Reset`（Unix 秒），
     * 直接算出还有几分钟恢复，避免用户反复重试。
     */
    private fun rateLimitMessage(headers: Map<String, List<String>>): String {
        val remaining = headers["X-RateLimit-Remaining"]?.firstOrNull()?.trim()?.toIntOrNull()
        val resetAt = headers["X-RateLimit-Reset"]?.firstOrNull()?.trim()?.toLongOrNull()
        return rateLimitMessage(remaining, resetAt, System.currentTimeMillis())
    }

    /** 纯函数版本，便于单测（不依赖系统时钟与 HttpURLConnection） */
    internal fun rateLimitMessage(remaining: Int?, resetAtEpochSec: Long?, nowMs: Long): String {
        val wait = resetAtEpochSec?.let { it * 1000L - nowMs } ?: -1L

        return when {
            wait in 1..A_CLOCK_SKEW_MAX_MS -> {
                val minutes = ((wait + 59_999L) / 60_000L).coerceAtLeast(1L)
                "GitHub 访问额度已用完（$minutes 分钟后恢复）"
            }
            // 403 但没有 Reset 头：多半是共享出口 IP 被判为异常流量
            remaining == 0 -> "GitHub 访问额度已用完，请稍后再试"
            else -> "GitHub 暂时拒绝访问，请稍后再试"
        }
    }

    /**
     * 从 assets 里挑 APK，按「本机架构优先」排序：
     *  1. 与 [apkNameCandidates] 同名者（本机 ABI → universal → 历史单包命名）；
     *  2. 兜底：任意名字里带 `universal` 的 .apk；
     *  3. 再兜底：任意 .apk。
     *
     * 返回 (选中直链, 选中文件名, 字节数, 备选直链列表)。
     */
    private fun pickApkAsset(
        json: JSONObject,
        version: String,
    ): Quadruple<String, String, Long, List<String>>? {
        val assets = json.optJSONArray("assets") ?: return null
        data class Asset(val name: String, val url: String, val size: Long)

        val apks = buildList {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url")
                if (url.isBlank()) continue
                add(Asset(name, url, a.optLong("size", 0L)))
            }
        }
        if (apks.isEmpty()) return null

        val preferred = apkNameCandidates(version)
            .firstNotNullOfOrNull { want -> apks.firstOrNull { it.name == want } }
            ?: apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.first()

        val others = apks.filter { it.url != preferred.url }.map { it.url }
        return Quadruple(preferred.url, preferred.name, preferred.size, others)
    }

    /** 四元组（Kotlin 只有 Triple，这里需要四个值） */
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // ------------------------------------------------------------ 下载

    /**
     * 下载安装包的候选线路（按尝试优先级排列）。
     *
     * 两个维度相乘：
     *  - **包名维度**（[urls]）：本机 ABI 包 → universal → 历史单包命名，见 [apkNameCandidates]。
     *    包名依次在前，因为 404 是立刻返回的，不会浪费多少时间；
     *  - **线路维度**（[route]）：由用户在「设置 → 更新 → 下载线路」里选择，默认 [DownloadRoute.AUTO]：
     *    · [DownloadRoute.AUTO]：直链优先，慢/失败时自动落到两个公开镜像；
     *    · [DownloadRoute.DIRECT]：只走 GitHub 官方直链；
     *    · 指定镜像：**只走这一条**（用户既然手动选了，就不该偷偷换线，失败时界面会提示去哪改）。
     *
     * 镜像只做字节转发，不改动文件内容，且下载后仍有 [verifyApk] 兜底校验。
     * 非 github.com 资源（如自定义直链）不套前缀，原样返回。
     */
    internal fun downloadCandidates(
        urls: List<String>,
        route: DownloadRoute = DownloadRoute.AUTO,
    ): List<String> {
        val out = LinkedHashSet<String>()
        urls.forEach { url ->
            if (!url.startsWith("https://github.com/", ignoreCase = true)) {
                out.add(url)
                return@forEach
            }
            val prefix = route.prefix
            if (prefix == null) {
                // 自动：直链 + 全部镜像
                out.add(url)
                MIRROR_PREFIXES.forEach { out.add(it + url) }
            } else {
                // 手动指定：只给这一条（空前缀 = 直链）
                out.add(prefix + url)
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
     * 下载 APK 到 `cacheDir/updates/`。
     * 下载前会清空该目录，避免历史版本堆积；先写 `.part` 再做完整性校验后才改名。
     *
     * **线路由 [route] 决定**（见 [downloadCandidates]）：自动模式下按顺序逐条尝试，
     * 每条线路开始后监测速度——起步 [STALL_WINDOW_MS] 内低于 [STALL_MIN_BYTES]
     * 视为「慢线路」，中断并切换下一条（对国内直连 GitHub 的场景这几乎是必然触发）；
     * 手动指定线路时只用那一条，失败即报错。
     * 全部线路失败时抛最后一次的错误，由界面引导用户浏览器手动下载。
     *
     * @param route 下载线路（设置页可选，默认自动降级）
     * @param onProgress 参数为 (已下载字节, 总字节)，总字节为 0 时表示长度未知
     * @param onLineFallback 某条线路失败即将切换时回调 (即将尝试的线路序号, 候选总数)，
     *   供界面提示「正在切换镜像」；进度条会从头重新计数
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        route: DownloadRoute = DownloadRoute.AUTO,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onLineFallback: (Int, Int) -> Unit = { _, _ -> },
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
            try {
                val responseTotal = downloadOnce(candidate, part, info, onProgress)
                verifyApk(part, info.apkSizeBytes, responseTotal)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                return@withContext target
            } catch (t: Throwable) {
                lastError = t
                runCatching { part.delete() }
                if (verbose) Log.w(TAG, "下载线路失败，切换下一条: $candidate", t)
                val next = index + 1
                if (next < candidates.size) onLineFallback(next + 1, candidates.size)
            }
        }
        throw lastError ?: IOException("所有下载线路均失败")
    }

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

    /** 单条线路的完整下载（连接 → 流式写盘 → 速度监测）。返回响应声明的总字节（未知为 0） */
    private fun downloadOnce(
        url: String,
        part: File,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WindTone-Android/$currentVersion")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("下载失败（HTTP $code）")

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSizeBytes
            var downloaded = 0L
            val startedAt = System.currentTimeMillis()
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    var n = input.read(buf)
                    while (n >= 0) {
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                        // 慢线路判定：起步窗口内几乎没进度 → 中断换线。
                        // 直链在国内常表现为「能连上但只有几 KB/s」，不切会挂 20 分钟。
                        // 文案保持中性（自动模式会接着换下一条，手动模式则直接失败并提示改线路）。
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (elapsed > STALL_WINDOW_MS && downloaded < STALL_MIN_BYTES) {
                            throw IOException("线路过慢（${elapsed / 1000}s 仅 ${downloaded / 1024}KB）")
                        }
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
     * 粗校验：APK 是 zip，头 4 字节应为 PK\x03\x04；同时拦住明显不完整的下载。
     *
     * @param expectedSize Release 声明的字节数（Atom 通道下为 0，表示未知）
     * @param responseSize 本次响应声明的 Content-Length（0 表示服务端未给出）
     */
    private fun verifyApk(file: File, expectedSize: Long, responseSize: Long) {
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
     * 统一的 GET 请求：不抛状态码异常，403/404 也照常返回响应体与响应头，
     * 这样限流信息可以被上层解析。真正的 IO 异常仍然抛出，由调用方翻译。
     */
    private fun httpGet(
        url: String,
        accept: String,
        configure: (HttpURLConnection) -> Unit = {},
    ): HttpResp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
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

    /** 轻量 Markdown 清理：Release 说明直接展示时不难看 */
    fun plainNotes(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.replace("\r\n", "\n")
        s = Regex("(?m)^\\s*#{1,6}\\s*").replace(s, "")
        s = Regex("(?m)^\\s*[-*+]\\s+").replace(s, "• ")
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s) { it.groupValues[1] }
        s = Regex("__(.+?)__").replace(s) { it.groupValues[1] }
        s = Regex("`([^`]+)`").replace(s) { it.groupValues[1] }
        s = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** 把异常翻译成用户能看懂的一句话 */
    private fun describe(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "网络不可用，请检查网络后重试"
        is java.net.SocketTimeoutException -> "连接 GitHub 超时，请稍后重试"
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

    /**
     * 会出现在 Release 里的 ABI 名（android 的写法，带连字符）。
     * 与 `app/build.gradle.kts` 的 `splits.abi.include(...)` 保持一致；
     * 设备上报的其它架构（如 riscv64）不会被用来推导资产名，直接退到 universal。
     */
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** 慢线路判定窗口：起步 8 秒 */
    private const val STALL_WINDOW_MS = 8_000L

    /** 慢线路判定阈值：8 秒内不足 512KB（≈64KB/s）即切换 */
    private const val STALL_MIN_BYTES = 512L * 1024

    /** 计算「还有多久恢复」时的容差上限（1 天），超过则认为 Reset 值不可信 */
    private const val A_CLOCK_SKEW_MAX_MS = 24L * 60 * 60 * 1000
}
