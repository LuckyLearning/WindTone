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

    @Test
    fun `坏输入 JSON 一律拒收或跳过坏条`() {
        assertNull(UserEqPresets.fromExportJson("""{"format":"windtone.user_eq_presets","presets":["""))
        assertNull(UserEqPresets.fromExportJson("[]"))
        assertNull(UserEqPresets.fromExportJson("null"))
        assertNull(UserEqPresets.fromExportJson("\"windtone.user_eq_presets\""))
        assertNull(UserEqPresets.fromExportJson("""{"format":"windtone.user_eq_presets"}"""))
        assertNull(
            UserEqPresets.fromExportJson(
                """{"format":"WindTone.user_eq_presets","presets":[]}""",
            ),
        )
        assertEquals(
            emptyList<UserEqPresets.Preset>(),
            UserEqPresets.fromExportJson(
                """{"format":"windtone.user_eq_presets","presets":[{"id":"u_1_1","name":"缺","bass":0,"virtualizer":0,"loudness":0,"reverbId":""}]}""",
            ),
        )
        assertEquals(
            emptyList<UserEqPresets.Preset>(),
            UserEqPresets.fromExportJson(
                """{"format":"windtone.user_eq_presets","presets":[{"id":"u_1_1","name":"坏","bands":"abc","bass":0,"virtualizer":0,"loudness":0,"reverbId":""}]}""",
            ),
        )
        assertEquals(
            emptyList<UserEqPresets.Preset>(),
            UserEqPresets.fromExportJson(
                """{"format":"windtone.user_eq_presets","presets":[{"id":"u_1_1","name":"坏","bands":"0","bass":"x","virtualizer":0,"loudness":0,"reverbId":""}]}""",
            ),
        )
        assertEquals(
            emptyList<UserEqPresets.Preset>(),
            UserEqPresets.fromExportJson(
                """{"format":"windtone.user_eq_presets","presets":[{"id":"builtin","name":"假","bands":"0","bass":0,"virtualizer":0,"loudness":0,"reverbId":""}]}""",
            ),
        )
    }

    @Test
    fun `空曲线可建档且 JSON Prefs 往返仍空`() {
        val p = UserEqPresets.create(
            "空", "", 0, 0, 0, "off", nowMillis = 2L, salt = 1,
        )!!
        assertEquals("", p.bandsCsv)
        assertEquals(listOf(p), UserEqPresets.decode(UserEqPresets.encode(listOf(p))))
        assertEquals(listOf(p), UserEqPresets.fromExportJson(UserEqPresets.toExportJson(listOf(p)))!!)

        val spaced = UserEqPresets.create(
            "空白", "  ", 0, 0, 0, "", nowMillis = 3L, salt = 2,
        )!!
        assertEquals("", spaced.bandsCsv)

        val parsed = UserEqPresets.fromExportJson(
            """{"format":"windtone.user_eq_presets","presets":[{"id":"u_abc_1","name":"空","bands":"","bass":0,"virtualizer":0,"loudness":0,"reverbId":"off"}]}""",
        )!!
        assertEquals(1, parsed.size)
        assertEquals("", parsed[0].bandsCsv)
        assertEquals("u_abc_1", parsed[0].id)
    }

    @Test
    fun `越界增益在创建解码导入时钳制`() {
        val over = UserEqPresets.create(
            "过", "0,0", bass = 9999, virtualizer = -50, loudness = 99999,
            reverbId = "off", nowMillis = 4L, salt = 3,
        )!!
        assertEquals(1000, over.bass)
        assertEquals(0, over.virtualizer)
        assertEquals(1500, over.loudness)

        val raw = listOf(
            "u_z_1", "钳", "10,-10", "2000", "-1", "3000", "hall",
        ).joinToString("\u001f")
        val decoded = UserEqPresets.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(1000, decoded[0].bass)
        assertEquals(0, decoded[0].virtualizer)
        assertEquals(1500, decoded[0].loudness)

        val fromJson = UserEqPresets.fromExportJson(
            """{"format":"windtone.user_eq_presets","presets":[{"id":"u_z_2","name":"钳","bands":"0","bass":5000,"virtualizer":-9,"loudness":9999,"reverbId":"x"}]}""",
        )!![0]
        assertEquals(1000, fromJson.bass)
        assertEquals(0, fromJson.virtualizer)
        assertEquals(1500, fromJson.loudness)

        val list = listOf(sample())
        val rewritten = UserEqPresets.rewrite(
            list, list[0].id, "1,2", 5000, -1, 9999, "off",
        )!!
        assertEquals(1000, rewritten[0].bass)
        assertEquals(0, rewritten[0].virtualizer)
        assertEquals(1500, rewritten[0].loudness)
    }

    @Test
    fun `JSON 与 Prefs 双重往返及边界增益一致`() {
        val edges = listOf(
            UserEqPresets.create("零", "", 0, 0, 0, "", nowMillis = 10L, salt = 1)!!,
            UserEqPresets.create(
                "满", "1500,-1500,0", 1000, 1000, 1500, "hall_medium",
                nowMillis = 11L, salt = 2,
            )!!,
            UserEqPresets.create(
                "负频", "-300,-100,50", 1, 2, 3, "off",
                nowMillis = 12L, salt = 3,
            )!!,
        )
        assertEquals(edges, UserEqPresets.decode(UserEqPresets.encode(edges)))
        val viaJson = UserEqPresets.fromExportJson(
            UserEqPresets.toExportJson(edges, exportedAtMillis = 99L),
        )!!
        assertEquals(edges, viaJson)
        val hop = UserEqPresets.fromExportJson(
            UserEqPresets.toExportJson(
                UserEqPresets.decode(UserEqPresets.encode(viaJson)),
            ),
        )!!
        assertEquals(edges, hop)
    }

    @Test
    fun `导入坏条计入 skipped 且 JSON 重复 id 保留先到`() {
        val good = sample("好", 5)
        val badBands = good.copy(id = "u_bad_1", name = "坏段", bandsCsv = "nope!")
        val badId = good.copy(id = "builtin", name = "假")
        val outcome = UserEqPresets.importPresets(
            emptyList(),
            listOf(good, badBands, badId),
            UserEqPresets.ImportMode.MERGE,
        )
        assertEquals(1, outcome.added)
        assertEquals(2, outcome.skipped)
        assertEquals(listOf(good), outcome.presets)

        val dups = UserEqPresets.fromExportJson(
            """{"format":"windtone.user_eq_presets","presets":[{"id":"u_dup_1","name":"先","bands":"1","bass":1,"virtualizer":0,"loudness":0,"reverbId":""},{"id":"u_dup_1","name":"后","bands":"2","bass":2,"virtualizer":0,"loudness":0,"reverbId":""}]}""",
        )!!
        assertEquals(1, dups.size)
        assertEquals("先", dups[0].name)
        assertEquals("1", dups[0].bandsCsv)
    }

    @Test
    fun `非法 bands 字符 create 与 rewrite 拒绝`() {
        assertNull(UserEqPresets.create("x", "1;2", 0, 0, 0, ""))
        assertNull(UserEqPresets.create("x", "1.5,0", 0, 0, 0, ""))
        assertNull(UserEqPresets.rewrite(listOf(sample()), sample().id, "a,b", 0, 0, 0, ""))
        assertTrue(UserEqPresets.validBands(""))
        assertTrue(UserEqPresets.validBands("0, -1, 2"))
        assertFalse(UserEqPresets.validBands("0+1"))
    }
}
