package com.luckylearning.windtone.overlay

import android.app.Application
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [OverlayStateBinder] 的接线冒烟测试。
 *
 * 把引擎各路 StateFlow 合并后逐帧驱动窗口外观 / 可见性，原本埋在 LyricOverlayService 里。
 * 这里用假 Host 接住所有回调，验证：① observe() 真的订阅并消费了首帧；② 后续任意状态
 * 变化（这里切一次前台态）都会重新走 applyAppearance + applyVisibility 的接线。
 * 纯决策（可见性判定 / 行数上限）已由 OverlayVisibility / OverlayTextComposer 各自的单测覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class OverlayStateBinderTest {
    private lateinit var app: Application
    private lateinit var host: FakeHost
    private lateinit var scope: CoroutineScope
    private lateinit var binder: OverlayStateBinder

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        LyricOverlayBus.clear()
        host = FakeHost(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        binder = OverlayStateBinder(host, scope, foregroundStableMs = 0L)
    }

    @After
    fun tearDown() {
        binder.dispose()
        scope.cancel()
        LyricOverlayBus.clear()
    }

    @Test
    fun `observe 订阅后会消费首帧，驱动 applyAppearance 与 applyVisibility`() {
        binder.observe()
        assertTrue("observe 后应当消费首帧并调用 applyAppearance", host.applyAppearanceCount > 0)
        assertTrue("observe 后应当消费首帧并调用 applyVisibility", host.applyVisibilityCount > 0)
    }

    @Test
    fun `后续状态变化会重新走接线（切前台态触发一次重算）`() {
        binder.observe()
        val beforeAppearance = host.applyAppearanceCount
        LyricOverlayBus.setForeground(true)
        // v1.2.42：显隐未变时不再 applyVisibility；接线是否还在跑看 applyAppearance
        assertTrue("状态变化后应当再次调用 applyAppearance", host.applyAppearanceCount > beforeAppearance)
    }

    @Test
    fun `重复 observe 不抛异常且仍能消费状态`() {
        binder.observe()
        binder.observe() // 内部应先取消上一次订阅再重建
        LyricOverlayBus.bumpAppearance()
        assertTrue(host.applyVisibilityCount > 0)
    }

    
    @Test
    fun `显隐未变时不重复 applyVisibility`() {
        // 首帧必刷；之后仅外观版本变化、可见性不变时不应再 applyVisibility
        binder.observe()
        val afterFirst = host.applyVisibilityCount
        assertTrue("首帧应 applyVisibility", afterFirst > 0)
        LyricOverlayBus.bumpAppearance()
        assertEquals(
            "可见性未变时不应重复 applyVisibility",
            afterFirst,
            host.applyVisibilityCount,
        )
    }

private class FakeHost(app: Application) : OverlayStateBinder.Host {
        var applyAppearanceCount = 0
        var applyVisibilityCount = 0
        private val prefs = Prefs(app)

        override val container = FrameLayout(app)
        override val lyricView = TextView(app)
        override val layoutParams = null
        override var screenInteractive = true
        override var editing = false
        override var pendingPositionSave = false
        override var desiredVisible = false

        override fun prefs() = prefs

        /**
         * 行数自适应在服务里算（要 dp / 字号 / 屏幕尺寸），替身直接返回用户档位；
         * 「自动」= 0 在这里换成 3，模拟「高度也是自动」时的兜底值。
         */
        override fun resolveLineCount(prefs: Prefs, full: Boolean): Int {
            val configured = prefs.lyricOverlayLines
            return if (configured > 0) configured else OverlayLineFit.STRIP_FALLBACK_LINES
        }

        override fun applyAppearance(p: Prefs) {
            applyAppearanceCount++
        }

        override fun syncForegroundTracking(hiddenApps: Set<String>, logEnabled: Boolean) {}
        override fun applyVisibility() {
            applyVisibilityCount++
        }

        override fun applyTitle(content: LyricOverlayBus.Content, p: Prefs) = false
        override fun applyPosition() {}
        override fun renderLines(
            tv: TextView,
            lines: List<OverlayTextComposer.StyledLine>,
            emphasize: Boolean,
            full: Boolean,
        ) {
        }

        override fun updateGeometryReport() {}
        override fun refreshModeChrome() {}
        override fun armEditingTimeout() {}
        override fun cancelEditingTimeout() {}
    }
}
