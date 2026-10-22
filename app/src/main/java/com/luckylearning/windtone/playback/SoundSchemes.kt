package com.luckylearning.windtone.playback

/**
 * 内置**音效方案**（v1.1.52，参考主流音乐 App 的「主题音效」形态）。
 *
 * 与 [EqPresets] 的差别：预设只描述**均衡器曲线**一件事；方案是
 * 「曲线 + 低音 + 环绕 + 响度 + 混响」**一揽子听感**的一键打包 ——
 * 用户选的是「想要什么感觉」，不是五个参数。应用方案后每个参数仍可
 * 单独微调（微调即离开方案，与预设的「拖动即自定义」同一口径）。
 *
 * 设计约束（都对应既有红线，别改）：
 *  - **纯 Kotlin 数据**：不碰任何 Android 类，普通 JVM 单测直接断言
 *    （`SoundSchemesTest` 守 id 唯一性与展开映射合法性）；
 *  - `id` 持久化到 Prefs，**发布后不可改**（改了用户已选方案会丢失）；
 *  - 引用的 `eqPresetId` / `reverbId` 必须是 [EqPresets] / [ReverbPresets]
 *    里真实存在的 id，由单测对质；
 *  - 不存「均衡器频段数组」：应用方案时只写 `eqPresetId`，绑定效果器时
 *    `AudioFx` 会按设备实际频段重新插值（与手选预设同一条路）。
 *
 * 「车载驾驶」是为车机场景预备的一条：行车噪声会盖掉两端频段，
 * 曲线取流行（中频保人声）+ 适度低音/响度补偿，不加混响（车内空间本身就是混响）。
 */
object SoundSchemes {

    /**
     * @param id 持久化到 Prefs 的稳定标识（勿改）
     * @param name 界面显示名（中文直写，与 [EqPresets.Preset.name] 同一口径）
     * @param desc 一句话描述：这条方案动了哪些地方、适合什么
     * @param eqPresetId 应用哪条均衡器曲线（[EqPresets] 的 id）
     * @param bass 低音增强强度（0..1000，与 `Prefs.eqBassBoost` 同单位）
     * @param virtual 环绕声强度（0..1000）
     * @param loudness 响度增强目标增益（0..1500 mB）
     * @param reverbId 混响档位（[ReverbPresets] 的 id；「off」= 不加）
     * @param forCar 是否为车载场景预备（界面上打「车载」标；手机模式也可见可选）
     */
    data class Scheme(
        val id: String,
        val name: String,
        val desc: String,
        val eqPresetId: String,
        val bass: Int,
        val virtual: Int,
        val loudness: Int,
        val reverbId: String,
        val forCar: Boolean = false,
    )

    /** 全平 + 全关。单独成方案：用户要的是「一键回到原汁原味」，仍是一次选择。 */
    val OFF = Scheme(
        "off", "原声", "全部音效归零，原汁原味",
        EqPresets.FLAT.id, 0, 0, 0, ReverbPresets.OFF.id,
    )
    val BASS = Scheme(
        "bass", "重低音", "低频冲击拉满，电音摇滚更带感",
        EqPresets.BASS_BOOST.id, 700, 150, 0, ReverbPresets.OFF.id,
    )
    val VOCAL = Scheme(
        "vocal", "清澈人声", "中频突出，人声播客更易听清",
        EqPresets.CLEAR.id, 0, 0, 0, ReverbPresets.OFF.id,
    )
    val LIVE = Scheme(
        "live", "现场临感", "适度厅堂混响 + 环绕，像坐在前排",
        EqPresets.DYNAMIC.id, 250, 450, 0, ReverbPresets.MEDIUM_HALL.id,
    )
    val SPACE = Scheme(
        "space", "沉浸环绕", "强环绕 + 板式混响，耳机里的空间感",
        EqPresets.WARM.id, 100, 700, 0, ReverbPresets.PLATE.id,
    )
    val HALL = Scheme(
        "hall", "音乐厅", "古典编制铺开，混响尾巴更长",
        EqPresets.CLASSIC.id, 150, 250, 0, ReverbPresets.LARGE_HALL.id,
    )
    val PARTY = Scheme(
        "party", "派对电音", "低频与极高频同时抬，节奏更跳",
        EqPresets.DANCE.id, 550, 350, 0, ReverbPresets.OFF.id,
    )
    val CAR_DRIVE = Scheme(
        "car_drive", "车载驾驶", "行车噪声下的补偿：人声与低音都提一档",
        EqPresets.POP.id, 350, 100, 300, ReverbPresets.OFF.id,
        forCar = true,
    )

    /** 界面固定展示顺序：原声在最前，车载方案放最后（带标，不混进听感组里） */
    val ALL: List<Scheme> = listOf(
        OFF, BASS, VOCAL, LIVE, SPACE, HALL, PARTY, CAR_DRIVE,
    )

    /** 与 [EqPresets.byId] 同一口径：未知 id / 空串返回 null */
    fun byId(id: String): Scheme? = ALL.firstOrNull { it.id == id }
}
