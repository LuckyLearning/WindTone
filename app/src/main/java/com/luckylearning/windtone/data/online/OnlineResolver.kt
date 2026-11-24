package com.luckylearning.windtone.data.online

import androidx.compose.runtime.mutableIntStateOf
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
 *
 * [sourceName] / [ownerId] 是**实际出力的那份脚本**：同一平台可能装了好几份音源，
 * 只有记下来，用户才能在「信息」页看到「酷我音乐 · fish 源」而不是一个笼统的平台名。
 */
data class ResolvedUrl(
    val url: String,
    val quality: Quality,
    val sourceId: String,
    /** 提供直链的音源名（`酷我音乐 · fish 源`）；旧调用点不传时退回平台名 */
    val sourceName: String = "",
    /** 提供直链的脚本 ownerId（同平台多脚本时用于解释「用的是哪个」） */
    val ownerId: String = "",
    /**
     * 这条降级链**第一个尝试的档位**。
     *
     * 与 [quality] 不同就说明自动降级过（例如曲目标 FLAC、源最高只给到 128k），
     * 信息页据此补一句「已降级」，避免列表标称与实际不符却没有任何解释。
     */
    val requested: Quality? = null,
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

    /**
     * 变更计数（快照状态）。
     *
     * 列表行上的音质标签要「先显示音源最高档、播过之后改成实际档」，而备忘录本身
     * 不在组合作用域里。让界面读这个计数即可在补链完成后自动刷新标签，
     * 不必为一行标签再挂一个 Flow。
     */
    val revision = mutableIntStateOf(0)

    @Synchronized
    fun remember(stableKey: String, resolved: ResolvedUrl) {
        map[stableKey] = resolved
        while (map.size > MAX) map.remove(map.keys.first())
        revision.intValue++
    }

    @Synchronized
    fun peek(stableKey: String): ResolvedUrl? = map[stableKey]

    /** 失效（401/403 或判定过期）时清掉，下次强制重取 */
    @Synchronized
    fun forget(stableKey: String) {
        if (map.remove(stableKey) != null) revision.intValue++
    }

    @Synchronized
    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        revision.intValue++
    }
}

/**
 * 在线曲目的**唯一取址入口**。
 *
 * 三条规则（任何调用点都必须遵守，否则会出现「同一个地址取两次、两次结果不同」）：
 *  1. 先查 [OnlineUrlMemo] —— 重播 / 回切 / 队列恢复都该命中，命中即 0 次网络请求；
 *  2. 音质按 [qualityChain] 逐级**下降**（不越级上升：用户选了 320k 就不该偷偷给 flac 白烧流量）；
 *  3. 每一步失败就换下一档、换下一个脚本，全失败返回 null —— **不抛异常**，让播放层按
 *     「暂时取不到」处理（跳下一首 / 提示重试），而不是崩在协程里。
 *
 * 与洛雪的区别：洛雪在 `musicUrl` 阶段只试一次、失败了由 UI 提示；这里多一层「换脚本 +
 * 降音质」，因为车机弱网 + 各家源对高档位限流的情况下，「降一级能响」比「报错让用户自己换音质」有用得多。
 */
object OnlineResolver {

    /** 单次取址上限。超过就当这个音质不可用，换下一档 */
    const val TIMEOUT_MS = 8_000L

