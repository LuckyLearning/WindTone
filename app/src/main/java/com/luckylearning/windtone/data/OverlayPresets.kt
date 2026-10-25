package com.luckylearning.windtone.data

/**
 * 悬浮歌词「样式预设」（v1.1.78，方案见 docs/设计-悬浮歌词预设化.md v2.1）。
 *
 * ## 为什么是「快照」而不是新渲染引擎
 *
 * 悬浮歌词的 21 项外观参数本就逐项存在 Prefs 里，服务每帧读 Prefs。预设只是
 * **一组调好的参数组合**：套用 = 把快照逐项写进现有 Prefs（R1 套用即写盘），
 * 不引入任何新键、新状态源（R5 零迁移）。悬浮窗下一帧就按新值渲染，
 * 无需重启服务（NFR-1）。
 *
 * ## 反推匹配（R5 的关键设计）
 *
 * 「当前预设」**不落盘**——由当前参数值反推：与某套预设的快照完全一致就显示
 * 它的名字；与出厂默认完全一致显示「默认预设」；都对不上就是「自定义」。
 * 这样预设 ID 与实际参数永远不可能分家（无双源真相问题）。
 *
 * 与 Prefs 出厂默认的关系：出厂默认（1 行 / 55% 深底 / 14dp 圆角）刻意**不是**
 * 七套预设中任何一套的快照，老用户升级后看到的悬浮窗一模一样，界面如实显示
 * 「默认预设」；点任何一套预设卡才会真正改写参数。
 *
 * ## 参数分层
 *
 * 预设快照只覆盖「外观类」参数（本文件 [OverlayPreset] 的字段）。
 * 以下内容**不属于预设**，套用不碰（FR-05）：
 *  - 主开关 `lyricOverlay`、全屏形态 `lyricOverlayFullHeight` 及其四个尺寸位置项
 *    （全屏是「结构切换」不是「皮肤」，见方案 §7.2 末尾）；
 *  - 「不显示悬浮窗的应用」名单 `lyricOverlayHiddenApps`；
 *  - 前台应用记录 / 调试解锁等诊断项。
 */
