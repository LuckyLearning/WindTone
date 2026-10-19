package com.luckylearning.windtone.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮歌词显隐规则（v1.1.22 从服务里抽出来的纯函数）。
 *
 * 这里的每条用例都对应一次真机 bug，不是「补覆盖率」：
 *  - 全屏形态在 App 前台画出来 → 整屏歌词压在设置页的控件上，字压着按钮；
 *  - 单条字幕在 App 前台画出来 → 打开开关那一刻 App 里什么都看不到（才是 bug）；
 *  - 调整 / 预览态被「仅后台」规则吞掉 → 用户点了「调整位置」却没反应。
 */
class OverlayVisibilityTest {

    private fun show(
        fullHeight: Boolean = false,
        foreground: Boolean = true,
        editing: Boolean = false,
        preview: Boolean = false,
        playing: Boolean = true,
        pauseHide: Boolean = false,
        screenInteractive: Boolean = true,
        hasContent: Boolean = true,
        foregroundHidden: Boolean = false,
    ) = OverlayVisibility.show(
        fullHeight, foreground, editing, preview,
        playing, pauseHide, screenInteractive, hasContent, foregroundHidden,
    )

    @Test
    fun `后台且有一句歌词时显示`() {
        assertTrue(show(foreground = false))
    }

    @Test
    fun `App 在前台时单条字幕不显示`() {
        assertFalse(show(foreground = true))
    }

    @Test
    fun `全屏形态即使在预览期也不在 App 内显示`() {
        // 这条就是 2026-10-18 车机照片的问题：改宽度会自动给 6 秒预览，
        // 整屏歌词于是盖住了宽度滑块与「靠左/居中/靠右」。
        assertFalse(show(fullHeight = true, foreground = true, preview = true))
        assertFalse(show(fullHeight = true, foreground = true, editing = true))
        // 退到桌面照常显示（预览 / 编辑是为了调位置，桌面才是能拖的地方）
        assertTrue(show(fullHeight = true, foreground = false, preview = true))
        assertTrue(show(fullHeight = true, foreground = false, editing = true))
    }

    @Test
    fun `单条字幕的预览与调整在 App 内仍然显示`() {
        assertTrue(show(foreground = true, preview = true))
        assertTrue(show(foreground = true, editing = true))
    }

    @Test
    fun `暂停隐藏只在非例外态下生效`() {
        // 后台 + 暂停 + 用户选了「暂停即隐藏」→ 藏
        assertFalse(show(foreground = false, playing = false, pauseHide = true))
        // 正在调整时不藏：否则用户一边调一边看它消失
        assertTrue(show(foreground = false, playing = false, pauseHide = true, editing = true))
        // 用户没选隐藏 → 暂停也留着
        assertTrue(show(foreground = false, playing = false, pauseHide = false))
    }

    @Test
    fun `屏幕熄灭时不显示`() {
        assertFalse(show(foreground = false, screenInteractive = false))
    }

    @Test
    fun `没有歌词内容时后台不显示`() {
        assertFalse(show(foreground = false, hasContent = false))
        // 但调整 / 预览态要显示提示行，否则「调整位置」按了像没反应
        assertTrue(show(foreground = false, hasContent = false, editing = true))
    }

    // ---------------- v1.1.29：前台应用命中「不显示悬浮窗的应用」名单 ----------------

    @Test
    fun `前台应用在隐藏名单里时一律收起`() {
        // 360 影像 / 视频类应用打开时，浮层会挡住画面 —— 后台照样收起
        assertFalse(show(foreground = false, foregroundHidden = true))
        // 单条字幕在 App 前台本来就不画，叠加名单依旧不画（不是「二更不画」）
        assertFalse(show(foreground = true, foregroundHidden = true))
    }

    @Test
    fun `名单命中压过调整与预览`() {
        // 用户正在设置页里调悬浮窗，恰好切到名单应用 → 继续画会盖住那个应用，
        // 调整可以等（这是刻意选的优先级，写死在 KDoc 里）
        assertFalse(show(foreground = true, editing = true, foregroundHidden = true))
        assertFalse(show(foreground = true, preview = true, foregroundHidden = true))
        assertFalse(show(foreground = false, editing = true, foregroundHidden = true))
    }

    @Test
    fun `名单命中压不过灭屏与无内容`() {
        // 这两种情况本来就不画，与名单无关（别为名单引入新的显示路径）
        assertFalse(show(foreground = false, foregroundHidden = true, screenInteractive = false))
        assertFalse(show(foreground = false, foregroundHidden = true, hasContent = false))
    }

    @Test
    fun `名单未命中时行为与旧版完全一致`() {
        assertTrue(show(foreground = false, foregroundHidden = false))
        assertTrue(show(foreground = true, preview = true, foregroundHidden = false))
        assertFalse(show(foreground = true, foregroundHidden = false))
    }
}
