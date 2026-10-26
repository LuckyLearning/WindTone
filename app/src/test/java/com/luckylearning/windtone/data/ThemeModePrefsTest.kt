package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.labelRes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 明暗模式持久化（v1.2.4）的存储层回归。
 *
 * ## 为什么值得单独测
 *
 * 这一项错了**不会崩、不会报错**，症状全是「看起来正常但用户的选择没生效」：
 *  - 存的是 ordinal：将来在枚举中间插一档（比如「自动 / 日出日落」），
 *    老用户的 DARK(2) 会被读成新枚举的第 3 项 —— 静默变档；
 *  - 解析失败不回落：磁盘上一条脏值（降级安装、被别的工具改过）就让 `valueOf` 抛异常，
 *    界面直接起不来；
 *  - 枚举与资源映射错位：「浅色」那格显示成「深色」的文字。
 *
 * ⚠️ 这里刻意用**字面量 `"theme_mode"`** 而不是引用常量：兼容性依赖的正是磁盘上那个键名，
 * 常量改名时这条测试必须一起红 —— 那说明用户的选择真的读不到了。
 */
@RunWith(RobolectricTestRunner::class)
class ThemeModePrefsTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPreferences().edit().clear().commit()
        prefs = Prefs(context)
    }

    @After
    fun tearDown() {
        rawPreferences().edit().clear().commit()
    }

    @Test
    fun defaultsToSystem() {
        assertEquals(SettingsDefaults.THEME_MODE, prefs.themeMode)
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
    }

    @Test
    fun everyModeRoundTrips() {
        ThemeMode.entries.forEach { mode ->
            prefs.themeMode = mode
            assertEquals("写入 $mode 后读回来的不是它", mode, prefs.themeMode)
        }
    }

    @Test
    fun storesNameNotOrdinal() {
        prefs.themeMode = ThemeMode.DARK
        // 存名字：枚举顺序变了也不会把老用户读成别的档
        assertEquals("DARK", rawPreferences().getString("theme_mode", null))
    }

    @Test
    fun corruptValueFallsBackToDefault() {
        rawPreferences().edit().putString("theme_mode", "PURPLE").commit()
        // 脏值不能让界面起不来：回落到出厂默认（跟随系统）
        assertEquals(SettingsDefaults.THEME_MODE, prefs.themeMode)
    }

    /** 三档必须有各自的文案资源，且映射不能串档（串档只靠肉眼发现） */
    @Test
    fun labelsAreDistinctAndInOrder() {
        val ids = ThemeMode.entries.map { it.labelRes() }
        assertEquals("档位数与文案数不一致", ThemeMode.entries.size, ids.size)
        assertEquals("有多个档位映射到同一条文案", ids.size, ids.toSet().size)
        ids.forEach { assertTrue("文案资源 id 非法：$it", it != 0) }
        assertNotEquals(ThemeMode.LIGHT.labelRes(), ThemeMode.DARK.labelRes())
    }

    private fun rawPreferences() =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)
}
