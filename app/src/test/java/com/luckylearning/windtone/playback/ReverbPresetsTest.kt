package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置混响档位表测试。
 *
 * 重点守两件事：
 *  ① **id 与名字唯一**（id 落盘，改了老用户的已选混响会丢）；
 *  ② **平台档位值与原厂实现一一对应** —— 这张表是纯数据、不 import 平台类，
 *     所以档位值只能靠测试钉住（写错了编译期看不出来，只会"选了小房间听起来像大厅"）。
 */
class ReverbPresetsTest {

    @Test
    fun `七个档位且 id 与名字唯一`() {
        assertEquals(7, ReverbPresets.ALL.size)
        assertEquals(ReverbPresets.ALL.size, ReverbPresets.ALL.map { it.id }.distinct().size)
        assertEquals(ReverbPresets.ALL.size, ReverbPresets.ALL.map { it.name }.distinct().size)
    }

    @Test
    fun `首项是关闭且平台档位为 0`() {
        assertEquals(ReverbPresets.OFF, ReverbPresets.ALL.first())
        assertEquals(0.toShort(), ReverbPresets.OFF.platform)
    }

    /**
     * `PresetReverb` 的档位常量：NONE=0 / SMALLROOM=1 / MEDIUMROOM=2 / LARGEROOM=3 /
     * MEDIUMHALL=4 / LARGEHALL=5 / PLATE=6。顺序错了听感就全乱，所以逐个点名。
     */
    @Test
    fun `平台档位与原厂常量顺序一致`() {
        assertEquals(1.toShort(), ReverbPresets.SMALL_ROOM.platform)
        assertEquals(2.toShort(), ReverbPresets.MEDIUM_ROOM.platform)
        assertEquals(3.toShort(), ReverbPresets.LARGE_ROOM.platform)
        assertEquals(4.toShort(), ReverbPresets.MEDIUM_HALL.platform)
        assertEquals(5.toShort(), ReverbPresets.LARGE_HALL.platform)
        assertEquals(6.toShort(), ReverbPresets.PLATE.platform)
    }

    @Test
    fun `平台档位互不重复`() {
        assertEquals(ReverbPresets.ALL.size, ReverbPresets.ALL.map { it.platform }.distinct().size)
    }

    @Test
    fun `每个档位都有一句描述`() {
        ReverbPresets.ALL.forEach {
            assertTrue("档位 ${it.id} 缺描述", it.desc.isNotBlank())
        }
    }

    @Test
    fun `byId 能找回每个档位且未知 id 返回 null`() {
        ReverbPresets.ALL.forEach { assertEquals(it, ReverbPresets.byId(it.id)) }
        assertNull(ReverbPresets.byId("no_such_reverb"))
        assertNull(ReverbPresets.byId("")) // 空串 = 从未选过，不是档位
    }

    /** 「动过」的判据：只有真的选了某档混响才算，没选 / 选了关闭都不算 */
    @Test
    fun `只有非关闭档位才算动过`() {
        assertFalse(ReverbPresets.isCustomized(null))
        assertFalse(ReverbPresets.isCustomized(ReverbPresets.OFF))
        assertTrue(ReverbPresets.isCustomized(ReverbPresets.SMALL_ROOM))
    }
}
