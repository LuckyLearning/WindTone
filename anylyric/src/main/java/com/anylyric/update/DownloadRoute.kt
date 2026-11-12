package com.anylyric.update

import com.anylyric.R

/**
 * jsDelivr 前缀标记：`prefix` 里出现它就说明要按 jsDelivr 规则重排 URL，
 * 而不是简单拼接。放在文件顶层而不是 companion 里 —— 枚举项在构造时读不到
 * companion 的常量（Kotlin 会直接报「uninitialized here」）。
 */
internal const val JSDELIVR_PREFIX_MARK = "jsdelivr:"

/**
 * 更新线路：**检查更新与安装包下载共用同一套线路**。
 *
 * 为什么需要选：国内车机连 `raw.githubusercontent.com` 经常超时，而各家加速镜像的
 * 可用性随网络环境变化（同一个镜像在家里通、在车里不通）。全部押在一条线上，
 * 结果就是「点了检查更新一直转圈」。
 *
 * [prefix] 的含义：
 *  · `null` —— 自动：全部线路都上（检查更新并行竞速，下载按顺序降级）；
 *  · `""`   —— 只走官方直连；
 *  · `"jsdelivr:xxx"` —— jsDelivr 的**仓库文件**通道，前缀不能简单拼接，要重排 URL；
 *  · 其它 —— 加速镜像前缀，把原始地址整串接在后面即可。
 */
enum class DownloadRoute(
    val prefix: String?,
    val labelRes: Int,
    val hintRes: Int
) {
    AUTO(null, R.string.route_auto, R.string.route_auto_hint),
    DIRECT("", R.string.route_direct, R.string.route_direct_hint),
    JSDELIVR(JSDELIVR_PREFIX_MARK + "fastly", R.string.route_jsdelivr, R.string.route_jsdelivr_hint),
    GH_PROXY_COM("https://gh-proxy.com/", R.string.route_gh_proxy_com, R.string.route_gh_proxy_com_hint),
    GHPROXY_NET("https://ghproxy.net/", R.string.route_ghproxy_net, R.string.route_ghproxy_net_hint),
    GH_FAST("https://ghfast.top/", R.string.route_gh_fast, R.string.route_gh_fast_hint),
    GIT_MIRROR("https://hub.gitmirror.com/", R.string.route_git_mirror, R.string.route_git_mirror_hint),
    ;

    companion object {
        /** 落盘值是枚举名，改了名字等于重置成默认值；这里刻意不存序号，序号会被枚举顺序改动带偏。 */
        fun of(id: String?): DownloadRoute = values().firstOrNull { it.name == id } ?: AUTO
    }
}
