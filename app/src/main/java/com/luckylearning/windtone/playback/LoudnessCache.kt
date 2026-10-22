package com.luckylearning.windtone.playback

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 曲目响度分析结果的**文件缓存**（v1.1.52）。
 *
 * ## 为什么不进 Room
 * 这份数据是**纯缓存**：丢了只是重新分析一遍，没有任何用户输入。为它加一张表
 * 意味着 Room schema 升版 → 触发 `fallbackToDestructiveMigration` **整库重建**，
 * 用户歌单/队列陪葬 —— 用一个缓存换掉真数据，得不偿失。所以放应用私有目录的
 * 单个文本文件里，格式自描述，坏行直接跳过（与在线歌单存档层同一容错口径）。
 *
 * ## 格式
 * 每行一条：`<增益mB><空格><stableKey>`。stableKey 是 URI（含 `:` `/`），
 * 空格分隔后**行内剩余部分整体是 key** —— key 里不会出现换行，天然无歧义。
 *
 * ## 并发
 * 读写都走 [ConcurrentHashMap] + 文件写入串行在调用方（[TrackVolume] 的
 * 单线程分析作用域）里，这里不再加锁 —— 崩在写文件最坏丢一条缓存，可接受。
 */
class LoudnessCache(private val file: File) {

    private val map = ConcurrentHashMap<String, Int>()
    @Volatile private var loaded = false
    @Volatile private var dirty = false

    /** 首次访问时把文件读进内存；坏行跳过、重行后写覆盖前值 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile) return
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val space = trimmed.indexOf(' ')
                if (space <= 0) return@forEach
                val gain = trimmed.substring(0, space).toIntOrNull() ?: return@forEach
                val key = trimmed.substring(space + 1)
                if (key.isNotBlank()) map[key] = gain
            }
        }
    }

    /** 某曲的增益（mB，带符号）；没分析过返回 null */
    fun gainMb(stableKey: String): Int? {
        ensureLoaded()
        return map[stableKey]
    }

    /** 记录一条分析结果并落盘（追加写；写入失败静默 —— 缓存丢了能重算） */
    fun put(stableKey: String, gainMb: Int) {
        ensureLoaded()
        map[stableKey] = gainMb
        dirty = true
        persist()
    }

    private fun persist() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            // 排序后写出：内容稳定，diff / 排障时两份文件才好对
            for (key in map.keys.toSortedSet()) {
                sb.append(map[key]).append(' ').append(key).append('\n')
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(file)) {
                // Windows 上目标被占用时 renameTo 会失败：退化为直接覆盖写
                file.writeText(sb.toString())
                tmp.delete()
            }
            dirty = false
        }
    }
}
