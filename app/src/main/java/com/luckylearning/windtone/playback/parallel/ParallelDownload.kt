package com.luckylearning.windtone.playback.parallel

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一首歌的**并行分片下载**（v1.1.47）。
 *
 * 为什么要有这一层：百度对第三方应用的下载限速是按单个 HTTP 连接算的，而播放器只用一条
 * 连接 —— 单首首播速度的上限被那条连接钉死。把同一个文件按 Range 切成 5 段、5 条连接同时拉，
 * 才能真正把首播等待压下去（用户明确要求「5 个并行、不限速」）。
 *
 * 数据落到 `cacheDir/parallel/<hash>.part`：先按总长预分配，各段线程 `seek` 到自己的偏移
 * 写，播放线程按 position 从同一个文件读 —— 所以「边下边播」不需要任何内存缓冲队列，
 * 也不需要改动播放器的读取模型。
 *
 * 五个必须处理的现实：
 *
 *  1. **服务器可能不支持 Range**（返回 200 而不是 206）。此时整段请求会拿回整个文件，
 *     5 条连接等于 5 倍流量。这里直接判失败，由上层降级为单连接 —— 不猜、不硬来。
 *  2. **dlink 会 302 到别的节点**（甚至跨 http/https），所以自己处理重定向、最多 5 跳。
 *  3. **播放会先读、下载还没到**。`readAt` 会等到该偏移真正落盘，超时/失败抛 IOException，
 *     ExoPlayer 自己会走它的重试与错误处理，不会静默出错数据。
 *  4. **取消要立刻生效**。协程取消打断不了阻塞中的 socket 读，所以标记位 + 中断 + 关闭文件
 *     三件事一起做；`reap()` 负责 join 线程并删临时文件。
 *  5. **不留半截垃圾**。切歌即 `cancel()` + `reap()`，临时文件随之删除，不占用户存储。
 */
