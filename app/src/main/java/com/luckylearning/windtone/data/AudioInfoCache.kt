package com.luckylearning.windtone.data

/**
 * 音频技术参数的内存缓存（v1.2.57）。
 *
 * ## 为什么需要
 *
 * 三页横滑让「来回翻页」变成常动作，而信息页的音频参数是**整条链路里最重的一步**：
 * `AudioInspector.inspect` 要开一次 `MediaExtractor`，无损容器还要再开一次解码器、
 * seek 到中段、解 3 秒 PCM 做频谱分析（判假无损）。云盘曲目更甚 —— `seekTo` 会触发
 * 网络 Range 请求。同一首歌来回翻两下就重算两遍，完全没必要：
 * 音频参数只由文件本身决定，文件不变结果就不变。
 *
 * ## 容量
 *
 * 按**条数**算（默认 [DEFAULT_CAPACITY] 条），不按 MB —— 每条只是十几个标量字段，
 * 和图片缓存按字节算完全不是一个量级，不需要挂 `Runtime.maxMemory()`。
 * 走 LRU，翻歌超过容量后最早的自动淘汰。
 *
 * ## null 也缓存
 *
 * 解析失败 / 远程不可读时结果是 `null`，这同样是「已知结论」，必须记住，
 * 否则每次翻回都会把那次注定失败的解析再跑一遍。
 */
internal class AudioInfoCache(private val capacity: Int = DEFAULT_CAPACITY) {

    /** 区分「没查过」与「查过但没有结果」—— 两者都合法，但只有前者值得再算一次 */
    class Entry(val info: AudioInspector.Info?)

    private val entries =
        object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > capacity
        }

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, info: AudioInspector.Info?) {
        entries[key] = Entry(info)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    /** 测试与诊断用：当前键的先后顺序（最久未用在最前） */
    @Synchronized
    fun keys(): List<String> = entries.keys.toList()

    companion object {
        const val DEFAULT_CAPACITY: Int = 8
    }
}
