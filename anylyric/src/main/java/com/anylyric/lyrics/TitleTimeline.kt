package com.anylyric.lyrics

/** TITLE 通道的判定结果。 */
enum class TitleVerdict {
    /** 标题稳定，是真正的歌名 */
    STABLE,

    /** 标题高频变化且曲目指纹不变 —— 播放器把歌词写进了 TITLE（伪歌名） */
    PSEUDO,

    /** 证据不足（刚换歌、只有一两条记录） */
    UNKNOWN
}

/**
 * 记录 `onMetadataChanged` 里的 TITLE 变化，判定发布端是否在「逐行推送歌词」。
 *
 * 判据（三条同时成立）：
 *  1. 窗口内 TITLE 变化次数 >= [minChanges]；
 *  2. duration / mediaId / artist / 专辑 / 音轨未变（变了就是真换歌，不是逐行歌词）；
 *  3. 相邻变化间隔的中位数 <= [maxMedianGapMs]（歌词行通常 2~5 秒一句）。
 *
 * [minChanges] 定为 **2** 而不是 3：逐行歌词每 3~5 秒一句，要求 3 条意味着判定前
 * 至少空等 6~15 秒。两条不同标题 + 8 秒内变化，在「曲目指纹未变」的前提下已经
 * 基本只能是歌词流；真正的误报风险（播放器把播放状态拼进标题）由
 * [isTrivialVariant] 单独挡掉。
 *
 * 纯逻辑，无 Android 依赖，可直接 JVM 单测。
 */
class TitleTimeline(
    private val windowMs: Long = 15_000L,
    private val minChanges: Int = 2,
    private val maxMedianGapMs: Long = 8_000L
) {

    data class Event(val atMs: Long, val title: String, val identity: String)

    private val events = ArrayDeque<Event>()
    private var identityChanged: Boolean = false

    /**
     * 事件集合的版本号。增删事件才 +1。
     *
     * 判定函数以前每次回调都重算一遍中位数（`zipWithNext` + `sorted` 两个临时 List）。
     * 而它只在「事件集合变了」时才会有不同结果 —— 播放器一秒好几个回调，大部分
     * 时候事件集合一个字都没动，缓存一下就把这整段开销变成一次整数比较。
     */
    private var version = 0
    private var cachedVersion = -1
    private var cachedVerdict = TitleVerdict.UNKNOWN

    /**
     * 曲目指纹按字段保存，不拼字符串。
     *
     * 拼串版本每秒要构造一次 `"duration|mediaId|artist|extra"`，而这两个判断其实
     * 只是一组空值安全的比较 —— 真机上元数据回调一秒能来好几次，白拼的串最后全进 GC。
     */
    private var hasIdentity = false
    private var idDuration: Long = 0L
    private var idMediaId: String? = null
    private var idArtist: String? = null
    private var idExtra: String = ""

    /**
     * 记录一次元数据回调。返回本次之后的判定。
     * [mediaId] 允许为空：此时指纹退化为 duration + artist + [identityExtra]。
     * [identityExtra] 由调用方补充专辑／音轨等「同一首歌内不变」的字段，用于在
     * `duration` / `mediaId` 都缺失的播放器上尽量分辨换歌。
     */
    fun record(
        nowMs: Long,
        title: String?,
        durationMs: Long,
        mediaId: String?,
        artist: String?,
        identityExtra: String = ""
    ): TitleVerdict {
        val normalized = title?.trim().orEmpty()
        if (normalized.isEmpty()) return verdict(nowMs)

        val sameTrack = hasIdentity &&
            idDuration == durationMs &&
            idMediaId == mediaId &&
            idArtist == artist &&
            idExtra == identityExtra
        if (hasIdentity && !sameTrack) {
            // 真换歌：清空历史，避免把上一首的歌词行算进本首
            identityChanged = true
            events.clear()
            version++
        }
        hasIdentity = true
        idDuration = durationMs
        idMediaId = mediaId
        idArtist = artist
        idExtra = identityExtra

        val last = events.lastOrNull()
        if (last != null && isTrivialVariant(last.title, normalized)) return verdict(nowMs)

        // 指纹只在真正记一条事件时才拼（换行/换歌是低频事件，不是每次回调）
        events.addLast(Event(nowMs, normalized, identity()))
        version++
        trim(nowMs)
        return verdict(nowMs)
    }

    /** 读取并清除「刚发生真换歌」标记，供责任链清空 L1 缓冲。 */
    fun consumeIdentityChanged(): Boolean {
        val changed = identityChanged
        identityChanged = false
        return changed
    }

    fun reset() {
        events.clear()
        version++
        hasIdentity = false
        identityChanged = false
    }

    private fun identity(): String =
        "$idDuration|${idMediaId.orEmpty()}|${idArtist.orEmpty()}|$idExtra"

    fun snapshot(): List<Event> = events.toList()

    private fun trim(nowMs: Long) {
        var removed = false
        while (events.isNotEmpty() && nowMs - events.first().atMs > windowMs) {
            events.removeFirst()
            removed = true
        }
        if (removed) version++
    }

    private fun verdict(nowMs: Long): TitleVerdict {
        trim(nowMs)
        if (version == cachedVersion) return cachedVerdict
        cachedVersion = version
        cachedVerdict = computeVerdict()
        return cachedVerdict
    }

    private fun computeVerdict(): TitleVerdict {
        if (events.size < 2) return TitleVerdict.UNKNOWN
        if (events.size < minChanges) return TitleVerdict.STABLE

        val gaps = events.zipWithNext { a, b -> b.atMs - a.atMs }.sorted()
        val median = gaps[gaps.size / 2]
        return if (median <= maxMedianGapMs) TitleVerdict.PSEUDO else TitleVerdict.STABLE
    }

    companion object {
        /**
         * 判断第二个标题是否只是第一个的「后缀变体」，例如
         * `歌名` → `歌名 - 播放中`、`歌名` → `歌名 (Live)`。
         *
         * 这类变化是播放器在标题里附带播放状态，不是歌词行。若不排除，
         * [minChanges] = 2 时两条状态标题就能把判定误推到 PSEUDO，
         * 于是把歌名当歌词显示。
         */
        fun isTrivialVariant(a: String, b: String): Boolean {
            if (a == b) return true
            val short = if (a.length <= b.length) a else b
            val long = if (a.length <= b.length) b else a
            if (long.length - short.length > 8) return false
            return long.startsWith(short)
        }
    }
}
