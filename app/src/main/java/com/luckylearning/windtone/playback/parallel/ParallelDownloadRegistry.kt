package com.luckylearning.windtone.playback.parallel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSpec
import com.luckylearning.windtone.data.baidu.BaiduRules
import com.luckylearning.windtone.data.model.TrackFragment
import com.luckylearning.windtone.playback.CachePrefetcher
import com.luckylearning.windtone.playback.OnlineMediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 「当前这首」的并行分片下载（v1.1.47）。
 *
 * 只服务一首歌，理由很实际：5 条连接不限速会把带宽吃满（用户明确要求），同时再给别的歌
 * 分片只会让两边都慢。所以这里只有一个 [active]，切歌即换人、旧文件立刻删掉。
 *
 * 用 `MediaController` 的真实播放曲目作判据（引擎在切歌时调 [setCurrentKey]）：
 * `DataSource` 层看不到「这一首是不是当前播放」，只能靠这个 key 对号入座，
 * 对不上的一律走原来的缓存/单连接路径 —— 不然连预取都会被并行分片抢走连接。
 */
object ParallelDownloadRegistry {

    private const val TAG = "ParallelRegistry"
    private const val DIR_NAME = "parallel"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var currentKey: String? = null

    @Volatile
    private var active: ParallelDownload? = null

    /** 本会话里已确认「服务器不支持 Range」的 key，避免每次 open 都白探一次 */
    private val unsupported = mutableSetOf<String>()

    private val _status = MutableStateFlow("空闲")
    val status: StateFlow<String> = _status.asStateFlow()

    private var ticker: Job? = null

    /**
     * 统一的「这是哪一首」判据。
     *
     * 网盘取 `pan:<fsId>`（dlink 每次签名都不同，不能拿整个 URI 当身份），
     * 其它在线地址去掉 fragment 后用完整 URI。
     */
    fun keyOf(uri: Uri): String =
        OnlineMediaCache.panCacheKey(uri) ?: uri.buildUpon().fragment(null).build().toString()

    fun isActive(): Boolean = active != null

    /** 引擎切歌时调用；传 null 表示当前是本地文件，不需要分片 */
    fun setCurrentKey(key: String?) {
        if (key == currentKey) return
        currentKey = key
        releaseActive()
        synchronized(lock) { unsupported.clear() }
        _status.value = when (key) {
            null -> "空闲（本地文件不分片）"
            else -> "等待播放"
        }
    }

    /**
     * 播放器打开数据源时调用：是当前曲目就返回并行下载器，否则返回 null（走原路径）。
     *
     * 这里会做一次 `Range: bytes=0-0` 探测，同步几十毫秒，发生在播放器加载线程上，
     * 与一次普通请求等价。
     */
    // DataSpec 是 UnstableApi 类型，这里显式 opt-in（不标在 object 上，避免污染 UI 调用方）
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun obtain(context: Context, dataSpec: DataSpec): ParallelDownload? {
        val key = currentKey ?: return null
        if (dataSpec.uri.fragment?.startsWith(TrackFragment.PREFIX) == true) return null
        if (keyOf(dataSpec.uri) != key) return null

        synchronized(lock) {
            active?.let { if (it.key == key) return it }
            if (key in unsupported) return null
        }

        val pan = BaiduRules.needsPanUserAgent(dataSpec.uri.host)
        val headers = if (pan) BaiduRules.panHeaders() else emptyMap()
        val userAgent = if (pan) "pan.baidu.com" else "WindTone/1.1 (Android; parallel)"
        val target = requestUri(dataSpec.uri)

        _status.value = "探测分段支持…"
        val total = probeTotalBytes(target, headers, userAgent)
        if (total == null) {
            synchronized(lock) { unsupported += key }
            _status.value = "服务器不支持分段，已退回单连接"
            return null
        }
        val segments = SegmentPlanner.plan(total)
        if (segments.isEmpty()) {
            synchronized(lock) { unsupported += key }
            _status.value = "文件较小，不需要分片"
            return null
        }

        val dir = File(context.applicationContext.cacheDir, DIR_NAME)
        runCatching { dir.mkdirs() }
        // 进程被 ROM 杀掉时来不及删 .part，启动时顺手清掉上一次的残留（超过 1 小时）
        runCatching {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (now - f.lastModified() > STALE_TEMP_MS) f.delete()
            }
        }
        // 预分配要占满整首：空间不够就别开始，否则写到一半 ENOSPC 反而变成播放失败
        if (runCatching { dir.usableSpace }.getOrDefault(0L) < total * 2) {
            _status.value = "存储不足，已退回单连接"
            return null
        }

