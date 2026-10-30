package com.luckylearning.windtone.diag

import android.content.Context
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.playback.SceneMode
import com.luckylearning.windtone.playback.SceneProfiles
import com.luckylearning.windtone.playback.SceneStash

/**
 * 场景化摘要（诊断包 `[场景]` 段）。
 *
 * 排查「杀进程后听感不对 / 退出场景还原错 / 以为在驾车其实已手改离开」时，
 * 车机上只能靠诊断包：这里把当前档、冷启动会怎么走、stash 是否还在、
 * 以及本进程最近几次 SCENE 切换一次写清。只读、无密钥；EQ bands 只报长度。
 */
internal object SceneProbe {

    private val KNOWN_LABELS = mapOf(
        "drive" to "驾车",
        "commute" to "通勤",
        "sleep" to "助眠",
    )

    fun buildReport(context: Context): String {
        val prefs = Prefs(context)
        val persisted = prefs.sceneId
        val active = runCatching { SceneMode.activeIdValue() }.getOrDefault("")
        val stashRaw = prefs.sceneStash
        return buildString {
            appendLine("[场景]")
            appendLine("当前：${describeCurrent(persisted, active)}")
            appendLine("冷启动计划：${describeColdStart(SceneProfiles.planColdStart(persisted))}")
            appendLine(
                "Prefs.sceneId：${if (persisted.isEmpty()) "（空=关闭）" else persisted}",
            )
            appendLine("Prefs.sceneStash：${stashPresence(stashRaw)}")
            val summary = summarizeStash(stashRaw)
            if (summary != null) {
                appendLine("快照摘要：$summary")
            }
            appendLine("最近切换（本进程 DiagLog · SCENE）：")
            val recent = recentSceneLogs(DiagLog.snapshot())
            if (recent.isEmpty()) {
                appendLine("（空：本进程尚未切换场景，或进程刚启动）")
            } else {
                // 最多 20 条，旧→新，与事件日志同向
                recent.takeLast(20).forEach { appendLine(it) }
            }
            appendLine("说明：只读摘要；不含凭据与密钥；bands 曲线只报长度不报数值。")
        }.trimEnd()
    }

    /** Prefs 与内存 activeId 对照；id 附中文名便于读包。 */
    fun describeCurrent(persistedId: String, activeId: String): String {
        val pLabel = labelOf(persistedId)
        val aLabel = labelOf(activeId)
        return when {
            persistedId.isEmpty() && activeId.isEmpty() -> "关闭"
            persistedId == activeId -> pLabel
            else -> "Prefs=$pLabel · 内存=$aLabel（不一致）"
        }
    }

    fun labelOf(id: String): String {
        if (id.isEmpty()) return "关闭"
        val zh = KNOWN_LABELS[id]
        return if (zh != null) "$id（$zh）" else "$id（未知档）"
    }

    fun describeColdStart(plan: SceneProfiles.ColdStart): String = when (plan) {
        is SceneProfiles.ColdStart.None -> "无场景（不重套）"
        is SceneProfiles.ColdStart.Reapply -> "重套 ${labelOf(plan.id)}（不重拍 stash）"
        is SceneProfiles.ColdStart.ClearInvalid -> "清无效 id（有 stash 则 exit 还原）"
    }

    fun stashPresence(raw: String): String {
        if (raw.isEmpty()) return "无"
        val ok = SceneStash.decode(raw) != null
        return if (ok) "有（可解码）" else "有（格式损坏，按无快照处理）"
    }

    /**
     * 快照人话摘要。不输出 bands 数值（可能很长），只报字符数。
     * @return null = 无快照或无法解码
     */
    fun summarizeStash(raw: String): String? {
        if (raw.isEmpty()) return null
        val snap = SceneStash.decode(raw) ?: return "解码失败（字段数/版本不对）"
        val eq = if (snap.enabled) "开" else "关"
        val scheme = snap.schemeId.ifEmpty { "（无方案）" }
        val preset = snap.presetId.ifEmpty { "（无）" }
        val bandsLen = snap.bandsCsv.length
        return "EQ$eq preset=$preset bands长度=$bandsLen " +
            "bass=${snap.bass} virtual=${snap.virtual} loudness=${snap.loudness} " +
            "reverb=${snap.reverbId.ifEmpty { "（无）" }} scheme=$scheme " +
            "speed=${snap.speed} pitch=${snap.pitchSemitones} sleep剩余=${snap.sleepRemainingMs}ms"
    }

    /** 从整份 DiagLog 抽出带 [SCENE] 的行（已含时间戳）。 */
    fun recentSceneLogs(allLines: List<String>): List<String> =
        allLines.filter { it.contains("[SCENE]") }
}
