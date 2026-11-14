package com.luckylearning.windtone.state

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.DuplicateGroup
import com.luckylearning.windtone.data.ListenCalendarMonth
import com.luckylearning.windtone.data.ListeningReport
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.LibraryFormatFilter
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.UpdateInfo
import com.luckylearning.windtone.data.buildListeningReport
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 界面状态的**全部类型定义**。
 *
 * 这些类型原先散在 `MusicViewModel.kt` 文件头部。分域之后它们被四个 ViewModel
 * 与若干无状态组件（如 [playModeOf]）共用，所以单独成文件——
 * 谁都不再需要 import 某个具体的 ViewModel 才能拿到状态类型。
 */

// ---------------------------------------------------------------- 曲库

/** 库主视图：歌曲 / 专辑 / 歌手 / 合辑艺人 / 流派 / 年代 / 文件夹 / 歌单 */
enum class LibraryView {
    SONGS,
    ALBUM,
    ARTIST,
    ALBUM_ARTIST,
    GENRE,
    YEAR,
    FOLDER,
    PLAYLIST,
    ;

    /** 非 Compose 兜底；主路径走 UiText.displayName / labelRes。 */
    val label: String
        get() {
            val id = when (this) {
                SONGS -> R.string.view_songs
                ALBUM -> R.string.view_albums
                ARTIST -> R.string.view_artists
                ALBUM_ARTIST -> R.string.view_album_artists
                GENRE -> R.string.view_genres
                YEAR -> R.string.view_years
                FOLDER -> R.string.view_folders
                PLAYLIST -> R.string.view_playlists
            }
            val s = AppStrings.s(id)
            return s.ifEmpty {
                when (this) {
                    SONGS -> "歌曲"
                    ALBUM -> "专辑"
                    ARTIST -> "歌手"
                    ALBUM_ARTIST -> "合辑艺人"
                    GENRE -> "流派"
                    YEAR -> "年代"
                    FOLDER -> "文件夹"
                    PLAYLIST -> "歌单"
                }
            }
        }

    companion object {
        /** 从持久化的名字还原，非法值回落到「歌曲」 */
        fun from(name: String): LibraryView = entries.firstOrNull { it.name == name } ?: SONGS
    }
}

/** 专辑 / 歌手 / 文件夹的分组条目 */
@Immutable
data class GroupInfo(
    val key: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val sample: Song?,
)

/** 歌单条目（含四个固定的虚拟歌单：最近播放 / 最近添加 / 最常播放 / 我的收藏） */
@Immutable
data class PlaylistInfo(
    val id: Long,
    val name: String,
    val songCount: Int,
    /**
     * 列表封面用的前几首（最多 4 首，按歌单顺序）。
     * 0 首 → 占位图标；1 首 → 单图；2~4 首 → 2×2 拼图。
     */
    val coverSongs: List<Song> = emptyList(),
    val virtual: Boolean = false,
) {
    /** 兼容旧调用：拼图第一格 / 单图封面 */
    val coverSong: Song? get() = coverSongs.firstOrNull()
}

/** 歌单列表封面取前 [limit] 首，供 2×2 拼图。 */
fun playlistCoverSongs(songs: List<Song>, limit: Int = 4): List<Song> =
    if (limit <= 0) emptyList() else songs.take(limit)

/** 封面区布局：空歌单图标 / 单曲整图 / 2×2 拼图（不足 4 格留空格）。 */
enum class PlaylistCoverMode { ICON, SINGLE, GRID }

fun playlistCoverMode(coverCount: Int): PlaylistCoverMode = when {
    coverCount <= 0 -> PlaylistCoverMode.ICON
    coverCount == 1 -> PlaylistCoverMode.SINGLE
    else -> PlaylistCoverMode.GRID
}

/** 虚拟歌单的主键（负数，不会与自增歌单撞号） */
object VirtualPlaylist {
    const val RECENT = -1L
    const val MOST_PLAYED = -2L
    const val FAVORITES = -3L
    /** 「最近添加」——按歌曲 dateAdded 倒序（扫描入库 / MediaStore DATE_ADDED） */
    const val RECENTLY_ADDED = -4L
    /** 「从未播放」——智能歌单：曲库里存在但一次都没播过 */
    const val NEVER_PLAYED = -5L
    /** 「很久没听」——智能歌单：播过但 [DORMANT_DAYS] 天内没再听 */
    const val DORMANT = -6L

