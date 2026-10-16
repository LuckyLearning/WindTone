package com.luckylearning.windtone.playback

/**
 * 内置均衡器预设曲线（v1.1.5）。
 *
 * 为什么不用平台预设（`Equalizer.usePreset`）：
 *  - 预设名是英文且**各 ROM 不一致**（同样的第 0 个，有的设备叫 Normal、有的直接是 Rock）；
 *  - 无法本地化、无法加一句描述、无法分组。
 *
 * 所以改为**自己定义曲线**：每个预设给一组「锚点频率 → 增益」，在
 * log(频率) 轴上对设备实际的频段中心频率做线性插值 —— 5 段 / 6 段 / 10 段
 * 机器都能落到合理的曲线上；超出锚点范围的频率取端点值（不外推）。
 *
 * 预设分两组并行（参考三星 One UI 6.1.1 的做法）：
 *  - [Group.FEEL] 按听感命名（新手友好，不需要懂流派也能选对）；
 *  - [Group.GENRE] 按流派命名（照顾老用户习惯）。
 */
object EqPresets {

    enum class Group(val label: String) { FEEL("听感"), GENRE("流派") }

    /**
     * @param id 持久化到 Prefs 的稳定标识（勿改，改了用户的已选预设会丢失）
     * @param name 界面显示名（中文，直接写死不用资源——与「均衡器与音效」同层级的弹层文案口径）
     * @param desc 一句话描述，用户看描述就能做决定，不必懂频段
     * @param anchors 锚点 [频率Hz, 增益mB]，按频率升序；增益范围 ±1500
     */
    data class Preset(
        val id: String,
        val name: String,
        val desc: String,
        val group: Group,
        val anchors: List<Pair<Int, Int>>,
    )

    // ---------------- 听感组 ----------------

    /** 全平基准。单独成预设而不是「无」——用户要的是「一键回到原汁原味」，仍是选择。 */
    val FLAT = Preset(
        "flat", "原声", "所有频段不做修饰，原汁原味", Group.FEEL,
        listOf(60 to 0, 230 to 0, 910 to 0, 3600 to 0, 14000 to 0),
    )
    val BASS_BOOST = Preset(
        "bass_boost", "低音增强", "显著增强低频，更有冲击感", Group.FEEL,
        listOf(60 to 550, 230 to 350, 910 to 50, 3600 to -100, 14000 to -150),
    )
    val TREBLE_BOOST = Preset(
        "treble_boost", "高音增强", "提升高频，歌声与细节更明亮", Group.FEEL,
        listOf(60 to -150, 230 to -100, 910 to 0, 3600 to 400, 14000 to 600),
    )
    val CLEAR = Preset(
        "clear", "人声清晰", "强调中频，人声播客更易听清", Group.FEEL,
        listOf(60 to -200, 230 to -100, 910 to 350, 3600 to 400, 14000 to 100),
    )
    val DYNAMIC = Preset(
        "dynamic", "动感", "高低频同时增强，声音鲜活有力", Group.FEEL,
        listOf(60 to 400, 230 to 200, 910 to -100, 3600 to 300, 14000 to 450),
    )

    // ---------------- 流派组 ----------------

    val POP = Preset(
        "pop", "流行", "人声突出，明快耐听", Group.GENRE,
        listOf(60 to -100, 230 to 250, 910 to 350, 3600 to 150, 14000 to -100),
    )
    val ROCK = Preset(
        "rock", "摇滚", "低频强劲，节奏感强", Group.GENRE,
        listOf(60 to 500, 230 to 300, 910 to -150, 3600 to 350, 14000 to 450),
    )
    val CLASSIC = Preset(
        "classic", "古典", "强化泛音与声场，适合大编制", Group.GENRE,
        listOf(60 to 300, 230 to 150, 910 to -100, 3600 to 200, 14000 to 350),
    )
    val JAZZ = Preset(
        "jazz", "爵士", "强调临场感，管乐与人声温暖", Group.GENRE,
        listOf(60 to 350, 230 to 200, 910 to -100, 3600 to 250, 14000 to 300),
    )

    /** 界面固定展示顺序：听感在前，流派在后 */
    val ALL: List<Preset> = listOf(FLAT, BASS_BOOST, TREBLE_BOOST, CLEAR, DYNAMIC, POP, ROCK, CLASSIC, JAZZ)

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    fun group(group: Group): List<Preset> = ALL.filter { it.group == group }

    /**
     * 把预设曲线落到设备的实际频段上。
     *
     * @param centerHz 设备各频段的中心频率（Hz，`Equalizer.getCenterFreq` / 100）
     * @return 每个频段的增益（mB），已夹取到 ±1500
     */
    fun curveFor(preset: Preset, centerHz: List<Int>): IntArray = IntArray(centerHz.size) { i ->
        gainAt(preset, centerHz[i])
    }

    /** 单频点增益：log 频率轴上线性插值，越界取端点 */
    fun gainAt(preset: Preset, freqHz: Int): Int {
        val anchors = preset.anchors
        if (freqHz <= anchors.first().first) return anchors.first().second
        if (freqHz >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (f0, g0) = anchors[i]
            val (f1, g1) = anchors[i + 1]
            if (freqHz in f0..f1) {
                // 在 log10 频率轴上插值：人耳对频率是对数感知，线性插值会让
                // 低频段的曲线形状随设备分段方式漂移
                val t = (Math.log10(freqHz.toDouble()) - Math.log10(f0.toDouble())) /
                    (Math.log10(f1.toDouble()) - Math.log10(f0.toDouble()))
                return (g0 + (g1 - g0) * t).toInt().coerceIn(-1500, 1500)
            }
        }
        return anchors.last().second
    }
}
