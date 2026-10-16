package com.luckylearning.windtone.lyrics

import com.luckylearning.windtone.lyrics.WordTimingSynth.withDerivedWordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派生逐字时间轴测试。
 *
 * 这是「逐字歌词完全没有效果」的修复核心：普通 LRC 没有逐字数据，
 * 必须在加载层派生，否则渲染层永远走整行变色分支。
 */
class WordTimingSynthTest {

    private fun lyrics(vararg lines: LyricLine) =
        Lyrics(lines = lines.toList(), synced = true, source = LyricSource.SIDECAR_LRC)

    @Test
    fun `普通同步歌词每一行都补出逐字时间轴`() {
        val src = lyrics(
            LyricLine(0, "你我皆凡人"),
            LyricLine(3_000, "生在人世间"),
            LyricLine(6_000, "终日奔波苦"),
        )
        val out = src.withDerivedWordTiming()
        assertTrue(out.hasWordTiming)
        out.lines.forEach { assertTrue("每行都应有逐字数据", it.words.isNotEmpty()) }
        assertEquals(5, out.lines[0].words.size) // 中文逐字
    }

    @Test
    fun `逐字文本拼接还原整行原文`() {
        val text = "你我皆凡人，Hello World 123"
        val out = lyrics(LyricLine(0, text), LyricLine(8_000, "下一行")).withDerivedWordTiming()
        assertEquals(text, out.lines[0].words.joinToString("") { it.text })
    }

    @Test
    fun `逐字时间单调递增且不越出行区间`() {
        val out = lyrics(LyricLine(1_000, "白日依山尽"), LyricLine(4_000, "黄河入海流"))
            .withDerivedWordTiming()
        val line = out.lines[0]
        var prevEnd = line.timeMs
        line.words.forEach { w ->
            assertTrue("字起点不得早于行起点", w.startMs >= line.timeMs)
            assertTrue("词序必须单调", w.startMs >= prevEnd - 1)
            assertTrue("每个字都要有正时长", w.durationMs > 0)
            prevEnd = w.startMs + w.durationMs
        }
        assertTrue("末字唱完不得越过下一行起点", prevEnd <= 4_000)
    }

    @Test
    fun `末字唱完时刻与行时长严格一致`() {
        // 渲染层的归一化分母就是 lineDurationMs：不一致 → 末字染不满
        val out = lyrics(LyricLine(0, "12345"), LyricLine(9_000, "x")).withDerivedWordTiming()
        val line = out.lines[0]
        val last = line.words.last()
        assertEquals(line.timeMs + line.lineDurationMs, last.startMs + last.durationMs)
    }

    @Test
    fun `行时长受下一行起点约束，不被长间奏拉长`() {        // 10 字的中文行，下一行 30 秒后才来 —— 不能摊成 30 秒的慢扫
        val out = lyrics(LyricLine(0, "十字歌词内容的测试行啊"), LyricLine(30_000, "下一行"))
            .withDerivedWordTiming()
        val dur = out.lines[0].lineDurationMs
        assertTrue("行时长 $dur 应远小于 30s 的间奏", dur <= 5_000)
        assertTrue("行时长 $dur 应至少唱够", dur >= 1_000)
    }

    @Test
    fun `行距接近估算时长时扫满整个间隔，不早停`() {
        // 5 字 → 估算 2100ms；下一行 2500ms 后到（差 400ms，属于正常停顿）→ 按间隔扫满
        val out = lyrics(LyricLine(0, "白日依山尽"), LyricLine(2_500, "黄河入海流"))
            .withDerivedWordTiming()
        assertEquals(2_500, out.lines[0].lineDurationMs)
        val last = out.lines[0].words.last()
        assertEquals(2_500, last.startMs + last.durationMs)
    }

    @Test
    fun `行距远大于估算时长（间奏）时按估算走`() {
        // 同样的 5 字（估算 2100ms），但下一行 20 秒后才来 → 不能被拉成 20 秒的慢扫
        val out = lyrics(LyricLine(0, "白日依山尽"), LyricLine(20_000, "黄河入海流"))
            .withDerivedWordTiming()
        assertEquals(2_100, out.lines[0].lineDurationMs)
    }

    @Test
    fun `最后一行没有下一行时也能派生`() {
        val out = lyrics(LyricLine(0, "第一行"), LyricLine(3_000, "最后一行"))
            .withDerivedWordTiming()
        assertTrue(out.lines.last().words.isNotEmpty())
        assertTrue(out.lines.last().lineDurationMs > 0)
    }

    @Test
    fun `真实逐字数据原样保留`() {
        val real = listOf(
            LyricWord(0, 500, "你"),
            LyricWord(500, 700, "好"),
        )
        val src = lyrics(LyricLine(0, "你好", words = real, lineDurationMs = 1_200))
        val out = src.withDerivedWordTiming()
        assertSame("已有逐字数据的行不该被重建", real, out.lines[0].words)
        assertEquals(1_200, out.lines[0].lineDurationMs)
    }

    @Test
    fun `真实逐字数据行时长为零时兜底`() {
        // YRC 里可能出现 [start,0]：分母为 0 会退化成整行瞬间全亮
        val src = lyrics(LyricLine(0, "你好", words = listOf(
            LyricWord(0, 400, "你"),
            LyricWord(400, 600, "好"),
        ), lineDurationMs = 0))
        val out = src.withDerivedWordTiming()
        assertEquals(1_000, out.lines[0].lineDurationMs)
    }

    @Test
    fun `纯文本歌词（无时间轴）不做派生`() {
        val src = Lyrics(lines = listOf(LyricLine(-1, "没有时间轴的歌词")), synced = false)
        val out = src.withDerivedWordTiming()
        assertSame(src, out)
        assertFalse(out.hasWordTiming)
    }

    @Test
    fun `空行与空白文本行不派生`() {
        val out = lyrics(LyricLine(0, ""), LyricLine(2_000, "  "), LyricLine(4_000, "有字"))
            .withDerivedWordTiming()
        assertTrue(out.lines[0].words.isEmpty())
        assertTrue(out.lines[1].words.isEmpty())
        assertTrue(out.lines[2].words.isNotEmpty())
    }

    @Test
    fun `重复调用是幂等的`() {
        val once = lyrics(LyricLine(0, "你我皆凡人"), LyricLine(3_000, "生在人世间"))
            .withDerivedWordTiming()
        val twice = once.withDerivedWordTiming()
        assertSame("第二次调用不该重建任何行", once.lines, twice.lines)
    }

    @Test
    fun `中英混排时西文段落按权重分摊`() {
        val out = lyrics(LyricLine(0, "爱你 love you"), LyricLine(6_000, "x"))
            .withDerivedWordTiming()
        val words = out.lines[0].words
        assertEquals("爱你 love you", words.joinToString("") { it.text })
        // 中文逐字，西文成段
        assertEquals("爱", words[0].text)
        assertEquals("你", words[1].text)
        assertTrue(words[2].text.startsWith(" "))
    }

    @Test
    fun `整份歌词经解析器后就有逐字数据`() {
        // 端到端：普通 LRC 文本 → 解析 → 派生
        val lrc = """
            [ti:测试]
            [00:00.00]你我皆凡人
            [00:03.00]生在人世间
            [00:06.00]终日奔波苦
        """.trimIndent()
        val parsed = LrcParser.parse(lrc, LyricSource.SIDECAR_LRC).withDerivedWordTiming()
        assertEquals(3, parsed.lines.size)
        assertTrue(parsed.hasWordTiming)
        parsed.lines.forEach { assertTrue(it.words.size >= 5) }
    }
}
