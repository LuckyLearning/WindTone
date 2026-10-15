package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.model.Song

/**
 * 批量多选域对外的**动作面**（进入 / 退出多选、全选 / 反选、以及底部操作栏的批量动作）。
 *
 * 抽成接口的目的见 [LibraryActions]。注意 `bulkRemoveFromLibrary` **不在**这里：
 * 它的默认值 `selectedSongs()` 依赖实例状态，接口默认值无法表达，
 * 因此改由 `LibraryViewModel` 直接声明（详见该类里的说明）。
 */
interface SelectionActions {
    fun enterSelection(song: Song)
    fun toggleSelected(song: Song)
    fun selectAllVisible()
    fun invertSelection()
    fun exitSelection()
    fun bulkAddToPlaylist(playlistId: Long, playlistName: String)
    fun bulkSetFavorite(favorite: Boolean)
    fun bulkAddToQueue()
    fun restoreExcludedSongs()
}
