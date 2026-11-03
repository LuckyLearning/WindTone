package com.luckylearning.windtone.ui.theme

/**
 * 一体化形态判据（UI v2 · 2026-11-03）。
 *
 * ## 为什么要这一层
 *
 * 重构前「用哪套 UI」由**三套互不相干的机制**同时决定，谁也不服谁：
 *  · `carMode`（用户在设置里手拨的布尔偏好）→ 决定主界面 / 播放页 / 设置页 / 列表 / 歌词 /
 *    音效 / 悬浮层的全部形态分支；
 *  · `screenWidthDp >= 840`（真实配置）→ 决定 Dock 让位 56dp 与 4 个全屏子页的 900dp 限宽；
 *  · `isCarHeadUnit`（UiModeManager / FEATURE_AUTOMOTIVE / BYD 品牌兜底）→ 只影响系统设置页
 *    跳转与诊断文案，与上面两者完全无关。
 *
 * 后果是「设备是车机 ≠ 用车载 UI」「车机模式开着 ≠ 所有页面都适配」「手机横屏也会白让
 * 56dp」。本层把三者收敛成**一个** [UiFormFactor]，全 App 只读这一个结果。
 *
 * ## 判据优先级（有意如此）
 *
 * 1. 用户显式拨了「车载模式」→ CAR（显式意图最高，也保持既有行为：手机上拨开关仍进车机版）；
 * 2. 设备本身被识别为车机 → CAR（修复「DiLink 上没拨开关却按手机竖屏渲染」的割裂）；
 * 3. 宽屏且横屏 → TABLET（大屏平板 / 折叠屏展开态）；
 * 4. 其余 → PHONE。
 *
 * `carHardware` 由调用方注入（`ForegroundAppDetector.isCarHeadUnit(context)`），本层刻意不依赖
 * Context / Android 框架，保证 [FormFactor.resolve] 是可在 JVM 单测里断言的纯函数。
 */
enum class UiFormFactor {
    PHONE,
    CAR,
    TABLET;

    val isCar: Boolean get() = this == CAR

    /** 宽形态：有常驻侧导航、内容限宽、更大的触控目标。 */
    val isLarge: Boolean get() = this == CAR || this == TABLET

    /** 是否用「常驻侧栏」而非「抽屉」承载一级导航。 */
    val hasSideNav: Boolean get() = isLarge

    /** 是否需要可见焦点环（方向盘 / 中控旋钮没有触摸点）。 */
    val needsFocusRing: Boolean get() = this == CAR
}

object FormFactor {

    /** 宽屏阈值：沿用重构前 MainScreen 的 LARGE_SCREEN_WIDTH_DP（840dp）。 */
    const val WIDE_SCREEN_DP = 840f

    /**
     * 统一判定入口。
     *
     * @param widthDp  当前配置宽度（dp）
     * @param heightDp 当前配置高度（dp）
     * @param carMode  用户是否显式开启「车载模式」
     * @param carHardware 设备是否被识别为车机（由调用方注入，避免 theme 层依赖 Android）
     * @param landscape 是否横屏；默认按短边判断，可由调用方覆盖
     */
    fun resolve(
        widthDp: Float,
        heightDp: Float,
        carMode: Boolean,
        carHardware: Boolean,
        landscape: Boolean = heightDp < widthDp,
    ): UiFormFactor = when {
        carMode || carHardware -> UiFormFactor.CAR
        landscape && widthDp >= WIDE_SCREEN_DP -> UiFormFactor.TABLET
        else -> UiFormFactor.PHONE
    }
}
