package com.anylyric.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 公共分发仓库（`LuckyLearning/Release`）中 `AnyLyric/latest.json` 描述的某个版本。
 *
 * 字段与 `scripts/gen-release-manifest.py` 生成的清单一一对应；清单里的**新增字段一律按可选处理**
 * —— 客户端散落在旧版本上，新清单不能把旧端弄崩。
 */
data class Release(
    /** 版本名，如 0.1.1（已去掉 tag 的 v 前缀） */
    val version: String,
    /** 清单里的 versionCode，0 表示清单没给 */
    val versionCode: Int,
    /** 原始 tag 名，如 anylyric-v0.1.1 */
    val tag: String,
    /** 更新说明（已整理为纯文本） */
    val notes: String,
    /** 安装包直链 */
    val apkUrl: String,
    /** 安装包文件名 */
    val apkName: String,
    /** 安装包字节数，0 表示未知 */
    val sizeBytes: Long,
    /** 安装包 SHA-256（小写十六进制），空串表示清单没给 */
    val sha256: String,
    /** 公共仓库里该版本的目录页（浏览器可打开） */
    val pageUrl: String,
)

/** 检查失败的类别。文案由界面按资源翻译，检查器本身不碰 Context。 */
enum class FailureKind {
    /** 连不上 / 其它 IO 异常 */
    NETWORK,

    /** 域名解析失败（国内 DNS 污染常见，和「没网」不是一回事） */
    DNS,

    /** 超时 */
    TIMEOUT,

    /** HTTP 非 2xx（非 404） */
    HTTP,

    /** 404：公共仓库里还没有这个软件的版本 */
    NOT_FOUND,

    /** 清单本身不是合法 JSON / 缺版本号（镜像返回错误页也会走到这里） */
    BAD_MANIFEST,

    /** 清单里没有可用的安装包 */
    NO_APK,

    /** 下载到的文件校验不过（长度不符 / 不是 APK / SHA-256 不一致） */
    VERIFY,
}

/** 检查更新的结论。 */
sealed interface CheckResult {
    /** 有新版本 */
    data class Available(val release: Release) : CheckResult

    /** 已是最新 */
    data class UpToDate(val latestVersion: String) : CheckResult

    /** 检查失败 */
    data class Failed(
        val kind: FailureKind,
        val detail: String = "",
        val httpCode: Int = 0,
    ) : CheckResult
}

/** 下载阶段的失败。调用方按 [kind] 翻译文案。 */
class UpdateException(
    val kind: FailureKind,
    detail: String = "",
    /** 仅 [FailureKind.HTTP] 有意义：服务端状态码 */
    val httpCode: Int = 0,
) : IOException(detail.ifBlank { kind.name })

/**
 * 应用内更新：从**公共分发仓库**读静态文件，不走 GitHub API。
 *
 * ```
 * https://raw.githubusercontent.com/LuckyLearning/Release/main/AnyLyric/latest.json
 * …/AnyLyric/anylyric-v0.1.1/AnyLyric-v0.1.1-universal.apk
 * ```
 *
 * 为什么不用 API：① 不需要任何鉴权；② raw 是 CDN，不计入每小时的 API 配额。
 *
 * 线路策略（国内车机常连不上 GitHub 官方域名）：`fastly.jsdelivr.net` → `gh-proxy.com`
 * → `ghproxy.net` → 官方 raw。清单只有 1 KB，逐条顺序试，失败即换下一条，
 * 全部失败才报错 —— 不做并行竞速，几十 KB 的代码不值得为此加复杂度。
 *
 * 版本判断**以 versionCode 为准**，清单缺 versionCode 时才退回版本名比较：
 * 光比版本名会出现「装过更高版本名、却看不到较低版本」的死角（WindTone 踩过这个坑）。
 *
 * 全部方法都是阻塞式的，**必须在工作线程调用**。
 */
object UpdateChecker {

    /** 公共仓库坐标：唯一出处，改这里就够。 */
    private const val OWNER = "LuckyLearning"
    private const val REPO = "Release"
    private const val BRANCH = "main"
    private const val APP_DIR = "AnyLyric"