    /** 「很久没听」的判定天数 */
    const val DORMANT_DAYS = 30
}

/**
 * 从曲库歌曲列表抽出「最近添加」虚拟列表。
 *
 * 只保留 `dateAdded > 0` 的行（0 表示未知入库时间，沉底无意义）；
 * 上限与其它虚拟歌单一致（默认 100）。纯函数，方便单测与 PlaylistStore 共用。
 */
fun recentlyAddedSongs(songs: List<Song>, limit: Int = 100): List<Song> {
    if (limit <= 0 || songs.isEmpty()) return emptyList()
    return songs.asSequence()
        .filter { it.dateAdded > 0L }
        .sortedByDescending { it.dateAdded }
        .take(limit)
        .toList()
}

/**
 * 「从未播放」虚拟列表：曲库里存在、但 `song_state.playCount` 为 0 的歌。
 *
 * 与「最近播放」（有 lastPlayedAt 才进）互补——两个列表加起来覆盖全库，
 * 用户可以很直观地看到「收藏了这么多，还有多少从没开过口」。纯函数，方便单测。
 * 顺序沿用曲库当前排序（调用方传入的即已排好），不在此处重排。
 */
fun neverPlayedSongs(
    songs: List<Song>,
    states: Collection<com.luckylearning.windtone.data.db.SongStateEntity>,
    limit: Int = 100,
): List<Song> {
    if (limit <= 0 || songs.isEmpty()) return emptyList()
    val played = states.asSequence().filter { it.playCount > 0 }.map { it.songId }.toHashSet()
    return songs.filter { it.dbId > 0L && it.dbId !in played }.take(limit)
}

/**
 * 「很久没听」虚拟列表：播过、但 [days] 天内没有再听过的歌，最久没听的排前面。
 *
 * 只看 `song_state.lastPlayedAt`：0（从未播）不算「很久没听」——那归 [neverPlayedSongs]，
 * 两个智能歌单的口径不重叠。纯函数，方便单测。
 */
fun dormantSongs(
    songs: List<Song>,
    states: Collection<com.luckylearning.windtone.data.db.SongStateEntity>,
    nowMs: Long,
    days: Int = VirtualPlaylist.DORMANT_DAYS,
    limit: Int = 100,
): List<Song> {
    if (limit <= 0 || songs.isEmpty() || days <= 0) return emptyList()
    val cutoff = nowMs - days * 24L * 60L * 60L * 1000L
    val lastPlayed = states.filter { it.lastPlayedAt > 0 }.associate { it.songId to it.lastPlayedAt }
    return songs
        .filter { it.dbId > 0L && (lastPlayed[it.dbId] ?: 0L) in 1..cutoff }
        .sortedBy { lastPlayed[it.dbId] ?: 0L }
        .take(limit)
}

/**
 * 四个虚拟歌单的展示条目（名称 / 曲目数 / 封面取自曲库状态）。
 *
 * 为什么需要这个函数、而不是去 `LibraryUiState.playlists` 里找：那个列表里
 * **只有用户自建歌单**（直接来自 playlist 表），虚拟歌单从来没进去过 ——
 * 它们是「曲库内存状态的一个视图」，由界面现拼。
 * v1.1.12 之前「打开我的收藏」快捷方式就是去 `playlists` 里按 id 找，
 * 永远找不到，于是只切了个视图就结束，用户看到的是歌单列表页（见 §6.7）。
 * 收到这里之后，列表页与快捷方式走的是**同一个构造函数**，不会再各拼一份。
 */
