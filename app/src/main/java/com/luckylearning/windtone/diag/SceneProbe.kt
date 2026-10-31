package com.luckylearning.windtone.diag

import android.content.Context
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.SceneMode
import com.luckylearning.windtone.playback.SceneProfiles
import com.luckylearning.windtone.playback.SceneStash
import com.luckylearning.windtone.ui.AppStrings

/**
 * 场景化摘要（诊断包 `[场景]` / `[Scene]` 段）。
 *
 * 排查「杀进程后听感不对 / 退出场景还原错 / 以为在驾车其实已手改离开」时，
 * 车机上只能靠诊断包：这里把当前档、冷启动会怎么走、stash 是否还在、
 * 以及本进程最近几次 SCENE 切换一次写清。只读、无密钥；EQ bands 只报长度。
 *
 * i18n 第 25 批：用户可见说明进 `scn_*`；scene id 字面量（drive/commute/sleep）保持机器可读。
 */
internal object SceneProbe {

    fun buildReport(context: Context): String {
        val prefs = Prefs(context)
        val persisted = prefs.sceneId
        val active = runCatching { SceneMode.activeIdValue() }.getOrDefault("")
        val stashRaw = prefs.sceneStash
        return buildString {
            appendLine(AppStrings.s(R.string.scn_sec))
            appendLine(AppStrings.s(R.string.scn_current, describeCurrent(persisted, active)))
            appendLine(
                AppStrings.s(
                    R.string.scn_cold,
                    describeColdStart(SceneProfiles.planColdStart(persisted)),
                ),
            )
            appendLine(
                AppStrings.s(
                    R.string.scn_prefs_id,
                    if (persisted.isEmpty()) AppStrings.s(R.string.scn_prefs_empty) else persisted,
                ),
            )
            appendLine(AppStrings.s(R.string.scn_prefs_stash, stashPresence(stashRaw)))
            val summary = summarizeStash(stashRaw)
            if (summary != null) {
                appendLine(AppStrings.s(R.string.scn_snap_summary, summary))
            }
            appendLine(AppStrings.s(R.string.scn_recent_hdr))
            val recent = recentSceneLogs(DiagLog.snapshot())
            if (recent.isEmpty()) {
                appendLine(AppStrings.s(R.string.scn_recent_empty))
            } else {
                // 最多 20 条，旧→新，与事件日志同向
                recent.takeLast(20).forEach { appendLine(it) }
            }
            appendLine(AppStrings.s(R.string.scn_note))
        }.trimEnd()
    }

    /** Prefs 与内存 activeId 对照；id 附本地化名便于读包。 */
    fun describeCurrent(persistedId: String, activeId: String): String {
        val pLabel = labelOf(persistedId)
        val aLabel = labelOf(activeId)
        return when {
            persistedId.isEmpty() && activeId.isEmpty() -> AppStrings.s(R.string.scn_off)
            persistedId == activeId -> pLabel
            else -> AppStrings.s(R.string.scn_mismatch, pLabel, aLabel)
        }
    }

    fun labelOf(id: String): String {
        if (id.isEmpty()) return AppStrings.s(R.string.scn_off)
        val gloss = when (id) {
            "drive" -> AppStrings.s(R.string.scn_drive)
            "commute" -> AppStrings.s(R.string.scn_commute)
            "sleep" -> AppStrings.s(R.string.scn_sleep)
            else -> null
        }
        return if (gloss != null) {
            AppStrings.s(R.string.scn_id_fmt, id, gloss)
        } else {
            AppStrings.s(R.string.scn_id_unknown, id)
        }
    }

    fun describeColdStart(plan: SceneProfiles.ColdStart): String = when (plan) {
        is SceneProfiles.ColdStart.None -> AppStrings.s(R.string.scn_cold_none)
        is SceneProfiles.ColdStart.Reapply ->
            AppStrings.s(R.string.scn_cold_reapply, labelOf(plan.id))
        is SceneProfiles.ColdStart.ClearInvalid -> AppStrings.s(R.string.scn_cold_clear)
    }

    fun stashPresence(raw: String): String {
        if (raw.isEmpty()) return AppStrings.s(R.string.scn_stash_none)
        val ok = SceneStash.decode(raw) != null
        return if (ok) {
            AppStrings.s(R.string.scn_stash_ok)
        } else {
            AppStrings.s(R.string.scn_stash_bad)
        }
    }

    /**
     * 快照人话摘要。不输出 bands 数值（可能很长），只报字符数。
     * @return null = 无快照或无法解码
     */
    fun summarizeStash(raw: String): String? {
        if (raw.isEmpty()) return null
        val snap = SceneStash.decode(raw)
            ?: return AppStrings.s(R.string.scn_stash_decode_fail)
        val eq = AppStrings.s(if (snap.enabled) R.string.dr_on else R.string.dr_off)
        val scheme = snap.schemeId.ifEmpty { AppStrings.s(R.string.scn_no_scheme) }
        val preset = snap.presetId.ifEmpty { AppStrings.s(R.string.scn_none_paren) }
        val bandsLen = snap.bandsCsv.length
        val reverb = snap.reverbId.ifEmpty { AppStrings.s(R.string.scn_none_paren) }
        return AppStrings.s(
            R.string.scn_snap_fmt,
            eq,
            preset,
            bandsLen,
            snap.bass,
            snap.virtual,
            snap.loudness,
            reverb,
            scheme,
            snap.speed.toString(),
            snap.pitchSemitones,
            snap.sleepRemainingMs,
        )
    }

    /** 从整份 DiagLog 抽出带 [SCENE] 的行（已含时间戳）。 */
    fun recentSceneLogs(allLines: List<String>): List<String> =
        allLines.filter { it.contains("[SCENE]") }
}
