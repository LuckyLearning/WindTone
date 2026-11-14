package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.PlayerPane
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放页横滑判据（[PlayerSwipe]，v1.2.55 三页版）。
 *
 * 这是「手势会不会误翻页 / 误切歌」的唯一防线：判错方向就是划一下跳一首，
 * 阈值判松就会在读歌词时被碰走。歌词纵向滚动不在本纯函数里（由方向锁交给子级）。
 */
class PlayerSwipeTest {

    private val width = 1000f

    // ---------------------------------------------------------------- 翻页

    @Test
    fun `位移不够且速度慢时留在原页`() {
        // 18% 屏宽 = 180px；这里只走了 100px 且松手很慢
        assertEquals(PageSwipe.Stay, PlayerSwipe.resolvePage(-100f, width, 200f, PlayerPane.ART))
    }

    @Test
    fun `向左越过阈值翻到下一页`() {
        assertEquals(
            PageSwipe.To(PlayerPane.LYRICS),
            PlayerSwipe.resolvePage(-200f, width, 0f, PlayerPane.ART),
        )
    }

    /**
     * 宽屏（车机）按比例算阈值会离谱：1700dp 横屏的 18% 是 300dp，用户根本划不了那么长。
     * 传了上限之后阈值被压到上限，短距离位移也能翻页。
     */
    @Test
    fun `宽屏上阈值被 dp 上限压住`() {
        val wide = 2400f
        val cap = 288f // 96dp × 3x
        // 纯比例：2400 × 18% = 432px，300px 不够 → 不动
        assertEquals(PageSwipe.Stay, PlayerSwipe.resolvePage(-300f, wide, 0f, PlayerPane.ART))
        // 压到 288px 之后 300px 就够 → 翻页
        assertEquals(
            PageSwipe.To(PlayerPane.LYRICS),
            PlayerSwipe.resolvePage(-300f, wide, 0f, PlayerPane.ART, cap),
        )
        // 窄屏（手机）比例阈值本来就小于上限，行为不受影响
        assertEquals(
            PageSwipe.Stay,
            PlayerSwipe.resolvePage(-100f, width, 0f, PlayerPane.ART, cap),
        )
    }

    @Test
    fun `向右越过阈值翻到上一页`() {
        assertEquals(
            PageSwipe.To(PlayerPane.INFO),
            PlayerSwipe.resolvePage(200f, width, 0f, PlayerPane.ART),
        )
    }

    @Test
    fun `位移不够但甩得够快也翻页`() {
        // 行车中手指划不长，只认位移会让人觉得「划了没反应」
        assertEquals(
            PageSwipe.To(PlayerPane.LYRICS),
            PlayerSwipe.resolvePage(-60f, width, -1200f, PlayerPane.ART),
        )
        assertEquals(
            PageSwipe.To(PlayerPane.INFO),
            PlayerSwipe.resolvePage(60f, width, 1200f, PlayerPane.ART),
        )
    }

    @Test
    fun `位移与速度方向冲突时以位移为准`() {
        // 甩出去又拉回来：真实意图是「不翻」，按速度判会反向翻一页
        assertEquals(
            PageSwipe.To(PlayerPane.LYRICS),
            PlayerSwipe.resolvePage(-200f, width, 2000f, PlayerPane.ART),
        )
    }

    @Test
    fun `宽度非法时不动`() {
        assertEquals(PageSwipe.Stay, PlayerSwipe.resolvePage(-500f, 0f, 5000f, PlayerPane.ART))
        assertEquals(PageSwipe.Stay, PlayerSwipe.resolvePage(-500f, -1f, 5000f, PlayerPane.ART))
    }

    // ---------------------------------------------------------------- 边界切歌

    @Test
    fun `歌词页再向左滑切下一首`() {
        assertEquals(
            PageSwipe.Skip(SwipeSwitch.NEXT),
            PlayerSwipe.resolvePage(-200f, width, 0f, PlayerPane.LYRICS),
        )
    }

    @Test
    fun `信息页再向右滑切上一首`() {
        assertEquals(
            PageSwipe.Skip(SwipeSwitch.PREVIOUS),
            PlayerSwipe.resolvePage(200f, width, 0f, PlayerPane.INFO),
        )
    }

    @Test
    fun `首尾页反向滑仍是翻页而不是切歌`() {
        assertEquals(
            PageSwipe.To(PlayerPane.ART),
            PlayerSwipe.resolvePage(200f, width, 0f, PlayerPane.LYRICS),
        )
        assertEquals(
            PageSwipe.To(PlayerPane.ART),
            PlayerSwipe.resolvePage(-200f, width, 0f, PlayerPane.INFO),
        )
    }

    @Test
    fun `边界甩速快也按同一判定切歌`() {
        assertEquals(
            PageSwipe.Skip(SwipeSwitch.NEXT),
            PlayerSwipe.resolvePage(-40f, width, -1200f, PlayerPane.LYRICS),
        )
    }

