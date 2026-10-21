package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import com.luckylearning.windtone.data.baidu.BaiduRules
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
 *  3. **上限固定 + LRU**。缓存目录在 `cacheDir` 下，系统随时可能回收；给 512MB 上限，
 *     超出按最近最少使用淘汰，避免把车机存储吃满。
 */
object OnlineMediaCache {

    /** 缓存上限。512MB 约等于 5~8 张无损专辑，够覆盖通勤路上的重播 */
    const val MAX_BYTES = 512L * 1024 * 1024

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
     * 百度网盘的 access_token 提供方（v1.1.40）。
     *
     * 网盘 dlink **必须**拼 `&access_token=` 才能下载（官方文档的硬要求），而 dlink 是随
     * [com.luckylearning.windtone.data.baidu.BaiduPan.dlinks] 一起进队列的，URI 里不该带
     * token —— token 会被写进 Room 的播放队列、也可能进日志。所以放在这一层按请求补齐：
     * 由 [com.luckylearning.windtone.MusicApp] 在启动时注入，取不到就原样放行（不该因为
     * 这个装饰把本地/普通在线播放一起搞坏）。
     */
    @Volatile
    var panTokenProvider: (() -> String?)? = null

    fun panToken(): String? = runCatching { panTokenProvider?.invoke() }.getOrNull()

    /** 网盘直链的稳定缓存键前缀（fragment 里带 `wt=<fsId>`，见 BaiduPan.toSongs） */
    private const val PAN_FRAGMENT_PREFIX = "wt="

    /**
     * 网盘直链的稳定缓存键（v1.1.43）。
     *
     * 百度 dlink 每次播放都带新的签名，拿整个 URI 当键永远不可能命中 —— 之前因此**完全跳过**
     * 网盘文件的缓存，代价是「同一首重听一遍就要重新下 28MB」，弱网下就是无尽卡顿。
     * 现在按 fsId 做键：同一文件不同签名指向同一份缓存。
     *
     * 用 fragment 而不是查询参数，是因为查询参数会参与百度那边的签名校验，改成 fragment
     * 则完全不进 HTTP 请求。
     */
    fun panCacheKey(uri: Uri?): String? {
        val frag = uri?.fragment ?: return null
        if (!frag.startsWith(PAN_FRAGMENT_PREFIX)) return null
        // 只取到第一个 `&` 为止：v1.1.43 里 token 曾被我误拼进 fragment，
        // 于是键变成 `pan:123&access_token=...` —— 既不稳定（换 token 就换键、缓存全废），
        // 也把凭据写进了缓存索引。这里兜一层，无论 fragment 后面还挂什么都不会带进来。
        val fsId = frag.removePrefix(PAN_FRAGMENT_PREFIX).substringBefore('&').trim()
        return if (fsId.isEmpty()) null else "pan:$fsId"
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
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext),
                )
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
        val dataSource: DataSource.Factory = if (cache == null) {
            // 缓存不可用时仍然要经过路由包装：百度直链需要改 UA，
            // 直接返回 DefaultDataSource 会让「没缓存」这种边缘状态连播放一起丢。
            SchemeRoutingFactory(DefaultDataSource.Factory(context), DefaultDataSource.Factory(context))
        } else {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                // 部分电台会 302 到另一个协议（http→https），不放开会直接失败
                .setAllowCrossProtocolRedirects(true)
            val cached = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(http)
                // 网盘直链按 fsId 做键，避免「每次签名不同 → 永不命中」。
                // 注意优先用 `dataSpec.key`：网盘请求的真实 URI 里已经摘掉了 `#wt=…`
                // （见 SchemeRoutingDataSource.open），键由那里显式塞进来。
                .setCacheKeyFactory(object : CacheKeyFactory {
                    override fun buildCacheKey(dataSpec: DataSpec): String =
                        dataSpec.key ?: panCacheKey(dataSpec.uri) ?: dataSpec.uri.toString()
                })
                // 缓存写失败（磁盘满 / 权限）时退化成直连，不要因此中断播放
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            SchemeRoutingFactory(cached, DefaultDataSource.Factory(context))
        }
        return DefaultMediaSourceFactory(dataSource)
    }

    /** 当前缓存占用（字节）；拿不到实例返回 0 */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun sizeBytes(context: Context): Long =
        runCatching { get(context)?.cacheSpace ?: 0L }.getOrDefault(0L)

    /** 清空缓存。返回是否真的清掉了（缓存没初始化时返回 false） */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun clear(context: Context): Boolean = runCatching {
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
) : DataSource.Factory {

    override fun createDataSource(): DataSource = SchemeRoutingDataSource(remote, local)
}

@UnstableApi
private class SchemeRoutingDataSource(
    private val remote: DataSource.Factory,
    private val local: DataSource.Factory,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        // 网盘直链带时效签名，每次播放的 URI 都不同。v1.1.43 起用 fsId 做稳定缓存键
        // （见 panCacheKey），所以**能缓存的就缓存**；只有拿不到 fsId 的网盘请求才跳过，
        // 否则会白占 512MB 淘汰配额。UA / token 注入不受影响，仍在下面统一处理。
        val pan = BaiduRules.needsPanUserAgent(dataSpec.uri.host)
        // fragment 只用于「算缓存键」，不能留在真实请求里（见下面的 withAccessToken 注释）
        val panKey = if (pan) OnlineMediaCache.panCacheKey(dataSpec.uri) else null
        val cacheable = OnlineMediaCache.shouldCache(dataSpec.uri) &&
            (!pan || panKey != null)
        val factory = if (cacheable) remote else local
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        // 百度网盘的直链要求 UA 伪装成网盘客户端（见 BaiduRules.needsPanUserAgent），
        // 并且**必须**在 URL 上补 access_token —— 两样缺一个都是 403，而 403 在播放器里
        // 只会表现为「不出声」，没有可读的错误。
        // 放在这一层是因为「每个文件一个 DataSource」，而 UA / token 只能按请求设置。
        val effective = if (pan) {
            val url = BaiduRules.withAccessToken(dataSpec.uri.toString(), OnlineMediaCache.panToken())
            dataSpec.buildUpon()
                // 摘掉 `#wt=<fsId>`：它只是缓存键的载体，不该出现在真实请求里。
                // 留着它曾把 access_token 拼进了 fragment（v1.1.43 的播放全失败）。
                .setUri(Uri.parse(url).buildUpon().fragment(null).build())
                .setKey(panKey ?: dataSpec.key)
                .setHttpRequestHeaders(BaiduRules.panHeaders())
                .build()
        } else {
            dataSpec
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
