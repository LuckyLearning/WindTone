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
 * 约束：
 *  · 单文件上限 [MAX_BYTES]，超出不写（正常 LRC 只有几 KB，超了必是异常响应）；
 *  · 目录上限 [MAX_FILES] 首，按最后写入时间淘汰最旧的 —— 车机存储小，不能无限长；
 *  · 全部 IO 都在调用方的工作线程上跑，读写在主线程之外（见 OnlineLyricsFetcher）。
 */
object LyricStore {

    private const val DIR_NAME = "lyrics"
    private const val EXT = ".lrc"
    private const val MAX_FILES = 240
    private const val MAX_BYTES = 96 * 1024

    @Volatile
    private var root: File? = null

    fun bind(context: Context) {
        if (root != null) return
        root = File(context.applicationContext.filesDir, DIR_NAME)
    }

    fun key(title: String?, artist: String?): String = LyricMatch.cacheKey(title, artist)

    fun load(key: String): String? {
        val dir = root ?: return null
        return loadAt(dir, key)
    }

    fun save(key: String, lrc: String) {
        val dir = root ?: return
        saveAt(dir, key, lrc)
    }

    /** 设置里关掉在线补词时清空，避免把不再使用的内容留在盘上。 */
    fun clear() {
        val dir = root ?: return
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    // ------------------------------------------------------------ 可单测的 IO

    internal fun loadAt(dir: File, key: String): String? {
        if (key.isEmpty()) return null
        val file = File(dir, key + EXT)
        if (!file.isFile) return null
        if (file.length() > MAX_BYTES) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    internal fun saveAt(dir: File, key: String, lrc: String) {
        if (key.isEmpty() || lrc.isBlank()) return
        if (lrc.length > MAX_BYTES) return
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val file = File(dir, key + EXT)
            file.writeText(lrc)
            // 显式 touch：淘汰按最后写入时间排序，写入顺序才是「最近用过」的意思。
            file.setLastModified(System.currentTimeMillis())
            trim(dir, MAX_FILES)
        }
    }

    internal fun trim(dir: File, max: Int) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= max) return
        files.sortedBy { it.lastModified() }
            .take(files.size - max)
            .forEach { runCatching { it.delete() } }
    }
}
