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

    /** 两句就够：要求三句会让首屏白等一个歌词周期。 */
    @Test
    fun twoFastChangesAreEnough() {
        val timeline = TitleTimeline()
        timeline.record(0, "第一句", 180_000, "m1", "歌手")
        assertEquals(TitleVerdict.PSEUDO, timeline.record(3_000, "第二句", 180_000, "m1", "歌手"))
    }

    @Test
    fun tenSecondsApartStayStable() {
        val timeline = TitleTimeline()
        timeline.record(0, "A", 180_000, "m1", "歌手")
        assertEquals(TitleVerdict.STABLE, timeline.record(10_000, "B", 180_000, "m1", "歌手"))
    }

    /** 「歌名」→「歌名 - 播放中」是播放器附状态，不是歌词，不能算作证据。 */
    @Test
    fun suffixVariantIsNotEvidence() {
        val timeline = TitleTimeline()
        timeline.record(0, "普通歌名", 180_000, "m1", "歌手")
        val verdict = timeline.record(2_000, "普通歌名 - 播放中", 180_000, "m1", "歌手")
        assertEquals(TitleVerdict.UNKNOWN, verdict)
        assertEquals(1, timeline.snapshot().size)
    }

    /** 元数据稀疏的播放器靠 identityExtra（包名／专辑／音轨）区分换歌。 */
    @Test
    fun identityExtraSplitsSongs() {
        val timeline = TitleTimeline()
        timeline.record(0, "第一句", 0, null, null, "app.a|专辑|1")
        timeline.record(2_000, "第二句", 0, null, null, "app.a|专辑|2")

        assertTrue(timeline.consumeIdentityChanged())
        assertEquals(1, timeline.snapshot().size)
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
