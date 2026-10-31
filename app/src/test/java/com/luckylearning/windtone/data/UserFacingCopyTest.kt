package com.luckylearning.windtone.data

import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.ui.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A4：原因 + 下一步 拼接规则（i18n 第 21 批后走 ufc_*）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class UserFacingCopyTest {

    @Before
    fun setUp() {
        AppStrings.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `withNext 拼接原因与路径`() {
        assertEquals(
            "原因。下一步：去做这件事",
            UserFacingCopy.withNext("原因", "去做这件事"),
        )
    }

    @Test
    fun `withNext 已含下一步则不重复`() {
        val already = "直链过期。下一步：重新授权云盘"
        assertEquals(already, UserFacingCopy.withNext(already, "再写一句"))
    }

    @Test
    fun `ensureNext 缺路径时补上`() {
        val msg = UserFacingCopy.ensureNext("扫描失败", "检查权限后重试，或改导入指定目录")
        assertTrue(msg.contains("扫描失败"))
        assertTrue(msg.contains("下一步"))
        assertTrue(msg.contains("指定目录") || msg.contains("权限"))
    }

    @Test
    fun `ensureNext 已有路径保持原样`() {
        val already = "HTTP 403。下一步：重新授权"
        val out = UserFacingCopy.ensureNext(already, "别的路径")
        assertEquals(already, out)
        assertFalse("别的路径" in out)
    }

    @Test
    fun `空原因只保留下一步`() {
        assertEquals("下一步：去设置", UserFacingCopy.withNext("", "去设置"))
    }

    @Test
    fun `英文 Next 标记也视为已有路径`() {
        val already = "Expired. Next: re-authorize"
        assertEquals(already, UserFacingCopy.withNext(already, "other"))
    }
}