fun virtualPlaylistInfo(id: Long, library: LibraryUiState): PlaylistInfo = when (id) {
    VirtualPlaylist.FAVORITES ->
        PlaylistInfo(
            id,
            AppStrings.s(R.string.vp_favorites).ifEmpty { "我的收藏" },
            library.extraFavorites.size,
            playlistCoverSongs(library.extraFavorites),
            virtual = true,
        )

    VirtualPlaylist.RECENT ->
        PlaylistInfo(
            id,
            AppStrings.s(R.string.vp_recent).ifEmpty { "最近播放" },
            library.extraRecent.size,
            playlistCoverSongs(library.extraRecent),
            virtual = true,
        )

    VirtualPlaylist.RECENTLY_ADDED ->
        PlaylistInfo(
            id,
            AppStrings.s(R.string.vp_recently_added).ifEmpty { "最近添加" },
            library.extraRecentlyAdded.size,
            playlistCoverSongs(library.extraRecentlyAdded),
            virtual = true,
        )

    VirtualPlaylist.MOST_PLAYED ->
        PlaylistInfo(
            id,
            AppStrings.s(R.string.vp_most_played).ifEmpty { "最常播放" },
            library.extraMostPlayed.size,
            playlistCoverSongs(library.extraMostPlayed),
            virtual = true,
        )

    VirtualPlaylist.NEVER_PLAYED ->
        PlaylistInfo(
            id,
            AppStrings.s(R.string.vp_never_played).ifEmpty { "从未播放" },
            library.extraNeverPlayed.size,
            playlistCoverSongs(library.extraNeverPlayed),
            virtual = true,
        )

    VirtualPlaylist.DORMANT ->
        PlaylistInfo(
            id,
            AppStrings.s(R.string.vp_dormant).ifEmpty { "很久没听" },
            library.extraDormant.size,
            playlistCoverSongs(library.extraDormant),
            virtual = true,
        )

    else -> error(AppStrings.s(R.string.vp_not_virtual, id).ifEmpty { "不是虚拟歌单：$id" })
}

/**
 * 设置页「扫描范围」里的一行目录。
 *
 * 为什么不是裸的 treeUri：用户要在这一行上做「排除 / 取消排除」的判断，
 * 得看到**目录名、路径、歌曲数与排除状态**，只给一串 `content://...` 说明不了任何事。
 * [treeUri] 仍是操作时用的键（扫描、移除、释放权限都按它走）。
 */
@Immutable
data class FolderItem(
    val treeUri: String,
    val name: String,
    val fullPath: String,
    val songCount: Int,
    /** 已排除：扫描时跳过这棵树，但**保留已导入的歌** */
    val excluded: Boolean = false,
)

@Immutable
data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val visible: List<Song> = emptyList(),
    val query: String = "",
    /** 曲库格式筛选（H5）；落盘见 Prefs.libraryFormatFilter */
    val formatFilter: LibraryFormatFilter = LibraryFormatFilter.NONE,
    val sortOrder: SortOrder = SortOrder.TITLE,
    val scanning: Boolean = false,
    val statusText: String = "",
    val skippedUnsupported: Int = 0,
    val lyricsCount: Int = 0,
    /**
     * 曲库的**首批装载是否已经给出结论**（v1.1.13 新增）。
     *
     * 语义是「要么拿到了歌，要么已经能确定就是没有」——不是「有歌」。
     * 为什么需要它：桌面入口（快捷方式 / 小组件）要等曲库就绪才能执行
     * （冷启动第一帧 `songs` 一定为空，那时调 `playQueue` 会被
     * `startQueue` 的 `if (queue.isEmpty()) return` 静默吞掉）。
     * 而「第一帧」再没有任何别的信号能判：`scanning` 此刻是 false（还没开始扫）、
     * `scanScope` 是上次选的值、`songs` 是空的 —— 三个都"看着像"已经就绪，
     * 早期的判据正是栽在这里（见 §6.7）。
     *
     * 注意 `scanning` 不能替代它：扫描中的空曲库应该**继续等**（扫完就有歌了），
     * 而装载结束的空曲库应该**立刻给提示**，两者恰好相反。
     */
    val libraryLoaded: Boolean = false,
    /**
     * 歌单（含虚拟歌单的曲目）是否已经算过至少一次（v1.1.13 新增）。
     *
     * [extraFavorites] 这些字段由 `PlaylistStore.refreshPlaylistsNow()` 填，
     * 与曲库装载是两条并行任务。所以「曲库就绪」不等于「收藏列表就绪」——
     * 「打开我的收藏」若只等前者，会打开一个还没算出内容的空收藏夹。
     */
    val playlistsLoaded: Boolean = false,
    val deepScan: Boolean = false,
    /** 扫描范围：未选择（首次安装）时不自动扫描，等待用户指定全盘或目录 */
    val scanScope: ScanScope = ScanScope.UNSET,
    /** 已导入的目录数量（含被排除的） */
    val folderCount: Int = 0,
    /** **参与扫描**的目录（SAF 树 URI）；被排除的目录不在这里 */
    val folderUris: List<String> = emptyList(),
    /** 目录明细（设置页展示与「排除」开关用） */
    val folders: List<FolderItem> = emptyList(),
    val view: LibraryView = LibraryView.SONGS,
    val groups: List<GroupInfo> = emptyList(),
    val selectedGroup: GroupInfo? = null,
    val detailSongs: List<Song> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val selectedPlaylist: PlaylistInfo? = null,
    val playlistSongs: List<Song> = emptyList(),
    val favoriteUris: Set<String> = emptySet(),
    val extraRecent: List<Song> = emptyList(),
    val extraMostPlayed: List<Song> = emptyList(),
    val extraFavorites: List<Song> = emptyList(),
    /** 「最近添加」：按 Song.dateAdded 倒序，最多 100 首 */
    val extraRecentlyAdded: List<Song> = emptyList(),
    /** 「从未播放」：曲库存在但 playCount=0，最多 100 首 */
    val extraNeverPlayed: List<Song> = emptyList(),
    /** 「很久没听」：播过但 30 天内没听，最久没听排前，最多 100 首 */
    val extraDormant: List<Song> = emptyList(),
    val letterIndex: Map<Char, Int> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
    val playStats: PlayStats = PlayStats(),
    // ---------------- 多选（批次 8.3） ----------------
    /**
     * 是否处于多选态。
     *
     * 与 [selection] 分开是必要的：用户「全部取消勾选」之后仍然在多选态里
     * （底部操作栏要留着，方便他重新选），此时 selection 为空但 selecting 为 true。
     */
    val selecting: Boolean = false,
    /** 已勾选的歌曲（stableKey / uri） */
    val selection: Set<String> = emptySet(),
    // ---------------- 收听报告（批次 10.1） ----------------
    val report: ReportUiState? = null,
    // ---------------- 重复清理（批次 10.2） ----------------
    /** 已扫描出的重复分组；空列表表示「没有重复」或「还没扫过」 */
    val duplicates: List<DuplicateGroup> = emptyList(),
    val duplicatesScanned: Boolean = false,
    val duplicatesScanning: Boolean = false,
)

