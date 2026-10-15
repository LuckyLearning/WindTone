package com.luckylearning.windtone.ui.library

/**
 * 重复歌曲清理域对外的**动作面**。
 *
 * 抽成接口的目的见 [LibraryActions]。
 */
interface DuplicateActions {
    fun scanDuplicates()
    fun cleanDuplicates()
    fun clearDuplicateScan()
}
