package com.anylyric.data

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机判定。
 *
 * 这条判定只影响「点按钮时先跳哪个设置页」，所以宁可多认（车机当手机 → 跳一条
 * 打不开的路，后面还有兜底），也别漏认（手机当车机 → 把手机用户带去车厂页面）。
 * 真机参照：DiLink5 的 uiMode 是 NORMAL，只有品牌字段能认出来。
 */
class DevicesTest {

    private val normal = Configuration.UI_MODE_TYPE_NORMAL
    private val car = Configuration.UI_MODE_TYPE_CAR

    @Test
    fun uiModeCarIsCar() {
        assertTrue(Devices.looksLikeCar(car, false, "Xiaomi", "Xiaomi", "Mi 11"))
    }

    @Test
    fun automotiveFeatureIsCarEvenWithNormalUiMode() {
        assertTrue(Devices.looksLikeCar(normal, true, "Google", "Google", "sdk_gphone"))
    }

    @Test
    fun dilinkBrandIsCarDespiteNormalUiMode() {
        // 真机报告：device=BYD AUTO/DiLink5.0 For BYD AUTO brand=BYD-AUTO
        assertTrue(
            Devices.looksLikeCar(
                normal,
                false,
                "BYD-AUTO",
                "BYD AUTO",
                "DiLink5.0 For BYD AUTO"
            )
        )
    }

    @Test
    fun plainPhoneIsNotCar() {
        assertFalse(Devices.looksLikeCar(normal, false, "samsung", "samsung", "SM-G9910"))
    }

    @Test
    fun nullBrandFieldsDoNotCrash() {
        assertFalse(Devices.looksLikeCar(normal, false, null, null, null))
    }

    @Test
    fun onlyModelMentionsByd() {
        assertTrue(Devices.looksLikeCar(normal, false, "unknown", "unknown", "DiLink 4.0"))
    }
}
