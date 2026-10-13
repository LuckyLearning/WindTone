package com.luckylearning.windtone.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志。
 *
 * 本应用不申请网络权限，崩溃信息无法上报，因此落地到
 * `filesDir/crash/crash-<时间戳>.txt`，用户可在设置页查看 / 导出。
 * 只保留最近 [KEEP] 份，避免长期占用空间。
 */
object CrashLog {

    private const val DIR_NAME = "crash"
    private const val KEEP = 5

    /** 在 Application.onCreate 里安装全局兜底处理器 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            // 交回系统默认处理，保证崩溃行为（对话框/退出）不被改变
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 按时间倒序排列的崩溃文件 */
    fun files(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun count(context: Context): Int = files(context).size

    /** 最近一次崩溃的全文 */
    fun latest(context: Context): String? =
        files(context).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    /** 全部崩溃日志合并（供导出） */
    fun allText(context: Context): String = buildString {
        val list = files(context)
        if (list.isEmpty()) {
            appendLine("（暂无崩溃记录）")
        } else {
            list.forEach { f ->
                appendLine("══════════ ${f.name} ══════════")
                appendLine(runCatching { f.readText() }.getOrDefault("(读取失败)"))
                appendLine()
            }
        }
    }

    fun clear(context: Context) {
        files(context).forEach { runCatching { it.delete() } }
    }

    private fun write(context: Context, thread: Thread, t: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "crash-$stamp.txt")
        val body = buildString {
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("线程：${thread.name}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine(t.stackTraceToString())
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine()
                appendLine("--- Caused by ---")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
        }
        runCatching { file.writeText(body) }
        prune(context)
    }

    private fun prune(context: Context) {
        files(context).drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
