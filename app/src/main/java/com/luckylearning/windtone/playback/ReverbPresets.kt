package com.luckylearning.windtone.playback

/**
 * 内置**混响**音效（2026-10-17 新增于「均衡器与音效」页）。
 *
 * 数据为什么单独成表、而不是直接用平台常量：
 *  - 平台常量只有 `PRESET_SMALLROOM` 这类英文名，无法本地化、无法加一句描述；
 *  - 界面需要的是一份**稳定 id + 中文名 + 一句描述**的清单，与 [EqPresets] 同一套做法。
 *
 * 与均衡器预设的**关键差别**：[EqPresets] 是自研曲线（对设备频段插值），
 * 而混响是**平台效果器**给死的几档（`PresetReverb`），设备不支持时整块降级，
 * 因此这里只做「id → 平台档位」的映射，不自研任何混响参数。
 *
 * `platform` 就是 `android.media.audiofx.PresetReverb` 的档位常量，取值与 AOSP 实现一致：
 * `PRESET_NONE=0 / PRESET_SMALLROOM=1 / PRESET_MEDIUMROOM=2 / PRESET_LARGEROOM=3 /
 * PRESET_MEDIUMHALL=4 / PRESET_LARGEHALL=5 / PRESET_PLATE=6`。
 * 这里写常量值而不是 import 平台类：本对象保持**纯 Kotlin 数据**，
 * 才能在普通 JVM 单测里直接断言（对照 [EqPresets] 的测试）。
 */
object ReverbPresets {

    /**
     * @param id 持久化到 Prefs 的稳定标识（勿改，改了用户的已选混响会丢失）
     * @param desc 一句话描述，用户看描述就能做决定
     * @param platform `PresetReverb` 的档位常量值
     */
    data class Preset(val id: String, val name: String, val desc: String, val platform: Short)

    /** 关闭：`PRESET_NONE`。界面上第一项，等价于「不加混响」。 */
    val OFF = Preset("off", "关闭", "干声原样播放，不加混响", 0)
    val SMALL_ROOM = Preset("room_small", "小房间", "短促的房间反射，人声更贴耳", 1)
    val MEDIUM_ROOM = Preset("room_medium", "中房间", "明显的房间感，流行与人声都合适", 2)
    val LARGE_ROOM = Preset("room_large", "大房间", "开阔的早期反射，声场变大", 3)
    val MEDIUM_HALL = Preset("hall_medium", "中厅", "厅堂混响，人声有余韵", 4)
    val LARGE_HALL = Preset("hall_large", "大厅", "长混响尾巴，适合古典与现场录音", 5)
    val PLATE = Preset("plate", "板式", "板混响的密集尾音，人声更平滑", 6)

    /** 界面固定展示顺序：先「关闭」，再从最干的房间到最湿的厅堂 */
    val ALL: List<Preset> = listOf(
        OFF, SMALL_ROOM, MEDIUM_ROOM, LARGE_ROOM, MEDIUM_HALL, LARGE_HALL, PLATE,
    )

    /** 与 [EqPresets.byId] 同一口径：未知 id 返回 null，空串不是合法 id */
    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /**
     * 已选混响是否算「动过」——只在没选关闭时才算偏离出厂。
     *
     * 与均衡器那一侧同一条口径：出厂默认是「什么都没选过」，即 `off`。
     */
    fun isCustomized(preset: Preset?): Boolean = preset != null && preset != OFF
}
