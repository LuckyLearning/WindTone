package com.luckylearning.windtone.data.online

/**
 * 在线封面地址的归一（纯函数，可单测）。
 *
 * 列表里「有的歌有图、有的没有」不是单一原因，这里按已经对上的几条分开处理：
 *
 * 1. **酷我 `pic.web` 不是图片。** 榜单和歌单把
 *    `http://artistpicserver.kuwo.cn/pic.web?...&rid=` 直接写进 `coverUrl`。
 *    它的响应体是一行图片 URL 文本。旧的下载把它当 JPEG 解码，必然失败，
 *    所以酷我榜单 / 歌单封面一直是占位，搜索（coverUrl 为空、会再请求一次）却有图。
 * 2. **`{size}` 模板。** 酷狗的 `Image` / `album_sizable_cover` 带 `{size}`，
 *    不替换就是 404。搜索在 [com.luckylearning.windtone.data.online.BuiltInSearch.kgCover]
 *    里替换过，榜单以前没走那条。
 * 3. **明文 http 被 CDN 拒绝。** 酷狗、QQ、咪咕的图床对 `http://` 经常 403，https 才稳定。
 *    酷我图床相反，`pic.web` 返回的地址很多只有 http，这里不把酷我图升到 https。
 * 4. **缺 Referer。** QQ 歌词接口已经带 `Referer: https://y.qq.com/`，
 *    同站的 `y.gtimg.cn` 封面不带就会间歇 403。酷狗、咪咕同样。
 */
internal object OnlineCoverUrls {

    fun isKuwoResolver(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return "artistpicserver.kuwo.cn" in u && "pic.web" in u
    }

    /**
     * 从酷我 `pic.web` 地址里取出 `rid`。
     *
     * 歌单封面只有这个地址（没有 `OnlineTrack.songId` 可借），所以解析要能从 URL 自取。
     * 只接受纯数字，避免把 `rid=` 后面的其他参数（或空串）当 id 去请求。
     */
    fun kuwoRid(url: String?): String? {
        if (!isKuwoResolver(url)) return null
        val raw = url!!.substringAfter("rid=", "")
            .substringBefore("&")
            .substringBefore("#")
            .trim()
        return raw.takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } }
    }

    /**
     * 能直接当图片下的地址。解析端点、空串、非 http 返回 null，
     * 调用方再决定要不要走酷我的二次请求。
     */
    fun normalizeImage(url: String?): String? {
        var u = url?.trim().orEmpty()
        if (u.isEmpty()) return null
        if (u.startsWith("//")) u = "https:$u"
        if (isKuwoResolver(u)) return null
        if (u.contains("{size}")) u = u.replace("{size}", "480")
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        return upgradeKnownCdn(u)
    }

    /** 封面字段优先，没有再用 raw 里的 `img`。两边都是解析端点时返回 null。 */
    fun directImage(coverUrl: String?, rawImg: String?): String? =
        normalizeImage(coverUrl) ?: normalizeImage(rawImg)

    fun refererFor(url: String): String? {
        val host = hostOf(url)
        return when {
            host.endsWith("qq.com") || host.endsWith("gtimg.cn") -> "https://y.qq.com/"
            host.endsWith("kugou.com") -> "https://www.kugou.com/"
            host.endsWith("migu.cn") -> "https://music.migu.cn/"
            host.endsWith("kuwo.cn") -> "http://www.kuwo.cn/"
            else -> null
        }
    }

    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", url)
        return rest.substringBefore("/").substringBefore(":").lowercase()
    }

    /** JPEG / PNG / GIF / WEBP / BMP 魔数。文本 URL 和 HTML 错误页都不是。 */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()) return true
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return true
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return true
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) return true
        return false
    }

    /**
     * 酷我 `pic.web` 的正文是一行 URL。图片魔数、HTML、多行都不当成地址，避免把错误页再请求一遍。
     */
    fun textUrl(bytes: ByteArray): String? {
        if (looksLikeImage(bytes) || bytes.size > 2048) return null
        val text = runCatching { bytes.toString(Charsets.ISO_8859_1).trim() }.getOrNull() ?: return null
        if (text.isEmpty() || text.length > 2048) return null
        if ('\n' in text || '\r' in text || '<' in text || ' ' in text) return null
        if (!text.startsWith("http://") && !text.startsWith("https://")) return null
        return normalizeImage(text)
    }

    private fun upgradeKnownCdn(url: String): String {
        if (!url.startsWith("http://")) return url
        val host = hostOf(url)
        val upgrade = host.endsWith("kugou.com") || host.endsWith("qq.com") ||
            host.endsWith("gtimg.cn") || host.endsWith("migu.cn")
        return if (upgrade) "https://" + url.removePrefix("http://") else url
    }
}
