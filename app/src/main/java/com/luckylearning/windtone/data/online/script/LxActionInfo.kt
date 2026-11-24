package com.luckylearning.windtone.data.online.script

import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import org.json.JSONObject

/**
 * 构造洛雪 v2 action 的入参结构（纯 JVM 逻辑，可单测）。
 *
 * 结构来自洛雪 `core/init/userApi/index.ts`：
 * ```
 * { type: '320k', musicInfo: { source, songmid, ... } }
 * ```
 * [OnlineTrack.raw] 里是 BuiltInSearch 生成的平铺字段，作为 musicInfo 原样传回。
 */
object LxActionInfo {

    fun build(track: OnlineTrack, quality: Quality? = null): JSONObject {
        val musicInfo = runCatching { JSONObject(track.raw.ifBlank { "{}" }) }.getOrNull()
            ?: JSONObject()
        // songId 兜底：raw 来自旧收藏且字段缺失时，用身份键里的源内 id 补
        if (!musicInfo.has("songmid") && !musicInfo.has("hash") &&
            !musicInfo.has("copyrightId")) {
            musicInfo.put("songmid", track.songId)
        }
        return JSONObject().apply {
            put("type", quality?.id ?: Quality.Q128.id)
            put("musicInfo", musicInfo)
        }
    }
}

/**
 * 宿主 → 脚本的请求报文（纯逻辑，可单测）。
 *
 * 结构来自洛雪 `core/init/userApi/index.ts` 的 `sendUserApiRequest({ requestKey, data })`：
 * ```
 * { requestKey: "...", data: { source, action, info } }
 * ```
 *
 * **两层是硬要求**。preload 的 `handleRequest = ({ requestKey, data }) => ...`
 * 直接读 `data.source` / `data.action` / `data.info`。少了 `data` 这一层，
 * 脚本收到的 `info` 是 undefined，表现是「搜索有结果、一首也播不了」，
 * 而且脚本侧只会抛一句与字段无关的错（fish 报的是 `kw song id missing`），极难定位。
 */
object LxRequestEnvelope {

    fun build(requestKey: String, source: String, action: String, info: JSONObject): JSONObject =
        JSONObject().apply {
            put("requestKey", requestKey)
            put(
                "data",
                JSONObject()
                    .put("source", source)
                    .put("action", action)
                    .put("info", info),
            )
        }
}
