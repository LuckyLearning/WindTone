package com.anylyric.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/**
 * 设备形态判定。
 *
 * 只用来选**跳转路径**，不用来限制功能：同一份 APK 在手机与车机上都能跑，
 * 区别只在系统有没有把某个设置页开放给第三方应用。判错了最坏结果也只是
 * 走了一条打不开的路径 —— 每条路径后面都有兜底。
 */
object Devices {

    /**
     * 是不是车机。
     *
     * 三个条件任一成立即可，因为车厂的实现各写各的：
     *  · `uiMode` = CAR —— 原生 Automotive OS；
     *  · `FEATURE_AUTOMOTIVE` —— 声明了车载特性但 uiMode 未必是 CAR；
     *  · 品牌 / 型号带 BYD、DiLink —— DiLink 跑的是手机版 Android 改的，
     *    前两条都不一定成立（真机实测：uiMode 是 NORMAL）。
     */
    fun isCarHeadUnit(context: Context): Boolean = looksLikeCar(
        uiMode = context.resources.configuration.uiMode,
        hasAutomotive = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        }.getOrDefault(false),
        brand = Build.BRAND,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL
    )

    /** 判定逻辑本体，纯函数，单测直接喂字符串。 */
    fun looksLikeCar(
        uiMode: Int,
        hasAutomotive: Boolean,
        brand: String?,
        manufacturer: String?,
        model: String?
    ): Boolean {
        if ((uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR) {
            return true
        }
        if (hasAutomotive) return true
        return listOf(brand, manufacturer, model).any { it.looksLikeByd() }
    }

    private fun String?.looksLikeByd(): Boolean =
        this != null && (contains("byd", ignoreCase = true) || contains("dilink", ignoreCase = true))
}
