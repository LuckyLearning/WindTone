package com.luckylearning.windtone.data.online

import android.net.Uri

/**
 * 在线曲目的**身份编码**：`lx:<音源 id>:<源内 id>`（如 `lx:kw:228908`）。
 *
 * 为什么单独抽一个对象：身份这套规则同时被三处使用，任何一处写法不一致都会造成
 * 「收藏了一首、重扫后变成另一首」这类不可逆问题：
 *  - 数据层写库（`songs.uri` / `Song.stableKey`）；
 *  - 播放层构造占位地址（[pendingUri]）；
 *  - 车机浏览树 `LibraryBrowser.resolveOne` 判断这条 mediaId 该怎么解析。
 *
 * 三条硬约束：
 *  1. **前缀必须是 `lx:`**。云盘用的是 `aliyun:`，两者都是「稳定键」而不是真协议；
 *     `LibraryBrowser.looksLikeUri()` 会把任何「字母+冒号」当成 URI 塞给播放器
 *     （v1.1.71「播放全挂」就是 `aliyun:` 被当成协议名，报 unknown protocol），
 *     所以在线键**必须在 looksLikeUri 之前**被识别出来。
 *  2. **音源 id 不允许含冒号**。导入自定义源时校验，否则 `lx:a:b:c` 无法无歧义解析。
 *  3. **源内 id 允许含冒号**（取第一个冒号之后的全部内容），以兼容少数把路径拼进 id 的音源。
 */
object OnlineKeys {

    /** 曲库里的身份前缀。与 [PENDING_SCHEME] 是两回事，别混用 */
    const val PREFIX = "lx:"

    /**
     * 还没取到直链时的占位 scheme。
     *
     * 播放器**不会**真正拿它去下载：`LibraryBrowser.resolveOne` 与播放引擎的窗口补链
     * 会在加载前把它换成真直链。取这个名字而不是 `lx:`，是为了让它看起来就**不像**
     * 一个能播的地址 —— 万一被谁漏判直接塞给 Media3，报错文案里也能一眼认出是谁的锅。
     */
    const val PENDING_SCHEME = "lx-pending"

    private const val PENDING_AUTHORITY = "track"

    /** 解析结果：音源 id + 源内 id */
    data class Key(val sourceId: String, val songId: String)

    /** 由音源 id 与源内 id 拼出稳定键 */
    fun of(sourceId: String, songId: String): String = PREFIX + sourceId + ":" + songId

    /** 稳定键 → 身份；不是在线键、或格式不完整时返回 null（调用方按「不是在线曲」处理） */
    fun parse(value: String?): Key? {
        if (value == null || !value.startsWith(PREFIX)) return null
        val rest = value.substring(PREFIX.length)
        val split = rest.indexOf(':')
        if (split <= 0 || split == rest.length - 1) return null
        val sourceId = rest.substring(0, split)
        val songId = rest.substring(split + 1)
        if (sourceId.isBlank() || songId.isBlank()) return null
        return Key(sourceId, songId)
    }

    /** 只走字符串重载；Uri 版本单独命名，避免 `parse(null)` 在调用处产生重载歧义 */
    fun fromUri(uri: Uri?): Key? = parse(uri?.toString())

    /** 是否是本应用的在线稳定键（`lx:` 开头且可解析） */
    fun isOnlineKey(value: String?): Boolean = parse(value) != null

    /** 音源 id 是否合法（导入自定义源时用：非空、不含冒号与空白） */
    fun isValidSourceId(sourceId: String?): Boolean =
        !sourceId.isNullOrBlank() && sourceId.none { it == ':' || it.isWhitespace() }

    /**
     * 占位地址：`lx-pending://track#lx:kw:228908`。
     *
     * 曲目身份放在 **fragment** 里，与云盘 `aliyun-pending://track#ay=…` 同一形态。
     * 这样同一套「fragment 里藏身份、请求前剥掉」的处理（[com.luckylearning.windtone.playback.OnlineMediaCache]）
     * 可以直接复用，不必为在线再写一条分支。
     */
    fun pendingUri(key: String): Uri =
        Uri.parse("$PENDING_SCHEME://$PENDING_AUTHORITY").buildUpon().fragment(key).build()

    fun isPending(scheme: String?): Boolean = scheme == PENDING_SCHEME

    fun isPending(uri: Uri?): Boolean = isPending(uri?.scheme)

    /** 从占位地址里取回稳定键；不是占位地址或 fragment 不合法时返回 null */
    fun pendingKey(uri: Uri?): String? {
        if (!isPending(uri)) return null
        val key = parse(uri?.fragment) ?: return null
        return of(key.sourceId, key.songId)
    }
}