/** 播放统计概览（设置页展示与「数据与存储」快照用） */
@Immutable
data class PlayStats(
    /** 播放过的歌曲数 */
    val listenedSongs: Int = 0,
    val totalPlays: Long = 0L,
    /** 累计收听时长（毫秒） */
    val totalListenedMs: Long = 0L,
    val favorites: Int = 0,
)

/** 榜单条目（收听报告用）：只带展示需要的字段，不背整个 Song */
@Immutable
data class TopSong(
    val songId: Long,
    val stableKey: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val listenedMs: Long,
    val cover: Song?,
)

/**
 * 收听报告页的状态。
 *
 * [days] 变化时整份重算（切换 7 天 / 30 天）；曲线与时段分布来自 `listen_log` 的小时桶，
 * 榜单来自 `song_state` 的累计统计——两者都只读本地库，不产生任何网络请求。
 */
@Immutable
data class ReportUiState(
    val days: Int = 30,
    val report: ListeningReport = EMPTY_REPORT,
    val topPlayed: List<TopSong> = emptyList(),
    val topListened: List<TopSong> = emptyList(),
    val loading: Boolean = false,
    /** 听歌日历（H7）；与报告共用同一小时桶数据源 */
    val calendar: CalendarUiState? = null,
)

/** 听歌日历某一月的 UI 状态 */
data class CalendarUiState(
    val year: Int,
    val month: Int,
    val monthData: ListenCalendarMonth? = null,
    val loading: Boolean = false,
)

/** 报告尚未加载时的占位（30 天空曲线），避免界面到处判空 */
val EMPTY_REPORT: ListeningReport = buildListeningReport(emptyList(), 0L, 1)

// ---------------------------------------------------------------- 播放

/**
 * 高频刷新的播放进度。
 *
 * 从 [PlayerUiState] 里拆出来的原因：进度每 200ms 就会变一次，
 * 混在 PlayerUiState 里会让整个主界面跟着每秒重组数次。
 * 拆开之后，只有真正读它的组件（进度条 / 迷你条 / 歌词面板）才重组。
 */
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
)

/**
 * 播放模式：把「随机」和「循环」两个开关归一成 4 个用户能理解的档位，
 * 共用播放页上的同一个按钮轮换，避免各占一个图标位。
 *
 * Media3 本身没有单一的“播放模式”，而是 `repeatMode`（关闭 / 列表 / 单曲）
 * 与 `shuffleModeEnabled` 两个独立开关；这里约定随机优先于循环，
 * 与主流播放器的表现一致。
 */
enum class PlayMode {
    /** 顺序播放：播完最后一首就停 */
    SEQUENTIAL,