class ParallelDownload(
    val key: String,
    private val uri: Uri,
    private val headers: Map<String, String>,
    private val userAgent: String,
    private val file: File,
    val totalBytes: Long,
    private val segments: List<RangeSegment>,
) {

    private val lock = ReentrantLock()
    private val progressChanged = lock.newCondition()
    private val written = LongArray(segments.size)
    private val finished = BooleanArray(segments.size)
    private val threads = ArrayList<Thread>(segments.size)
    private val canceled = AtomicBoolean(false)

    @Volatile
    private var failure: Throwable? = null

    // 读句柄只在播放线程用；写句柄每段各自持有（RandomAccessFile 的 seek 位置是实例级的，
    // 共享一个句柄会让 5 个线程互相抢文件指针，是这类实现最常见的错）
    private val reader = RandomAccessFile(file, "rw")
    private var readerClosed = false

    val segmentCount: Int
        get() = segments.size

    val downloadedBytes: Long
        get() = lock.withLock { written.sum() }

    fun start() {
        file.parentFile?.mkdirs()
        reader.setLength(totalBytes)
        segments.forEach { seg ->
            val t = Thread({ runSegment(seg) }, "wt-seg-${seg.index}")
            t.isDaemon = true
            threads += t
            t.start()
        }
    }

    /** 进程级取消：标记位 + 唤醒等待者 + 中断线程（socket 读会因此退出） */
    fun cancel() {
        canceled.set(true)
        lock.withLock { progressChanged.signalAll() }
        threads.forEach { runCatching { it.interrupt() } }
    }

    /** 取消后收尾：等线程退出、关句柄、删临时文件 */
    fun reap() {
        cancel()
        threads.forEach { runCatching { it.join(REAP_TIMEOUT_MS) } }
        lock.withLock {
            if (!readerClosed) {
                readerClosed = true
                runCatching { reader.close() }
            }
        }
        runCatching { file.delete() }
    }

    /** 诊断页用的一句话状态 */
    fun snapshot(): String {
        val (done, total, failed) = lock.withLock {
            Triple(written.sum(), totalBytes, failure)
        }
        if (failed != null) return "失败：${failed.message?.take(24) ?: "未知原因"}"
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return "已下 ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB（$pct%）"
    }

    /**
     * 从 [position] 读最多 [length] 字节。
     *
     * 该偏移的数据还没落盘时会等待（最长 [WAIT_TIMEOUT_MS]）；下载失败或取消同样抛
     * IOException —— 让播放器按「网络错误」处理，而不是拿到一片零字节。
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= totalBytes) return -1
        val index = SegmentPlanner.indexOf(segments, position)
        if (index < 0) return -1
        val seg = segments[index]
        awaitAvailable(seg, position - seg.start + 1)

        val availableEnd = lock.withLock { seg.start + written[index] }
        val canRead = minOf(length.toLong(), availableEnd - position, totalBytes - position).toInt()
        if (canRead <= 0) return -1
        lock.withLock {
            reader.seek(position)
            reader.readFully(buffer, offset, canRead)
        }
        return canRead
    }

    private fun awaitAvailable(seg: RangeSegment, need: Long) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        lock.withLock {
            while (written[seg.index] < need) {
                failure?.let { throw IOException("并行下载失败：${it.message}", it) }
                if (canceled.get()) throw IOException("并行下载已取消")
                if (finished[seg.index]) throw IOException("分段数据不完整")
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) throw IOException("等待分段数据超时")
                progressChanged.await(minOf(left, 500L), TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun runSegment(seg: RangeSegment) {
        var pos = seg.start
        try {
            RandomAccessFile(file, "rw").use { raf ->
                while (pos <= seg.endInclusive && !canceled.get()) {
                    val conn = connect(pos, seg.endInclusive)
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) throw IOException("HTTP $code")
                        // 200 表示服务器忽略了 Range：继续按段请求只会重复下整首
                        if (code == 200 && seg.start > 0) {
                            throw IOException("服务器不支持分段下载")
                        }
                        conn.inputStream.use { input ->
                            raf.seek(pos)
                            val buf = ByteArray(BUFFER_BYTES)
                            while (pos <= seg.endInclusive && !canceled.get()) {
                                val want = minOf(buf.size.toLong(), seg.endInclusive - pos + 1).toInt()
                                val read = input.read(buf, 0, want)
                                if (read <= 0) break
                                raf.write(buf, 0, read)
                                pos += read
                                lock.withLock {
                                    written[seg.index] = pos - seg.start
                                    progressChanged.signalAll()
                                }
                            }
                        }
                    } finally {
                        runCatching { conn.disconnect() }
                    }
                    // 连接提前断在段中间：循环会从当前位置续传，不算失败
                }
            }
            lock.withLock {
                finished[seg.index] = true
                progressChanged.signalAll()
            }
        } catch (t: Throwable) {
            if (canceled.get()) return
            Log.w(TAG, "分段 ${seg.index} 失败（${seg.start}-${seg.endInclusive}）", t)
            failure = t
            lock.withLock { progressChanged.signalAll() }
        }
    }

    /** 发一个 Range 请求；手动处理 3xx（dlink 常跳到另一个节点，还可能跨协议） */
    private fun connect(from: Long, to: Long): HttpURLConnection {
        var target = uri
        repeat(MAX_REDIRECTS) {
            val conn = (URL(target.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=$from-$to")
                // 身份编码：gzip 与 Range 混在一起会让字节偏移对不上
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (!headers.keys.any { it.equals("User-Agent", true) }) {
                    setRequestProperty("User-Agent", userAgent)
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                runCatching { conn.disconnect() }
                if (location.isNullOrBlank()) throw IOException("HTTP $code 缺少 Location")
                target = Uri.parse(URL(URL(target.toString()), location).toString())
            } else {
                return conn
            }
        }
        throw IOException("重定向次数过多")
    }

    private companion object {
        const val TAG = "ParallelDownload"
        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 5
        const val WAIT_TIMEOUT_MS = 60_000L
        const val REAP_TIMEOUT_MS = 600L
    }
}
