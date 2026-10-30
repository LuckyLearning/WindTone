package com.luckylearning.windtone.diag

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.AudioFx
import com.luckylearning.windtone.playback.softEq.SoftEqCurve
import com.luckylearning.windtone.playback.softEq.SoftEqGate
import com.luckylearning.windtone.playback.softEq.SoftEqPath
import com.luckylearning.windtone.playback.softEq.SoftEqSpike

/**
 * Soft-EQ summary for the diagnostics pack (`[软EQ]` section, E5).
 *
 * Car HU troubleshooting for "why is EQ flat / why soft path not on" needs
 * phase, user-confirm Prefs, and a short curve digest in one paste — no adb.
 * Read-only; no secrets. Curve gains are the five soft centers (mB), short enough
 * to print. Docs: docs/功能-软EQ.md.
 */
@UnstableApi
internal object SoftEqProbe {

    private val PHASE_ZH = mapOf(
        SoftEqGate.Phase.IDLE to "空闲",
        SoftEqGate.Phase.CANDIDATE to "候选未启用",
        SoftEqGate.Phase.SOFT_ACTIVE to "软路径启用中",
        SoftEqGate.Phase.HARD_ACTIVE to "硬 EQ 优先",
    )

    fun buildReport(context: Context): String {
        val prefs = Prefs(context)
        val state = SoftEqPath.state.value
        return buildFrom(
            phase = state.phase,
            prefsConfirm = prefs.softEqUserConfirm,
            memoryConfirm = state.userConfirmSoft,
            enabled = state.enabled,
            candidate = state.candidate,
            lastHard = state.lastHardUnavailable,
            spikeEnabled = SoftEqSpike.ENABLED,
            curveSource = state.curveSource,
            curvePresetId = state.curvePresetId,
            curveGainsMb = state.curveGainsMb,
            centersHz = SoftEqPath.currentCentersHz().toList(),
        )
    }

    /** Pure formatter for unit tests (no Android Prefs / SoftEqPath). */
    fun buildFrom(
        phase: SoftEqGate.Phase,
        prefsConfirm: Boolean,
        memoryConfirm: Boolean,
        enabled: Boolean,
        candidate: Boolean,
        lastHard: AudioFx.Unavailable,
        spikeEnabled: Boolean,
        curveSource: SoftEqCurve.Source,
        curvePresetId: String,
        curveGainsMb: List<Int>,
        centersHz: List<Int>,
    ): String = buildString {
        appendLine("[软EQ]")
        appendLine("相位：${describePhase(phase)}")
        appendLine("Prefs.softEqUserConfirm：${yn(prefsConfirm)}")
        appendLine(
            "内存确认：${yn(memoryConfirm)}" +
                if (prefsConfirm != memoryConfirm) "（与 Prefs 不一致）" else "",
        )
        appendLine("处理器启用：${yn(enabled)}   候选：${yn(candidate)}")
        appendLine("SoftEqSpike.ENABLED：$spikeEnabled")
        appendLine("硬不可用原因：${describeHard(lastHard)}")
        appendLine("曲线：${describeCurve(curveSource, curvePresetId, curveGainsMb)}")
        appendLine("中心Hz：${centersHz.joinToString("/")}")
        appendLine(
            "说明：只读摘要；Prefs 键 soft_eq_user_confirm；" +
                "SOFT_ACTIVE 才真正滤波；硬恢复强制关软（不双开）。",
        )
    }.trimEnd()

    fun describePhase(phase: SoftEqGate.Phase): String {
        val zh = PHASE_ZH[phase] ?: "未知"
        return "${phase.name}（$zh）"
    }

    fun describeHard(reason: AudioFx.Unavailable): String = when (reason) {
        AudioFx.Unavailable.NONE -> "NONE（无）"
        AudioFx.Unavailable.SESSION_PENDING -> "SESSION_PENDING（等会话）"
        AudioFx.Unavailable.DEVICE_REJECTED -> "DEVICE_REJECTED（设备拒绝）"
    }

    fun describeCurve(
        source: SoftEqCurve.Source,
        presetId: String,
        gainsMb: List<Int>,
    ): String {
        val preset = if (presetId.isEmpty()) "（无预设 id）" else "preset=$presetId"
        val gains = if (gainsMb.isEmpty()) "（空）" else gainsMb.joinToString(",")
        val audible = SoftEqCurve.isAudible(gainsMb.toIntArray())
        return "$source $preset 增益mB=[$gains] 可听=${yn(audible)}"
    }

    private fun yn(v: Boolean) = if (v) "开" else "关"
}