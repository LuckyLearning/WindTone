package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
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
            DefaultDataSource.Factory(context)
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
        val factory = if (OnlineMediaCache.shouldCache(dataSpec.uri)) remote else local
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        delegate = source
        return source.open(dataSpec)
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
