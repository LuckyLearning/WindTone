package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryView

/**
 * 曲库域对外的**动作面**（扫描 / 视图 / 排序 / 搜索 / 收藏 / 删除文件 / 统计 / 搜索历史）。
 *
 * 抽成接口的唯一目的：让 `LibraryViewModel` 用 Kotlin 的委托（`by`）把它整块转出去，
 * 而不是手写几十个转发方法——转发代码既没有信息量，又会在加新动作时被漏掉。
 * 接口必须是 `public`：`LibraryViewModel` 是 public，Kotlin 不允许 public 类的
 * 超类型里出现 internal 接口（"public class exposes internal supertype"）。
 *
 * 带默认值的 [scan] 保留在接口上：Kotlin 规定重写**不能**再声明默认值，
 * 所以默认值只能挂在接口声明上，由调用点静态解析。
 *
 * 原先把「文件夹 / SAF / 歌词 / 权限」拆去了 [FolderActions]，把「M3U 与歌曲信息编辑」
 * 拆去了 [M3uActions]——同一个实现类 [LibraryCore] 已超过千行，按分节边界分开后
 * 每摊都能独立阅读；`LibraryViewModel` 的委托列表相应变成三块。
 */
interface LibraryActions {
    fun reloadAll()
    fun onLibraryCleared()
    fun startFullScan()
    fun setScanScope(scope: ScanScope)
    fun minSongDurationSec(): Int
    fun setMinSongDurationSec(sec: Int)
    fun scan(auto: Boolean = false)
    fun setQuery(q: String)
    fun setSort(order: SortOrder)
    fun setView(view: LibraryView)
    fun openGroup(group: GroupInfo)
    fun closeGroup()
    fun toggleFavorite(song: Song)
    fun deleteSong(song: Song, launchSystemConfirm: (android.content.IntentSender) -> Unit)
    fun onSystemDeleteResult(resultOk: Boolean)
    fun onSystemBulkDeleteResult(resultOk: Boolean)
    fun clearPlayStats()
    fun submitSearch(query: String)
    fun clearSearchHistory()
}