    /** 用户代理标识，方便在仓库/CDN 侧区分来源 */
    private const val USER_AGENT = "AnyLyric-Android"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000

    /** 单个安装包的下限：真包 100 KB 上下，低于这个数基本是代理返回的错误页 */
    private const val MIN_APK_BYTES = 20L * 1024

    private val appBase: String
        get() = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$APP_DIR"

    /** 最新版本清单地址（单测与界面展示用） */
    val manifestUrl: String get() = "$appBase/latest.json"

    /** 公共仓库里本软件的目录页，检查失败时可引导用户手动打开 */
    val releasePageUrl: String get() = "https://github.com/$OWNER/$REPO/tree/$BRANCH/$APP_DIR"

    // ------------------------------------------------------------ 检查

    /**
     * 清单的候选线路，按尝试顺序。
     *
     * jsDelivr 走「仓库文件」通道（`cdn.jsdelivr.net/gh/<owner>/<repo>@<branch>/<path>`），
     * 前缀不能简单拼接，必须重排；gh-proxy 一类是「整串 URL 当路径」，直接加前缀。
     */
    internal fun manifestCandidates(): List<String> {
        val url = manifestUrl
        return listOfNotNull(
            jsdelivr(url, "fastly.jsdelivr.net"),
            jsdelivr(url, "gcore.jsdelivr.net"),
            "https://gh-proxy.com/$url",
            url,
        )
    }

    /**
     * 查询最新版本。永不抛异常，失败一律折成 [CheckResult.Failed]。
     *
     * 某条线路拿到的是**坏数据**（镜像错误页、半截 JSON）时继续试下一条，
     * 不把「代理坏了」当成本软件的结论。
     */
    fun check(currentName: String, currentCode: Int): CheckResult {
        var last: CheckResult.Failed? = null
        for (candidate in manifestCandidates()) {
            try {
                val body = httpGetText(candidate)
                when (val result = parseManifest(body, currentName, currentCode)) {
                    is CheckResult.Failed -> last = result
                    else -> return result
                }
            } catch (t: Throwable) {
                last = fold(t)
            }
        }
        return last ?: CheckResult.Failed(FailureKind.NETWORK)
    }

    /**
     * 解析清单并给出结论。纯函数，可单测。
     *
     * ```json
     * { "schema":1, "app":"AnyLyric", "version":"0.1.1", "versionCode":2,
     *   "tag":"anylyric-v0.1.1", "minSdk":31, "notes":"…",
     *   "assets":[{"abi":"universal","file":"AnyLyric-v0.1.1-universal.apk",
     *              "size":152000,"sha256":"…"}] }
     * ```
     */
    internal fun parseManifest(body: String, currentName: String, currentCode: Int): CheckResult {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            return CheckResult.Failed(FailureKind.BAD_MANIFEST, t.javaClass.simpleName)
        }
        val version = normalizeVersion(json.optString("version"))
        if (version.isBlank()) return CheckResult.Failed(FailureKind.BAD_MANIFEST, "no version")

        val code = json.optInt("versionCode", 0)
        val newer = if (code > 0 && currentCode > 0) {
            code > currentCode
        } else {
            compareVersions(version, currentName) > 0
        }
        if (!newer) return CheckResult.UpToDate(version)

        // 版本目录名就是清单里的 tag（`scripts/gen-release-manifest.py --tag v<版本>`），
        // 缺 tag 时按同一规则补：`v0.1.1`，**不是** GitHub tag 的 `anylyric-v0.1.1`。
        val tag = json.optString("tag").ifBlank { "v$version" }
        val asset = pickAsset(json.optJSONArray("assets"))
            ?: return CheckResult.Failed(FailureKind.NO_APK)

