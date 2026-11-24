package com.luckylearning.windtone.playback

import android.net.Uri
import com.luckylearning.windtone.data.aliyun.AliyunPlaybackWindow
import com.luckylearning.windtone.data.online.OnlineKeys

/**
 * 两类「还没取到直链」的占位地址的统一判定。
 *
 * 云盘与在线音源走的是同一套补链形状：
 * ```
 * 占位地址（scheme=*-pending） → 异步取直链 → replaceMediaItem(mediaId 不变)
 * ```
 * 区别只在「直链从哪来」。播放链里所有判断「这项是不是还没好」的地方都必须
 * 同时认两类，否则在线歌会撞上两种症状：
 *
 *  - `onPlayerError` 只认云盘 → 在线占位地址加载失败被当成真失败，直接跳下一首；
 *  - `fillAliyunWindow` 只认云盘 → 在线占位地址永远没人补。
 *
 * 这里集中一处，避免每个调用点各写一个 `||`。滑窗的窗口范围仍复用
 * [AliyunPlaybackWindow.range]（AHEAD=2 / BEHIND=1），两类曲目共用同一套距离策略。
 */
object PendingUrls {

    /** 当前项是不是「等补链」的占位地址 */
    fun isPending(scheme: String?): Boolean =
        AliyunPlaybackWindow.isPending(scheme) || OnlineKeys.isPending(scheme)

    fun isPending(uri: Uri?): Boolean = isPending(uri?.scheme)

    /**
     * 是否是一首「还没补到直链」的在线曲。
     *
     * 必须同时认两种形态，否则窗口调度会漏掉最容易遇见的场景：
     *  - `lx:kw:123`：曲库里的身份形态，引擎本地 `currentQueue` 里的值；
     *  - `lx-pending://track#lx:kw:123`：过 Binder 后 `LibraryBrowser.resolveOne` 重建的
     *    占位地址，控制器 `MediaItem.localConfiguration.uri` 里的值。
     *
     * 补链后的 `https://…#lx:kw:123` 不会命中（字符串不以 `lx:` 开头），
     * 所以已经取到直链的歌不会被反复重取。
     */
    fun isOnline(uri: Uri?): Boolean =
        uri != null && (OnlineKeys.isPending(uri) || OnlineKeys.isOnlineKey(uri.toString()))

    /** 是否云盘的占位地址 */
    fun isCloud(uri: Uri?): Boolean = AliyunPlaybackWindow.isPending(uri?.scheme)

    /**
     * 窗口内属于 [kind] 的未补链下标。
     *
     * @param kind 只统计这一类，避免一次补链把两类地址的直链都重取一遍
     */
    fun pendingIndices(
        uris: List<Uri?>,
        current: Int,
        kind: Kind,
        ahead: Int = AliyunPlaybackWindow.AHEAD,
        behind: Int = AliyunPlaybackWindow.BEHIND,
    ): List<Int> {
        val span = AliyunPlaybackWindow.range(current, uris.size, ahead, behind)
        if (span.isEmpty()) return emptyList()
        return span.filter { i ->
            when (kind) {
                Kind.CLOUD -> isCloud(uris[i])
                Kind.ONLINE -> isOnline(uris[i])
            }
        }
    }

    enum class Kind { CLOUD, ONLINE }
}
