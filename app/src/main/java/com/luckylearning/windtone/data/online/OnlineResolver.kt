package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.data.online.source.MusicSource
import com.luckylearning.windtone.data.online.source.OnlineSourceRegistry
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次成功取址的结果。
 *
 * [quality] 是**实际用上的档位**，不是用户选的那个 —— 用户选 flac 而源只有 320k 时，
 * 播放页必须显示 320k，否则「我选了无损怎么听着像 128」永远说不清。
 */
data class ResolvedUrl(
    val url: String,
    val quality: Quality,
    val sourceId: String,
)

/**
 * 取到的直链的进程内备忘录（LRU）。
 *
 * 与 [com.luckylearning.windtone.playback.CloudUrlMemo] 同构，也同因：
 * 播放项跨进程边界传给 `MediaSessionService` 时 `LocalConfiguration` 会被丢掉，
 * 服务端 `LibraryBrowser.resolveOne` 只能凭 mediaId 重建地址。引擎刚取到的直链
 * 若不先记进来，resolve 会把它重建回占位地址 —— 补链等于白做（云盘侧踩过的坑）。
 *
 * 直链有短时效（分钟到小时级），本表只做**短期**复用，不做持久化。
 */
object OnlineUrlMemo {

    private const val MAX = 256

    /** accessOrder=true：命中即移到尾部，淘汰最久未用的 */
    private val map = LinkedHashMap<String, ResolvedUrl>(16, 0.75f, true)

    @Synchronized
    fun remember(stableKey: String, resolved: ResolvedUrl) {
        map[stableKey] = resolved
        while (map.size > MAX) map.remove(map.keys.first())
    }

    @Synchronized
    fun peek(stableKey: String): ResolvedUrl? = map[stableKey]

    /** 失效（401/403 或判定过期）时清掉，下次强制重取 */
    @Synchronized
    fun forget(stableKey: String) {
        map.remove(stableKey)
    }

    @Synchronized
    fun clear() = map.clear()
}

/**
 * 在线曲目的**唯一取址入口**。
 *
 * 三条规则（任何调用点都必须遵守，否则会出现「同一个地址取两次、两次结果不同」）：
 *  1. 先查 [OnlineUrlMemo] —— 重播 / 回切 / 队列恢复都该命中，命中即 0 次网络请求；
 *  2. 音质按 [qualityChain] 逐级**下降**（不越级上升：用户选了 320k 就不该偷偷给 flac 白烧流量）；
 *  3. 每一步失败就换下一档，全失败返回 null —— **不抛异常**，让播放层按「暂时取不到」处理
 *     （跳下一首 / 提示重试），而不是崩在协程里。
 *
 * 与洛雪的区别：洛雪在 `musicUrl` 阶段只试一次、失败了由 UI 提示；这里多一层音质降级，
 * 因为车机弱网 + 各家源对高档位限流的情况下，「降一级能响」比「报错让用户自己换音质」有用得多。
 */
object OnlineResolver {

    /** 单次取址上限。超过就当这个音质不可用，换下一档 */
    const val TIMEOUT_MS = 8_000L

    /**
     * 取一条可播地址。
     *
     * @param preferred 用户偏好的音质；null = 用音源支持的最高档
     */
    suspend fun resolve(track: OnlineTrack, preferred: Quality? = null): ResolvedUrl? {
        OnlineUrlMemo.peek(track.stableKey)?.let { return it }
        val source = OnlineSourceRegistry.find(track.sourceId) ?: return null

        for (quality in qualityChain(source, track, preferred)) {
            val url = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { source.resolveUrl(track, quality) }.getOrNull()
            }
            if (url.isPlayable()) {
                val resolved = ResolvedUrl(url!!.trim(), quality, track.sourceId)
                OnlineUrlMemo.remember(track.stableKey, resolved)
                return resolved
            }
        }
        return null
    }

    /**
     * 已知稳定键时取址（曲库点播 / 队列恢复 / 车机面板都走这条）。
     *
     * 需要 [OnlineTrackStore] 才能拿到 `raw`：音源取直链要靠 search 返回的完整信息，
     * 光有 `lx:kw:123` 是取不到的（见 [OnlineTrack] 的注释）。
     */
    suspend fun resolveKey(
        stableKey: String,
        store: OnlineTrackStore,
        preferred: Quality? = null,
    ): ResolvedUrl? {
        OnlineUrlMemo.peek(stableKey)?.let { return it }
        val track = store.get(stableKey) ?: return null
        return resolve(track, preferred)
    }

    /** 取歌词文本（不支持 / 失败返回 null，静默降级到「无歌词」） */
    suspend fun lyric(track: OnlineTrack): String? {
        val source = OnlineSourceRegistry.find(track.sourceId) ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { source.lyric(track) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    /** 取封面地址（不支持 / 失败返回 null，静默降级到占位图） */
    suspend fun cover(track: OnlineTrack): String? {
        val source = OnlineSourceRegistry.find(track.sourceId) ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { source.cover(track) }.getOrNull()
        }?.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 音质降级链：从**用户偏好档位**开始，按「由高到低」给出剩余档位。
     *
     * 例：源支持 [flac24bit, flac, 320k]，用户选 flac → [flac, 320k]；
     * 用户什么都没选 → 从最高档开始 → [flac24bit, flac, 320k]。
     *
     * 为什么不在用户没选时直接取 128k（最省流量）：听感差异太大，且用户既然点了在线播放
     * 就是想要「能听」，默认给最好的、失败再降，比默认给最差的、让用户自己升更符合预期。
     */
    internal fun qualityChain(
        source: MusicSource,
        track: OnlineTrack,
        preferred: Quality?,
    ): List<Quality> {
        // 曲目自带的档位优先（同一首歌在不同专辑下发售的档位可能不同），空则用音源全集
        val supported = track.qualities.ifEmpty { source.qualities }.toSet()
        val ordered = Quality.BY_QUALITY.filter { it in supported }
        if (ordered.isEmpty()) return emptyList()
        if (preferred == null) return ordered
        val from = ordered.indexOf(preferred).let { if (it >= 0) it else 0 }
        return ordered.drop(from)
    }

    /** 只接受 http(s)：脚本返回本地路径 / data URI / 空串都算失败 */
    private fun String?.isPlayable(): Boolean =
        this != null && this.length <= 4096 && startsWith("http", ignoreCase = true)
}
