package com.luckylearning.windtone.lyrics

import androidx.media3.common.Player
import com.luckylearning.windtone.state.PlayerUiState

/**
 * 把当前歌词行写进 MediaSession 当前项的 [androidx.media3.common.MediaMetadata.description]
 * （Legacy `METADATA_KEY_DISPLAY_DESCRIPTION` / SYNOPSIS），供通知栏与锁屏展示。
 *
 * ## 为什么用 replaceMediaItem
 * Media3 会话元数据来自 Player 队列项；没有「只改 description」的公开 API。
 * 对 index 做 `replaceMediaItem` 只换 metadata、不换 URI 时，播放位置保持，
 * 但会触发 `onMediaItemTransition(REASON_PLAYLIST_CHANGED)`——因此引擎侧必须
 * 看 [isUpdating]，跳过清 A-B / 片头跳过 / 重载歌词等切歌逻辑。
 *
 * ## 去重（资源：勿每 ticker 推 Binder）
 * 1. **文案去重**：同一 SYNOPSIS 不重复 `replaceMediaItem`；
 * 2. **时间窗跳过**：同一行有效期内（本句起点～下一句起点）直接 return，
 *    连 [LockScreenLyrics.current] / resolver 都不跑——ticker 仍 500ms，
 *    但锁屏路径只在换句、seek 越界、切歌时动刀。
 * 切歌时 [reset] 清缓存，下一 tick 再发。
 */
object LockScreenLyricPublisher {

    @Volatile
    private var lastPublished: String? = null

    /** 当前持有文案对应的调整后时间轴区间 [from, until)（已含 lyricOffset） */
    @Volatile
    private var holdFromAdjustedMs: Long = Long.MIN_VALUE

    @Volatile
    private var holdUntilAdjustedMs: Long = -1L

    /**
     * 正在因歌词刷新而 replaceMediaItem。引擎的 `onMediaItemTransition` 见到此标志应直接 return。
     */
    @Volatile
    var isUpdating: Boolean = false
        private set

    /** 切到另一首时调用，避免旧歌词粘在新曲上、也避免误判「文案未变」跳过首帧。 */
    fun reset() {
        lastPublished = null
        holdFromAdjustedMs = Long.MIN_VALUE
        holdUntilAdjustedMs = -1L
        originalTitle = null
        originalArtist = null
    }

    /** 开关切换后调用：下一 tick 强制重发一次（否则「文案未变」会直接 return） */
    fun invalidate() {
        lastPublished = null
    }

    // ---- 车载蓝牙歌词（实验）：占用 AVRCP 的 TITLE 字段 ----
    //
    // 蓝牙车机只传 title / artist / album / duration，不传 description。
    // 所以要让歌词出现在车机屏 / 仪表上，唯一可行的通用通道是把**当前歌词行**
    // 写进 title，把「歌名 · 歌手」挤到 artist。这不优雅，但不需要任何白名单、
    // 不依赖 DiLink，对任意车机都成立。默认关，放在实验室里。
    //
    // 原始标题只在「首次接管前」拍一次（之后 title 就已经是歌词了）。
    @Volatile
    private var originalTitle: CharSequence? = null

    @Volatile
    private var originalArtist: CharSequence? = null

