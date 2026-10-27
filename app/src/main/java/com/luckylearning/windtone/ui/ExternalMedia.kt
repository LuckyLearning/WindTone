package com.luckylearning.windtone.ui

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 从**外部**进来的播放入口（2026-10-27）。
 *
 * 之前本应用只有自己的入口（抽屉 / 快捷方式 / 小组件 / 通知），对外只暴露了
 * MediaBrowserService 的浏览树。车机把应用列进「默认音乐」候选时看的不是浏览树，
 * 而是这几条 intent-filter —— 声明了就必须真的接住，否则用户点进来是空的。
 *
 * 两条入口（清单里对应两个 intent-filter）：
 *  - [OpenUri]：文件管理器 / 车机文件浏览器里点一首歌 →「用听风打开」；
 *  - [SearchSong]：系统语音「播放周杰伦」→ `MEDIA_PLAY_FROM_SEARCH` + 查询词。
 *
 * ## 为什么不在 Activity 里直接播
 *
 * 与 [AppShortcut] 同一个原因：冷启动时曲库还在从 Room 往内存里装，此刻 `playQueue`
 * 拿到空列表，用户看到的是「应用开了，什么也没播，也没提示」。这里只登记意图，
 * 由 `MainScreen` 在曲库报出结论之后消费一次（并有单测钉住「等待」这条行为）。
 */
sealed interface ExternalRequest {

    /** 外部要求直接播放某个音频文件（`ACTION_VIEW`，类型为音频通配）。 */
    data class OpenUri(val uri: String) : ExternalRequest

    /** 系统语音点歌（`MEDIA_PLAY_FROM_SEARCH`），[query] 是识别出来的那段话。 */
    data class SearchSong(val query: String) : ExternalRequest
}

object ExternalMedia {

    /** 系统语音搜索的 action（与清单里的 intent-filter 逐字一致）。 */
    const val ACTION_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

    /** 部分 ROM 用的备用键（`SearchManager.QUERY` 之外的老写法）。 */
    private const val EXTRA_QUERY_LOWER = "query"

    private val _pending = MutableStateFlow<ExternalRequest?>(null)
    val pending: StateFlow<ExternalRequest?> = _pending.asStateFlow()

    /**
     * 解析外部意图。抽成纯函数（只吃字符串，不吃 Intent）是为了能单测：
     * 这三条判据写错的表现都是「点进来没反应」，真机上很难复现定位。
     */
    fun parse(action: String?, uri: String?, query: String?): ExternalRequest? = when {
        // ACTION_VIEW 必须有 uri；没有 uri 的 VIEW 是别的用途（如通知点击），不接
        action == Intent.ACTION_VIEW && !uri.isNullOrBlank() -> ExternalRequest.OpenUri(uri)
        // 语音搜索：查询词为空时什么都不做（播整库不是用户说的）
        action == ACTION_SEARCH && !query.isNullOrBlank() ->
            ExternalRequest.SearchSong(query.trim())
        else -> null
    }

    /** 从 Activity 收到的 Intent 解析（`onCreate` / `onNewIntent` 各调一次）。 */
    fun from(intent: Intent?): ExternalRequest? {
        val i = intent ?: return null
        val query = i.getStringExtra(android.app.SearchManager.QUERY)
            ?: i.getStringExtra(EXTRA_QUERY_LOWER)
        return parse(i.action, i.dataString, query)
    }

    fun put(request: ExternalRequest?) {
        if (request != null) _pending.value = request
    }

    /** 取走即清空，保证同一次点击只播一次（与 [AppShortcut.consumePending] 同口径）。 */
    fun consumePending(): ExternalRequest? {
        val v = _pending.value
        _pending.value = null
        return v
    }

    /**
     * 把外部 uri 变成一首**可播的临时曲**（不在曲库里）。
     *
     * 为什么不要求先加进曲库：用户在文件管理器里点一首歌，期待的是「现在放这首」，
     * 不是「先去设置里把那个目录加进扫描范围，等扫完再回来点一次」。
     *
     * `id` 固定为 0：曲库里的歌 id 由 Room 分配、恒大于 0；0 表示「未落库」，
     * 与扫描过程中临时构造的 Song 一致。副作用只有一个 —— 歌词缓存按 id 做键，
     * 两首不同的临时曲可能互相顶掉（下次再打开重新解析一次），可以接受。
     */
    fun transientSong(context: Context, uriString: String): Song {
        val uri = Uri.parse(uriString)
        var title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "未知曲目"
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        // 读取元数据可能因权限 / 格式 / 已拔出的 U 盘失败：失败就用文件名兜底，
        // 不要因为「读不到封面和歌手」就整首播不了。
        //
        // ⚠️ 用 `release()` 而不是 `.use { }`：`MediaMetadataRetriever` 到 **API 29**
        // 才实现 AutoCloseable，minSdk 24 的设备上 `close()` 会 NoSuchMethodError
        // （与项目里 `java.time` 那条红线同一个坑）。`release()` 从 API 10 就有。
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // 静默：兜底信息（文件名）已经够界面显示
        } finally {
            runCatching { retriever.release() }
        }
        return Song(
            id = 0L,
            uri = uri,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            displayName = title,
            source = SongSource.FILE,
        )
    }
}
