package com.luckylearning.windtone.playback

/**
 * 进入场景前的**整包快照**（音效 + 速度 + 变调 + 睡眠剩余）。
 *
 * 退出场景时还原；坏格式按「无快照」处理（保住现状）。
 * 字段用单元分隔符，纯 JVM 可测，不引入 JSON。
 *
 * 编码：`v1` + 11 个字段（enabled / presetId / bands / bass / virtual /
 * loudness / reverbId / schemeId / speed / pitch / sleepMs）
 */
object SceneStash {

    private const val FIELD = '\u001f'
    private const val VERSION = "v1"
    private const val FIELD_COUNT = 12 // version + 11

    data class Snapshot(
        val enabled: Boolean,
        val presetId: String,
        val bandsCsv: String,
        val bass: Int,
        val virtual: Int,
        val loudness: Int,
        val reverbId: String,
        /** 进场景前手机/车载当前方案 id（空 = 没选方案） */
        val schemeId: String,
        val speed: Float,
        val pitchSemitones: Int,
        /** 进场景时睡眠定时剩余毫秒；0 = 当时未开 */
        val sleepRemainingMs: Long,
    )

    fun encode(snap: Snapshot): String = listOf(
        VERSION,
        if (snap.enabled) "1" else "0",
        snap.presetId,
        snap.bandsCsv,
        snap.bass.toString(),
        snap.virtual.toString(),
        snap.loudness.toString(),
        snap.reverbId,
        snap.schemeId,
        snap.speed.toString(),
        snap.pitchSemitones.toString(),
        snap.sleepRemainingMs.toString(),
    ).joinToString(FIELD.toString())

    fun decode(raw: String): Snapshot? {
        if (raw.isBlank()) return null
        val f = raw.split(FIELD)
        if (f.size != FIELD_COUNT) return null
        if (f[0] != VERSION) return null
        val enabled = when (f[1]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        val bass = f[4].toIntOrNull() ?: return null
        val virtual = f[5].toIntOrNull() ?: return null
        val loudness = f[6].toIntOrNull() ?: return null
        val speed = f[9].toFloatOrNull() ?: return null
        val pitch = f[10].toIntOrNull() ?: return null
        val sleep = f[11].toLongOrNull() ?: return null
        return Snapshot(
            enabled = enabled,
            presetId = f[2],
            bandsCsv = f[3],
            bass = bass,
            virtual = virtual,
            loudness = loudness,
            reverbId = f[7],
            schemeId = f[8],
            speed = speed,
            pitchSemitones = pitch,
            sleepRemainingMs = sleep.coerceAtLeast(0L),
        )
    }
}
