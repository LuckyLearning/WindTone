package com.luckylearning.windtone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.ui.library.DuplicateActions
import com.luckylearning.windtone.ui.library.FolderActions
import com.luckylearning.windtone.ui.library.LibraryActions
import com.luckylearning.windtone.ui.library.LibrarySession
import com.luckylearning.windtone.ui.library.M3uActions
import com.luckylearning.windtone.ui.library.PlaylistActions
import com.luckylearning.windtone.ui.library.ReportActions
import com.luckylearning.windtone.ui.library.SelectionActions
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 曲库域 ViewModel：界面与 [LibrarySession] 之间的那一层。
 *
 * 它自己不装任何行为——各摊行为分别住在 `ui.library` 包下的实现类里
 * （曲库 [com.luckylearning.windtone.ui.library.LibraryCore] / 文件夹
 * [com.luckylearning.windtone.ui.library.FolderController] / M3U 与歌曲信息
 * [com.luckylearning.windtone.ui.library.M3uStore] / 歌单 / 批量多选 / 收听报告 / 重复清理），
 * 动作面用 Kotlin 的 `by` 整块委托出去（与 `PlayerViewModel` 对 `PlayerActions` 的惯用法一致），
 * 所以这里没有几十个转发方法，只有三条「默认值依赖实例状态」的方法（见下面那段说明）。
 *
 * 唯一的共享状态 [library] 与消息流 [messages] 由 [LibrarySession] 持有，
 * 这里只是把它们导出给界面；公开 API 与拆分前逐字一致。
 */
class LibraryViewModel private constructor(
    app: Application,
    private val session: LibrarySession,
) : AndroidViewModel(app),
    LibraryActions by session.core,
    FolderActions by session.folders,
    M3uActions by session.m3u,
    PlaylistActions by session.playlists,
    SelectionActions by session.selection,
    ReportActions by session.reports,
    DuplicateActions by session.dedupe {

    val library: StateFlow<LibraryUiState> = session.library

    /** 全局一次性提示（扫描完成 / 收藏成功 / 播放失败都走这里），界面收集后弹 Snackbar */
    val messages: SharedFlow<String> = session.messages

    init {
        // 行为类统一挂到本 ViewModel 的 viewModelScope 上：作用域与调度器
        // （以及「随 ViewModel 一起取消」的语义）与拆分前的 `viewModelScope.launch` 完全一致。
        session.scope = viewModelScope
        reloadAll()
    }

    // ---------------------------------------------------------------- 默认值依赖实例状态的三条方法
    //
    // 这三条不走接口委托：Kotlin 规定「重写不能声明默认值」，而接口上的默认值表达式
    // 又拿不到实例状态（`selectedSongs()` / 当前报告天数）。因此在这里直接声明默认值，
    // 再转发给对应域的实现；接口侧则刻意不包含它们（见三个 Actions 接口的注释）。

    fun loadReport(days: Int = session.reports.currentDays()) = session.reports.loadReport(days)

    fun bulkRemoveFromLibrary(songs: List<Song> = session.selection.selectedSongs()) =
        session.selection.bulkRemoveFromLibrary(songs)

    fun bulkDeleteFiles(
        songs: List<Song> = session.selection.selectedSongs(),
        launchSystemConfirm: (android.content.IntentSender) -> Unit,
    ) = session.core.bulkDeleteFiles(songs, launchSystemConfirm)

    constructor(app: Application) : this(app, LibrarySession(app))
}
