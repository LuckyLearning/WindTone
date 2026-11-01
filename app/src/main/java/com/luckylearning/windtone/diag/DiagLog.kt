package com.luckylearning.windtone.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存环形事件日志（v1.1.88）。
 *
 * 定位：这是**给用户回传**用的轻量流水，不是给开发者看 Logcat 的替代品。
 * 车机上连 adb 都不一定有，用户能提供的只有「截图 + 一段文本」，所以关键路径
 * （服务启停、前台切换、悬浮窗显隐）都在这里留一行，诊断包一次性带走。
 *
 * 三条硬约束：
 *  1. **不落盘**：只在内存里留最近 [MAX] 条，进程结束即清 —— 不占空间、不涉及隐私留存；
 *  2. **不记敏感内容**：只记包名 / 状态 / 数量，不记歌名以外的用户数据，也不记 token；
 *  3. **永不抛异常**：任何调用点都不该因为日志失败而影响功能。
 */
internal object DiagLog {

    private const val MAX = 300

    private val entries = ArrayDeque<String>()

    /**
     * 连续重复合并（A13，2026-11-01）。
     *
     * 车机上「前台 → 同一个包名」会连着报很多次（导航 / 桌面来回切的时候尤其密），
     * 每条都要格式化一次时间戳。合并之后同一条只占一行、只在首次格式化，
     * 并在行尾标 `×N` —— 信息量反而更清楚：一眼看出「这件事发生了多少次」。
     *
     * 只合并**紧邻的相同 (tag, msg)**：中间插了别的事件就重新开一条，
     * 否则时间序就丢了（排障时最需要的就是先后关系）。
     */
    private var lastTag: String? = null
    private var lastMsg: String? = null

    /** 上一行在 [entries] 里是否处于「可被合并」形态（刚写、还没被裁掉） */
    private var lastMergeable = false
    private var repeatCount = 0

    /**
     * `SimpleDateFormat` 非线程安全，而这个日志从无障碍回调线程、主线程、
     * 多个协程并发写入 —— 共享一个实例会产出错乱时间戳甚至抛异常（外围
     * `runCatching` 会静默吞掉）。每线程一份，既线程安全又无锁争用。
     */
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /** 记录一条。tag 用短词（A11Y / OVERLAY / PLAYER / LIB），msg 写人话。 */
    fun log(tag: String, msg: String) {
        runCatching {
            synchronized(this) {
                if (lastMergeable && tag == lastTag && msg == lastMsg) {
                    repeatCount++
                    // 沿用首次出现的时间戳：合并的全部意义就是省掉这次格式化
                    val stamp = entries.lastOrNull()?.substringBefore(" [")
                        ?.ifBlank { null } ?: fmt.get()!!.format(Date())
                    if (entries.isNotEmpty()) entries.removeLast()
                    entries.addLast("$stamp [$tag] $msg ×$repeatCount")
                    return
                }
                val line = "${fmt.get()!!.format(Date())} [${tag}] $msg"
                entries.addLast(line)
                while (entries.size > MAX) entries.removeFirst()
                lastTag = tag
                lastMsg = msg
                repeatCount = 1
                lastMergeable = true
            }
        }
    }

    /** 最近的全部记录（旧的在前）；供诊断包读取 */
    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        lastTag = null
        lastMsg = null
        lastMergeable = false
        repeatCount = 0
    }

    /** 当前条数（诊断包里报一句，便于判断「是不是刚重启过」） */
    @Synchronized
    fun size(): Int = entries.size
}
