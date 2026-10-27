package com.luckylearning.windtone.state

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.DuplicateGroup
import com.luckylearning.windtone.data.ListeningReport
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.UpdateInfo
import com.luckylearning.windtone.data.buildListeningReport
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.Lyrics

/**
 * 界面状态的**全部类型定义**。
 *
 * 这些类型原先散在 `MusicViewModel.kt` 文件头部。分域之后它们被四个 ViewModel
 * 与若干无状态组件（如 [playModeOf]）共用，所以单独成文件——
 * 谁都不再需要 import 某个具体的 ViewModel 才能拿到状态类型。
 */

// ---------------------------------------------------------------- 曲库

/** 库主视图：歌曲 / 专辑 / 歌手 / 文件夹 / 歌单 */
enum class LibraryView(val label: String) {
    SONGS("歌曲"),
    ALBUM("专辑"),
    ARTIST("歌手"),
    FOLDER("文件夹"),
    PLAYLIST("歌单");

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

/** 歌单条目（含三个固定的虚拟歌单：最近播放 / 最常播放 / 我的收藏） */
@Immutable
data class PlaylistInfo(
    val id: Long,
    val name: String,
    val songCount: Int,
    val coverSong: Song?,
    val virtual: Boolean = false,
)

/** 虚拟歌单的主键（负数，不会与自增歌单撞号） */
object VirtualPlaylist {
    const val RECENT = -1L
    const val MOST_PLAYED = -2L
    const val FAVORITES = -3L
}

/**
 * 三个虚拟歌单的展示条目（名称 / 曲目数 / 封面取自曲库状态）。
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
        PlaylistInfo(id, "我的收藏", library.extraFavorites.size, library.extraFavorites.firstOrNull(), virtual = true)

    VirtualPlaylist.RECENT ->
        PlaylistInfo(id, "最近播放", library.extraRecent.size, library.extraRecent.firstOrNull(), virtual = true)

    VirtualPlaylist.MOST_PLAYED ->
        PlaylistInfo(id, "最常播放", library.extraMostPlayed.size, library.extraMostPlayed.firstOrNull(), virtual = true)

    else -> error("不是虚拟歌单：$id")
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
     * 歌单（含三个虚拟歌单的曲目）是否已经算过至少一次（v1.1.13 新增）。
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
        get() = when (this) {
            SEQUENTIAL -> "顺序播放"
            REPEAT_ALL -> "列表循环"
            REPEAT_ONE -> "单曲循环"
            SHUFFLE -> "随机播放"
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
    val lyricOverlayTitlePercent: Int = SettingsDefaults.OVERLAY_TITLE_PERCENT,
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
    val lyricOverlayFadeMs: Int = SettingsDefaults.OVERLAY_FADE_MS,
    val lyricOverlayTitleMode: Int = SettingsDefaults.OVERLAY_TITLE_MODE,
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
    /** 无缝播放显式开关（C1，默认开） */
    val gaplessPlayback: Boolean = SettingsDefaults.GAPLESS_PLAYBACK,
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
    titlePercent = lyricOverlayTitlePercent,
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
