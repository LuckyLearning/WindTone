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
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 记录一条。tag 用短词（A11Y / OVERLAY / PLAYER / LIB），msg 写人话。 */
    fun log(tag: String, msg: String) {
        runCatching {
            val line = "${fmt.format(Date())} [${tag}] $msg"
            synchronized(this) {
                entries.addLast(line)
                while (entries.size > MAX) entries.removeFirst()
            }
        }
    }

    /** 最近的全部记录（旧的在前）；供诊断包读取 */
    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /** 当前条数（诊断包里报一句，便于判断「是不是刚重启过」） */
    @Synchronized
    fun size(): Int = entries.size
}
