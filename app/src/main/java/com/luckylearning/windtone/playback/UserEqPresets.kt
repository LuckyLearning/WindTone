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
 * 内部 Prefs 仍是一行一条、字段用单元分隔符隔开（纯 JVM 可测）；跨机备份另用 JSON 文件（[toExportJson]/[fromExportJson]）。
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

    // ------------------------------------------------------------------ 文件导入 / 导出（JSON）

    /** 导出文件里的 format 字段；不是本值的文件直接拒收。 */
    const val EXPORT_FORMAT = "windtone.user_eq_presets"
    const val EXPORT_VERSION = 1

    /** 导入时怎么并进现有「我的」列表。 */
    enum class ImportMode {
        /** 同 id 覆盖内容；新 id 追加；满 [MAX_PRESETS] 后跳过剩余并计入 skipped。 */
        MERGE,
        /** 用导入列表整表替换现有（超过上限截到 [MAX_PRESETS]，截掉的计入 skipped）。 */
        REPLACE,
    }

    data class ImportOutcome(
        val presets: List<Preset>,
        val added: Int,
        val updated: Int,
        val skipped: Int,
        val mode: ImportMode,
    )

    /**
     * 把用户预设写成可分享的 JSON。内部 Prefs 仍用 [encode]/[decode] 的分隔符格式；
     * 文件格式与 Prefs 编码无关，方便跨机备份。
     */
    fun toExportJson(
        presets: List<Preset>,
        exportedAtMillis: Long = System.currentTimeMillis(),
    ): String {
        val items = presets.joinToString(",") { p ->
            buildString {
                append('{')
                append("\"id\":").append(jsonQuote(p.id)).append(',')
                append("\"name\":").append(jsonQuote(p.name)).append(',')
                append("\"bands\":").append(jsonQuote(p.bandsCsv)).append(',')
                append("\"bass\":").append(p.bass).append(',')
                append("\"virtualizer\":").append(p.virtualizer).append(',')
                append("\"loudness\":").append(p.loudness).append(',')
                append("\"reverbId\":").append(jsonQuote(p.reverbId))
                append('}')
            }
        }
        return buildString {
            append('{')
            append("\"format\":").append(jsonQuote(EXPORT_FORMAT)).append(',')
            append("\"version\":").append(EXPORT_VERSION).append(',')
            append("\"exportedAt\":").append(exportedAtMillis).append(',')
            append("\"presets\":[").append(items).append(']')
            append('}')
        }
    }

    /**
     * 解析导出 JSON。format 不对或根本不是对象 → null（调用方提示「不是听风预设文件」）。
     * 单条坏项跳过；合法空列表返回 emptyList。
     */
    fun fromExportJson(raw: String): List<Preset>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val format = readJsonStringField(trimmed, "format") ?: return null
        if (format != EXPORT_FORMAT) return null
        val arrayBody = readJsonArrayBody(trimmed, "presets") ?: return null
        val objects = splitJsonObjects(arrayBody)
        val out = ArrayList<Preset>(objects.size.coerceAtMost(MAX_PRESETS))
        for (obj in objects) {
            val preset = presetFromJsonObject(obj) ?: continue
            if (out.any { it.id == preset.id }) continue
            out += preset
            if (out.size >= MAX_PRESETS) break
        }
        return out
    }

    fun importPresets(
        existing: List<Preset>,
        imported: List<Preset>,
        mode: ImportMode,
    ): ImportOutcome {
        val cleanImported = imported.filter {
            isUserId(it.id) && sanitizeName(it.name) != null && validBands(it.bandsCsv)
        }
        var skippedBad = imported.size - cleanImported.size
        return when (mode) {
            ImportMode.REPLACE -> {
                val kept = cleanImported.take(MAX_PRESETS)
                skippedBad += cleanImported.size - kept.size
                ImportOutcome(
                    presets = kept,
                    added = kept.size,
                    updated = 0,
                    skipped = skippedBad,
                    mode = mode,
                )
            }
            ImportMode.MERGE -> {
                val byId = existing.associateBy { it.id }.toMutableMap()
                var added = 0
                var updated = 0
                var skippedCap = 0
                for (p in cleanImported) {
                    if (byId.containsKey(p.id)) {
                        byId[p.id] = p
                        updated++
                    } else if (byId.size >= MAX_PRESETS) {
                        skippedCap++
                    } else {
                        byId[p.id] = p
                        added++
                    }
                }
                val order = existing.map { it.id }.toMutableList()
                for (p in cleanImported) {
                    if (p.id !in order && byId.containsKey(p.id)) order += p.id
                }
                val merged = order.mapNotNull { byId[it] }
                ImportOutcome(
                    presets = merged,
                    added = added,
                    updated = updated,
                    skipped = skippedBad + skippedCap,
                    mode = mode,
                )
            }
        }
    }

    private fun presetFromJsonObject(obj: String): Preset? {
        val id = readJsonStringField(obj, "id") ?: return null
        if (!isUserId(id)) return null
        val name = sanitizeName(readJsonStringField(obj, "name") ?: return null) ?: return null
        val bands = (readJsonStringField(obj, "bands") ?: return null).trim()
        if (!validBands(bands)) return null
        val bass = readJsonLongField(obj, "bass")?.toInt() ?: return null
        val virtualizer = readJsonLongField(obj, "virtualizer")?.toInt() ?: return null
        val loudness = readJsonLongField(obj, "loudness")?.toInt() ?: return null
        val reverbId = readJsonStringField(obj, "reverbId") ?: ""
        return Preset(
            id = id,
            name = name,
            bandsCsv = bands,
            bass = bass.coerceIn(0, 1000),
            virtualizer = virtualizer.coerceIn(0, 1000),
            loudness = loudness.coerceIn(0, 1500),
            reverbId = reverbId,
        )
    }

    private fun jsonQuote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    internal fun readJsonStringField(json: String, key: String): String? {
        val keyPat = "\"$key\""
        var i = 0
        while (i < json.length) {
            val at = json.indexOf(keyPat, i)
            if (at < 0) return null
            var j = at + keyPat.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != ':') {
                i = at + 1
                continue
            }
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != '"') {
                i = at + 1
                continue
            }
            return decodeJsonString(json, j)
        }
        return null
    }

    internal fun readJsonLongField(json: String, key: String): Long? {
        val keyPat = "\"$key\""
        var i = 0
        while (i < json.length) {
            val at = json.indexOf(keyPat, i)
            if (at < 0) return null
            var j = at + keyPat.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j >= json.length || json[j] != ':') {
                i = at + 1
                continue
            }
            j++
            while (j < json.length && json[j].isWhitespace()) j++
            val start = j
            if (j < json.length && json[j] == '-') j++
            while (j < json.length && json[j].isDigit()) j++
            if (start == j || (json[start] == '-' && start + 1 == j)) {
                i = at + 1
                continue
            }
            return json.substring(start, j).toLongOrNull()
        }
        return null
    }

    private fun decodeJsonString(json: String, openQuoteIndex: Int): String? {
        if (openQuoteIndex >= json.length || json[openQuoteIndex] != '"') return null
        val out = StringBuilder()
        var i = openQuoteIndex + 1
        while (i < json.length) {
            val c = json[i]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i + 1 >= json.length) return null
                    when (val n = json[i + 1]) {
                        '"', '\\', '/' -> {
                            out.append(n)
                            i += 2
                        }
                        'n' -> {
                            out.append('\n')
                            i += 2
                        }
                        'r' -> {
                            out.append('\r')
                            i += 2
                        }
                        't' -> {
                            out.append('\t')
                            i += 2
                        }
                        'u' -> {
                            if (i + 5 >= json.length) return null
                            val hex = json.substring(i + 2, i + 6)
                            val cp = hex.toIntOrNull(16) ?: return null
                            out.append(cp.toChar())
                            i += 6
                        }
                        else -> return null
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return null
    }

    internal fun readJsonArrayBody(json: String, key: String): String? {
        val keyPat = "\"$key\""
        val at = json.indexOf(keyPat)
        if (at < 0) return null
        var j = at + keyPat.length
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length || json[j] != ':') return null
        j++
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length || json[j] != '[') return null
        var depth = 0
        val start = j + 1
        var i = j
        while (i < json.length) {
            val c = json[i]
            when (c) {
                '"' -> {
                    var k = i + 1
                    var escaped = false
                    while (k < json.length) {
                        val ch = json[k]
                        if (escaped) {
                            escaped = false
                            k++
                            continue
                        }
                        when (ch) {
                            '\\' -> {
                                escaped = true
                                k++
                            }
                            '"' -> {
                                i = k
                                break
                            }
                            else -> k++
                        }
                    }
                }
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i)
                }
            }
            i++
        }
        return null
    }

    internal fun splitJsonObjects(arrayBody: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < arrayBody.length) {
            while (i < arrayBody.length && (arrayBody[i].isWhitespace() || arrayBody[i] == ',')) i++
            if (i >= arrayBody.length) break
            if (arrayBody[i] != '{') break
            var depth = 0
            val start = i
            while (i < arrayBody.length) {
                val c = arrayBody[i]
                when (c) {
                    '"' -> {
                        var k = i + 1
                        var escaped = false
                        while (k < arrayBody.length) {
                            val ch = arrayBody[k]
                            if (escaped) {
                                escaped = false
                                k++
                                continue
                            }
                            when (ch) {
                                '\\' -> {
                                    escaped = true
                                    k++
                                }
                                '"' -> {
                                    i = k
                                    break
                                }
                                else -> k++
                            }
                        }
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            out += arrayBody.substring(start, i + 1)
                            i++
                            break
                        }
                    }
                }
                i++
            }
        }
        return out
    }

}