    /** 列表循环 */
    REPEAT_ALL,

    /** 单曲循环 */
    REPEAT_ONE,

    /** 随机播放（乱序 + 列表循环） */
    SHUFFLE,
    ;

    /** 点击按钮后切换到下一档 */
    val next: PlayMode
        get() = when (this) {
            SEQUENTIAL -> REPEAT_ALL
            REPEAT_ALL -> REPEAT_ONE
            REPEAT_ONE -> SHUFFLE
            SHUFFLE -> SEQUENTIAL
        }

    /** 展示名，同时用作切换后的浮动提示文案 */
    val label: String
        get() {
            val id = when (this) {
                SEQUENTIAL -> R.string.play_mode_sequential
                REPEAT_ALL -> R.string.play_mode_repeat_all
                REPEAT_ONE -> R.string.play_mode_repeat_one
                SHUFFLE -> R.string.play_mode_shuffle
            }
            val s = AppStrings.s(id)
            return s.ifEmpty {
                when (this) {
                    SEQUENTIAL -> "顺序播放"
                    REPEAT_ALL -> "列表循环"
                    REPEAT_ONE -> "单曲循环"
                    SHUFFLE -> "随机播放"
                }
            }
        }

    /**
     * 该档位对应的 Media3 底层开关（随机优先：SHUFFLE 同时打开列表循环）。
     * [cyclePlayMode] 据此落定控制器；接线顺序（先关随机再设循环 / 先设 repeatMode 再开随机）
     * 由调用方负责，避免闪过中间态。
     */
    fun targets(): Pair<Int, Boolean> = when (this) {
        SEQUENTIAL -> Player.REPEAT_MODE_OFF to false
        REPEAT_ALL -> Player.REPEAT_MODE_ALL to false
        REPEAT_ONE -> Player.REPEAT_MODE_ONE to false
        SHUFFLE -> Player.REPEAT_MODE_ALL to true
    }
}

/** 由 Media3 的 repeatMode + shuffle 两个开关推导当前档位（随机优先） */
fun playModeOf(repeatMode: Int, shuffle: Boolean): PlayMode = when {
    shuffle -> PlayMode.SHUFFLE
    repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
    repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
    else -> PlayMode.SEQUENTIAL
}

