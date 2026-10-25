package com.luckylearning.windtone.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 安装包签名自检（v1.1.97）。
 *
 * 这里只能覆盖"官方 / debug 不误伤"这一侧 —— 真正的防改包效果要在签名不同的 APK 上验，
 * 而单测跑的是 debug 包（`BuildConfig.DEBUG = true` 时恒为官方，这是刻意的：
 * 开发与自编译不该被自己的校验挡住）。
 */
@RunWith(RobolectricTestRunner::class)
class AppIntegrityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `debug 构建恒判为官方`() {
        AppIntegrity.evaluate(context)
        assertTrue("debug 包不应被签名校验挡住", AppIntegrity.isOfficial)
    }

    @Test
    fun `官方包的描述文本直白`() {
        AppIntegrity.evaluate(context)
        assertEquals("官方签名", AppIntegrity.describe())
    }
}
