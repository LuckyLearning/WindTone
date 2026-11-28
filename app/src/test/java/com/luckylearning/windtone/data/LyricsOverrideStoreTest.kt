package com.luckylearning.windtone.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 手动选定的歌词按 stableKey 存取；清掉后回到自动链路。 */
@RunWith(RobolectricTestRunner::class)
class LyricsOverrideStoreTest {

    private lateinit var context: Context
    private val key = "lx:kw:228908"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lyrics_override", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("lyrics_override", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `按稳定键存取并清除`() {
        assertNull(LyricsOverrideStore.get(context, key))
        LyricsOverrideStore.put(context, key, "[00:01.00]测试")
        assertEquals("[00:01.00]测试", LyricsOverrideStore.get(context, key))

        LyricsOverrideStore.clear(context, key)
        assertNull(LyricsOverrideStore.get(context, key))

        LyricsOverrideStore.put(context, key, "另一份")
        LyricsOverrideStore.clearAll(context)
        assertNull(LyricsOverrideStore.get(context, key))
    }

    @Test
    fun `空白正文不覆盖原值`() {
        LyricsOverrideStore.put(context, key, "有效歌词")
        LyricsOverrideStore.put(context, key, "   ")
        // 空白正文不覆盖已选好的歌词，避免一次空结果把用户的选择冲掉
        assertEquals("有效歌词", LyricsOverrideStore.get(context, key))
    }
}