    /**
     * 甩速兜底必须配最小位移：按下即抬的抖动在 VelocityTracker 里也可能报几千 px/s，
     * 不设防就会在歌词页「碰一下切下一首」。
     */
    @Test
    fun `极短抖动即使甩速极高也不翻页不切歌`() {
        assertEquals(PageSwipe.Stay, PlayerSwipe.resolvePage(-8f, width, -5000f, PlayerPane.LYRICS))
        assertEquals(PageSwipe.Stay, PlayerSwipe.resolvePage(8f, width, 5000f, PlayerPane.INFO))
        // 刚好到最小位移则仍按甩速翻页
        assertEquals(
            PageSwipe.To(PlayerPane.LYRICS),
            PlayerSwipe.resolvePage(-PlayerSwipe.MinMovePx, width, -1200f, PlayerPane.ART),
        )
    }

    // ---------------------------------------------------------------- 跟手

    @Test
    fun `中间页跟手一比一`() {
        assertEquals(-200f, PlayerSwipe.followPx(-200f, width, PlayerPane.ART), 0.01f)
        assertEquals(200f, PlayerSwipe.followPx(200f, width, PlayerPane.ART), 0.01f)
    }

    @Test
    fun `首尾页越界跟手带阻力且夹在上限`() {
        // 歌词页继续向左 = 越界，阻力 0.32
        assertEquals(-200f * 0.32f, PlayerSwipe.followPx(-200f, width, PlayerPane.LYRICS), 0.01f)
        // 上限 = 屏宽 30%：-900 × 0.32 = -288 还没到上限，所以要用更大的位移才夹得住
        assertEquals(-width * 0.30f, PlayerSwipe.followPx(-2000f, width, PlayerPane.LYRICS), 0.01f)
        // 信息页向右同理
        assertEquals(width * 0.30f, PlayerSwipe.followPx(2000f, width, PlayerPane.INFO), 0.01f)
    }

    @Test
    fun `越界反方向仍是一比一`() {
        // 在歌词页向右滑是「翻回图片页」，不是越界，应全额跟手
        assertEquals(200f, PlayerSwipe.followPx(200f, width, PlayerPane.LYRICS), 0.01f)
    }

    @Test
    fun `页序号与出厂默认页一致`() {
        assertEquals(0, PlayerPane.INFO.index)
        assertEquals(1, PlayerPane.ART.index)
        assertEquals(2, PlayerPane.LYRICS.index)
        assertEquals(PlayerPane.ART, PlayerPane.DEFAULT)
        assertEquals(
            com.luckylearning.windtone.data.SettingsDefaults.PLAYER_PANE,
            PlayerPane.DEFAULT.index,
        )
        assertEquals(
            com.luckylearning.windtone.data.SettingsDefaults.PLAYER_PANE_COUNT,
            PlayerPane.entries.size,
        )
    }

    @Test
    fun `越界页码退回默认页而不抛异常`() {
        assertEquals(PlayerPane.DEFAULT, PlayerPane.of(-1))
        assertEquals(PlayerPane.DEFAULT, PlayerPane.of(9))
        assertEquals(PlayerPane.LYRICS, PlayerPane.of(2))
    }

    @Test
    fun `翻页位移换算成整页宽度`() {
        assertEquals(1, PlayerSwipe.pageDelta(PlayerPane.ART, PlayerPane.LYRICS))
        assertEquals(-1, PlayerSwipe.pageDelta(PlayerPane.ART, PlayerPane.INFO))
        assertEquals(2, PlayerSwipe.pageDelta(PlayerPane.INFO, PlayerPane.LYRICS))
    }

    /**
     * 落位位移必须是「页相位」的反号：让目标页居中，整排要朝**目标所在方向**推。
     *
     * 这一条是 v1.2.55 首发翻页失灵的真凶 —— 之前用的是 `pageDelta * width`（正号），
     * 于是每次翻页都先往反方向弹回、再硬切到目标页，看起来像「滑了没反应」。
     */
    @Test
    fun `落位位移把目标页搬到中心`() {
        // 往右翻页（ART → LYRICS）：整排往左推一屏，位移为负
        assertEquals(-width, PlayerSwipe.settlePx(PlayerPane.ART, PlayerPane.LYRICS, width), 0.01f)
        // 往左翻页（ART → INFO）：整排往右推一屏，位移为正
        assertEquals(width, PlayerSwipe.settlePx(PlayerPane.ART, PlayerPane.INFO, width), 0.01f)
        // 目标页相位 + 落位位移 = 0（正好居中），这才是判据的来源
        assertEquals(
            0f,
            PlayerSwipe.pageDelta(PlayerPane.INFO, PlayerPane.LYRICS) * width +
                PlayerSwipe.settlePx(PlayerPane.INFO, PlayerPane.LYRICS, width),
            0.01f,
        )
    }
}
