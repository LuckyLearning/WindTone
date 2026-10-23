package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.luckylearning.windtone.data.baidu.BaiduRules
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 队列后续曲目的**后台预取**（v1.1.46）。
 *
 * 为什么这样做：百度对第三方应用的下载限速是按**单个连接**算的（这不是猜测 —— 同一首歌
 * 用两个连接分段拉，合计速度明显高于单连接）。而 ExoPlayer 播放时只开一条连接，
 * 所以「当前这首」首播速度的上限被那条连接钉死了，代码里绕不过去。
 *
 * 但切歌那一下的等待是可以在用户没注意时消掉的：当前这首播稳（缓冲领先 20 秒）之后，
 * 用最多两个连接把**接下来两首**先拉进 [OnlineMediaCache] 的同一个 `SimpleCache`，
 * 切过去时 `CacheDataSource` 直接命中本地，起播几乎瞬时。
 *
 * 四条约束：
 *
 *  1. **绝不能抢当前曲的带宽**。开始条件由 [CachePrefetchPolicy.shouldStart] 把关
 *     （缓冲领先不够就不动手），并且每个连接限速（[CachePrefetchPolicy.BYTES_PER_SECOND]）。
 *  2. **键必须与播放时一致**，否则下了一整首也用不上：网盘用 `pan:<fsId>`，普通在线地址
 *     用完整 URI —— 与 [OnlineMediaCache] 里 `CacheKeyFactory` 的取法完全对齐。
 *  3. **失败必须静默**。预取是「顺手做的事」，任何一个文件拉不下来都不该弹提示，
 *     只写日志、只影响那一首。
 *  4. **同一批不重复调度**。ticker 每 3 秒问一次，靠 id 列表去重；真下完了
 *     `ProgressiveDownloader` 也会立刻返回（它按 span 跳过已缓存区间）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CachePrefetcher {

    private const val TAG = "CachePrefetcher"
    private const val USER_AGENT = "WindTone/1.1 (Android; prefetch)"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var jobs: List<Job> = emptyList()
    private var plannedIds: List<Long> = emptyList()
    private val active = mutableListOf<ProgressiveDownloader>()

    private val _status = MutableStateFlow("空闲")
    val status: StateFlow<String> = _status.asStateFlow()

    /**
     * 规划一次预取。由引擎在 ticker 里按 [CachePrefetchPolicy.PLAN_INTERVAL_MS] 节流调用。
     *
     * [upcoming] 是**按播放顺序**排好的后续曲目（随机播放时不是曲库顺序，调用方负责取
     * MediaController 的真实队列）。
     */
    fun plan(context: Context, upcoming: List<Song>) {
        val picked = CachePrefetchPolicy.candidates(upcoming.map { it.uri.scheme })
            .map { upcoming[it] }
        val ids = picked.map { it.id }

        synchronized(lock) {
            if (ids.isEmpty()) {
                // 队列里没有远程歌了：把还在跑的停掉，别让它们白占带宽
                if (plannedIds.isNotEmpty()) {
                    cancelLocked()
                    _status.value = "空闲（后面没有在线歌曲）"
                }
                return
            }
            // 同一批只调度一次：ticker 每 3 秒问一遍，不必重复起线程
            if (ids == plannedIds) return
            cancelLocked()
            plannedIds = ids
        }

        val app = context.applicationContext
        _status.value = if (picked.size == 1) "预取中：1 首" else "预取中：${picked.size} 首（2 个连接）"
        val started = picked.size
        var left = started
        val newJobs = picked.map { song ->
            scope.launch {
                val ok = runCatching { download(app, song) }
                    .onFailure { Log.d(TAG, "预取失败（忽略）: ${song.displayName}", it) }
                    .getOrDefault(false)
                synchronized(lock) {
                    left--
                    if (left <= 0) {
                        _status.value = if (ok) "已预取 $started 首" else "预取结束（部分失败）"
                    }
                }
            }
        }
        synchronized(lock) {
            // 期间可能被 cancel()（用户在诊断页点了停止）或换了批次：这一批必须立刻作废，
            // 否则会留下「没人管得住」的下载线程继续吃带宽
            if (plannedIds == ids) jobs = newJobs else newJobs.forEach { it.cancel() }
        }
    }

    /** 停止全部预取（切歌时上一批已经没意义了 / 用户在诊断页手动停） */
    fun cancel() {
        synchronized(lock) {
            cancelLocked()
            _status.value = "空闲"
        }
    }

    /** 必须在有锁的情况下调用 */
    private fun cancelLocked() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        // 取消协程不会立刻打断阻塞中的 read，必须显式 cancel 下载器
        active.forEach { runCatching { it.cancel() } }
        active.clear()
        plannedIds = emptyList()
    }

    /** 下载一首到缓存；返回是否真的下成功（已完整缓存也算成功） */
    private fun download(context: Context, song: Song): Boolean {
        val cache = OnlineMediaCache.cacheForPrefetch(context) ?: return false
        val pan = BaiduRules.needsPanUserAgent(song.uri.host)
        // 键与播放侧完全一致：网盘用 pan:<fsId>，普通在线地址用完整 URI
        val key = OnlineMediaCache.stableKey(song.uri)
            ?: song.uri.buildUpon().fragment(null).build().toString()
        val uri = if (pan) {
            val signed = BaiduRules.withAccessToken(song.uri.toString(), OnlineMediaCache.panToken())
            // 摘掉只用于算键的 `#wt=…`，它不该进真实请求（v1.1.45 的教训）
            Uri.parse(signed).buildUpon().fragment(null).build()
        } else {
            song.uri.buildUpon().fragment(null).build()
        }

        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                ThrottledSourceFactory(httpFactory(song), CachePrefetchPolicy.BYTES_PER_SECOND),
            )
            .setCacheKeyFactory(object : CacheKeyFactory {
                override fun buildCacheKey(dataSpec: DataSpec): String = key
            })
            // 写缓存失败（磁盘满 / 锁被播放占用）时退化成直连，别让预取把文件丢掉
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val downloader = ProgressiveDownloader(MediaItem.fromUri(uri), cacheFactory)
        synchronized(lock) { active.add(downloader) }
        try {
            downloader.download(Downloader.ProgressListener { _, _, _ -> })
            return true
        } finally {
            synchronized(lock) { active.remove(downloader) }
        }
    }

    private fun httpFactory(song: Song): DefaultHttpDataSource.Factory {
        val pan = BaiduRules.needsPanUserAgent(song.uri.host)
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(if (pan) "pan.baidu.com" else USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            // 网盘 / 电台经常 302 到别的节点甚至别的协议，不放开会直接失败
            .setAllowCrossProtocolRedirects(true)
        if (pan) factory.setDefaultRequestProperties(BaiduRules.panHeaders())
        return factory
    }
}

