package com.luckylearning.windtone.playback

/**
 * 内置均衡器预设曲线。
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
 *
 * 2026-10-17 两组合计 **16 个**（听感 8 / 流派 8）。这一版补齐了 v1.1.5 公告里
 * 承诺过、但当时**只写进文案没写进代码**的「温暖 / 明亮 / 通透」与「电子」，
 * 并把听感组按「低频——中频——高频——整体」铺满，避免挑不出想要的那一条。
 */
object EqPresets {

    /**
     * 预设分组。
     *
     * @param label 分组 tab 上的短名
     * @param hint 分组下方的一句说明，回答「这两组到底差在哪」
     */
    enum class Group(val label: String, val hint: String) {
        FEEL("听感", "按听感命名，不必懂流派"),
        GENRE("流派", "按曲风命名，照顾听歌习惯"),
    }

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

    /**
     * 锚点频率轴：**五段机的频段中心频率正好就是这五个点**，所以五段机上插值不产生误差、
     * 曲线精确落在锚点上（`EqPresetsTest` 里有断言钉着这条）。
     */
    private fun anchors(a: Int, b: Int, c: Int, d: Int, e: Int) = listOf(
        60 to a, 230 to b, 910 to c, 3600 to d, 14000 to e,
    )

    // ---------------- 听感组（低频 → 中频 → 高频 → 整体） ----------------

    /** 全平基准。单独成预设而不是「无」——用户要的是「一键回到原汁原味」，仍是选择。 */
    val FLAT = Preset(
        "flat", "原声", "所有频段不做修饰，原汁原味", Group.FEEL,
        anchors(0, 0, 0, 0, 0),
    )
    val BASS_BOOST = Preset(
        "bass_boost", "低音增强", "显著增强低频，更有冲击感", Group.FEEL,
        anchors(550, 350, 50, -100, -150),
    )
    val TREBLE_BOOST = Preset(
        "treble_boost", "高音增强", "提升高频，歌声与细节更明亮", Group.FEEL,
        anchors(-150, -100, 0, 400, 600),
    )
    val CLEAR = Preset(
        "clear", "人声清晰", "强调中频，人声播客更易听清", Group.FEEL,
        anchors(-200, -100, 350, 400, 100),
    )
    val DYNAMIC = Preset(
        "dynamic", "动感", "高低频同时增强，声音鲜活有力", Group.FEEL,
        anchors(400, 200, -100, 300, 450),
    )
    val WARM = Preset(
        "warm", "温暖", "中低频饱满、高频收敛，久听不累", Group.FEEL,
        anchors(250, 300, 100, -100, -250),
    )
    val BRIGHT = Preset(
        "bright", "明亮", "抬高中高频、淡化低频，画面更开阔", Group.FEEL,
        anchors(-150, -100, 250, 400, 300),
    )
    val TRANSPARENT = Preset(
        "transparent", "通透", "几乎不动低频，只补极高频的空气感", Group.FEEL,
        anchors(-150, 0, 50, 300, 650),
    )

    // ---------------- 流派组 ----------------

    val POP = Preset(
        "pop", "流行", "人声突出，明快耐听", Group.GENRE,
        anchors(-100, 250, 350, 150, -100),
    )
    val ROCK = Preset(
        "rock", "摇滚", "低频强劲，节奏感强", Group.GENRE,
        anchors(500, 300, -150, 350, 450),
    )
    val CLASSIC = Preset(
        "classic", "古典", "强化泛音与声场，适合大编制", Group.GENRE,
        anchors(300, 150, -100, 200, 350),
    )
    val JAZZ = Preset(
        "jazz", "爵士", "强调临场感，管乐与人声温暖", Group.GENRE,
        anchors(350, 200, -100, 250, 300),
    )
    val ELECTRONIC = Preset(
        "electronic", "电子", "中频收窄，突出合成器的低频与亮度", Group.GENRE,
        anchors(450, 120, -200, 120, 650),
    )
    val FOLK = Preset(
        "folk", "民谣", "轻柔的中低频，木吉他与吟唱更近", Group.GENRE,
        anchors(150, 250, 200, 50, 0),
    )
    val HIPHOP = Preset(
        "hiphop", "嘻哈", "极低频最重，鼓点与人声分得开", Group.GENRE,
        anchors(650, 200, -100, 250, -150),
    )
    val DANCE = Preset(
        "dance", "舞曲", "低频冲击 + 极高频空气感，节奏更跳", Group.GENRE,
        anchors(550, 300, -250, 100, 500),
    )

    /** 界面固定展示顺序：组内按「低频 → 中频 → 高频 → 整体」排列，听感组在前 */
    val ALL: List<Preset> = listOf(
        FLAT, BASS_BOOST, TREBLE_BOOST, CLEAR, DYNAMIC, WARM, BRIGHT, TRANSPARENT,
        POP, ROCK, CLASSIC, JAZZ, ELECTRONIC, FOLK, HIPHOP, DANCE,
    )

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

    /**
     * 曲线预览用的归一化高度序列（0..1，0 = 该设备量程的最低增益、1 = 最高）。
     *
     * 只给界面上的那根折线用：以设备的 `bandLevelRange` 为满量程换算，
     * 因此**形状可信、绝对高度不代表真实增益**（真实数值看频段滑块右侧的 dB 标签）。
     * 之所以不平移到「整条曲线最小值为 0」：那样全平曲线的形状会随设备的量程漂移，
     * 而且「原声」这一条的预览会从一条平线变成贴底的平线。
     */
    fun previewHeights(levels: IntArray, minMb: Int = -1500, maxMb: Int = 1500): List<Float> {
        if (levels.isEmpty()) return emptyList()
        val span = (maxMb - minMb).coerceAtLeast(1)
        return levels.map { ((it - minMb).toFloat() / span).coerceIn(0f, 1f) }
    }
}
