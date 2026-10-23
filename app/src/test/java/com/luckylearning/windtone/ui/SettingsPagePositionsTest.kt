package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pagePositions` 与 [SettingsPage] 的**长度契约**。
 *
 * 为什么单独测：这张表是按 `page.ordinal` 直接下标的，长度少一格就 IndexOutOfBounds，
 * 而它是个手写/隐式的长度，编译期完全不检查。
 * v1.1.56 就这么炸过——枚举涨到 11 项，初始表还是 9 个 0（v1.1.52 加「悬浮歌词」时漏的），
 * 用户点「阿里云盘」当场崩在设置页：
 *
 * ```
 * java.lang.IndexOutOfBoundsException: index: 10, size: 9
 *   at SettingsScreenKt.SettingsScreen(SettingsScreen.kt)
 * ```
 *
 * 所以这里既锁「初始表长度 == 枚举项数」，也锁「旧存档能补齐」——
 * 后者是老用户从旧版本覆盖升级时的真实路径（savedInstanceState 里存的还是短列表）。
 */
class SettingsPagePositionsTest {

    /** 初始表必须刚好覆盖每一个页面的 ordinal —— 以后加子页忘了同步，这里先红。 */
    @Test
    fun initialPositionsCoverEveryPageOrdinal() {
        val positions = initialPagePositions()
        assertEquals(SettingsPage.entries.size, positions.size)
        SettingsPage.entries.forEach { page ->
            // 模拟 SettingsScreen 里的 pagePositions[page.ordinal]
            assertEquals(0, positions[page.ordinal])
        }
    }

    /** 旧版本存下来的短列表：补齐到当前项数，越界那几位补 0。 */
    @Test
    fun oldSaveIsPaddedUpToCurrentSize() {
        // 故意比枚举短（v1.1.52 之前的存档就是这个形状）
        val saved = List(SettingsPage.entries.size - 1) { it + 1 }
        val restored = paddedPagePositions(saved)

        assertEquals(SettingsPage.entries.size, restored.size)
        saved.forEachIndexed { i, v -> assertEquals(v, restored[i]) }
        assertTrue(
            "补齐出来的尾巴必须是 0（= 该页从未滚动过）",
            restored.drop(saved.size).all { it == 0 },
        )
    }

    /** 存档比枚举还长（回滚到旧版本又升回来）不许截断，多出来的照原样留着。 */
    @Test
    fun longerSaveIsKeptAsIs() {
        val saved = List(SettingsPage.entries.size + 3) { it + 1 }
        val restored = paddedPagePositions(saved)

        assertEquals(saved.size, restored.size)
        assertEquals(saved, restored)
    }
}