data class OverlayPreset(
    val id: String,
    val name: String,
    /** 一句话定位：速选面板与设置网格里的副标题 */
    val tagline: String,
    /** 车机专属：仅车载模式展示 / 可套用（D3）。池子 v1.1.78；车机设置三枚大卡（FR-07）已接；驾驶中简化（FR-08）已否决 */
    val carOnly: Boolean = false,
    // ---- 位置与尺寸 ----
    val lines: Int,
    val widthPercent: Int,
    val boxHeightPercent: Int,
    val xPercent: Int,
    val yPercent: Int,
    // ---- 字体 ----
    val textSp: Int,
    val align: Int,
    val textColor: Int,
    val currentColor: Int,
    val dimPercent: Int,
    val strokeWidthDp: Int,
    val strokeColor: Int,
    val lineSpacingDp: Int,
    // ---- 标题 ----
    val showTitle: Boolean,
    val titlePercent: Int,
    val titleMode: Int,
    // ---- 背景 ----
    val bgAlphaPercent: Int,
    val bgColor: Int,
    val cornerRadiusDp: Int,
    val borderWidthDp: Int,
    val borderColor: Int,
    // ---- 行为 ----
    val pauseMode: Int,
    val fadeMs: Int,
) {

    /** 展示名：车机专属预设带 🚗 标（RK-6：手机用户看到车机样式时要能一眼识别） */
    val displayName: String get() = if (carOnly) "🚗 $name" else name

    /**
     * 一行参数摘要：速选面板「差异参数卡」与设置网格卡用。
     * 挑的是各套之间**真正不同**的几项，别把 24 项全念一遍。
     */
    fun summary(): String = buildString {
        append(lines).append(if (lines <= 0) " 行（自动）" else " 行")
        append(" · ").append(textSp).append("sp")
        append(" · 底板 ").append(if (bgAlphaPercent <= 0) "无" else "$bgAlphaPercent%")
        append(" · 圆角 ").append(cornerRadiusDp).append("dp")
        if (showTitle) append(" · 标题")
        if (carOnly) append(" · 车机")
    }

    companion object {

        // ============================================================
        // 手机四套（P1~P4；数值口径见设计文档 §7.1，取色取自
        // 设置页背景色板既有档位，不引入新颜色体系）
        // ============================================================

        /** P1 极简单行：游戏 / 阅读 / 强干扰场景，最低存在感 */
        val P1 = OverlayPreset(
            id = "P1",
            name = "极简单行",
            tagline = "只留一句词，游戏 / 阅读不碍事",
            lines = 1,
            widthPercent = 60,
            boxHeightPercent = 0,
            xPercent = 50,
            yPercent = 12,
            textSp = 18,
            align = 0,
            textColor = 0xFFFFFFFF.toInt(),
            currentColor = 0,
            dimPercent = 42,
            strokeWidthDp = 0,
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 2,
            showTitle = false,
            titlePercent = 62,
            titleMode = 0,
            bgAlphaPercent = 72,
            bgColor = 0xFF101014.toInt(),
            cornerRadiusDp = 28, // 范围上限 = 胶囊全圆角
            borderWidthDp = 0,
            borderColor = 0x66FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 160,
        )

        /** P2 毛玻璃双行：桌面听歌的推荐样式（速选面板排第一位的「当前推荐」） */
        val P2 = OverlayPreset(
            id = "P2",
            name = "毛玻璃双行",
            tagline = "当前句 + 下一句，信息量刚好（推荐）",
            lines = 2,
            widthPercent = 80,
            boxHeightPercent = 0,
            xPercent = 50,
            yPercent = 12,
            textSp = 20,
            align = 0,
            textColor = 0xFFFFFFFF.toInt(),
            currentColor = 0,
            dimPercent = 42,
            strokeWidthDp = 0,
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 2,
            showTitle = true,
            titlePercent = 62,
            titleMode = 0,
            bgAlphaPercent = 68,
            bgColor = 0xFF1D2330.toInt(),
            cornerRadiusDp = 14,
            borderWidthDp = 1, // 玻璃边框：半透明白勾出边界
            borderColor = 0x66FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 160,
        )

        /**
         * P3 液态渐变·唱片：氛围感。方案 A 下不新增渲染管线——「液态渐变」
         * 用深紫底 + 淡紫当前行高亮 + 细描边表达（设计文档 §五：底板颜色 +
         * 圆角 + 边框组合实现）；封面唱片缩略不在本期（无新渲染能力）。
         */
        val P3 = OverlayPreset(
            id = "P3",
            name = "液态渐变",
            tagline = "深紫底 + 当前行高亮，氛围感拉满",
            lines = 2,
            widthPercent = 84,
            boxHeightPercent = 0,
            xPercent = 50,
            yPercent = 16,
            textSp = 20,
            align = 0,
            textColor = 0xFFFFFFFF.toInt(),
            currentColor = 0xFFC5B8FF.toInt(),
            dimPercent = 42,
            strokeWidthDp = 0,
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 3,
            showTitle = true,
            titlePercent = 62,
            titleMode = 1, // 只显示歌名，更沉浸
            bgAlphaPercent = 82,
            bgColor = 0xFF241B33.toInt(),
            cornerRadiusDp = 16,
            borderWidthDp = 1,
            borderColor = 0x33FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 200,
        )

        /** P4 经典方块：小卡片观感（D1：无任何控制按钮，纯展示） */
        val P4 = OverlayPreset(
            id = "P4",
            name = "经典方块",
            tagline = "浅色小卡片，纸面质感",
            lines = 2,
            widthPercent = 72,
            boxHeightPercent = 0,
            xPercent = 50,
            yPercent = 8,
            textSp = 18,
            align = 0,
            textColor = 0xFF15161A.toInt(), // 浅底配深字
            currentColor = 0,
            dimPercent = 55, // 浅底上压暗太狠会读不清，比默认亮一档
            strokeWidthDp = 0,
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 2,
            showTitle = true,
            titlePercent = 62,
            titleMode = 0,
            bgAlphaPercent = 92,
            bgColor = 0xFFE8E8E8.toInt(),
            cornerRadiusDp = 8, // 近乎方角
            borderWidthDp = 0,
            borderColor = 0x66FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 160,
        )

        // ============================================================
        // 车机三套（C1~C3，carOnly；D3 拍板按车机场景设计。
        // v1.1.78 已进预设池（车载模式下速选面板 / 设置网格可见可套用）。
        // FR-07 车机设置大卡已接；FR-08「驾驶中简化」已否决 / 不做。
        // ============================================================

        /** C1 导航窄条：驾驶中默认。贴顶、窄、大字、高对比，不挡导航 */
        val C1 = OverlayPreset(
            id = "C1",
            name = "导航窄条",
            tagline = "贴顶窄条大字，驾驶中不挡地图",
            carOnly = true,
            lines = 1,
            widthPercent = 64,
            boxHeightPercent = 0,
            xPercent = 62, // 居中偏右：左侧留给导航转向箭头
            yPercent = 0, // 贴顶
            textSp = 28,
            align = 0,
            textColor = 0xFFFFFFFF.toInt(),
            currentColor = 0,
            dimPercent = 42,
            strokeWidthDp = 2, // 高对比描边（1.5dp 档取整）
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 2,
            showTitle = false,
            titlePercent = 62,
            titleMode = 0,
            bgAlphaPercent = 90,
            bgColor = 0xFF101014.toInt(),
            cornerRadiusDp = 10,
            borderWidthDp = 0,
            borderColor = 0x66FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 160,
        )

        /** C2 驻停歌词：红灯 / 等人 / 充电时看。三行 + 歌名，玻璃底 */
        val C2 = OverlayPreset(
            id = "C2",
            name = "驻停歌词",
            tagline = "三行带歌名，驻停时慢慢看",
            carOnly = true,
            lines = 3,
            widthPercent = 80,
            boxHeightPercent = 0,
            xPercent = 50,
            yPercent = 40,
            textSp = 24,
            align = 0,
            textColor = 0xFFFFFFFF.toInt(),
            currentColor = 0,
            dimPercent = 42,
            strokeWidthDp = 0,
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 4, // 行距加大（设计文档 §7.2）
            showTitle = true,
            titlePercent = 62,
            titleMode = 0,
            bgAlphaPercent = 60,
            bgColor = 0xFF1D2330.toInt(),
            cornerRadiusDp = 14,
            borderWidthDp = 1,
            borderColor = 0x66FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 160,
        )

        /** C3 纯净浮层：车机桌面干净时的低存在感辅助。无底板纯文字 */
        val C3 = OverlayPreset(
            id = "C3",
            name = "纯净浮层",
            tagline = "无底板纯文字，低存在感",
            carOnly = true,
            lines = 1,
            widthPercent = 70,
            boxHeightPercent = 0,
            xPercent = 50,
            yPercent = 12,
            textSp = 20,
            align = 0,
            textColor = 0xFFFFFFFF.toInt(),
            currentColor = 0,
            dimPercent = 42,
            strokeWidthDp = 0, // 自动：无底板 → 2dp 硬描边
            strokeColor = 0xFF000000.toInt(),
            lineSpacingDp = 2,
            showTitle = false,
            titlePercent = 62,
            titleMode = 0,
            bgAlphaPercent = 0, // 无底板
            bgColor = 0xFF101014.toInt(),
            cornerRadiusDp = 0,
            borderWidthDp = 0,
            borderColor = 0x66FFFFFF.toInt(),
            pauseMode = 0,
            fadeMs = 160,
        )

        /** 全部七套：手机 4 + 车机 3（方案 G3 口径） */
        val ALL: List<OverlayPreset> = listOf(P1, P2, P3, P4, C1, C2, C3)

        /** 手机预设池（车载模式之外可见） */
        val PHONE: List<OverlayPreset> = ALL.filterNot { it.carOnly }

        /** 车机专属池（仅车载模式可见，D3） */
        val CAR: List<OverlayPreset> = ALL.filter { it.carOnly }

        /** 默认推荐（FR-12 的恢复目标；随 FR-07 同批核对「全部恢复默认」） */
        const val DEFAULT_ID = "P2"

        fun byId(id: String?): OverlayPreset? = ALL.firstOrNull { it.id == id }
    }
}

