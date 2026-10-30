package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserEqPresetsTest {

    private fun sample(name: String = "夜路", idSalt: Int = 7) =
        UserEqPresets.create(
            name = name,
            bandsCsv = "300,-100,0,50,400",
            bass = 700,
            virtualizer = 150,
            loudness = 300,
            reverbId = "hall_medium",
            nowMillis = 1_700_000_000_000L,
            salt = idSalt,
        )!!

    @Test
    fun `往返编码解码一致`() {
        val saved = UserEqPresets.add(emptyList(), sample())!!
        val again = UserEqPresets.decode(UserEqPresets.encode(saved))
        assertEquals(saved, again)
    }

    @Test
    fun `空串是一份都没有，坏行跳过`() {
        assertTrue(UserEqPresets.decode("").isEmpty())
        assertTrue(UserEqPresets.decode("not-a-preset").isEmpty())
        val good = UserEqPresets.encode(listOf(sample()))
        val mixed = "broken\n$good\n"
        assertEquals(1, UserEqPresets.decode(mixed).size)
        assertEquals("夜路", UserEqPresets.decode(mixed)[0].name)
    }

    @Test
    fun `增删改`() {
        val a = sample("甲", 1)
        val b = sample("乙", 2)
        val added = UserEqPresets.add(UserEqPresets.add(emptyList(), a)!!, b)!!
        assertEquals(listOf("甲", "乙"), added.map { it.name })
        val renamed = UserEqPresets.rename(added, a.id, "甲改")!!
        assertEquals("甲改", renamed.first { it.id == a.id }.name)
        assertEquals("乙", renamed.first { it.id == b.id }.name)
        val rewritten = UserEqPresets.rewrite(renamed, a.id, "0,0,0", 10, 0, 0, "off")!!
        assertEquals("0,0,0", rewritten.first { it.id == a.id }.bandsCsv)
        assertEquals(10, rewritten.first { it.id == a.id }.bass)
        val left = UserEqPresets.remove(rewritten, a.id)
        assertEquals(listOf(b.id), left.map { it.id })
    }

    @Test
    fun `空名字与满员存不进去`() {
        assertNull(UserEqPresets.create("   ", "", 0, 0, 0, ""))
        assertNull(UserEqPresets.rename(listOf(sample()), sample().id, "  "))
        val full = (0 until UserEqPresets.MAX_PRESETS).map { sample("n$it", it) }
        assertNull(UserEqPresets.add(full, sample("再来", 99)))
    }

    @Test
    fun `用户 id 不与内置预设和方案相撞`() {
        val id = sample().id
        assertTrue(UserEqPresets.isUserId(id))
        EqPresets.ALL.forEach {
            assertFalse(UserEqPresets.isUserId(it.id))
            assertFalse(it.id.startsWith("u_"))
        }
        SoundSchemes.ALL.forEach { assertFalse(UserEqPresets.isUserId(it.id)) }
        assertFalse(UserEqPresets.isUserId(""))
        assertFalse(UserEqPresets.isUserId("bass_boost"))
    }

    @Test
    fun `只动增强时用户预设离开、内置曲线预设保留`() {
        val user = sample().id
        assertEquals("", UserEqPresets.presetIdAfterManualEnhancement(user))
        assertEquals("rock", UserEqPresets.presetIdAfterManualEnhancement("rock"))
        assertEquals("", UserEqPresets.presetIdAfterManualEnhancement(""))
    }

    @Test
    fun `名字里的换行和分隔符会被去掉`() {
        val p = UserEqPresets.create("夜\n路\u001f一", "0", 0, 0, 0, "")!!
        assertEquals("夜路一", p.name)
        assertEquals("夜路一", UserEqPresets.decode(UserEqPresets.encode(listOf(p)))[0].name)
    }

    @Test
    fun `JSON 往返与坏文件`() {
        val saved = listOf(sample("夜路", 1), sample("晨跑", 2))
        val json = UserEqPresets.toExportJson(saved, exportedAtMillis = 42L)
        assertTrue(json.contains("\"format\":\"windtone.user_eq_presets\""))
        assertTrue(json.contains("\"exportedAt\":42"))
        val again = UserEqPresets.fromExportJson(json)!!
        assertEquals(saved, again)
        assertNull(UserEqPresets.fromExportJson(""))
        assertNull(UserEqPresets.fromExportJson("{}"))
        assertNull(UserEqPresets.fromExportJson("""{"format":"other","presets":[]}"""))
        assertEquals(
            emptyList<UserEqPresets.Preset>(),
            UserEqPresets.fromExportJson(
                """{"format":"windtone.user_eq_presets","version":1,"presets":[]}""",
            ),
        )
        val oneGood = UserEqPresets.toExportJson(listOf(sample("好", 3)), 1L)
        val goodObj = oneGood.substring(oneGood.indexOf("[") + 1, oneGood.lastIndexOf("]"))
        val mixedRaw =
            """{"format":"windtone.user_eq_presets","presets":[{"id":"nope","name":"x","bands":"0","bass":0,"virtualizer":0,"loudness":0,"reverbId":""},""" +
                goodObj + "]}"
        val parsed = UserEqPresets.fromExportJson(mixedRaw)!!
        assertEquals(1, parsed.size)
        assertEquals("好", parsed[0].name)
    }

    @Test
    fun `导入合并与覆盖`() {
        val a = sample("甲", 1)
        val b = sample("乙", 2)
        val a2 = a.copy(name = "甲改", bass = 100)
        val c = sample("丙", 3)
        val merge = UserEqPresets.importPresets(listOf(a, b), listOf(a2, c), UserEqPresets.ImportMode.MERGE)
        assertEquals(listOf("甲改", "乙", "丙"), merge.presets.map { it.name })
        assertEquals(1, merge.added)
        assertEquals(1, merge.updated)
        assertEquals(0, merge.skipped)
        val replace = UserEqPresets.importPresets(listOf(a, b), listOf(c), UserEqPresets.ImportMode.REPLACE)
        assertEquals(listOf(c), replace.presets)
        assertEquals(1, replace.added)
        assertEquals(0, replace.updated)
        val full = (0 until UserEqPresets.MAX_PRESETS).map { sample("n$it", it) }
        val extra = sample("溢", 99)
        val capped = UserEqPresets.importPresets(full, listOf(extra), UserEqPresets.ImportMode.MERGE)
        assertEquals(1, capped.skipped)
        assertEquals(UserEqPresets.MAX_PRESETS, capped.presets.size)
        val replaceCap = UserEqPresets.importPresets(
            emptyList(),
            full + extra,
            UserEqPresets.ImportMode.REPLACE,
        )
        assertEquals(UserEqPresets.MAX_PRESETS, replaceCap.presets.size)
        assertEquals(1, replaceCap.skipped)
    }

    @Test
    fun `JSON 名字转义`() {
        val p = UserEqPresets.create(
            "引\"号\\斜", "0,0", 0, 0, 0, "off", nowMillis = 9L, salt = 1,
        )!!
        val again = UserEqPresets.fromExportJson(UserEqPresets.toExportJson(listOf(p)))!!
        assertEquals("引\"号\\斜", again[0].name)
    }
}