    /**
     * 取一条可播地址。
     *
     * 两层失败处理，缺一不可：
     *  1. **同平台换脚本**（v1.2.62）：一个平台可以装好几份音源脚本，按优先级逐个试。
     *     内置搜索产出的 `raw` 是通用字段，同平台的任何脚本都认，所以换脚本不需要重新搜索；
     *  2. **同脚本降音质**：档位由高到低，降不动了才判这条曲失败了。
     *
     * @param preferred 用户偏好的音质；null = 用音源支持的最高档
     */
    suspend fun resolve(track: OnlineTrack, preferred: Quality? = null): ResolvedUrl? {
        OnlineUrlMemo.peek(track.stableKey)?.let { return it }
        val sources = OnlineSourceRegistry.candidates(track.sourceId)
        if (sources.isEmpty()) return null

        for (source in sources) {
            val chain = qualityChain(source, track, preferred)
            for (quality in chain) {
                val url = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { source.resolveUrl(track, quality) }.getOrNull()
                }
                if (url.isPlayable()) {
                    val resolved = ResolvedUrl(
                        url = url!!.trim(),
                        quality = quality,
                        sourceId = track.sourceId,
                        sourceName = source.name,
                        ownerId = source.ownerId,
                        requested = chain.firstOrNull(),
                    )
                    OnlineUrlMemo.remember(track.stableKey, resolved)
                    return resolved
                }
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
        for (source in OnlineSourceRegistry.candidates(track.sourceId)) {
            val text = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { source.lyric(track) }.getOrNull()
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /** 取封面地址（不支持 / 失败返回 null，静默降级到占位图） */
    suspend fun cover(track: OnlineTrack): String? {
        for (source in OnlineSourceRegistry.candidates(track.sourceId)) {
            val url = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { source.cover(track) }.getOrNull()
            }
            if (url != null && url.startsWith("http", ignoreCase = true)) return url
        }
        return null
    }

    /**
     * 列表行上该显示哪一档音质。
     *
     * 优先级：**已经取过址的用实际档位** > 「这个平台的音源声明 ∩ 曲目自带」的最高档。
     *
     * 为什么要取交集：平台元数据里常写着 FLAC（酷我歌单的 `N_MINFO` 就有），但脚本
     * 根本不支持无损时，列表标 FLAC、进去播 128k，用户只会认为是 bug。取交集等于
     * 「标的就是这份音源真能给的」，与洛雪按源能力过滤档位的口径一致。
     */
    fun displayQuality(track: OnlineTrack): Quality? {
        OnlineUrlMemo.peek(track.stableKey)?.let { return it.quality }
        val source = OnlineSourceRegistry.find(track.sourceId)
        val fallback = track.qualities.maxByOrNull { it.ordinal }
        if (source == null) return fallback
        return qualityChain(source, track, null).firstOrNull() ?: fallback
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
     *
     * 候选集取 **曲目自带 ∩ 音源声明**（[effectiveQualities]）。以前只用曲目自带档位，
     * 遇到「平台标 FLAC、脚本只能 128k」的曲目时会先发两次必然失败的请求，列表标的档位
     * 也和实际播出来的对不上。
     */
    internal fun qualityChain(
        source: MusicSource,
        track: OnlineTrack,
        preferred: Quality?,
    ): List<Quality> {
        val supported = effectiveQualities(source, track).toSet()
        val ordered = Quality.BY_QUALITY.filter { it in supported }
        if (ordered.isEmpty()) return emptyList()
        if (preferred == null) return ordered
        val from = ordered.indexOf(preferred).let { if (it >= 0) it else 0 }
        return ordered.drop(from)
    }

    /**
     * 这份音源对这个曲目**真能提供**的档位。
     *
     * 三种情况都有真实来源：
     *  - 曲目没带档位（榜单/部分歌单接口不给）→ 信音源声明；
     *  - 音源没声明档位（老脚本省略 `qualitys`）→ 信曲目；
     *  - 两边都有 → 取交集，交集为空时退回音源声明（该脚本至少能以这个档位交付）。
     */
    internal fun effectiveQualities(source: MusicSource, track: OnlineTrack): List<Quality> {
        val own = track.qualities
        val declared = source.qualities
        return when {
            own.isEmpty() -> declared
            declared.isEmpty() -> own
            else -> own.filter { it in declared }.ifEmpty { declared }
        }
    }

    /** 只接受 http(s)：脚本返回本地路径 / data URI / 空串都算失败 */
    private fun String?.isPlayable(): Boolean =
        this != null && this.length <= 4096 && startsWith("http", ignoreCase = true)
}
