package com.luckylearning.windtone.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.toSong
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 车机 / 外部媒体浏览器（Android Auto、车机自带媒体面板）的**浏览树**。
 *
 * 背景：v1.1.8 之前服务是 `MediaSessionService`，只能把「当前播放项」推给系统，
 * 车机面板因此只有一个正在播放的卡片，**看不到任何可见歌单层级**
 * （这条一直写在《权限与更新》的「已知限制」里）。改用 `MediaLibraryService` 之后，
 * 需要由本文件回答三件事：
 *   ① 树的根是什么；② 某个节点的子节点是什么；③ 某个节点怎么变成可播放项。
 *
 * 设计取舍：
 *  - **纯查询、零缓存**：浏览是低频操作（用户掀开车机面板才触发），
 *    每次都直接读 Room。不引入跨域缓存，也就不会出现「车机看到的歌单和手机里不一致」。
 *  - **树形只做一层下钻**：根 → 歌单 → 歌曲。不做「专辑 / 歌手 / 文件夹」——
 *    车机面板的屏幕尺寸与操作方式决定了它只适合做「选一个歌单，从头放」，
 *    把五个视图原样搬上去只会让人在方向盘上翻不完。
 *  - **歌曲节点的 mediaId 就是音频 URI**，与播放页、队列、封面缓存用的是同一个 id
 *    （见 [Song.stableKey]），因此车机点一首歌与手机里点一首歌走的是同一条路。
 *  - **分类节点用 `wt:` 前缀**：音频 URI 一定是 `content://` / `file://` / 绝对路径，
 *    不会以 `wt:` 开头，于是「这是分类节点还是歌曲节点」只需看前缀，无需额外字段。
 */
object BrowseIds {

    /** 浏览树根（车机面板里的「听风」入口） */
    const val ROOT = "wt:browse"

    /** 「我的收藏」——与设置页 / 曲库里那张虚拟歌单同一个口径 */
    const val FAVORITES = "wt:vpl:fav"

    /** 「最近播放」——按 `song_state.lastPlayedAt` 倒序 */
    const val RECENT = "wt:vpl:recent"

    /** 「最常播放」——按 `song_state.playCount` 倒序 */
    const val MOST_PLAYED = "wt:vpl:most"

    private const val LIBRARY_PREFIX = "wt:"
    private const val PLAYLIST_PREFIX = "wt:pl:"

    /** 用户自建歌单的节点 id */
    fun playlist(id: Long): String = PLAYLIST_PREFIX + id

    /** 是否是本应用自定义的分类节点（false = 歌曲节点，mediaId 即音频 URI） */
    fun isLibraryId(mediaId: String?): Boolean =
        mediaId != null && mediaId.startsWith(LIBRARY_PREFIX)

    /** 用户歌单节点 → 歌单主键；不是歌单节点时返回 null */
    fun playlistIdOf(mediaId: String?): Long? = mediaId
        ?.takeIf { it.startsWith(PLAYLIST_PREFIX) }
        ?.removePrefix(PLAYLIST_PREFIX)
        ?.toLongOrNull()
}

/** 虚拟歌单（收藏 / 最近 / 最常）在车机上最多列出多少首，与 App 内的口径一致 */
private const val VIRTUAL_LIMIT = 100

/** 浏览树根节点：只可下钻，不可直接播放 */
internal fun browseRootItem(): MediaItem = MediaItem.Builder()
    .setMediaId(BrowseIds.ROOT)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle("听风")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build(),
    )
    .build()

/**
 * 歌单 / 分类节点：可下钻，也可**整单播放**。
 *
 * `isPlayable = true` 是关键——车机面板上的长按菜单里「播放」才有反应，
 * 具体的展开动作在 [PlaybackService] 的 `onSetMediaItems` 里完成。
 */
internal fun browseFolderItem(mediaId: String, title: String, songCount: Int): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle("$songCount 首")
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

