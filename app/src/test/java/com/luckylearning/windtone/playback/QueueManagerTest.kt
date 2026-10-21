package com.luckylearning.windtone.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.QueueEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [QueueManager] 行为测试。
 *
 * 用 [FakeQueuePlayer] 顶替真实的 MediaController（记录所有调用），并用 [RecordingHost] 记录
 * 引擎侧的回调。由于 [QueueManager] 的 `act` 走的是注入的 `scope.launch`，
 * 这里给一个 [Dispatchers.Unconfined] 的 scope，使其同步执行到底，断言无需等待。
 *
 * 用 Robolectric 跑：需要构造 [Song]（`Uri.parse` 在纯 JVM 下是 Android 桩会返回 null）。
 */
@RunWith(RobolectricTestRunner::class)
class QueueManagerTest {

    private val toMediaItem: (Song) -> MediaItem = { s ->
        MediaItem.Builder()
            .setMediaId(s.stableKey)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).setArtist(s.title).build())
            .build()
    }

    private fun song(key: String) = Song(
        id = 0L,
        uri = Uri.parse("content://t/$key"),
        title = key,
        artist = null,
        album = null,
        durationMs = 1234L,
    )

    private fun item(id: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setArtist(id).build())
        .build()

    private fun fixture(songs: List<Song> = emptyList()): Pair<QueueManager, FakeQueuePlayer> {
        val player = FakeQueuePlayer()
        player.items.addAll(songs.map { toMediaItem(it) })
        player.mediaItemCount = player.items.size
        val host = RecordingHost()
        val manager = QueueManager(
            scope = CoroutineScope(Dispatchers.Unconfined),
            connect = { player },
            toMediaItem = toMediaItem,
            host = host,
        )
        player.host = host
        return manager to player
    }

    // ---------------------------------------------------------------- 起播

    @Test
    fun `playQueue 空列表不触碰播放器`() {
        val (m, p) = fixture()
        m.playQueue(emptyList(), 0)
        assertTrue("空队列不应有任何播放器调用", p.events.isEmpty())
    }

    @Test
    fun `playQueue 正常起播：setMediaItems + prepare + play + 同步状态 + 落库`() {
        val songs = listOf(song("A"), song("B"), song("C"))
        val (m, p) = fixture(songs)
        m.playQueue(songs, 1)

        assertEquals("setMediaItems 应收到 3 首且起始下标 1", "setMediaItems(3,1)", p.events.first())
        assertTrue(p.events.contains("prepare"))
        assertTrue(p.events.contains("play"))
        assertEquals(1, p.host.started.size)
        assertEquals(1, p.host.started[0].first)
        assertEquals(1234L, p.host.started[0].second)
        assertEquals(3, p.host.started[0].third)
        assertEquals(songs, p.host.currentQueue)
        assertEquals(1, p.host.persistCount)
        assertEquals(1, p.host.applyParamsCount)
    }

    @Test
    fun `playQueue 起始下标越界会被夹紧`() {
        val songs = listOf(song("A"), song("B"), song("C"))
        val (m, p) = fixture(songs)
        m.playQueue(songs, 99)
        assertEquals("setMediaItems(3,2)", p.events.first())
    }

    @Test
    fun `playShuffled 先落定「随机+列表循环」再起播`() {
        val songs = listOf(song("A"), song("B"), song("C"))
        val (m, p) = fixture(songs)
        m.playShuffled(songs)

        val repeatAt = p.events.indexOf("repeat=${Player.REPEAT_MODE_ALL}")
        val shuffleAt = p.events.indexOf("shuffle=true")
        val setAt = p.events.indexOf("setMediaItems(3,0)")
        assertTrue(repeatAt in 0 until setAt)
        assertTrue(shuffleAt in 0 until setAt)
        assertTrue(p.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, p.repeatMode)
    }

    // ---------------------------------------------------------------- 播放控制

    @Test
    fun `togglePlay 队列为空且有曲库时走「从曲库起播」`() {
        val (m, p) = fixture()
        p.host.library = listOf(song("X"))
        m.togglePlay()
        assertEquals(1, p.host.libraryPlaybackStarted)
        assertFalse("不应直接操作空播放器", p.events.contains("play"))
    }

    @Test
    fun `togglePlay 队列为空且没有曲库时不操作`() {
        val (m, p) = fixture()
        p.host.library = emptyList()
        m.togglePlay()
        assertEquals(0, p.host.libraryPlaybackStarted)
        assertTrue(p.events.isEmpty())
    }

    @Test
    fun `togglePlay 正在播放则暂停`() {
        val (m, p) = fixture(listOf(song("A")))
        p.isPlaying = true
        m.togglePlay()
        assertTrue(p.events.contains("pause"))
    }

    @Test
    fun `togglePlay 已暂停则播放`() {
        val (m, p) = fixture(listOf(song("A")))
        p.isPlaying = false
        m.togglePlay()
        assertTrue(p.events.contains("play"))
    }

    @Test
    fun `next 调 seekToNextMediaItem`() {
        val (m, p) = fixture(listOf(song("A")))
        m.next()
        assertTrue(p.events.contains("next"))
    }

    @Test
    fun `previous 播放超过 3 秒回到本曲开头`() {
        val (m, p) = fixture(listOf(song("A")))
        p.currentPosition = 5000L
        m.previous()
        assertTrue(p.events.contains("seekTo(0)"))
    }

    @Test
    fun `previous 播放不足 3 秒跳上一首`() {
        val (m, p) = fixture(listOf(song("A"), song("B")))
        p.currentPosition = 1000L
        m.previous()
        assertTrue(p.events.contains("prev"))
    }

    @Test
    fun `seekTo 会被时长夹紧`() {
        val (m, p) = fixture(listOf(song("A")))
        p.duration = 1000L
        m.seekTo(5000L)
        assertTrue(p.events.contains("seekTo(1000)"))
        assertEquals(1000L, p.host.lastProgress)
    }

    // ---------------------------------------------------------------- 模式轮换

    @Test
    fun `cyclePlayMode 顺序到列表循环`() {
        val (m, p) = fixture()
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.shuffleModeEnabled = false
        m.cyclePlayMode()
        assertEquals(Player.REPEAT_MODE_ALL, p.repeatMode)
        assertFalse(p.shuffleModeEnabled)
    }

    @Test
    fun `cyclePlayMode 列表循环到单曲循环`() {
        val (m, p) = fixture()
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.shuffleModeEnabled = false
        m.cyclePlayMode()
        assertEquals(Player.REPEAT_MODE_ONE, p.repeatMode)
        assertFalse(p.shuffleModeEnabled)
    }

    @Test
    fun `cyclePlayMode 单曲循环到随机（先设 repeat 再开随机）`() {
        val (m, p) = fixture()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.shuffleModeEnabled = false
        m.cyclePlayMode()
        val repeatAt = p.events.indexOf("repeat=${Player.REPEAT_MODE_ALL}")
        val shuffleAt = p.events.indexOf("shuffle=true")
        assertTrue(repeatAt in 0 until shuffleAt)
        assertEquals(Player.REPEAT_MODE_ALL, p.repeatMode)
        assertTrue(p.shuffleModeEnabled)
    }

    @Test
    fun `cyclePlayMode 随机到顺序（先关随机再清 repeat）`() {
        val (m, p) = fixture()
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.shuffleModeEnabled = true
        m.cyclePlayMode()
        val shuffleAt = p.events.indexOf("shuffle=false")
        val repeatAt = p.events.indexOf("repeat=${Player.REPEAT_MODE_OFF}")
        assertTrue(shuffleAt in 0 until repeatAt)
        assertFalse(p.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, p.repeatMode)
    }

    // ---------------------------------------------------------------- 入队

    @Test
    fun `playNext 新歌插到当前曲之后并提示`() {
        val a = song("A"); val b = song("B"); val c = song("C")
        val (m, p) = fixture(listOf(a, b))
        p.currentMediaItemIndex = 0
        m.playNext(c)
        assertTrue(p.events.contains("addAt(1)"))
        assertEquals(3, p.host.queueSize)
        assertEquals("已加入播放队列：C", p.host.messages.last())
        assertEquals(1, p.host.persistCount)
    }

    @Test
    fun `playNext 已在队列里的歌改为移动而非新增`() {
        val a = song("A"); val b = song("B")
        val (m, p) = fixture(listOf(a, b))
        p.currentMediaItemIndex = 0
        m.playNext(b) // b 的 stableKey 已在 items 里
        assertFalse(p.events.any { it.startsWith("addAt") })
        assertTrue(p.events.any { it.startsWith("move") })
    }

    @Test
    fun `enqueue 跳过已在队列的歌`() {
        val a = song("A"); val b = song("B")
        val (m, p) = fixture(listOf(a))
        m.enqueue(listOf(a, b))
        assertTrue(p.events.contains("addMany(1)")) // 只新增 B
        assertEquals(2, p.items.size)
        assertEquals(1, p.host.persistCount)
        assertEquals(2, p.host.entries.size) // enqueue 末尾 refreshQueue
    }

    @Test
    fun `enqueue 全部已在队列里则提示且不新增`() {
        val a = song("A"); val b = song("B")
        val (m, p) = fixture(listOf(a, b))
        m.enqueue(listOf(a, b))
        assertEquals("所选歌曲都在播放队列里了", p.host.messages.last())
        assertFalse(p.events.any { it.startsWith("addMany") })
    }

    // ---------------------------------------------------------------- 队列抽屉

    @Test
    fun `refreshQueue 读成带 isCurrent 标记的条目`() {
        val a = song("A"); val b = song("B")
        val (m, p) = fixture(listOf(a, b))
        p.currentMediaItemIndex = 1
        m.refreshQueue()
        assertEquals(2, p.host.entries.size)
        assertEquals(a.stableKey, p.host.entries[0].mediaId)
        assertEquals(b.stableKey, p.host.entries[1].mediaId)
        assertFalse(p.host.entries[0].isCurrent)
        assertTrue(p.host.entries[1].isCurrent)
    }

    @Test
    fun `jumpToQueue 跳转并播放且落库`() {
        val (m, p) = fixture(listOf(song("A"), song("B"), song("C")))
        m.jumpToQueue(2)
        assertTrue(p.events.contains("seekDefault(2)"))
        assertTrue(p.events.contains("play"))
        assertEquals(1, p.host.persistCount)
    }

    @Test
    fun `removeQueueItem 删除并刷新`() {
        val (m, p) = fixture(listOf(song("A"), song("B")))
        m.removeQueueItem(1)
        assertTrue(p.events.contains("remove(1)"))
        assertEquals(1, p.items.size)
        assertEquals(1, p.host.persistCount) // removeQueueItem 自身 saveQueue；refreshQueue 不落库
    }

    @Test
    fun `moveQueueItem 合法范围才移动`() {
        val (m, p) = fixture(listOf(song("A"), song("B"), song("C")))
        m.moveQueueItem(0, 2)
        assertTrue(p.events.contains("move(0,2)"))
        assertEquals(1, p.host.persistCount)
        assertEquals(3, p.host.entries.size)
    }

    @Test
    fun `moveQueueItem 越界不操作`() {
        val (m, p) = fixture(listOf(song("A"), song("B")))
        m.moveQueueItem(0, 9)
        assertFalse(p.events.any { it.startsWith("move") })
        assertEquals(0, p.host.persistCount)
    }

    // ================================================================ 替身

    class FakeQueuePlayer : QueuePlayer {
        var items = mutableListOf<MediaItem>()
        override var mediaItemCount: Int = 0
        override var currentMediaItemIndex: Int = 0
        override var currentPosition: Long = 0L
        override var duration: Long = 0L
        override var isPlaying: Boolean = false
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
            get() = field
            set(value) { field = value; events.add("repeat=$value") }
        override var shuffleModeEnabled: Boolean = false
            get() = field
            set(value) { field = value; events.add("shuffle=$value") }

        val events = mutableListOf<String>()
        lateinit var host: RecordingHost

        override fun getMediaItemAt(index: Int): MediaItem = items[index]
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            this.items.clear(); this.items.addAll(items)
            this.currentMediaItemIndex = startIndex
            this.mediaItemCount = items.size
            events.add("setMediaItems(${items.size},$startIndex)")
        }
        override fun prepare() { events.add("prepare") }
        override fun play() { isPlaying = true; events.add("play") }
        override fun pause() { isPlaying = false; events.add("pause") }
        override fun seekTo(ms: Long) { currentPosition = ms; events.add("seekTo($ms)") }
        override fun seekToNextMediaItem() { events.add("next") }
        override fun seekToPreviousMediaItem() { events.add("prev") }
        override fun seekToDefaultPosition(index: Int) { currentMediaItemIndex = index; events.add("seekDefault($index)") }
        override fun moveMediaItem(from: Int, to: Int) {
            val it = items.removeAt(from); items.add(to, it); events.add("move($from,$to)")
        }
        override fun addMediaItem(index: Int, item: MediaItem) {
            items.add(index, item); mediaItemCount = items.size; events.add("addAt($index)")
        }
        override fun addMediaItems(items: List<MediaItem>) {
            this.items.addAll(items); mediaItemCount = this.items.size; events.add("addMany(${items.size})")
        }
        override fun removeMediaItem(index: Int) {
            items.removeAt(index); mediaItemCount = items.size; events.add("remove($index)")
        }
    }

    class RecordingHost : QueueManager.QueueHost {
        val currentQueue = mutableListOf<Song>()
        var queueSize: Int = -1
        val entries = mutableListOf<QueueEntry>()
        var lastProgress: Long = -1
        val messages = mutableListOf<String>()
        var persistCount = 0
        var applyParamsCount = 0
        val started = mutableListOf<Triple<Int, Long, Int>>()
        var library: List<Song> = emptyList()
        var libraryPlaybackStarted = 0

        override fun setCurrentQueue(queue: List<Song>) { currentQueue.clear(); currentQueue.addAll(queue) }
        override fun notifyQueueSize(size: Int) { queueSize = size }
        override fun notifyQueueEntries(e: List<QueueEntry>) { entries.clear(); entries.addAll(e) }
        override fun updateProgress(positionMs: Long) { lastProgress = positionMs }
        override fun postMessage(text: String) { messages.add(text) }
        override fun persistQueue() { persistCount++ }
        override fun applyPlaybackParams() { applyParamsCount++ }
        override fun onPlaybackStarted(currentIndex: Int, durationMs: Long, queueSize: Int) {
            started.add(Triple(currentIndex, durationMs, queueSize))
        }
        override fun currentLibrary(): List<Song> = library
        override fun startLibraryPlayback() { libraryPlaybackStarted++ }
    }
}
