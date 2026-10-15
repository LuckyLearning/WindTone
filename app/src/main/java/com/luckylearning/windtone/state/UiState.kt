package com.luckylearning.windtone.state

import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import com.luckylearning.windtone.data.AudioInspector
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.UpdateInfo
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

/** 歌单条目（含两个固定的虚拟歌单：最近播放 / 最常播放） */
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
}

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
    val deepScan: Boolean = false,
    /** 扫描范围：未选择（首次安装）时不自动扫描，等待用户指定全盘或目录 */
    val scanScope: ScanScope = ScanScope.UNSET,
    /** 已导入的目录数量 */
    val folderCount: Int = 0,
    /** 已导入的目录（SAF 树 URI），用于范围抽屉里的目录管理 */
    val folderUris: List<String> = emptyList(),
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
    val letterIndex: Map<Char, Int> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
    val playStats: PlayStats = PlayStats(),
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
    val lyricWordByWord: Boolean = true,
    /** 车载模式：启动直进播放页 + 播放页 5 秒无操作进入沉浸态 */
    val carMode: Boolean = false,
    /** 桌面悬浮歌词：退到桌面后仍在最上层显示当前歌词行 */
    val lyricOverlay: Boolean = false,
    /** 悬浮歌词显示行数：1 = 只显示当前行；3 = 当前行 + 上下各一行上下文 */
    val lyricOverlayLines: Int = 1,
    /** 悬浮歌词字号档位：0 小 / 1 中 / 2 大 */
    val lyricOverlayTextScale: Int = 1,
    /** 悬浮歌词底板：0 无 / 1 半透明 / 2 深色实底 */
    val lyricOverlayBackdrop: Int = 1,
    /** 悬浮歌词暂停时：0 淡出 / 1 隐藏 */
    val lyricOverlayPauseMode: Int = 0,
    /** 悬浮歌词水平对齐：0 居中 / 1 居左 */
    val lyricOverlayAlign: Int = 0,
    /** 悬浮歌词是否处于「调整位置」模式（临时可拖动，会话级，不落盘） */
    val overlayEditing: Boolean = false,
    val playerExpanded: Boolean = false,
    val queueSize: Int = 0,
    val speed: Float = 1f,
    val sleepRemainingMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val audioInfo: AudioInspector.Info? = null,
    val audioInfoLoading: Boolean = false,
    val skipSilence: Boolean = false,
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
    /** 需要用户先授权「安装未知应用」 */
    val needInstallPermission: Boolean = false,
    /** 本次下载使用的线路（自动 / 直连 / 指定镜像），用于在弹窗里说明当前走哪条线 */
    val route: DownloadRoute = DownloadRoute.AUTO,
)
