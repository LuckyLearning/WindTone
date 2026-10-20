package com.luckylearning.windtone.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.SongIndex
import com.luckylearning.windtone.data.db.MusicDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 播放统计的去重规则回归。
 *
 * 这条规则以前埋在 `PlaybackEngine` 的私有方法里，只能靠人读代码；抽成
 * [PlaybackStats] 后可以直接验证：同一首歌 15 秒内的重复切换不算新播放
 * （`replaceMediaItem` 回填封面也会触发 transition，不判别会把每首歌多计几次）。
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStatsTest {

    private lateinit var db: MusicDb
    private lateinit var scope: CoroutineScope
    private lateinit var stats: PlaybackStats

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        stats = PlaybackStats(PlaybackStore(db), SongIndex(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun repeatedTransitionWithin15sIsNotCountedAgain() {
        assertTrue("首次切到这首歌应计数", stats.shouldCountPlay("a"))
        assertFalse("15 秒内重复切回同一首不应重复计数", stats.shouldCountPlay("a"))
        assertTrue("换一首应计数", stats.shouldCountPlay("b"))
    }
}
