package com.luckylearning.windtone.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动自动扫描节流的判定口径。
 *
 * 这条规则一旦偏松，用户新下载的歌就可能长时间不出现；一旦偏严，节流等于没做
 * （每次启动照样全盘扫）。所以边界要逐条钉：时间窗、指纹一致、以及「拿不到指纹」。
 */
class AutoScanThrottleTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun neverScannedMeansScan() {
        assertFalse(
            "从未扫过（lastScanAt = 0）必须照常扫描",
            LibraryCore.shouldSkipAutoScan(0L, "100:3000", now, "100:3000"),
        )
    }

    @Test
    fun withinWindowAndSameFingerprintSkips() {
        assertTrue(
            LibraryCore.shouldSkipAutoScan(now - 5 * minute, "100:3000", now, "100:3000"),
        )
    }

    @Test
    fun withinWindowButFingerprintChangedScans() {
        assertFalse(
            "曲库变了（新增/删除歌）就必须扫",
            LibraryCore.shouldSkipAutoScan(now - 5 * minute, "100:3000", now, "101:3001"),
        )
    }

    @Test
    fun outsideWindowAlwaysScans() {
        assertFalse(
            "超过 10 分钟窗口，即使指纹没变也要扫（可能有外部改动没反映在指纹上）",
            LibraryCore.shouldSkipAutoScan(now - 20 * minute, "100:3000", now, "100:3000"),
        )
    }

    @Test
    fun windowBoundaryCountsAsExpired() {
        assertFalse(
            "恰好 10 分钟按过期处理，避免「差一毫秒」的不确定行为",
            LibraryCore.shouldSkipAutoScan(now - 10 * minute, "100:3000", now, "100:3000"),
        )
        assertTrue(
            LibraryCore.shouldSkipAutoScan(now - 10 * minute + 1, "100:3000", now, "100:3000"),
        )
    }

    @Test
    fun blankFingerprintNeverSkips() {
        assertFalse(
            "拿不到指纹就老实地扫一遍，宁可慢也不能漏歌",
            LibraryCore.shouldSkipAutoScan(now - minute, "", now, "100:3000"),
        )
        assertFalse(
            LibraryCore.shouldSkipAutoScan(now - minute, "100:3000", now, ""),
        )
    }

    @Test
    fun clockGoingBackwardsDoesNotSkip() {
        assertFalse(
            "系统时间被回拨时不能把用户永久锁在「已是最新」状态",
            LibraryCore.shouldSkipAutoScan(now + 5 * minute, "100:3000", now, "100:3000"),
        )
    }
}
