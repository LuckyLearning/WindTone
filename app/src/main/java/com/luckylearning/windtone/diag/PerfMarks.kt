package com.luckylearning.windtone.diag

import android.content.Context
import com.luckylearning.windtone.data.BootTrace

/**
 * 轻量性能埋点（A3）：冷启动 / 首帧复用 [BootTrace]；悬浮窗挂载单独记一次耗时。
 *
 * 只写内存计数与时间戳，**不改播放路径**；供诊断包「性能」段读取。
 */
object PerfMarks {

    @Volatile
    private var overlayMountStartElapsed: Long = -1L

    @Volatile
    private var overlayMountCostMs: Long? = null

    @Volatile
    private var overlayMountOk: Boolean? = null

    /** attachWindow 入口调用 */
    fun overlayMountBegin() {
        overlayMountStartElapsed = android.os.SystemClock.elapsedRealtime()
        overlayMountCostMs = null
        overlayMountOk = null
    }

    /** addView 成功或失败时调用 */
    fun overlayMountEnd(ok: Boolean) {
        val start = overlayMountStartElapsed
        if (start < 0L) {
            overlayMountOk = ok
            return
        }
        overlayMountCostMs = (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        overlayMountOk = ok
    }

    /** 供单测重置 */
    fun resetForTest() {
        overlayMountStartElapsed = -1L
        overlayMountCostMs = null
        overlayMountOk = null
    }

    fun overlayMountCostMs(): Long? = overlayMountCostMs

    fun overlayMountOk(): Boolean? = overlayMountOk

    /** 拼进诊断包的「性能」段 */
    fun buildReport(context: Context): String = buildString {
        appendLine("[性能]")
        val prev = runCatching { BootTrace.previous(context) }.getOrNull()
        if (prev == null || prev.lines.isEmpty()) {
            appendLine("冷启动：尚无上一轮 BootTrace（首次安装或文件被清）")
        } else {
            val frame = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_FRAME }
            val app = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_APP }
            val activity = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_ACTIVITY }
            val content = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_CONTENT }
            appendLine(
                "冷启动（上一轮）：" + when {
                    frame != null -> "已出首帧，耗时 ${frame.elapsedMs} ms"
                    prev.stoppedAtDiag -> "停在诊断页（未点进入应用）"
                    else -> "未出首帧，最后阶段=${prev.lastStage ?: "（空）"}"
                },
            )
            if (app != null) appendLine("  · ${BootTrace.STAGE_APP} @ ${app.elapsedMs} ms")
            if (activity != null) appendLine("  · ${BootTrace.STAGE_ACTIVITY} @ ${activity.elapsedMs} ms")
            if (content != null) appendLine("  · ${BootTrace.STAGE_CONTENT} @ ${content.elapsedMs} ms")
            if (frame != null) appendLine("  · ${BootTrace.STAGE_FRAME} @ ${frame.elapsedMs} ms（首帧）")
            prev.failures.forEach {
                appendLine("  · 失败 ${it.stage.removePrefix("fail:")}：${it.note ?: ""}")
            }
        }
        val cost = overlayMountCostMs
        val ok = overlayMountOk
        appendLine(
            "悬浮窗挂载：" + when {
                cost != null && ok == true -> "成功，耗时 ${cost} ms"
                cost != null && ok == false -> "失败，耗时 ${cost} ms"
                ok == true -> "已挂载（未采到耗时）"
                ok == false -> "挂载失败（未采到耗时）"
                else -> "本进程尚未挂载过"
            },
        )
    }.trimEnd()
}
