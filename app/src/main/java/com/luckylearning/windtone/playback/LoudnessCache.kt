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

    /** 距上次全量重写又追加了多少条 */
    private var appendsSinceRewrite = 0

    companion object {
        /**
         * 累计追加多少条之后整理一次。
         *
         * 追加写天然会产生重复 key 行（同一首歌重复分析），`ensureLoaded` 的
         * 「后写覆盖前值」会正确处理，所以重复不是正确性问题；但无限追加会让文件
         * 缓慢变长。每 [REWRITE_EVERY] 条整理一次，行数稳定在 `N ~ N+256`。
         */
        private const val REWRITE_EVERY = 256
    }

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

    /**
     * 记录一条分析结果并落盘。**追加一行**，不是全量重写。
     *
     * 2026-10-31 改：旧实现每 `put` 一次就 `toSortedSet()` + 全文件写一遍。
     * 而每分析完一首歌会 `put` 三处（track / album / 分析结果各一次），
     * 于是把整个曲库跑一遍的累计写盘量是 **O(N²)** —— 3000 首约 1.5GB
     * 累计写入，8000 首约 10GB。车机上是 eMMC，这笔账直接体现在「越用越卡」。
     *
     * 追加写之后文件里会有重复 key 行，`ensureLoaded` 的「后写覆盖前值」
     * 本来就是这么设计的（见类注释），不需要额外去重。
     */
    fun put(stableKey: String, gainMb: Int) {
        ensureLoaded()
        map[stableKey] = gainMb
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("$gainMb $stableKey\n")
            appendsSinceRewrite++
            if (appendsSinceRewrite >= REWRITE_EVERY) rewriteAll()
        }.onFailure {
            // 追加失败（目录被清、存储不可用）退化为全量写，缓存丢了能重算
            rewriteAll()
        }
    }

    /** 全量重写一份有序、无重复的文件 */
    private fun rewriteAll() {
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
            appendsSinceRewrite = 0
        }
    }
}
