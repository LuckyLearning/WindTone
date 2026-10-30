package com.luckylearning.windtone.playback

/**
 * 用户自己保存的均衡器预设（曲线 + 低音 / 环绕 / 响度 / 混响）。
 *
 * 与 [EqPresets]、[SoundSchemes] 并存：
 *  - 内置预设只描述曲线，方案是一键听感包，这里是用户命名的快照；
 *  - id 以 `u_` 开头，和内置 id 不会撞车（发布后的内置 id 也不许改成这个前缀）；
 *  - 选中后 id 写进 `Prefs.eqPresetId`。拖频段会把该 id 清空（回到「自定义」）；
 *    只动低音 / 环绕 / 响度 / 混响时，内置曲线预设保留，用户预设则离开
 *    （用户预设把这些增强也存进去了，再改就不算「还是这一条」）。
 *
 * 编码是一行一条、字段用单元分隔符隔开，纯 JVM 可测，不引入 JSON。
 * 名字里的换行和分隔符会在写入前去掉，所以不需要转义。
 */
object UserEqPresets {

    const val MAX_PRESETS = 24
    const val MAX_NAME_LENGTH = 24

    private const val FIELD = '\u001f'
    private const val FIELD_COUNT = 7

    data class Preset(
        val id: String,
        val name: String,
        /** 设备频段增益，逗号分隔的 mB，与 `Prefs.eqBands` 同一格式 */
        val bandsCsv: String,
        val bass: Int,
        val virtualizer: Int,
        val loudness: Int,
        val reverbId: String,
    )

    fun isUserId(id: String): Boolean =
        id.startsWith("u_") && id.length >= 4 && id.all { it.isLetterOrDigit() || it == '_' }

    /**
     * 只改增强（低音 / 环绕 / 响度 / 混响）、曲线没动时，预设 id 怎么留。
     *
     * 内置曲线预设不含这些增强，id 保留；用户预设含，id 清空。方案 id 由调用方另清。
     */
    fun presetIdAfterManualEnhancement(presetId: String): String =
        if (isUserId(presetId)) "" else presetId

    fun sanitizeName(raw: String): String? {
        val cleaned = raw.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(FIELD.toString(), "")
            .take(MAX_NAME_LENGTH)
        return cleaned.ifBlank { null }
    }

    fun validBands(csv: String): Boolean =
        csv.all { it.isDigit() || it == ',' || it == '-' || it == ' ' }

    fun create(
        name: String,
        bandsCsv: String,
        bass: Int,
        virtualizer: Int,
        loudness: Int,
        reverbId: String,
        nowMillis: Long = System.currentTimeMillis(),
        salt: Int = (0..0xFFFF).random(),
    ): Preset? {
        val n = sanitizeName(name) ?: return null
        if (!validBands(bandsCsv)) return null
        val safeSalt = salt and 0xFFFF
        return Preset(
            id = "u_${nowMillis.toString(36)}_$safeSalt",
            name = n,
            bandsCsv = bandsCsv.trim(),
            bass = bass.coerceIn(0, 1000),
            virtualizer = virtualizer.coerceIn(0, 1000),
            loudness = loudness.coerceIn(0, 1500),
            reverbId = reverbId,
        )
    }

    fun add(existing: List<Preset>, preset: Preset): List<Preset>? {
        if (existing.size >= MAX_PRESETS) return null
        if (!isUserId(preset.id)) return null
        if (existing.any { it.id == preset.id }) return null
        return existing + preset
    }

    fun rename(existing: List<Preset>, id: String, name: String): List<Preset>? {
        val n = sanitizeName(name) ?: return null
        if (existing.none { it.id == id }) return null
        return existing.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun rewrite(
        existing: List<Preset>,
        id: String,
        bandsCsv: String,
        bass: Int,
        virtualizer: Int,
        loudness: Int,
        reverbId: String,
    ): List<Preset>? {
        if (!validBands(bandsCsv)) return null
        if (existing.none { it.id == id }) return null
        return existing.map {
            if (it.id != id) it
            else it.copy(
                bandsCsv = bandsCsv.trim(),
                bass = bass.coerceIn(0, 1000),
                virtualizer = virtualizer.coerceIn(0, 1000),
                loudness = loudness.coerceIn(0, 1500),
                reverbId = reverbId,
            )
        }
    }

    fun remove(existing: List<Preset>, id: String): List<Preset> =
        existing.filter { it.id != id }

    fun encode(presets: List<Preset>): String =
        presets.joinToString("\n") { p ->
            listOf(
                p.id,
                p.name,
                p.bandsCsv,
                p.bass.toString(),
                p.virtualizer.toString(),
                p.loudness.toString(),
                p.reverbId,
            ).joinToString(FIELD.toString())
        }

    /** 坏行跳过，不抛。空串 = 一份预设都没有。 */
    fun decode(raw: String): List<Preset> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Preset>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val f = line.split(FIELD)
            if (f.size != FIELD_COUNT) continue
            if (!isUserId(f[0])) continue
            val name = sanitizeName(f[1]) ?: continue
            if (!validBands(f[2])) continue
            val bass = f[3].toIntOrNull() ?: continue
            val virtualizer = f[4].toIntOrNull() ?: continue
            val loudness = f[5].toIntOrNull() ?: continue
            out += Preset(
                id = f[0],
                name = name,
                bandsCsv = f[2].trim(),
                bass = bass.coerceIn(0, 1000),
                virtualizer = virtualizer.coerceIn(0, 1000),
                loudness = loudness.coerceIn(0, 1500),
                reverbId = f[6],
            )
            if (out.size >= MAX_PRESETS) break
        }
        return out
    }
}
