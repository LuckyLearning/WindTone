package com.luckylearning.windtone.data.model

/**
 * 阿里云盘曲目的稳定身份，放在播放地址的 fragment 里。
 *
 * 直链会过期，fragment 不进入 HTTP 请求。缓存、收藏、统计和续播都用这里算出的键，
 * 不用整段签名地址。
 */
object TrackFragment {

    const val PREFIX = "ay="

    fun of(driveId: String, fileId: String): String = "$PREFIX${enc(driveId)}/${enc(fileId)}"

    fun parse(fragment: String?): Pair<String, String>? {
        if (fragment == null || !fragment.startsWith(PREFIX)) return null
        val body = fragment.removePrefix(PREFIX)
        val slash = body.indexOf('/')
        if (slash <= 0 || slash >= body.lastIndex) return null
        val driveId = dec(body.substring(0, slash))
        val fileId = dec(body.substring(slash + 1))
        if (driveId.isBlank() || fileId.isBlank()) return null
        return driveId to fileId
    }

    /** 缓存与界面查找用的稳定键。不是阿里云盘曲目时返回 null。 */
    fun cacheKey(fragment: String?): String? {
        if (fragment == null || !fragment.startsWith(PREFIX)) return null
        return "aliyun:$fragment"
    }

    fun cacheKeyOfMediaId(mediaId: String): Boolean = mediaId.startsWith("aliyun:$PREFIX")

    private fun enc(value: String): String =
        value.replace("%", "%25").replace("/", "%2F").replace(":", "%3A")

    private fun dec(value: String): String =
        value.replace("%3A", ":").replace("%2F", "/").replace("%25", "%")
}
