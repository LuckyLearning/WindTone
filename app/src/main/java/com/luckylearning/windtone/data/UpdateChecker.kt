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
    /** APK 下载直链 */
    val apkUrl: String,
    /** APK 文件名，如 WindTone-v1.0.0.apk */
    val apkName: String,
    /** APK 字节数，0 表示未知 */
    val apkSizeBytes: Long,
    /** 发布时间（原始 ISO 字符串） */
    val publishedAt: String,
    /** Release 网页地址 */
    val releaseUrl: String,
)

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
 * 数据来源是公开的 GitHub Releases，因此**不需要任何鉴权**，
 * 只读取 `releases/latest`（自动跳过草稿与预发布），
 * 再从 assets 里挑出 APK 直链下载。
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

    private val latestReleaseApi: String
        get() = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

    /** 当前安装版本（来自 BuildConfig，与 build.gradle.kts 的 versionName 一致） */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Release 列表页，检查失败时可引导用户手动打开 */
    fun releasePageUrl(): String = BuildConfig.GITHUB_RELEASES_URL

    // ------------------------------------------------------------ 查询

    /**
     * 查询最新版本。永不抛异常，失败一律折成 [UpdateCheckResult.Failed]。
     */
    suspend fun checkLatest(current: String = currentVersion): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(latestReleaseApi).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "WindTone-Android/$currentVersion")
                }
                when (val code = conn.responseCode) {
                    200 -> Unit
                    404 -> return@withContext UpdateCheckResult.Failed("仓库还没有发布正式版本")
                    403, 429 -> return@withContext UpdateCheckResult.Failed("GitHub 接口访问过于频繁，请稍后再试")
                    else -> return@withContext UpdateCheckResult.Failed("检查更新失败（HTTP $code）")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                val tag = json.optString("tag_name").trim()
                val version = normalizeVersion(tag).ifBlank { normalizeVersion(json.optString("name")) }
                if (version.isBlank()) {
                    return@withContext UpdateCheckResult.Failed("无法解析最新版本号")
                }

                if (!isNewer(version, current)) {
                    return@withContext UpdateCheckResult.UpToDate(version)
                }

                val asset = pickApkAsset(json)
                    ?: return@withContext UpdateCheckResult.Failed("新版本没有附带 APK 安装包")

                UpdateCheckResult.Available(
                    UpdateInfo(
                        version = version,
                        tagName = tag.ifBlank { "v$version" },
                        title = json.optString("name").ifBlank { "WindTone $version" },
                        notes = plainNotes(json.optString("body")),
                        apkUrl = asset.first,
                        apkName = asset.second,
                        apkSizeBytes = asset.third,
                        publishedAt = json.optString("published_at"),
                        releaseUrl = json.optString("html_url").ifBlank { releasePageUrl() },
                    )
                )
            } catch (t: Throwable) {
                if (verbose) Log.w(TAG, "检查更新失败", t)
                UpdateCheckResult.Failed(describe(t))
            } finally {
                conn?.disconnect()
            }
        }

    /** 从 assets 里挑 APK：优先名字带 WindTone / 听风 的，其次任意 .apk */
    private fun pickApkAsset(json: JSONObject): Triple<String, String, Long>? {
        val assets = json.optJSONArray("assets") ?: return null
        var fallback: Triple<String, String, Long>? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = a.optString("browser_download_url")
            if (url.isBlank()) continue
            val size = a.optLong("size", 0L)
            val hit = Triple(url, name, size)
            val preferred = name.contains("WindTone", ignoreCase = true) || name.contains("听风")
            if (preferred) return hit
            if (fallback == null) fallback = hit
        }
        return fallback
    }

    // ------------------------------------------------------------ 下载

    /**
     * 下载 APK 到 `cacheDir/updates/`。
     * 下载前会清空该目录，避免历史版本堆积；先写 `.part` 再做完整性校验后才改名。
     *
     * @param onProgress 参数为 (已下载字节, 总字节)，总字节为 0 时表示长度未知
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val safeName = info.apkName
            .ifBlank { "WindTone-v${info.version}.apk" }
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val target = File(dir, safeName)
        val part = File(dir, "$safeName.part")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WindTone-Android/$currentVersion")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("下载失败（HTTP $code）")

            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSizeBytes
            var downloaded = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    var n = input.read(buf)
                    while (n >= 0) {
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                        n = input.read(buf)
                    }
                    out.flush()
                }
            }

            verifyApk(part, info.apkSizeBytes)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            target
        } catch (t: Throwable) {
            runCatching { part.delete() }
            throw t
        } finally {
            conn?.disconnect()
        }
    }

    /** 粗校验：APK 是 zip，头 4 字节应为 PK\x03\x04；同时拦住明显不完整的下载 */
    private fun verifyApk(file: File, expectedSize: Long) {
        if (!file.exists() || file.length() < 100 * 1024) {
            throw IOException("下载的安装包不完整")
        }
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) }
        val isZip = head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        if (!isZip) throw IOException("下载的文件不是有效的 APK")
        if (expectedSize > 0 && file.length() != expectedSize) {
            if (verbose) Log.w(TAG, "APK 大小与 Release 声明不一致：${file.length()} vs $expectedSize")
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

    /** 把字节数格式化成 MB，用于界面展示 */
    fun formatSize(bytes: Long): String =
        if (bytes <= 0) "未知大小" else "%.2f MB".format(bytes / 1024.0 / 1024.0)

    private const val DIR_NAME = "updates"
}
