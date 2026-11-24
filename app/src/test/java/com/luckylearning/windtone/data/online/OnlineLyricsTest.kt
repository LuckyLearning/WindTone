package com.luckylearning.windtone.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在线歌词与封面的纯逻辑回归。
 *
 * 只钉「能被一次改动弄坏、又不会被编译发现」的部分：时间戳格式、酷狗封面模板、
 * QQ 签名取样。真正能不能取到词要靠真机（各接口的字段随时可能变），不在这里伪造。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineLyricsTest {

    // ---------------------------------------------------------------- 酷我时间戳

    /**
     * 酷我的 `lrclist[].time` 是**秒的字符串**（`"22.05"`），而 LRC 要求 `[mm:ss.xx]`。
     * 曾把秒直接拼进方括号，整份歌词成了 `[22.05]`，播放器一行都定位不到。
     */
    @Test
    fun formatsKwSecondsIntoLrcTimestamp() {
        assertEquals("[00:00.00]", OnlineLyrics.formatStamp(0.0))
        assertEquals("[00:22.05]", OnlineLyrics.formatStamp(22.05))
        assertEquals("[01:00.00]", OnlineLyrics.formatStamp(60.0))
        assertEquals("[03:19.99]", OnlineLyrics.formatStamp(199.99))
    }

    /** 接口偶发返回负数（-1 表示无时间轴），不能拼出 `[-1:-1.00]` 这种非法标签 */
    @Test
    fun clampsNegativeSecondsToZero() {
        assertEquals("[00:00.00]", OnlineLyrics.formatStamp(-1.0))
    }

    // ---------------------------------------------------------------- 酷狗封面

    /**
     * 酷狗搜索结果的 `Image` 是**带 `{size}` 占位的完整 URL**，原样当图片地址下载会 404。
     */
    @Test
    fun expandsKugouCoverTemplate() {
        val raw = "http://imge.kugou.com/stdmusic/{size}/20250125/20250125121901851911.jpg"
        assertEquals(
            "https://imge.kugou.com/stdmusic/480/20250125/20250125121901851911.jpg",
            BuiltInSearch.kgCover(raw),
        )
    }

    /** 已经是具体地址时不改动（只升 https）；空 / null 一律 null，交给下游现取 */
    @Test
    fun keepsConcreteKugouCoverAndDropsEmpty() {
        assertEquals(
            "https://imge.kugou.com/stdmusic/20250125/a.jpg",
            BuiltInSearch.kgCover("http://imge.kugou.com/stdmusic/20250125/a.jpg"),
        )
        assertNull(BuiltInSearch.kgCover(""))
        assertNull(BuiltInSearch.kgCover(null))
    }

    // ---------------------------------------------------------------- QQ 签名

    /**
     * 索引表里含 40，而 SHA-1 hex 只有 40 个字符（下标 0..39）。
     * 洛雪是 JS，越界得到 `undefined` 且被 `join('')` 吞成空串；
     * Kotlin 直接取下标会抛 StringIndexOutOfBoundsException —— 之前 QQ 搜索
     * 「接口正常但一首都没有」就是因为签名计算先崩了，异常被重试吞掉。
     */
    @Test
    fun qqSignDoesNotThrowOnOutOfRangeIndex() {
        val sign = BuiltInSearch.zzcSign("""{"comm":{"ct":19}}""")
        assertTrue("应带 zzc 前缀：$sign", sign.startsWith("zzc"))
        assertFalse("必须全小写：$sign", sign.any { it.isUpperCase() })
        // base64 段被刻意裁掉 + / = 三个字符（洛雪原样如此），出现它们就是裁剪失效
        assertFalse("不应含 base64 特殊字符：$sign", sign.contains('+') || sign.contains('/') || sign.contains('='))
    }

    /** 同一输入必须稳定；不同输入必须不同（取样一旦错位，QQ 会返回 code=2000） */
    @Test
    fun qqSignIsStableAndInputSensitive() {
        val a1 = BuiltInSearch.zzcSign("""{"q":"a"}""")
        val a2 = BuiltInSearch.zzcSign("""{"q":"a"}""")
        val b = BuiltInSearch.zzcSign("""{"q":"b"}""")
        assertEquals(a1, a2)
        assertFalse(a1 == b)
    }
}