        return CheckResult.Available(
            Release(
                version = version,
                versionCode = code,
                tag = tag,
                notes = plainNotes(json.optString("notes")),
                apkUrl = "$appBase/$tag/${asset.first}",
                apkName = asset.first,
                sizeBytes = asset.second,
                sha256 = asset.third,
                pageUrl = releasePageUrl,
            )
        )
    }

    /**
     * 从 assets 里挑一个包：优先 `universal`，否则取第一个有文件名的。
     *
     * AnyLyric 只发一个通用包，这里仍按「优先 universal」写，是为了以后真分了 ABI 也不会挑错。
     */
    internal fun pickAsset(assets: JSONArray?): Triple<String, Long, String>? {
        if (assets == null) return null
        val items = (0 until assets.length()).mapNotNull { index ->
            val item = assets.optJSONObject(index) ?: return@mapNotNull null
            val file = item.optString("file").trim()
            if (file.isBlank()) return@mapNotNull null
            Triple(file, item.optLong("size", 0L), item.optString("sha256").trim())
        }
        if (items.isEmpty()) return null
        return items.firstOrNull { it.first.contains("universal", ignoreCase = true) } ?: items.first()
    }

    // ------------------------------------------------------------ 下载

    /**
     * 下载安装包到 [dir]，返回落盘文件；失败抛 [UpdateException]。
     *
     * @param onProgress (已下载字节, 总字节)，总字节为 0 表示长度未知
     */
    fun download(release: Release, dir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (!dir.exists() && !dir.mkdirs()) {
            throw UpdateException(FailureKind.NETWORK, "cannot create ${dir.absolutePath}")
        }
        // 文件名要能安全落盘：清单来自网络，不能直接当路径用
        val safeName = release.apkName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "AnyLyric-${release.version}.apk" }
        val part = File(dir, "$safeName.part")
        val target = File(dir, safeName)

        var lastError: Throwable? = null
        for (candidate in downloadCandidates(release.apkUrl)) {
            try {
                val total = downloadOnce(candidate, part, onProgress)
                verify(part, release, total)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                return target
            } catch (t: Throwable) {
                lastError = t
                // 内容坏了才丢掉半截；网络失败留着下次覆盖写（这里不做 Range 续传：包才 150 KB）
                if (t is UpdateException && t.kind == FailureKind.VERIFY) {
                    runCatching { part.delete() }
                }
            }
        }
        throw lastError ?: UpdateException(FailureKind.NETWORK)
    }

    /** 下载候选线路：镜像优先，官方 raw 最后兜底。 */
    internal fun downloadCandidates(apkUrl: String): List<String> {
        val mirrors = mutableListOf<String>()
        if (apkUrl.startsWith("https://raw.githubusercontent.com/", ignoreCase = true)) {
            jsdelivr(apkUrl, "fastly.jsdelivr.net")?.let { mirrors.add(it) }
            jsdelivr(apkUrl, "gcore.jsdelivr.net")?.let { mirrors.add(it) }
            mirrors.add("https://gh-proxy.com/$apkUrl")
        }
        return (mirrors + apkUrl).distinct()
    }

    /** raw 直链 → jsDelivr 仓库文件链接；不是 raw 直链时返回 null */
    private fun jsdelivr(url: String, host: String): String? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!url.startsWith(prefix, ignoreCase = true)) return null
        val parts = url.substring(prefix.length).split('/', limit = 4)
        if (parts.size < 4) return null
        val (owner, repo, branch, path) = parts
        if (owner.isBlank() || repo.isBlank() || branch.isBlank() || path.isBlank()) return null
        return "https://$host/gh/$owner/$repo@$branch/$path"
    }

    /** 单线路下载到 [part]，返回响应声明的总字节（0 = 未知）。 */
    private fun downloadOnce(
        url: String,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw UpdateException(
                if (code == 404) FailureKind.NOT_FOUND else FailureKind.HTTP,
                "HTTP $code",
                code,
            )

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            var downloaded = 0L
            onProgress(0L, total)
            part.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(part, false).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var n = input.read(buffer)
                    while (n > 0) {
                        out.write(buffer, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                        n = input.read(buffer)
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
     * 校验下载结果。三层，从便宜到贵：
     *  1. 长度下限 + zip 头 `PK`（防代理返回错误页）；
     *  2. 与清单声明的大小比对（防截断）；
     *  3. SHA-256（唯一能发现「内容被中间环节替换」的检查，镜像线路越多越需要）。
     */
    internal fun verify(file: File, release: Release, responseTotal: Long) {
        if (!file.exists() || file.length() < MIN_APK_BYTES) {
            throw UpdateException(FailureKind.VERIFY, "too small: ${file.length()}")
        }
        val head = ByteArray(2)
        file.inputStream().use { it.read(head) }
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
            throw UpdateException(FailureKind.VERIFY, "not a zip/apk")
        }
        val basis = when {
            release.sizeBytes > 0 -> release.sizeBytes
            responseTotal > 0 -> responseTotal
            else -> 0L
        }
        if (basis > 0 && file.length() != basis) {
            throw UpdateException(FailureKind.VERIFY, "size ${file.length()} != $basis")
        }
        if (release.sha256.isNotBlank()) {
            val actual = sha256Of(file)
            if (!actual.equals(release.sha256.trim(), ignoreCase = true)) {
                throw UpdateException(FailureKind.VERIFY, "sha256 $actual")
            }
        }
    }

    /** 文件 SHA-256（小写十六进制） */
    internal fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 工具

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            // 镜像/CDN 可能缓存旧的清单；显式要求重新校验
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UpdateException(
                    if (code == 404) FailureKind.NOT_FOUND else FailureKind.HTTP,
                    "HTTP $code",
                    code,
                )
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fold(t: Throwable): CheckResult.Failed = when (t) {
        is java.net.UnknownHostException ->
            CheckResult.Failed(FailureKind.DNS, t.message.orEmpty())

        is java.net.SocketTimeoutException ->
            CheckResult.Failed(FailureKind.TIMEOUT, t.message.orEmpty())

        is UpdateException ->
            CheckResult.Failed(t.kind, t.message.orEmpty(), t.httpCode)

        else -> CheckResult.Failed(FailureKind.NETWORK, t.message ?: t.javaClass.simpleName)
    }

    /** 语义化版本比较：返回 <0 / 0 / >0。非数字段按 0 处理，`1.0.0-beta` 视作 `1.0.0`。 */
    fun compareVersions(left: String, right: String): Int {
        val a = parseVersion(left)
        val b = parseVersion(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    private fun parseVersion(raw: String): List<Int> =
        normalizeVersion(raw)
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.trim().toIntOrNull() ?: 0 }

    /**
     * 把 Release 正文里的 Markdown 折成可读纯文本。
     *
     * 只做「界面不需要语法符号」这件事：标题去掉 `#`、粗体去掉 `**`、列表换成 `•`、
     * 链接只留文字、表格按行拆成 `• a · b`。不做 HTML 解码 —— 发版正文由固定模板生成，
     * 里面只有 Markdown。
     */
    fun plainNotes(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.replace("\r\n", "\n")

        s = Regex("(?m)^[ \\t]*\\|[ \\t:|-]*\\|[ \\t]*$").replace(s, "")
        s = Regex("(?m)^[ \\t]*\\|(.+)\\|[ \\t]*$").replace(s) { row ->
            val cells = row.groupValues[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.isEmpty()) "" else "• " + cells.joinToString(" · ")
        }
        s = Regex("(?m)^[ \\t]*#{1,6}[ \\t]*").replace(s, "")
        s = Regex("(?m)^[ \\t]*[-*+][ \\t]+").replace(s, "• ")
        s = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
        s = Regex("\\*\\*(.+?)\\*\\*").replace(s) { it.groupValues[1] }
        s = Regex("__(.+?)__").replace(s) { it.groupValues[1] }
        s = Regex("`([^`]+)`").replace(s) { it.groupValues[1] }
        s = Regex("(?m)^[ \\t]+").replace(s, "")
        s = Regex("[ \\t]+\\n").replace(s, "\n")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    /** 字节数 → MB，用于界面展示；未知大小返回空串（拼接时分隔符一并消失）。 */
    fun formatSize(bytes: Long): String =
        if (bytes <= 0) "" else "%.2f MB".format(bytes / 1024.0 / 1024.0)
}
