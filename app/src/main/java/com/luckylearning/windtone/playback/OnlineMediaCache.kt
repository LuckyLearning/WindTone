package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import com.luckylearning.windtone.data.aliyun.AliyunEndpoints
import com.luckylearning.windtone.data.model.TrackFragment
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.luckylearning.windtone.playback.parallel.ParallelRoutingFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File

/**
 * 在线音频的播放缓存（v1.1.38，对应评审文档的阶段 3 / E.5）。
 *
 * 为什么必须做：车机常年弱网，没有缓存时**每次重播都要重新下载**，隧道 / 地库里直接断音；
 * 用户感知是「在线播放不如本地稳」，而不是「没做缓存」。
 *
 * 三条设计约束：
 *
 *  1. **只缓存 http(s)**。`content://` / `file://` 已经是本地文件，再套一层缓存只会
 *     多一次磁盘拷贝，还容易在 `content://` 的随机读上出问题（CacheDataSource 对
 *     非 seekable 的内容源支持有限）。路由靠 [ResolvingDataSource] 按 scheme 决定。
 *  2. **缓存失败不能影响播放**。`SimpleCache` 初始化要抢目录锁、要建 SQLite 索引，
 *     车机存储紧张时可能直接抛异常 —— 全部 `runCatching` 兜住，失败就退回「无缓存」，
 *     播放继续。宁可费流量，也不能因为缓存起不来就不出声。
 *  3. **上限固定 + LRU**。缓存目录在 `cacheDir` 下，系统随时可能回收；默认 128MB 上限
 *     （车机多本地，在线缓存偏省；设置里仍可选更高档），超出按最近最少使用淘汰。
 */
object OnlineMediaCache {

    /**
     * 默认上限（资源极致 A3：512→128）。
     *
     * [limitBytes] 用 `getLong(KEY, MAX_BYTES)`：从未写入过 prefs 的新装/升级用户拿新默认；
     * 已在设置里选过档位的用户保留现值。[LIMIT_STEPS] 不变。
     */
    const val MAX_BYTES = 128L * 1024 * 1024

    /** 认为「本地已有可播前缀」的最小连续缓存（512KB） */
    const val DEFAULT_CACHED_PREFIX_BYTES: Long = 512L * 1024

    val LIMIT_STEPS: List<Long> = listOf(128, 256, 512, 1024, 2048).map { it * 1024L * 1024L }

    private const val LIMIT_PREFS = "online_media_prefs"
    private const val LIMIT_KEY = "max_bytes"

    @androidx.annotation.OptIn(UnstableApi::class)
    private var evictor: AdjustableEvictor? = null

    private const val DIR_NAME = "online_media"
    private const val USER_AGENT = "WindTone/1.1 (Android; online-media)"

    // SimpleCache 是 UnstableApi 类型，字段本身也要 opt-in —— 否则 lint 会在
    // 「声明处」报 UnsafeOptInUsageError（函数内的 opt-in 覆盖不到属性类型）
    @androidx.annotation.OptIn(UnstableApi::class)
    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var failed = false

    /**
     * 历史百度网盘缓存键前缀（fragment `wt=<fsId>`）。产品面已删除百度，
     * 仍保留解析以免旧缓存索引里的 `pan:` 键无法对上；新写入只用阿里云盘 `#ay=`。
     */
    private const val PAN_FRAGMENT_PREFIX = "wt="

    fun panCacheKey(uri: Uri?): String? {
        val frag = uri?.fragment ?: return null
        if (!frag.startsWith(PAN_FRAGMENT_PREFIX)) return null
        val fsId = frag.removePrefix(PAN_FRAGMENT_PREFIX).substringBefore('&').trim()
        return if (fsId.isEmpty()) null else "pan:$fsId"
    }

    /** 播放与预取共用的稳定键（阿里云盘 `#ay=` 优先）。 */
    fun stableKey(uri: Uri?): String? =
        TrackFragment.cacheKey(uri?.fragment) ?: panCacheKey(uri)

    fun limitBytes(context: Context): Long {
        val stored = context.applicationContext
            .getSharedPreferences(LIMIT_PREFS, Context.MODE_PRIVATE)
            .getLong(LIMIT_KEY, MAX_BYTES)
        return LIMIT_STEPS.minBy { kotlin.math.abs(it - stored) }
    }