/**
 * 预设匹配用的**参数快照**：字段与 [OverlayPreset] 一一对应，与
 * `PlayerUiState` / `Prefs` 解耦——匹配与标签是纯函数，可以不开 Robolectric 直接单测。
 */
data class OverlayPresetParams(
    val lines: Int,
    val widthPercent: Int,
    val boxHeightPercent: Int,
    val xPercent: Int,
    val yPercent: Int,
    val textSp: Int,
    val align: Int,
    val textColor: Int,
    val currentColor: Int,
    val dimPercent: Int,
    val strokeWidthDp: Int,
    val strokeColor: Int,
    val lineSpacingDp: Int,
    val showTitle: Boolean,
    val titlePercent: Int,
    val titleMode: Int,
    val bgAlphaPercent: Int,
    val bgColor: Int,
    val cornerRadiusDp: Int,
    val borderWidthDp: Int,
    val borderColor: Int,
    val pauseMode: Int,
    val fadeMs: Int,
)

/** 预设 → 参数快照（套用与反推共用同一份映射，两边永远不会走样） */
fun OverlayPreset.toParams() = OverlayPresetParams(
    lines = lines,
    widthPercent = widthPercent,
    boxHeightPercent = boxHeightPercent,
    xPercent = xPercent,
    yPercent = yPercent,
    textSp = textSp,
    align = align,
    textColor = textColor,
    currentColor = currentColor,
    dimPercent = dimPercent,
    strokeWidthDp = strokeWidthDp,
    strokeColor = strokeColor,
    lineSpacingDp = lineSpacingDp,
    showTitle = showTitle,
    titlePercent = titlePercent,
    titleMode = titleMode,
    bgAlphaPercent = bgAlphaPercent,
    bgColor = bgColor,
    cornerRadiusDp = cornerRadiusDp,
    borderWidthDp = borderWidthDp,
    borderColor = borderColor,
    pauseMode = pauseMode,
    fadeMs = fadeMs,
)