/**
 * 按字节/秒限速的 [DataSource] 包装（只给预取用）。
 *
 * `ProgressiveDownloader` 没有速率参数，而「预取把当前曲的带宽吃掉」正是这功能最大的
 * 风险，所以在这里做令牌桶式的粗限速：累计预算跑在时间前面就 sleep 一会儿。
 * 睡眠发生在 IO 线程，不会卡界面。
 */
@UnstableApi
private class ThrottledSourceFactory(
    private val upstream: DataSource.Factory,
    private val bytesPerSecond: Int,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        ThrottledDataSource(upstream.createDataSource(), bytesPerSecond)
}

@UnstableApi
private class ThrottledDataSource(
    private val delegate: DataSource,
    private val bytesPerSecond: Int,
) : DataSource {

    private var windowStart = 0L
    private var windowBytes = 0L

    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = delegate.open(dataSpec)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = delegate.read(buffer, offset, length)
        if (read <= 0) return read
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) windowStart = now
        windowBytes += read
        val budgetMs = windowBytes * 1000L / bytesPerSecond
        val elapsed = now - windowStart
        if (elapsed < budgetMs) {
            // 超预算：睡到「该到的时间」，连接慢时这条路根本不会走到（本来就没超）
            runCatching { Thread.sleep(budgetMs - elapsed) }
        } else if (elapsed > 3_000L) {
            // 窗口滚动：预算按累计字节算，不滚动的话越跑越宽松，等于没限速
            windowStart = now
            windowBytes = 0
        }
        return read
    }

    override fun close() = delegate.close()
}
