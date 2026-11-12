package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTimelineTest {

    @Test
    fun singleTitleIsUnknown() {
        val timeline = TitleTimeline()
        assertEquals(TitleVerdict.UNKNOWN, timeline.record(0, "歌名", 180_000, "m1", "歌手"))
    }

    @Test
    fun fastTitleChangesLookPseudo() {
        val timeline = TitleTimeline()
        timeline.record(0, "第一句", 180_000, "m1", "歌手")
        timeline.record(3_000, "第二句", 180_000, "m1", "歌手")
        val verdict = timeline.record(6_000, "第三句", 180_000, "m1", "歌手")
        assertEquals(TitleVerdict.PSEUDO, verdict)
    }

    @Test
    fun slowTitleChangesStayStable() {
        val timeline = TitleTimeline(windowMs = 30_000, minChanges = 3, maxMedianGapMs = 6_000)
        timeline.record(0, "A", 180_000, "m1", "歌手")
        timeline.record(9_000, "B", 180_000, "m1", "歌手")
        val verdict = timeline.record(18_000, "C", 180_000, "m1", "歌手")
        assertEquals(TitleVerdict.STABLE, verdict)
    }

    @Test
    fun sameTitleIsNotRecordedTwice() {
        val timeline = TitleTimeline()
        timeline.record(0, "歌名", 180_000, "m1", "歌手")
        timeline.record(1_000, "歌名", 180_000, "m1", "歌手")
        assertEquals(1, timeline.snapshot().size)
    }

    @Test
    fun identityChangeClearsHistory() {
        val timeline = TitleTimeline()
        timeline.record(0, "A", 180_000, "m1", "歌手")
        timeline.record(1_000, "B", 180_000, "m1", "歌手")
        timeline.record(2_000, "C", 200_000, "m2", "歌手")

        assertTrue(timeline.consumeIdentityChanged())
        assertEquals(1, timeline.snapshot().size)
        assertEquals("C", timeline.snapshot()[0].title)
        // 只上报一次
        assertFalse(timeline.consumeIdentityChanged())
    }

    @Test
    fun blankTitleIsIgnored() {
        val timeline = TitleTimeline()
        assertEquals(TitleVerdict.UNKNOWN, timeline.record(0, "   ", 180_000, "m1", "歌手"))
        assertEquals(0, timeline.snapshot().size)
    }
}
