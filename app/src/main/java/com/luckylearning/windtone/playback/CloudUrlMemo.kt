package com.luckylearning.windtone.playback

/**
 * 云盘曲目「已取到的直链」的进程内备忘录（LRU）。
 *
 * 为什么需要它：播放项跨 Binder 时 `LocalConfiguration` 会被丢掉，服务端的
 * `LibraryBrowser.resolve` 只能凭 mediaId（稳定键 `aliyun:ay=盘/文件`）重建地址。
 * 播放引擎的滑动窗口补链（`fillAliyunWindow` / `refreshAliyunUrl`）取到真直链后
 * 调 `replaceMediaItem` —— 这一传同样要过 resolve，地址会再被剥一次。引擎把
 * 刚取到的直链先记进来，resolve 直接取用，省一次网络请求，也让补链真正生效。
 *
 * 直链约 4 小时过期，本表只做**短期**复用（窗口补链 / 401 重试的当次传递），
 * 不做持久化；取久了过期的条目由引擎的 401 重试兜底。
 */
object CloudUrlMemo {

    private const val MAX = 256

    /** accessOrder=true：命中即移到尾部，淘汰最久未用的 */
    private val map = LinkedHashMap<String, String>(16, 0.75f, true)

    /** 记住一条直链（[key] = 曲目稳定键，[uri] = 完整播放地址，含 fragment） */
    @Synchronized
    fun remember(key: String, uri: String) {
        map[key] = uri
        while (map.size > MAX) map.remove(map.keys.first())
    }

    /** 取回直链；没有或已淘汰返回 null */
    @Synchronized
    fun peek(key: String): String? = map[key]
}