@Immutable
data class PlayerUiState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val lyrics: Lyrics = Lyrics.EMPTY,
    val lyricsLoading: Boolean = false,
    val lyricOffsetMs: Int = 0,
    val lyricScale: Float = 1f,
    /** 点击歌词行是否跳转（默认关，v1.1.23） */
    val lyricTapSeeks: Boolean = SettingsDefaults.LYRIC_TAP_SEEKS,
    /** 悬浮窗侧钮（收起 / 播放暂停 / 播放页）是否显示（v1.2.0，仅车载模式生效） */
    val overlayHandles: Boolean = SettingsDefaults.OVERLAY_HANDLES,
    /** 单颗侧钮开关（v1.3.0） */
    val overlayHandleCollapse: Boolean = SettingsDefaults.OVERLAY_HANDLE_COLLAPSE,
    val overlayHandlePlayPause: Boolean = SettingsDefaults.OVERLAY_HANDLE_PLAY_PAUSE,
    val overlayHandlePlayer: Boolean = SettingsDefaults.OVERLAY_HANDLE_PLAYER,
    /** 侧钮无障碍三档：0 不显示 / 1 仅未运行时显示 / 2 常显（2026-10-31） */
    val overlayHandleA11yMode: Int = SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE,
    /** 侧钮上下顺序 CSV（B1），默认与改前写死顺序一致 */
    val overlayHandleOrder: String = SettingsDefaults.OVERLAY_HANDLE_ORDER,
    /** 播放页左侧竖排是否显示「无障碍状态」按钮（v1.1.92） */
    val showA11yAction: Boolean = SettingsDefaults.PLAYER_A11Y_ACTION,
    /**
     * 双语歌词显示模式：0 中英都显示 / 1 只显示中文 / 2 只显示英文（v1.1.18）。
     *
     * ⚠️ 这里已经**过滤过**的 [lyrics] 才是正确显示源；原始歌词在引擎里另存一份，
     * 只给导出用。别在渲染处再按本字段过滤一遍。
     */
    /** 播放页中部显示歌词（true）还是封面（false）；跨会话保留 */
    val lyricsPaneVisible: Boolean = true,
    /** 车载模式：启动直进播放页 + 播放页 5 秒无操作进入沉浸态 */
    val carMode: Boolean = false,
    /** 桌面悬浮歌词：退到桌面后仍在最上层显示当前歌词行 */
    val lyricOverlay: Boolean = false,
    /** 悬浮歌词显示行数：1..5（当前行居中，上下带上下文） */
    val lyricOverlayLines: Int = 1,
    /** 悬浮窗最上面是否显示「歌名 + 歌手」两行小字 */
    val lyricOverlayShowTitle: Boolean = SettingsDefaults.OVERLAY_SHOW_TITLE,
    /** 歌名 / 歌手两行小字的字号：正文的百分比（40..100，v1.1.51） */
    val lyricOverlayTitleSp: Int = SettingsDefaults.OVERLAY_TITLE_SP,
    /** 悬浮歌词正文字号（sp，12..60，v1.1.51 起任意值） */
    val lyricOverlayTextSp: Int = SettingsDefaults.OVERLAY_TEXT_SP,
    /** 悬浮歌词暂停时：0 淡出 / 1 隐藏 */
    val lyricOverlayPauseMode: Int = 0,
    /**
     * 「不显示悬浮窗的应用」名单（包名集合，v1.1.29）。
     * 这些应用在前台时收起悬浮歌词；空 = 功能不介入。
     */
    val lyricOverlayHiddenApps: Set<String> = SettingsDefaults.OVERLAY_HIDDEN_APPS,
    /** 是否记录「前台应用切换」历史（v1.1.29，默认关；记录只写本机文件） */
    val foregroundLogEnabled: Boolean = SettingsDefaults.FOREGROUND_LOG_ENABLED,
    /** 调试分区是否已解锁（v1.1.31，默认隐藏；关于连点 5 次打开） */
    val debugUnlocked: Boolean = SettingsDefaults.DEBUG_UNLOCKED,
    /** 悬浮歌词水平对齐：0 居中 / 1 居左 */
    val lyricOverlayAlign: Int = 0,
    /** 悬浮窗宽度：屏幕宽度的百分比（1..100，v1.1.4 起可调） */
    val lyricOverlayWidthPercent: Int = SettingsDefaults.OVERLAY_WIDTH_PERCENT,
    /**
     * 悬浮歌词「全屏高度」（v1.1.19）：铺满屏宽、高度 = 屏高 − 车机 Dock 让位，
     * 内容按播放页歌词区排版（当前行居中放大，上下各 5 句压暗）。
     */
    val lyricOverlayFullHeight: Boolean = SettingsDefaults.OVERLAY_FULL_HEIGHT,
    /**
     * 全屏模式下的窗口宽度（屏宽百分比，30..100）与水平位置（余量百分比，0 最左 / 50 居中 / 100 最右）。
     *
     * 与单条字幕的 [lyricOverlayWidthPercent] 分开存：两种形态是两套排版，
     * 调窄整屏歌词不应该顺手把单条字幕也缩窄。
     */
    val lyricOverlayFullWidthPercent: Int = SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT,
    val lyricOverlayFullOffsetPercent: Int = SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT,
    /**
     * 自定义尺寸 / 位置 / 背景（v1.1.48）。
     *
     * 位置一律存「可移动余量的百分比」而不是像素：换分辨率、转屏之后仍然正确，
     * 也才能让设置页的滑块与桌面拖动共用同一份数据。
     */
    val lyricOverlayBoxHeightPercent: Int = SettingsDefaults.OVERLAY_BOX_HEIGHT_PERCENT,
    val lyricOverlayFullHeightPercent: Int = SettingsDefaults.OVERLAY_FULL_HEIGHT_PERCENT,
    val lyricOverlayXPercent: Int = SettingsDefaults.OVERLAY_X_PERCENT,
    val lyricOverlayYPercent: Int = SettingsDefaults.OVERLAY_Y_PERCENT,
    val lyricOverlayFullYPercent: Int = SettingsDefaults.OVERLAY_FULL_Y_PERCENT,
    val lyricOverlayBgColor: Int = SettingsDefaults.OVERLAY_BG_COLOR,
    /** 背景不透明度（0..100，0 = 无底板）——v1.1.51 起背景只由颜色 + 这一项决定 */
    val lyricOverlayBgAlphaPercent: Int = SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT,
    /** v1.1.52 样式细节 */
    val lyricOverlayCornerRadiusDp: Int = SettingsDefaults.OVERLAY_CORNER_RADIUS_DP,
    val lyricOverlayBorderWidthDp: Int = SettingsDefaults.OVERLAY_BORDER_WIDTH_DP,
    val lyricOverlayBorderColor: Int = SettingsDefaults.OVERLAY_BORDER_COLOR,
    val lyricOverlayLineSpacingDp: Int = SettingsDefaults.OVERLAY_LINE_SPACING_DP,
    val lyricOverlayCurrentColor: Int = SettingsDefaults.OVERLAY_CURRENT_COLOR,
    val lyricOverlayDimPercent: Int = SettingsDefaults.OVERLAY_DIM_PERCENT,
    val lyricOverlayStrokeWidthDp: Int = SettingsDefaults.OVERLAY_STROKE_WIDTH_DP,
    val lyricOverlayStrokeColor: Int = SettingsDefaults.OVERLAY_STROKE_COLOR,
    /** 描边总开关（v1.2.53）：关掉 = 纯文字，连可读性阴影都不画 */
    val lyricOverlayStrokeEnabled: Boolean = SettingsDefaults.OVERLAY_STROKE_ENABLED,
    val lyricOverlayFadeMs: Int = SettingsDefaults.OVERLAY_FADE_MS,
    val lyricOverlayTitleMode: Int = SettingsDefaults.OVERLAY_TITLE_MODE,
    /** v1.2.54 标题块：位置（0 上 / 1 下）、对齐（0 中 / 1 左 / 2 右）、颜色（0 跟随） */
    val lyricOverlayTitlePos: Int = SettingsDefaults.OVERLAY_TITLE_POS,
    val lyricOverlayTitleAlign: Int = SettingsDefaults.OVERLAY_TITLE_ALIGN,
    val lyricOverlayTitleColor: Int = SettingsDefaults.OVERLAY_TITLE_COLOR,
    val lyricOverlayTitleStrokeEnabled: Boolean = SettingsDefaults.OVERLAY_TITLE_STROKE_ON,
    val lyricOverlayTitleStrokeWidthDp: Int = SettingsDefaults.OVERLAY_TITLE_STROKE_WIDTH_DP,
    val lyricOverlayTitleStrokeColor: Int = SettingsDefaults.OVERLAY_TITLE_STROKE_COLOR,
    /** v1.2.54 分角色字号（正文字号的百分比）与「其他行」颜色（0 跟随） */
    val lyricOverlayCurrentSp: Int = SettingsDefaults.OVERLAY_CURRENT_SP,
    val lyricOverlayContextSp: Int = SettingsDefaults.OVERLAY_CONTEXT_SP,
    val lyricOverlayContextColor: Int = SettingsDefaults.OVERLAY_CONTEXT_COLOR,
    /** 悬浮窗里的歌词文字颜色（ARGB，默认纯白） */
    val lyricOverlayTextColor: Int = SettingsDefaults.OVERLAY_TEXT_COLOR,
    /** 悬浮歌词是否处于「调整位置」模式（临时可拖动，会话级，不落盘） */
    val overlayEditing: Boolean = false,
    val playerExpanded: Boolean = false,
    val queueSize: Int = 0,
    val speed: Float = 1f,
    /** 变调：半音（-12..+12，0 = 原调）。倍率换算见 `playback/Pitch` */
    val pitchSemitones: Int = SettingsDefaults.PITCH_SEMITONES,
    val sleepRemainingMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val audioInfo: AudioInspector.Info? = null,
    val audioInfoLoading: Boolean = false,
    val skipSilence: Boolean = false,
    /** H2：片头固定跳过秒数（0=关） */
    val skipIntroSeconds: Int = SettingsDefaults.SKIP_INTRO_SECONDS,
    /** H2：片尾固定跳过秒数（0=关） */
    val skipOutroSeconds: Int = SettingsDefaults.SKIP_OUTRO_SECONDS,
    /**
     * A-B 循环点（C2，会话级，不落盘；切歌清除）。
     * null = 未设；两端都有且 B>A 时引擎在进度 ticker 里 seek 回 A。
     */
    val abLoopAMs: Long? = null,
    val abLoopBMs: Long? = null,
    /** D1：当前 A-B 是否来自「逐句循环」（与手动设 A/B 共用引擎；切歌清除） */
    val abLoopFromLyric: Boolean = false,
    /**
     * 打开应用时是否从上次的进度接着播（v1.1.16）。
     *
     * 与 [skipSilence] 一样是「播放域偏好」：改动只落 Prefs，真正的判定发生在
     * 冷启动恢复队列那一刻（见 `PlaybackEngine.restoreQueueIfNeeded`）。
     */
    val autoResume: Boolean = SettingsDefaults.AUTO_RESUME,
    /** 开机后台起播（v1.1.24，默认关） */
    val autoPlayOnBoot: Boolean = SettingsDefaults.AUTO_PLAY_ON_BOOT,
)

