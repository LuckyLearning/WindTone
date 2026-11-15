package com.anylyric.lyrics

import android.content.Context
import java.io.File

/**
 * 在线歌词的**磁盘缓存**。
 *
 * 这是「歌词稳不稳」的关键一环，而它和网络无关：一首歌的词一旦取到过，就永远在本地。
 * 只有内存缓存时，每次冷启动、每次换歌回来都要重新联网 —— 网络慢一拍就是「暂无歌词」，
 * 而用户看到的是「这个应用时好时坏」。
 *
 * 0.2.25 起缓存**独立于在线补词开关**：关掉在线补词只是不再联网，
 * 已经存下来的词照样能用（否则用户一关开关，屏上原本有词的歌立刻变「暂无歌词」，
 * 看起来像开关把数据删了）。缓存上限也做成设置项（[setLimit]），0 = 不缓存。
 *
 * 约束：
 *  · 单文件上限 [MAX_BYTES]，超出不写（正常 LRC 只有几 KB，超了必是异常响应）；
 *  · 目录上限 = [limit] 首，按最后写入时间淘汰最旧的 —— 车机存储小，不能无限长；
 *  · 全部 IO 都在调用方的工作线程上跑，读写在主线程之外（见 OnlineLyricsFetcher）。
 */
object LyricStore {

    private const val DIR_NAME = "lyrics"
    private const val EXT = ".lrc"
    private const val MAX_BYTES = 96 * 1024

    /** 出厂上限。真正的值来自设置（`Prefs.lyricCacheLimit`），这里只是兜底。 */
    const val DEFAULT_LIMIT = 240

    @Volatile
    private var root: File? = null

    /** 上限（首）。0 = 不缓存：不写新文件，旧文件在 [setLimit] 里清掉。 */
    @Volatile
    private var limit: Int = DEFAULT_LIMIT

    fun bind(context: Context) {
        if (root != null) return
        root = File(context.applicationContext.filesDir, DIR_NAME)
    }

    /**
     * 改上限并立即收敛到新预算。
     *
     * `0` 是「关掉缓存」的意思，必须把已有文件一并删掉 —— 只挡新写入的话，
     * 用户关掉后还能看到旧歌有词，会以为开关没生效。
     */
    fun setLimit(value: Int) {
        val next = value.coerceAtLeast(0)
        limit = next
        val dir = root ?: return
        runCatching {
            if (next == 0) dir.listFiles()?.forEach { it.delete() } else trim(dir, next)
        }
    }

    fun key(title: String?, artist: String?): String = LyricMatch.cacheKey(title, artist)

    fun load(key: String): String? {
        val dir = root ?: return null
        return loadAt(dir, key)?.let { stripTag(it) }
    }

    /**
     * @param source 这一版词的来源 id（[com.anylyric.lyrics.online.OnlineSource.id]）。
     *   写进文件头一行 `[by:AnyLyric/<id>]` —— 单独放边车文件会让淘汰与统计都要
     *   维护两份，而 LRC 本来就允许 `[by:]` 这类元信息行，播放器与解析器都会忽略它。
     */
    fun save(key: String, lrc: String, source: String? = null) {
        val dir = root ?: return
        saveAt(dir, key, tagged(lrc, source), limit)
    }

    /** 缓存里这一版词的来源 id；旧版本写的缓存没有标记，返回 null。 */
    fun sourceOf(key: String): String? {
        val dir = root ?: return null
        return sourceAt(dir, key)
    }

    /** 设置里清空缓存时调用。 */
    fun clear() {
        val dir = root ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /** 当前占用：首数与字节数。设置页显示用，文件不多（≤ 上限）时开销可以忽略。 */
    fun stats(): Pair<Int, Long> {
        val dir = root ?: return 0 to 0L
        return statsAt(dir)
    }

    internal fun statsAt(dir: File): Pair<Int, Long> {
        val files = runCatching { dir.listFiles()?.filter { it.isFile } }.getOrNull()
            ?: return 0 to 0L
        var bytes = 0L
        for (file in files) bytes += file.length()
        return files.size to bytes
    }

    // ------------------------------------------------------------ 可单测的 IO

    internal fun loadAt(dir: File, key: String): String? {
        if (key.isEmpty()) return null
        val file = File(dir, key + EXT)
        if (!file.isFile) return null
        if (file.length() > MAX_BYTES) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * @param max 目录预算（首）。0 表示不缓存，直接不写。
     */
    internal fun saveAt(dir: File, key: String, lrc: String, max: Int = DEFAULT_LIMIT) {
        if (max <= 0) return
        if (key.isEmpty() || lrc.isBlank()) return
        if (lrc.length > MAX_BYTES) return
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val file = File(dir, key + EXT)
            file.writeText(lrc)
            // 显式 touch：淘汰按最后写入时间排序，写入顺序才是「最近用过」的意思。
            file.setLastModified(System.currentTimeMillis())
            trim(dir, max)
        }
    }

    internal fun trim(dir: File, max: Int) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= max) return
        files.sortedBy { it.lastModified() }
            .take(files.size - max)
            .forEach { runCatching { it.delete() } }
    }

    // ------------------------------------------------------------ 来源标记

    /** 来源标记行的前缀。整行形如 `[by:AnyLyric/netease]`。 */
    private const val TAG_HEAD = "[by:AnyLyric/"

    internal fun tagged(lrc: String, source: String?): String =
        if (source.isNullOrBlank()) lrc else TAG_HEAD + source + "]\n" + lrc

    /**
     * 去掉来源标记行。
     *
     * 解析器本来就会忽略 `[by:]`（没有时间戳），但**纯文本**路径（[LrcParser.spread]）
     * 会把每个非空行都当成一句歌词 —— 不去掉的话，无轴歌词的第一句会变成
     * 「[by:AnyLyric/netease]」。所以读盘时就剥掉，别把这件事留给下游。
     */
    internal fun stripTag(text: String): String {
        if (!text.startsWith(TAG_HEAD)) return text
        val end = text.indexOf(']')
        if (end < 0) return text
        return text.substring(end + 1).trimStart('\n', '\r')
    }

    internal fun sourceAt(dir: File, key: String): String? {
        val raw = loadAt(dir, key) ?: return null
        if (!raw.startsWith(TAG_HEAD)) return null
        val end = raw.indexOf(']')
        if (end <= TAG_HEAD.length) return null
        return raw.substring(TAG_HEAD.length, end).trim().takeIf { it.isNotEmpty() }
    }
}