/**
 * Prefs 出厂默认值的参数快照。
 *
 * 老用户从没动过设置时参数恰好是这一组 → 显示「默认预设」（FR-01 GWT 口径），
 * 而不是误报成某套预设或「自定义」。
 */
val OVERLAY_FACTORY_PARAMS = OverlayPresetParams(
    lines = SettingsDefaults.OVERLAY_LINES, // 1
    widthPercent = SettingsDefaults.OVERLAY_WIDTH_PERCENT, // 80
    boxHeightPercent = SettingsDefaults.OVERLAY_BOX_HEIGHT_PERCENT, // 0（自动）
    xPercent = SettingsDefaults.OVERLAY_X_PERCENT, // 50
    yPercent = SettingsDefaults.OVERLAY_Y_PERCENT, // 12
    textSp = SettingsDefaults.OVERLAY_TEXT_SP, // 20
    align = SettingsDefaults.OVERLAY_ALIGN, // 0 居中
    textColor = SettingsDefaults.OVERLAY_TEXT_COLOR,
    currentColor = SettingsDefaults.OVERLAY_CURRENT_COLOR, // 0 = 跟随文字色
    dimPercent = SettingsDefaults.OVERLAY_DIM_PERCENT, // 42
    strokeWidthDp = SettingsDefaults.OVERLAY_STROKE_WIDTH_DP, // 0 = 自动
    strokeColor = SettingsDefaults.OVERLAY_STROKE_COLOR,
    lineSpacingDp = SettingsDefaults.OVERLAY_LINE_SPACING_DP, // 2
    showTitle = SettingsDefaults.OVERLAY_SHOW_TITLE, // false
    titlePercent = SettingsDefaults.OVERLAY_TITLE_PERCENT, // 62
    titleMode = SettingsDefaults.OVERLAY_TITLE_MODE, // 0
    bgAlphaPercent = SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT, // 55
    bgColor = SettingsDefaults.OVERLAY_BG_COLOR,
    cornerRadiusDp = SettingsDefaults.OVERLAY_CORNER_RADIUS_DP, // 14
    borderWidthDp = SettingsDefaults.OVERLAY_BORDER_WIDTH_DP, // 0
    borderColor = SettingsDefaults.OVERLAY_BORDER_COLOR,
    pauseMode = SettingsDefaults.OVERLAY_PAUSE_MODE, // 0 淡出
    fadeMs = SettingsDefaults.OVERLAY_FADE_MS, // 160
)

/** 反推当前预设：参数与某套快照完全一致即命中（R5；匹配不到返回 null） */
fun currentOverlayPreset(params: OverlayPresetParams): OverlayPreset? =
    OverlayPreset.ALL.firstOrNull { it.toParams() == params }

/**
 * 当前参数的**展示名**：
 *  - 命中预设 → 预设名（车机套带 🚗）；
 *  - 与出厂默认完全一致 → 「默认预设」（FR-01：未配过的用户不该看到「自定义」）；
 *  - 其余 → 「自定义」（RK-4：深度调参后的预期结果，配合「恢复预设原值」闭环）。
 */
fun overlayPresetLabel(params: OverlayPresetParams): String =
    currentOverlayPreset(params)?.displayName
        ?: if (params == OVERLAY_FACTORY_PARAMS) "默认预设" else "自定义"

/**
 * 进程内记住「最近套用的预设」（R2 已修改 / 恢复原值用）。
 *
 * 刻意**不落盘**（R5 零新键）：反推匹配已经能回答「当前是哪套 / 是不是自定义」；
 * 这里只补一件事——参数被改乱后用户想一键回到上一套预设时，得知道「上一套」是哪套。
 * 进程被杀后记忆消失，届时网格上每张卡仍可重新套用，不构成功能缺口。
 */
object OverlayPresetTracker {
    @Volatile
    var lastAppliedId: String? = null
}
