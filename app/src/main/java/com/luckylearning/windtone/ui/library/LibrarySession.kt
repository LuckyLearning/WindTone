package com.luckylearning.windtone.ui.library

import android.app.Application
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.LibraryView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 曲库域的「共享状态宿主」。
 *
 * 拆分自原先的 1695 行 `LibraryViewModel`：那份文件同时装着五摊互不相干的行为
 * （曲库 / 歌单 / 批量多选 / 收听报告 / 重复清理），它们却共用一个
 * [_library] 状态流、一个消息总线、一份曲库索引与数据库句柄。这里把这份
 * **共享设施**收拢到一处，再装配出五个行为类（[LibraryCore] / [PlaylistStore] /
 * [SelectionController] / [ReportController] / [DuplicateController]）。
 *
 * 为什么状态不跟着一起拆：[LibraryUiState] 是一个整体广播给界面的（`MainScreen`
 * 顶层 `collectAsStateWithLifecycle` 一次性订阅全部字段），把一个 `StateFlow`
 * 拆成五个只会让界面多订阅四次、还会引入跨域字段的粘连；所以这里仍然是
 * **唯一的 `MutableStateFlow<LibraryUiState>`**，五个行为类都读写它。
 *
 * 为什么行为要拆：五摊行为之间的耦合其实很稀（曲库刷新时顺手刷一下歌单和统计），
 * 真正的耦合点是上面那几件共享设施，把它们抽出来后每摊行为都能独立阅读。
 *
 * 线程约定：行为类不自己建作用域，统一用 [scope]（由 `LibraryViewModel` 在构造后
 * 覆盖成它自己的 `viewModelScope`，见 `LibraryViewModel.init`），IO 再切 IO 线程——
 * 与拆分前的 `viewModelScope.launch` 完全一致。
 */
internal class LibrarySession(app: Application) {

    internal val app: Application = app

    private val container = (app as MusicApp).container
    internal val prefs = container.prefs
    internal val repository = container.libraryRepository
    internal val db = container.db
    internal val lyricsLoader = container.lyricsLoader
    internal val index = container.songIndex
    internal val bus = container.messages
    internal val playback = container.playback

    /**
     * 行为类共用的协程作用域。
     *
     * 默认值与 `viewModelScope` 形态一致（`SupervisorJob + Dispatchers.Main.immediate`），
     * 但**构造后会被 `LibraryViewModel` 覆盖成它真正的 `viewModelScope`**，
     * 这样随 ViewModel 一起取消的语义与拆分前完全相同（见 `LibraryViewModel.init`）。
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 唯一的共享状态流；界面只读导出的 [library]，行为类读写它 */
    internal val _library = MutableStateFlow(
        LibraryUiState(
            sortOrder = prefs.sortOrder,
            view = LibraryView.from(prefs.libraryView),
            deepScan = repository.deepScanEnabled(),
            scanScope = prefs.scanScope,
        )
    )
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    /** 全局一次性提示（扫描完成 / 收藏成功 / 播放失败都走这里），界面收集后弹 Snackbar */
    val messages: SharedFlow<String> = bus.messages

    internal val core: LibraryCore = LibraryCore(this)
    internal val folders: FolderController = FolderController(this)
    internal val m3u: M3uStore = M3uStore(this)
    internal val playlists: PlaylistStore = PlaylistStore(this)
    internal val selection: SelectionController = SelectionController(this)
    internal val reports: ReportController = ReportController(this)
    internal val dedupe: DuplicateController = DuplicateController(this)

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    internal fun idOf(song: Song): Long? = index.idOf(song)

    internal fun pushMessage(text: String) = bus.post(text)
}
