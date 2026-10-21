package com.luckylearning.windtone.data.baidu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 直链响应解析（v1.1.40）。
 *
 * 这一层的错误**不抛异常、`errno` 还是 0**，表现就是「目录能看、点播放说没有直链」。
 * 2026-10-21 真机就是这样：`/xpan/multimedia?method=filemetas` 把记录放在 `list`，
 * 而第一版只读 `info`，于是永远拿到空表。所以三种形态都要有用例钉住。
 *
 * ⚠️ 必须挂 [RobolectricTestRunner]：纯 JVM 单测用的是 android.jar 的 mockable `org.json`，
 * 所有方法返回默认值，`optJSONArray` 恒为 null —— 那样测出来的「通过」毫无意义。
 */
@RunWith(RobolectricTestRunner::class)
class BaiduLinkParseTest {

    private fun json(text: String) = JSONObject(text)

    @Test
    fun `multimedia 形态的 list 能被解析`() {
        val r = BaiduLinkParse.links(
            json(
                """
                {"errno":0,"list":[
                  {"fs_id":111,"dlink":"https://d.pcs.baidu.com/file/a?sign=1"},
                  {"fs_id":222,"dlink":"https://d.pcs.baidu.com/file/b?sign=2"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(2, r.size)
        assertEquals("https://d.pcs.baidu.com/file/a?sign=1", r[111L])
    }

    @Test
    fun `老接口的 info 数组也能解析`() {
        val r = BaiduLinkParse.links(
            json("""{"errno":0,"info":[{"fs_id":333,"dlink":"http://d.pcs.baidu.com/f"}]}"""),
        )
        assertEquals(mapOf(333L to "http://d.pcs.baidu.com/f"), r)
    }

    @Test
    fun `info 是对象映射时按 key 兜住 fs_id`() {
        val r = BaiduLinkParse.links(
            json("""{"errno":0,"info":{"444":{"dlink":"https://d.pcs.baidu.com/g"}}}"""),
        )
        assertEquals("https://d.pcs.baidu.com/g", r[444L])
    }

    @Test
    fun `空串与非 http 的 dlink 不算可播地址`() {
        val r = BaiduLinkParse.links(
            json(
                """
                {"errno":0,"list":[
                  {"fs_id":1,"dlink":""},
                  {"fs_id":2,"dlink":"no permission"},
                  {"fs_id":3}
                ]}
                """.trimIndent(),
            ),
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `记录数能区分没数据与没直链`() {
        assertEquals(2, BaiduLinkParse.recordCount(json("""{"list":[{"fs_id":1},{"fs_id":2}]}""")))
        assertEquals(1, BaiduLinkParse.recordCount(json("""{"info":[{"fs_id":1}]}""")))
        assertEquals(1, BaiduLinkParse.recordCount(json("""{"info":{"9":{}}}""")))
        assertEquals(0, BaiduLinkParse.recordCount(json("""{"errno":0}""")))
    }

    @Test
    fun `全部拿到时不产生提示`() {
        assertEquals("", BaiduLinkParse.note(2, mapOf(1L to "u", 2L to "u"), 2))
    }

    @Test
    fun `零记录时说清是接口没返回资料`() {
        val note = BaiduLinkParse.note(2, emptyMap(), 0)
        // 必须提示「0 条记录」，并给出可操作的方向（放到应用目录 / 先点开一次）
        assertTrue(note.contains("0 条记录"))
        assertTrue(note.contains("我的应用数据"))
    }

    @Test
    fun `部分失败时给出缺的个数`() {
        val note = BaiduLinkParse.note(3, mapOf(1L to "u"), 3)
        assertTrue(note.contains("2 个"))
    }
}
