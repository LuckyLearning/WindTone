package com.luckylearning.windtone.overlay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台应用切换的滚动记录（v1.1.29，设计见 docs/设计-悬浮窗按应用隐藏.md）。
 *
 * 为什么需要它：悬浮窗按应用隐藏的前提是「知道哪些应用在前台」，
 * 而悬浮窗的价值全在**听风在后台**时 —— 用户在开车，看不到诊断页的实时行。
 * 所以检测通道把每次前台切换写进本机文件，停车后打开听风即可在诊断页回看、
 * 复制给开发者，或直接把应用勾进「不显示悬浮窗的应用」名单。
 *
 * 存储选型：`files/foreground_app_log.tsv` 纯文本，**不动数据库 schema**
 * （项目红线：改 schema 要 version+1，一个诊断日志不值得）。
 * 上限 [MAX_ENTRIES] 条滚动，写满从头截断；清应用数据时随之清掉，可接受。
 *
 * ⚠️ 时间格式化用 `java.text.SimpleDateFormat` 而非 `java.time`：
 * 后者要 API 26，而 minSdk 是 24（见 notes-3「低版本必崩」第一条）。
 */
internal object ForegroundAppLog {

    private const val TAG = "WindToneFgLog"
    private const val FILE_NAME = "foreground_app_log.tsv"

    /** 滚动保留的最大条数 */
    const val MAX_ENTRIES = 300

    /** 一条记录：切换发生的时间 + 前台应用包名 + 应用名（解析不到为 null） */
    data class Entry(val timeMs: Long, val pkg: String, val label: String?)

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    // ---------------------------------------------------------------- 纯函数

    /**
     * 这次切换要不要记录。
     *
     * 连续同一个应用不重复记：UsageStats 每 1.5s 轮一次，同一个应用会连报
     * 几十次，不去重历史就成了复读。A→B→A 算两次切换，都记（它们确实是两次）。
     */
    fun shouldRecord(lastPkg: String?, newPkg: String): Boolean =
        newPkg.isNotBlank() && newPkg != lastPkg

    /** 只保留最后 [MAX_ENTRIES] 条（列表按时间正序，新条目追加在尾部） */
    fun trim(entries: List<Entry>): List<Entry> =
        if (entries.size <= MAX_ENTRIES) entries else entries.takeLast(MAX_ENTRIES)

    /** 单行解析；格式不符返回 null（损坏行静默跳过，不让一条坏行毁掉整份历史） */
    fun parseLine(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val time = parts[0].toLongOrNull() ?: return null
        val pkg = parts[1]
        if (pkg.isBlank()) return null
        return Entry(time, pkg, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
    }

    /** 序列化一行 */
    fun formatLine(entry: Entry): String =
        "${entry.timeMs}\t${entry.pkg}\t${entry.label.orEmpty()}"

    /** 时间列的显示格式（`MM-dd HH:mm:ss`） */
    fun formatTime(timeMs: Long): String = fmt.format(Date(timeMs))

    /**
     * 包名 → 应用名（写入历史时解析一次存下来；解析不到返回 null）。
     *
     * 共享给两个检测通道（无障碍服务 / UsageStats 轮询）—— 应用名在**写入时**
     * 固化，是因为读历史时应用可能已经卸载，届时只剩包名可用。
     */
    fun labelOf(context: Context, pkg: String): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    /**
     * 复制给开发者用的纯文本：一行一条，人话优先。
     * 例：`10-19 08:32:14  全景影像  com.byd.avm`
     */
    fun formatForCopy(entries: List<Entry>): String =
        entries.joinToString("\n") { e ->
            "${formatTime(e.timeMs)}  ${e.label ?: "（应用名解析不到）"}  ${e.pkg}"
        }

    // ---------------------------------------------------------------- 文件

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * 记一条切换。内部加锁：无障碍事件与轮询可能来自不同线程。
     * 失败只记日志、绝不上抛 —— 这是诊断功能，不能因为它把播放打扰了。
     */
    fun append(context: Context, pkg: String, label: String?) {
        if (!shouldRecord(readLastPkg(context), pkg)) return
        synchronized(this) {
            runCatching {
                val entries = trim(read(context) + Entry(System.currentTimeMillis(), pkg, label))
                file(context).writeText(entries.joinToString("\n") { formatLine(it) } + "\n")
            }.onFailure { Log.w(TAG, "写前台记录失败", it) }
        }
    }

    /** 读全部历史（按时间正序；诊断页展示时反转成最新在上） */
    fun read(context: Context): List<Entry> = synchronized(this) {
        runCatching {
            file(context).takeIf { it.isFile }?.readLines()?.mapNotNull { parseLine(it) } ?: emptyList()
        }.getOrElse { e ->
            Log.w(TAG, "读前台记录失败", e)
            emptyList()
        }
    }

    /** 仅供 [append] 去重：只读最后一条的包名 */
    private fun readLastPkg(context: Context): String? = read(context).lastOrNull()?.pkg

    /** 清空（诊断页按钮，调用方负责二次确认） */
    fun clear(context: Context) {
        synchronized(this) {
            runCatching { file(context).delete() }
                .onFailure { Log.w(TAG, "清前台记录失败", it) }
        }
    }
}
