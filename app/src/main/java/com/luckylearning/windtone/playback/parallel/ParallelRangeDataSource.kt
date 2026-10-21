package com.luckylearning.windtone.playback.parallel

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 并行分片的接入点（v1.1.47）。
 *
 * 它在整条数据源链的**最外层**：只有「当前正在播放的那一首」会被 [ParallelDownloadRegistry]
 * 认领并改走并行分片；其它一切（预取、本地文件、队列里别的歌、服务器不支持 Range 的情况）
 * 原样交给 [fallback]，也就是原来的缓存 / 单连接路径 —— 这样这一层的风险被限制在
 * 「一首歌、且只在支持分片时」。
 *
 * 注意 [close] 不取消下载：播放器 seek 时会 close 再 open 同一首，下载必须继续跑。
 * 真正的取消由切歌（[ParallelDownloadRegistry.setCurrentKey]）触发。
 */
@UnstableApi
internal class ParallelRoutingFactory(
    private val context: Context,
    private val fallback: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        ParallelRangeDataSource(context.applicationContext, fallback)
}

@UnstableApi
internal class ParallelRangeDataSource(
    private val context: Context,
    private val fallbackFactory: DataSource.Factory,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var download: ParallelDownload? = null
    private var fallback: DataSource? = null
    private var currentSpec: DataSpec? = null
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        // fallback 可能还没创建（open 时才决定），所以两边都记一份
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        readPosition = dataSpec.position
        val parallel = ParallelDownloadRegistry.obtain(context, dataSpec)
        if (parallel != null) {
            download = parallel
            val available = parallel.totalBytes - dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, available)
            } else {
                available
            }
            return bytesRemaining
        }

        val source = fallbackFactory.createDataSource()
        listeners.forEach(source::addTransferListener)
        fallback = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val parallel = download
        if (parallel != null) {
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val want = if (bytesRemaining > 0) {
                minOf(length.toLong(), bytesRemaining).toInt()
            } else {
                length
            }
            val read = parallel.readAt(readPosition, buffer, offset, want)
            if (read <= 0) return C.RESULT_END_OF_INPUT
            readPosition += read
            if (bytesRemaining > 0) bytesRemaining -= read
            // 上报给播放器的带宽计 / 缓冲统计，否则「已缓冲」不动，看起来像卡住
            currentSpec?.let { spec -> listeners.forEach { it.onBytesTransferred(this, spec, /* isNetwork = */ true, read) } }
            return read
        }
        return fallback?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = if (download != null) currentSpec?.uri else fallback?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallback?.responseHeaders ?: emptyMap()

    override fun close() {
        // 只关掉「这一次 open」的引用：并行下载属于整首歌，seek 之后还要用
        download = null
        val source = fallback
        fallback = null
        source?.close()
    }
}