/**
 * 当前悬浮歌词参数 → 预设匹配用的参数快照（v1.1.78）。
 *
 * 「当前预设」不落盘（R5 零新键），由这组值反推：命中某套预设显示其名，
 * 与出厂默认一致显示「默认预设」，否则「自定义」（见 data/OverlayPresets.kt）。
 * 放在 state 包而不是 data 包：反向依赖只此一处，别让纯数据文件沾上 UI 状态类型。
 */
fun PlayerUiState.overlayPresetParams() = com.luckylearning.windtone.data.OverlayPresetParams(
    lines = lyricOverlayLines,
    widthPercent = lyricOverlayWidthPercent,
    boxHeightPercent = lyricOverlayBoxHeightPercent,
    xPercent = lyricOverlayXPercent,
    yPercent = lyricOverlayYPercent,
    textSp = lyricOverlayTextSp,
    align = lyricOverlayAlign,
    textColor = lyricOverlayTextColor,
    currentColor = lyricOverlayCurrentColor,
    dimPercent = lyricOverlayDimPercent,
    strokeWidthDp = lyricOverlayStrokeWidthDp,
    strokeColor = lyricOverlayStrokeColor,
    lineSpacingDp = lyricOverlayLineSpacingDp,
    showTitle = lyricOverlayShowTitle,
    titleSp = lyricOverlayTitleSp,
    titleMode = lyricOverlayTitleMode,
    bgAlphaPercent = lyricOverlayBgAlphaPercent,
    bgColor = lyricOverlayBgColor,
    cornerRadiusDp = lyricOverlayCornerRadiusDp,
    borderWidthDp = lyricOverlayBorderWidthDp,
    borderColor = lyricOverlayBorderColor,
    pauseMode = lyricOverlayPauseMode,
    fadeMs = lyricOverlayFadeMs,
)