        val download = ParallelDownload(
            key = key,
            uri = target,
            headers = headers,
            userAgent = userAgent,
            file = File(dir, sha1(key) + ".part"),
            totalBytes = total,
            segments = segments,
        )
        val started = runCatching { download.start() }
            .onFailure { Log.w(TAG, "启动并行分片失败", it) }
            .isSuccess
        if (!started) {
            download.reap()
            return null
        }

        synchronized(lock) { active = download }
        // 5 条不限速的连接已经在抢带宽了，预取这时候只会让两边都慢
        runCatching { CachePrefetcher.cancel() }
        startTicker()
        return download
    }

    private fun releaseActive() {
        val old = synchronized(lock) { active.also { active = null } }
        ticker?.cancel()
        ticker = null
        if (old != null) io.launch { runCatching { old.reap() } }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = io.launch {
            while (isActive) {
                val dl = active ?: break
                _status.value = "${dl.segmentCount} 段并行 · ${dl.snapshot()}"
                delay(1_000L)
            }
        }
    }

    /** 网盘的直链要补 token、并摘掉只作缓存键的 fragment */
    private fun requestUri(uri: Uri): Uri {
        if (!BaiduRules.needsPanUserAgent(uri.host)) return uri.buildUpon().fragment(null).build()
        val signed = BaiduRules.withAccessToken(uri.toString(), OnlineMediaCache.panToken())
        return Uri.parse(signed).buildUpon().fragment(null).build()
    }

    /**
     * 用 `Range: bytes=0-0` 问一次总长。
     *
     * 只有返回 **206 + Content-Range** 才算支持分段；返回 200 说明服务器忽略了 Range，
     * 此时硬切 5 段会让流量变成 5 倍。
     */
    private fun probeTotalBytes(uri: Uri, headers: Map<String, String>, userAgent: String): Long? {
        var target = uri
        repeat(MAX_REDIRECTS) {
            val conn = runCatching {
                (URL(target.toString()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    setRequestProperty("Range", "bytes=0-0")
                    setRequestProperty("Accept-Encoding", "identity")
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    if (!headers.keys.any { it.equals("User-Agent", true) }) {
                        setRequestProperty("User-Agent", userAgent)
                    }
                }
            }.getOrNull() ?: return null

            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                runCatching { conn.disconnect() }
                if (location.isNullOrBlank()) return null
                target = Uri.parse(URL(URL(target.toString()), location).toString())
                return@repeat
            }
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                runCatching { conn.disconnect() }
                return null
            }
            // 形如 `bytes 0-0/28304512`；`*` 表示总长未知
            val contentRange = conn.getHeaderField("Content-Range")
            runCatching { conn.disconnect() }
            return contentRange?.substringAfterLast('/', "")?.toLongOrNull()?.takeIf { it > 0 }
        }
        return null
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 残留临时文件的清理阈值 */
    private const val STALE_TEMP_MS = 60 * 60 * 1000L

    /** 清掉并行分片的临时文件（「清除在线缓存」时一并用） */
    fun clearTemp(context: Context) {
        runCatching {
            File(context.applicationContext.cacheDir, DIR_NAME).listFiles()?.forEach { it.delete() }
        }
    }

    private const val MAX_REDIRECTS = 5
}
