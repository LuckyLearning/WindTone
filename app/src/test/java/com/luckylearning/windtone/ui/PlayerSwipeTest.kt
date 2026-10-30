package com.luckylearning.windtone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页滑动判据（[PlayerSwipe]）：纵向切歌 + 横向切面板。
 *
 * 这几条是「手势会不会误切歌」的唯一防线：判错方向就是划右跳下一首，
 * 阈值判松就会误切。歌词中段滚动靠 nestedScroll 边缘策略，不在本纯函数里。
 */
class PlayerSwipeTest {

    private val width = 1000f

    @Test
    fun `位移不够且速度慢时不切歌`() {
        // 12% 屏宽 = 120px；这里只走了 60px 且松手很慢
        assertEquals(SwipeSwitch.NONE, PlayerSwipe.resolve(-60f, width, 200f))
    }

    @Test
    fun `向左越过阈值切下一首`() {
        assertEquals(SwipeSwitch.NEXT, PlayerSwipe.resolve(-150f, width, 0f))
    }

    @Test
    fun `向右越过阈值切上一首`() {
        assertEquals(SwipeSwitch.PREVIOUS, PlayerSwipe.resolve(150f, width, 0f))
    }

    @Test
    fun `位移不够但甩得够快也切歌`() {
        // 行车中手指划不长，只认位移会让人觉得「划了没反应」
        assertEquals(SwipeSwitch.NEXT, PlayerSwipe.resolve(-40f, width, -1200f))
        assertEquals(SwipeSwitch.PREVIOUS, PlayerSwipe.resolve(40f, width, 1200f))
    }

    @Test
    fun `位移与速度方向冲突时以位移为准`() {
        // 甩出去又拉回来：真实意图是「不切」，按速度判会反向跳一首
        assertEquals(SwipeSwitch.NEXT, PlayerSwipe.resolve(-200f, width, 2000f))
    }

    @Test
    fun `宽度非法时不动`() {
        assertEquals(SwipeSwitch.NONE, PlayerSwipe.resolve(-500f, 0f, 5000f))
    }

    @Test
    fun `跟手位移带阻力且夹在上限内`() {
        // 阻力 0.45
        assertEquals(-45f, PlayerSwipe.resisted(-100f, width), 0.01f)
        // 上限 = 屏宽 1/6，超出后不再跟手（否则会盖住顶栏与底部控制行）
        assertEquals(-width / 6f, PlayerSwipe.resisted(-900f, width), 0.01f)
        assertEquals(width / 6f, PlayerSwipe.resisted(900f, width), 0.01f)
    }

    @Test
    fun `跟手位移方向跟随手指`() {
        assertEquals(0f, PlayerSwipe.resisted(0f, width), 0.01f)
        assertTrue(PlayerSwipe.resisted(-80f, width) < 0f)
        assertTrue(PlayerSwipe.resisted(80f, width) > 0f)
    }


    @Test
    fun `向左越过阈值切到封面`() {
        assertEquals(SwipePane.SHOW_COVER, PlayerSwipe.resolvePane(-150f, width, 0f))
    }

    @Test
    fun `向右越过阈值切到歌词`() {
        assertEquals(SwipePane.SHOW_LYRICS, PlayerSwipe.resolvePane(150f, width, 0f))
    }

    @Test
    fun `横向位移不够且速度慢时不切面板`() {
        assertEquals(SwipePane.NONE, PlayerSwipe.resolvePane(-60f, width, 200f))
    }

    @Test
    fun `纵向向上越过阈值切下一首`() {
        // 与横向同一套 resolve：负向 = NEXT
        assertEquals(SwipeSwitch.NEXT, PlayerSwipe.resolve(-150f, width, 0f))
    }
}