/**
 * 歌曲节点：直接可播。
 *
 * 复用 [PlaybackConnection.toMediaItem]（同一套 uri / mediaId / 标题），
 * 只补两件浏览场景才需要的东西：`mediaType` 与时长（车机列表要显示 03:42）。
 *
 * `@OptIn` 说明：`MediaMetadata.Builder.setDurationMs` 标着 Media3 的 `@UnstableApi`，
 * 而构建脚本里的 `-opt-in=...UnstableApi` 只对**编译器**生效、lint 不认
 * （`UnsafeOptInUsageError`）。处理方式与 `PlaybackService` / `PlayerHandle` 一致：
 * 用 `@androidx.annotation.OptIn` 显式标一次，而不是把 lint 关掉。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun browseSongItem(song: Song): MediaItem {
    val base = PlaybackConnection.toMediaItem(song)
    val metadata = base.mediaMetadata.buildUpon()
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setDurationMs(song.durationMs.takeIf { it > 0 })
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
    return base.buildUpon().setMediaMetadata(metadata).build()
}

/**
 * 从 Room 读出浏览树。
 *
 * 只做查询，不做权限判断：能连上本服务的浏览器要么是系统车机面板，要么是本应用自己。
 */
internal class LibraryBrowser(private val db: MusicDb) {

    /**
     * 某个节点的子节点。
     *
     * ⚠️ **`page` 是「页码」不是「条目偏移量」**——Media3 的 Javadoc 原文是
     * *"The page number to get the paginated result starting from 0"*，
     * 起点要自己乘 `pageSize`。按偏移量理解会让第二页之后全部错位（车机上千首的歌单才看得出来）。
     *
     * @param page     页码，从 0 开始
     * @param pageSize 每页条数；`Int.MAX_VALUE` 表示「不分页，一次给全」（应用内自己调用时用）
     */
    suspend fun childrenOf(parentId: String, page: Int, pageSize: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val nodes = when {
                parentId == BrowseIds.ROOT -> rootNodes()
                parentId == BrowseIds.FAVORITES ->
                    songNodes(db.songStateDao().favoriteIdsByRecent())
                parentId == BrowseIds.RECENT ->
                    songNodes(db.songStateDao().recentIds(VIRTUAL_LIMIT))
                parentId == BrowseIds.MOST_PLAYED ->
                    songNodes(db.songStateDao().mostPlayedIds(VIRTUAL_LIMIT))
                else -> BrowseIds.playlistIdOf(parentId)
                    ?.let { songNodes(playlistSongIds(it)) }
                    .orEmpty()
            }
            when {
                pageSize <= 0 -> emptyList()
                pageSize == Int.MAX_VALUE -> nodes
                else -> {
                    // 用 Long 乘法：page 由外部传入，直接乘会溢出成负数
                    val start = (page.coerceAtLeast(0).toLong() * pageSize)
                        .coerceAtMost(nodes.size.toLong())
                        .toInt()
                    nodes.drop(start).take(pageSize)
                }
            }
        }

    /** 单个节点的信息（车机问「这个 id 是什么」时用） */
    suspend fun itemOf(mediaId: String): MediaItem? = withContext(Dispatchers.IO) {
        when {
            mediaId == BrowseIds.ROOT -> browseRootItem()
            mediaId == BrowseIds.FAVORITES -> browseFolderItem(
                mediaId, "我的收藏", db.songStateDao().favoriteIdsByRecent().size,
            )
            mediaId == BrowseIds.RECENT -> browseFolderItem(
                mediaId, "最近播放", db.songStateDao().recentIds(VIRTUAL_LIMIT).size,
            )
            mediaId == BrowseIds.MOST_PLAYED -> browseFolderItem(
                mediaId, "最常播放", db.songStateDao().mostPlayedIds(VIRTUAL_LIMIT).size,
            )
            else -> BrowseIds.playlistIdOf(mediaId)?.let { id ->
                db.playlistDao().byId(id)?.let {
                    browseFolderItem(mediaId, it.name, db.playlistDao().songsOf(id).size)
                }
            }
        }
    }