/** 播放队列条目（队列抽屉用） */
@Immutable
data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val isCurrent: Boolean,
)

// ---------------------------------------------------------------- 更新

/**
 * 应用内更新的界面状态。
 *
 * 与 [PlayerUiState] 独立，因为更新流程的重组频率极低（只在用户操作时变），
 * 没必要混进播放状态里。
 */
@Immutable
data class UpdateUiState(
    /** 当前安装版本 */
    val currentVersion: String = "",
    /** 正在查询远端 */
    val checking: Boolean = false,
    /** 正在下载 APK */
    val downloading: Boolean = false,
    /** 下载进度 0..100；总长未知时为 -1 */
    val progress: Int = 0,
    /** 已下载 / 总字节，用于文案 */
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** 发现的新版本；为空表示暂无 */
    val info: UpdateInfo? = null,
    /** 是否弹出更新对话框（手动检查到新版本、或下载完成时置为 true） */
    val dialogVisible: Boolean = false,
    /** 一次性提示（成功 / 失败），展示后由界面调用 `consumeUpdateMessage()` 清掉 */
    val message: String? = null,
    /** 下载完成、待安装的本地文件路径 */
    val readyApkPath: String? = null,
    /** 待安装 APK 的版本号（v1.2.8 起随路径一起持久化，重启后更新页仍能显示「装的是哪个版本」） */
    val readyApkVersion: String = "",
    /** 需要用户先授权「安装未知应用」 */
    val needInstallPermission: Boolean = false,
    /** 本次下载使用的线路（自动 / 直连 / 指定镜像），用于在弹窗里说明当前走哪条线 */
    val route: DownloadRoute = DownloadRoute.AUTO,
    /**
     * 「测试延迟」的结果：每条线路到 GitHub 的响应耗时（毫秒）。
     * `null` = 该线路测不通（超时 / 被阻断）；键缺失 = 尚未测过。
     */
    val routeLatencies: Map<DownloadRoute, Long?> = emptyMap(),
    /** 正在逐条测试线路延迟 */
    val pingingRoutes: Boolean = false,
)