    /**
     * 按当前播放位置刷新 SYNOPSIS。应在进度 ticker 里调用（与悬浮歌词同频即可）。
     * 无歌词时清空 description，通知栏回落默认艺人行。
     *
     * 只有文案相对上次**真的变了**才 `setMediaMetadata` / `replaceMediaItem`。
     */
    fun publish(player: Player, state: PlayerUiState, positionMs: Long, btCarTitle: Boolean = false) {
        val offset = state.lyricOffsetMs
        val adjusted = positionMs + offset
        // 同一句时间窗内：不解析、不碰 Player（seek 回退/快进越界则窗外，会重算）
        if (lastPublished != null &&
            adjusted >= holdFromAdjustedMs &&
            adjusted < holdUntilAdjustedMs
        ) {
            return
        }

        val display = LockScreenLyrics.current(
            lyrics = state.lyrics,
            positionMs = positionMs,
            offsetMs = offset,
            title = state.current?.title.orEmpty(),
            artist = state.current?.artist.orEmpty(),
        )
        // 有歌词：写入当前行；无歌词：用空串表示「清空 description」
        val want = if (display.fromLyrics) display.text else ""
        rememberHoldWindow(state.lyrics, adjusted)

        // 蓝牙标题目标：AVRCP 长度有限（常见 255 字节），中文一字符 3 字节，取 40 字符
        val btWanted = if (btCarTitle) want.take(BT_TITLE_MAX_CHARS) else ""
        // 文案没变、且标题也没「该变没变」→ 什么都不用做。
        // 后半句是必须的：开关中途打开/关闭时 want 不变，但 title 需要切换/还原。
        if (want == lastPublished && !btDirty(btWanted)) return

        val index = player.currentMediaItemIndex
        if (index < 0) return
        val item = player.getMediaItemAt(index)
        val old = item.mediaMetadata
        val oldDesc = old.description?.toString().orEmpty()
        if (oldDesc == want && !btDirty(btWanted)) {
            lastPublished = want
            return
        }

        val builder = old.buildUpon().setDescription(want.ifEmpty { null })
        if (btWanted.isNotEmpty()) {
            // 接管 title 前先拍原始文案（每首只拍一次；reset() 清掉）
            if (originalTitle == null) {
                originalTitle = old.title
                originalArtist = old.artist
            }
            val origTitle = originalTitle?.toString().orEmpty()
            val origArtist = originalArtist?.toString().orEmpty()
            builder.setTitle(btWanted)
            builder.setArtist(if (origArtist.isBlank()) origTitle else "$origTitle · $origArtist")
        } else if (originalTitle != null) {
            // 无歌词 / 开关关：把 title/artist 还回去，避免车机永远停在上一句
            builder.setTitle(originalTitle).setArtist(originalArtist)
            if (!btCarTitle) {
                originalTitle = null
                originalArtist = null
            }
        }
        val newMeta = builder.build()
        isUpdating = true
        try {
            player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(newMeta).build())
            lastPublished = want
            btApplied = btWanted
        } finally {
            isUpdating = false
        }
    }

    /** 当前实际写进 title 的歌词（空 = 未接管）；诊断用 */
    @Volatile
    private var btApplied: String = ""

    /** 需要重发吗：目标歌词与已写入的不一致（含「应该还原但还写着歌词」） */
    private fun btDirty(btWanted: String): Boolean = btWanted != btApplied

    private const val BT_TITLE_MAX_CHARS = 40

    /**
     * 记下「这一句还能撑到何时」。纯数据，供单测与 [publish] 共用口径。
     *
     * - 同步歌词且落在某句：区间 = [lineIntervalMs]；
     * - 前奏（indexAt < 0）：撑到第一句起点；
     * - 无时间轴：整曲内文案不变，用极大 until，切歌 [reset] 清掉。
     */
    internal fun rememberHoldWindow(lyrics: Lyrics, adjustedMs: Long) {
        if (lyrics.isEmpty || !lyrics.synced) {
            holdFromAdjustedMs = Long.MIN_VALUE
            holdUntilAdjustedMs = Long.MAX_VALUE / 4
            return
        }
        val i = lyrics.indexAt(adjustedMs)
        if (i < 0) {
            val first = lyrics.lines.firstOrNull()?.timeMs ?: (Long.MAX_VALUE / 4)
            holdFromAdjustedMs = Long.MIN_VALUE
            holdUntilAdjustedMs = first
            return
        }
        val interval = lyrics.lineIntervalMs(i)
        if (interval == null) {
            holdFromAdjustedMs = Long.MIN_VALUE
            holdUntilAdjustedMs = -1L
            return
        }
        holdFromAdjustedMs = interval.first
        // until 与下一句起点重合；用 < until，换句瞬间必重算
        holdUntilAdjustedMs = interval.second
    }

    /** 测试可见：当前时间窗 until（未持有时为 -1） */
    internal fun debugHoldUntil(): Long = holdUntilAdjustedMs

    /** 测试可见：当前时间窗 from */
    internal fun debugHoldFrom(): Long = holdFromAdjustedMs
}