    fun setLimitBytes(context: Context, bytes: Long) {
        val snapped = LIMIT_STEPS.minBy { kotlin.math.abs(it - bytes) }
        context.applicationContext
            .getSharedPreferences(LIMIT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(LIMIT_KEY, snapped)
            .apply()
        applyLimit(context)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun applyLimit(context: Context) {
        val cache = get(context) ?: return
        evictor?.trim(cache)
    }

    /** 只有网络流进缓存；本地 URI 一律走原路径 */
    fun shouldCache(scheme: String?): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    fun shouldCache(uri: Uri?): Boolean = shouldCache(uri?.scheme)

    /**
     * 取缓存实例；拿不到返回 null（调用方必须按「无缓存」继续）。
     *
     * `SimpleCache` 对同一目录**全进程只能有一个实例**，第二个会抛
     * `IllegalStateException: Another SimpleCache instance uses the folder`。
     * 所以这里用单例 + 失败标记，失败后不再反复尝试（每次失败都要抢一次锁，很贵）。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun get(context: Context): SimpleCache? {
        instance?.let { return it }
        if (failed) return null
        synchronized(this) {
            instance?.let { return it }
            if (failed) return null
            return runCatching {
                val dir = File(context.applicationContext.cacheDir, DIR_NAME)
                val created = AdjustableEvictor(context.applicationContext)
                evictor = created
                SimpleCache(
                    dir,
                    created,
                    StandaloneDatabaseProvider(context.applicationContext),
                ).also { created.trim(it) }
            }.onFailure { failed = true }.getOrNull().also { instance = it }
        }
    }

    /**
     * 播放器用的 MediaSource 工厂：http(s) 走「缓存 + 网络」，其余走系统默认。
     *
     * 路由**必须按 DataSpec 的 scheme 逐次决定**，因为 `DataSource.Factory.createDataSource()`
     * 拿不到 URI。Media3 的 `ResolvingDataSource` 只能改 DataSpec、不能换 DataSource 实例，
     * 所以这里用一个小包装类在 `open(dataSpec)` 时选真实来源。
     *
     * 为什么不能给所有 scheme 都套缓存：本地文件走缓存既没收益，又可能在 `content://`
     * 上读失败 —— 那会把「本地播放」一起搞坏，代价远大于省下的那点磁盘拷贝。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun mediaSourceFactory(context: Context): MediaSource.Factory {
        val cache = get(context)
        val keys = object : CacheKeyFactory {
            override fun buildCacheKey(dataSpec: DataSpec): String =
                stableKey(dataSpec.uri) ?: dataSpec.key ?: dataSpec.uri.toString()
        }
        // 阿里直链必须用自己的工厂：共用工厂的 User-Agent 写在请求头之后，
        // 会把 downloadHeaders 里的浏览器 UA 盖掉，数据节点照样掐连接。
        val aliyunHttp = httpFactory(AliyunEndpoints.DOWNLOAD_UA, AliyunEndpoints.downloadHeaders())
        val dataSource: DataSource.Factory = if (cache == null) {
            // 缓存不可用时仍然走路由包装：阿里直链与本地 file 要分发到不同 DataSource。
            // 直接返回 DefaultDataSource 会让「没缓存」这种边缘状态连播放一起丢。
            SchemeRoutingFactory(
                DefaultDataSource.Factory(context),
                DefaultDataSource.Factory(context),
                aliyunHttp,
            )
        } else {
            val http = httpFactory(USER_AGENT)
            val cached = cacheFactory(cache, http, keys)
            val aliyunCached = cacheFactory(cache, aliyunHttp, keys)
            SchemeRoutingFactory(cached, DefaultDataSource.Factory(context), aliyunCached)
        }
        // 最外层再套一层并行分片路由（v1.1.47）：只有「当前正在播放的那一首」会被认领，
        // 其余请求原样落到上面这套缓存 / 单连接链路上
        return DefaultMediaSourceFactory(ParallelRoutingFactory(context.applicationContext, dataSource))
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun httpFactory(
        userAgent: String,
        headers: Map<String, String> = emptyMap(),
    ): DefaultHttpDataSource.Factory {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            // 部分电台会 302 到另一个协议（http→https），不放开会直接失败
            .setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) factory.setDefaultRequestProperties(headers)
        return factory
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun cacheFactory(
        cache: SimpleCache,
        upstream: DataSource.Factory,
        keys: CacheKeyFactory,
    ): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        // 网盘直链按 fileId 做键，避免「每次签名不同 → 永不命中」。
        // 历史 pan: 键在 SchemeRoutingDataSource 里已摘掉 fragment；阿里直链用 fileId 做稳定键。
        .setCacheKeyFactory(keys)
        // 缓存写失败（磁盘满 / 权限）时退化成直连，不要因此中断播放
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 后台预取要用同一个 `SimpleCache` 实例（v1.1.46）。
     *
     * 单独开一个实例会直接抛 `Another SimpleCache instance uses the folder`，所以只能借。
     * 返回 null 表示缓存起不来（存储紧张），调用方必须放弃预取而不是自己再建一份。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    internal fun cacheForPrefetch(context: Context): SimpleCache? = get(context)

    /**
     * 该缓存键从文件头起已经连续落盘了至少 [minBytes] 字节。
     *
     * 并行分片会**绕过** SimpleCache 另起炉灶；若这里已经有可播的前缀，应让位给
     * CacheDataSource，否则弱网下一抖就会「歌词都出来了、播放却报网络错误」。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun hasCachedPrefix(context: Context, key: String, minBytes: Long = DEFAULT_CACHED_PREFIX_BYTES): Boolean {
        if (key.isBlank() || minBytes <= 0L) return false
        val cache = get(context) ?: return false
        // 正数 = 从 position 起连续已缓存的长度；负数 = 空洞长度
        val cached = runCatching { cache.getCachedLength(key, 0L, Long.MAX_VALUE) }.getOrDefault(0L)
        return cached >= minBytes
    }

    /** 当前缓存占用（字节）；拿不到实例返回 0 */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun sizeBytes(context: Context): Long =
        runCatching { get(context)?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** 清空缓存。返回是否真的清掉了（缓存没初始化时返回 false） */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun clear(context: Context): Boolean = runCatching {
        // 并行分片的 .part 也在 cacheDir 下，用户点「清除在线缓存」时应当一并清掉
        com.luckylearning.windtone.playback.parallel.ParallelDownloadRegistry.clearTemp(context)
        val c = get(context) ?: return false
        // 逐个 key 移除而不是删目录：SimpleCache 还持有文件句柄与索引，
        // 直接删目录会让它在下次写入时报错
        c.keys.toList().forEach { key -> runCatching { c.removeResource(key) } }
        true
    }.getOrDefault(false)

    /** 服务销毁时释放（不释放的话下次启动会抢占同一个目录锁） */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun release() {
        val c = instance ?: return
        instance = null
        runCatching { c.release() }
    }
}

/**
 * 按 scheme 把请求分给两个真实来源的 [DataSource] 包装。
 *
 * 之所以自己写而不是用 `ResolvingDataSource`：后者只能在 DataSpec 上做文章（改 URI / 头 / key），
 * 换不了「用哪个 DataSource 读」，而「本地走默认、远程走缓存」要求的正是换实现。
 *
 * 注意必须把 [TransferListener] **转给每一次新建的 delegate**：CacheDataSource 靠监听器
 * 计算已缓存进度，漏掉的话缓存会写但状态永远停在 0，表现为「进度条不走、看着像卡住」。
 */
@UnstableApi
private class SchemeRoutingFactory(
    private val remote: DataSource.Factory,
    private val local: DataSource.Factory,
    private val aliyun: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = SchemeRoutingDataSource(remote, local, aliyun)
}

@UnstableApi
private class SchemeRoutingDataSource(
    private val remote: DataSource.Factory,
    private val local: DataSource.Factory,
    private val aliyun: DataSource.Factory,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val frag = dataSpec.uri.fragment
        // 占位地址是 aliyun-pending://，也带着 #ay=。它还没有直链，不能送进 HTTP 工厂。
        val aliyun = OnlineMediaCache.shouldCache(dataSpec.uri) && (
            (frag != null && frag.startsWith(TrackFragment.PREFIX)) ||
                AliyunEndpoints.isDownloadHost(dataSpec.uri.host)
            )
        val cacheable = OnlineMediaCache.shouldCache(dataSpec.uri)
        val factory = when {
            aliyun -> this.aliyun
            cacheable -> remote
            else -> local
        }
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        val effective = if (aliyun) {
            val built = dataSpec.buildUpon()
                .setUri(dataSpec.uri.buildUpon().fragment(null).build())
            if (frag != null && frag.startsWith(TrackFragment.PREFIX)) {
                built.setKey(TrackFragment.cacheKey(frag))
            }
            built.build()
        } else {
            // 摘掉仅作缓存键的 fragment（含历史 `wt=`），不进真实 HTTP。
            if (frag != null) {
                dataSpec.buildUpon()
                    .setUri(dataSpec.uri.buildUpon().fragment(null).build())
                    .setKey(OnlineMediaCache.stableKey(dataSpec.uri) ?: dataSpec.key)
                    .build()
            } else {
                dataSpec
            }
        }
        return source.open(effective)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = delegate ?: return -1
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = delegate
        delegate = null
        source?.close()
    }
}

@UnstableApi
private class AdjustableEvictor(
    private val context: Context,
) : CacheEvictor {

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        trim(cache)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        trim(cache, length.coerceAtLeast(0L))
    }

    fun trim(cache: Cache, extra: Long = 0L) {
        val max = OnlineMediaCache.limitBytes(context)
        var guard = 0
        while (cache.cacheSpace + extra > max && guard++ < 10_000) {
            val oldest = cache.keys
                .asSequence()
                .flatMap { cache.getCachedSpans(it) }
                .minByOrNull { it.lastTouchTimestamp }
                ?: break
            cache.removeSpan(oldest)
        }
    }
}
