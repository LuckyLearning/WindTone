package com.luckylearning.windtone.diag

import android.content.Context
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.ui.AppStrings

/**
 * 轻量性能埋点（A3）：冷启动 / 首帧复用 [BootTrace]；悬浮窗挂载单独记一次耗时。
 *
 * 只写内存计数与时间戳，**不改播放路径**；供诊断包「性能」段读取。
 *
 * i18n 第 25 批：报告正文进 `pf_*`；BootTrace stage 常量字面量保持机器可读。
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
        appendLine(AppStrings.s(R.string.pf_sec))
        val prev = runCatching { BootTrace.previous(context) }.getOrNull()
        if (prev == null || prev.lines.isEmpty()) {
            appendLine(AppStrings.s(R.string.pf_cold_none))
        } else {
            val frame = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_FRAME }
            val app = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_APP }
            val activity = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_ACTIVITY }
            val content = prev.lines.firstOrNull { it.stage == BootTrace.STAGE_CONTENT }
            val coldDetail = when {
                frame != null -> AppStrings.s(R.string.pf_cold_frame, frame.elapsedMs)
                prev.stoppedAtDiag -> AppStrings.s(R.string.pf_cold_diag)
                else -> AppStrings.s(
                    R.string.pf_cold_no_frame,
                    prev.lastStage ?: AppStrings.s(R.string.pf_empty),
                )
            }
            appendLine(AppStrings.s(R.string.pf_cold_hdr, coldDetail))
            if (app != null) {
                appendLine(AppStrings.s(R.string.pf_stage_line, BootTrace.STAGE_APP, app.elapsedMs))
            }
            if (activity != null) {
                appendLine(
                    AppStrings.s(R.string.pf_stage_line, BootTrace.STAGE_ACTIVITY, activity.elapsedMs),
                )
            }
            if (content != null) {
                appendLine(
                    AppStrings.s(R.string.pf_stage_line, BootTrace.STAGE_CONTENT, content.elapsedMs),
                )
            }
            if (frame != null) {
                appendLine(
                    AppStrings.s(R.string.pf_stage_frame, BootTrace.STAGE_FRAME, frame.elapsedMs),
                )
            }
            prev.failures.forEach {
                appendLine(
                    AppStrings.s(
                        R.string.pf_fail_line,
                        it.stage.removePrefix("fail:"),
                        it.note ?: "",
                    ),
                )
            }
        }
        val cost = overlayMountCostMs
        val ok = overlayMountOk
        val overlayDetail = when {
            cost != null && ok == true -> AppStrings.s(R.string.pf_overlay_ok, cost)
            cost != null && ok == false -> AppStrings.s(R.string.pf_overlay_fail, cost)
            ok == true -> AppStrings.s(R.string.pf_overlay_ok_nocost)
            ok == false -> AppStrings.s(R.string.pf_overlay_fail_nocost)
            else -> AppStrings.s(R.string.pf_overlay_never)
        }
        appendLine(AppStrings.s(R.string.pf_overlay, overlayDetail))
    }.trimEnd()
}