    /**
     * 把「点了一个分类节点」展开成可播放的歌曲列表。
     *
     * 返回空列表表示这个 id 认不出来（车机拿着上一次的树点了进来，
     * 而歌单已经被删了 —— 这是正常时序，调用方按「结果为空」处理即可）。
     */
    suspend fun playableItemsUnder(mediaId: String): List<MediaItem> =
        childrenOf(mediaId, 0, Int.MAX_VALUE).filter { it.mediaMetadata.isPlayable != false }

    /**
     * 把浏览器交回来的一组播放项变成真正能播的项。
     *
     * 两种输入：
     *  - **分类节点**（`wt:` 前缀）→ 展开成它下面的歌曲；
     *  - **歌曲节点** → 跨 Binder 时 `LocalConfiguration` 被丢掉，
     *    这里把 `mediaId` 重新写回 uri（歌曲节点的 mediaId 就是音频 URI）。
     *
     * 认不出 uri 形态的项**原样返回**，交给 Media3 去报错——
     * 这里不猜、也不伪造一个播放地址。
     */
    suspend fun resolve(items: List<MediaItem>): List<MediaItem> = items.flatMap { item ->
        val mediaId = item.mediaId
        when {
            BrowseIds.isLibraryId(mediaId) -> playableItemsUnder(mediaId)
            looksLikeUri(mediaId) -> listOf(item.buildUpon().setUri(mediaId).build())
            else -> listOf(item)
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 是不是一个可以当 uri 用的字符串。
     *
     * 只用于「要不要把 mediaId 写回 uri」这一个判断，故意写得保守：
     * 有 scheme（`content:` / `file:` / `http:`）或以 `/` 开头的绝对路径才算。
     */
    private fun looksLikeUri(value: String): Boolean =
        value.startsWith("/") || value.substringBefore(':', "").let {
            it.isNotEmpty() && it.all { c -> c.isLetter() || c.isDigit() || c in "+-." }
        }

    /**
     * 根节点的子节点：三个虚拟歌单在前，用户歌单按创建时间倒序在后。
     *
     * 三个虚拟歌单要多跑三条 COUNT 性质的查询来显示「N 首」。
     * 这是冷路径（掀开车机面板才发生一次），换来的是界面上不出现「我的收藏（？）」。
     */
    private suspend fun rootNodes(): List<MediaItem> {
        val linkCounts = db.playlistDao().allSongs().groupingBy { it.playlistId }.eachCount()
        val virtual = listOf(
            browseFolderItem(
                BrowseIds.FAVORITES, "我的收藏", db.songStateDao().favoriteIdsByRecent().size,
            ),
            browseFolderItem(
                BrowseIds.RECENT, "最近播放", db.songStateDao().recentIds(VIRTUAL_LIMIT).size,
            ),
            browseFolderItem(
                BrowseIds.MOST_PLAYED, "最常播放", db.songStateDao().mostPlayedIds(VIRTUAL_LIMIT).size,
            ),
        )
        val user = db.playlistDao().all().map { p ->
            browseFolderItem(BrowseIds.playlist(p.id), p.name, linkCounts[p.id] ?: 0)
        }
        return virtual + user
    }

    private suspend fun playlistSongIds(playlistId: Long): List<Long> =
        db.playlistDao().songsOf(playlistId).map { it.songId }

    /**
     * 主键 → 歌曲节点，**顺序沿用传入的 id 顺序**
     * （歌单顺序 / 最近播放倒序都是 id 列表本身携带的信息，不能被 Map 打乱）。
     */
    private suspend fun songNodes(ids: List<Long>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val rows = db.songDao().byIds(ids).associateBy { it.id }
        val states = db.songStateDao().bySongIds(ids).associateBy { it.songId }
        return ids.mapNotNull { id -> rows[id]?.toSong(states[id]) }.map { browseSongItem(it) }
    }
}
