package com.luckylearning.windtone.data.online.script

import com.luckylearning.windtone.data.online.source.Capability
import com.luckylearning.windtone.data.online.source.MusicSource
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import org.json.JSONObject

/**
 * 把一个脚本声明的 `musicUrl` 能力适配成 [MusicSource]。
 *
 * 注意这里**不实现 search**：洛雪 v2 规范里自定义源只被允许换直链，搜索/歌词/封面
 * 由客户端内置实现承担（见 docs/规划-在线音乐与音源接入.md §2.2.1）。
 * 所以本类的 [capabilities] 不含 SEARCH，[search] 也不会被任何搜索入口调用。
 *
 * [OnlineTrack.raw] 是内置搜索结果按洛雪字段名生成的 JSON（songmid / strMediaMid /
 * albumId / hash …），取直链时原样回传并补上 `type`（用户选中的音质）——
 * 这正是洛雪 preload 传给脚本 request handler 的形状，脚本不需要知道宿主用 Kotlin 写的。
 */
class LxScriptSource(
    private val runtime: LxRuntime,
    private val declared: LxDeclaredSource,
    private val scriptName: String,
) : MusicSource {

    override val id: String get() = declared.id

    override val name: String get() = DISPLAY_NAMES[declared.id]?.let {
        "$it · $scriptName"
    } ?: scriptName

    // 本接口没有独立的 PLAY 能力项；musicUrl 是脚本存在的前提，不额外声明。
    override val capabilities: Set<Capability> = setOfNotNull(
        Capability.SEARCH,
        if ("lyric" in declared.actions) Capability.LYRIC else null,
        if ("pic" in declared.actions) Capability.PIC else null,
    )

    override val qualities: List<Quality> =
        declared.qualities.mapNotNull { Quality.of(it) }

    override suspend fun search(keyword: String, limit: Int): List<OnlineTrack> =
        // 内置搜索负责发现歌曲；脚本只负责取直链。返回空列表而不是抛异常，
        // 让误调用方（例如未来的通用 UI）安全降级为「无结果」。
        emptyList()

    /**
     * 洛雪 v2 的 action 入参是 **两层**结构：`{ type, musicInfo }`。
     *
     * 这里曾踩过实测的坑：把歌曲字段平铺进 info 会让 fish 源报
     * `kw song id missing`，一个网络请求都不发。洛雪 `userApi/index.ts` 的
     * getMusicUrl 明确包成 `{ type, musicInfo: songInfo }`，脚本读的是
     * `info.musicInfo.songmid`。少包这一层 = 内置搜索能出结果、但一首都播不了。
     *
     * 注意 raw 保持 BuiltInSearch 生成的平铺字段（songmid/hash/strMediaMid …
     * 都在一层）—— 这是被 fish 源实测验证过的形状；洛雪 MusicInfo 类型里那些
     * 字段在 `meta` 下，但 fish 的 bydcar 定制版读平铺，两者不同。以实测为准。
     */
    private fun actionInfo(track: OnlineTrack, quality: Quality? = null): JSONObject =
        LxActionInfo.build(track, quality)

    override suspend fun resolveUrl(track: OnlineTrack, quality: Quality): String? =
        runtime.invoke(id, "musicUrl", actionInfo(track, quality))

    override suspend fun lyric(track: OnlineTrack): String? {
        if ("lyric" !in declared.actions) return null
        return runtime.invoke(id, "lyric", actionInfo(track))
    }

    override suspend fun cover(track: OnlineTrack): String? {
        if ("pic" !in declared.actions) return null
        return runtime.invoke(id, "pic", actionInfo(track))
    }

    companion object {
        val DISPLAY_NAMES = mapOf(
            "kw" to "酷我音乐", "wy" to "网易云音乐", "kg" to "酷狗音乐",
            "tx" to "QQ音乐", "mg" to "咪咕音乐", "local" to "本地",
        )
    }
}
