package com.luckylearning.windtone.playback

/**
 * 音效值的**进车快照**（v1.1.52，配合车载音效档案）。
 *
 * ## 语义
 * 进入车载模式换方案前，把当前全部音效值编码存进 `Prefs.soundStash`；
 * 退出车载模式时解码还原。这样「车载模式里调的声音只在车载模式生效」，
 * 手机模式的声音不会被车载方案覆盖 —— 两套听感各管各的，互不串味。
 *
 * ## 为什么是自研文本编码而不是 JSON
 * 内容只有 7 个标量（布尔 / id / CSV 数字串），没有嵌套结构；
 * 一行 `split('|')` 比引入（或再走一遍）JSON 解析更直白，且**纯 JVM 可测**。
 * 字段里的 `presetId` / `reverbId` 是受控 id（字母数字下划线），频段 CSV
 * 只有数字与逗号，都不会出现分隔符，无需转义。
 *
 * 编码格式（v1）：`enabled|presetId|bandsCsv|bass|virtual|loudness|reverbId`
 */
object SoundStash {

    private const val SEP = "|"
    private const val FIELD_COUNT = 7

    /** 当前音效值 → 快照字符串。`bands` 为空串表示「跟随预设曲线，无自定义频段」。 */
    fun encode(
        enabled: Boolean,
        presetId: String,
        bandsCsv: String,
        bass: Int,
        virtual: Int,
        loudness: Int,
        reverbId: String,
    ): String = listOf(
        if (enabled) "1" else "0",
        presetId,
        bandsCsv,
        bass.toString(),
        virtual.toString(),
        loudness.toString(),
        reverbId,
    ).joinToString(SEP)

    /** 快照 → 可直接写回 Prefs 的一组值；格式不对返回 null（调用方按「无快照」处理） */
    fun decode(stash: String): Snapshot? {
        val f = stash.split(SEP)
        if (f.size != FIELD_COUNT) return null
        val enabled = when (f[0]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        val bass = f[3].toIntOrNull() ?: return null
        val virtual = f[4].toIntOrNull() ?: return null
        val loudness = f[5].toIntOrNull() ?: return null
        return Snapshot(
            enabled = enabled,
            presetId = f[1],
            bandsCsv = f[2],
            bass = bass,
            virtual = virtual,
            loudness = loudness,
            reverbId = f[6],
        )
    }

    /** 解码出来的一组音效值，字段与 [encode] 入参一一对应 */
    data class Snapshot(
        val enabled: Boolean,
        val presetId: String,
        val bandsCsv: String,
        val bass: Int,
        val virtual: Int,
        val loudness: Int,
        val reverbId: String,
    )
}
