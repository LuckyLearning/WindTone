package com.luckylearning.windtone.ui.library

import android.content.Intent
import android.net.Uri
import com.luckylearning.windtone.state.GroupInfo

/**
 * 文件夹域对外的**动作面**（SAF 目录导入 / 排除 / 移除、歌词刷新、扫描权限、移除分组扫描结果）。
 *
 * 从 [LibraryActions] 里按分节边界拆出来：这一摊操作的对象是「导入目录」而不是曲库本身，
 * 依赖与共享状态改从 [LibrarySession] 取（曲库域的实现是 [LibraryCore]）。
 * 抽成接口的目的与可见性取舍见 [LibraryActions]；[addFolder] 的默认值留在接口上
 * （Kotlin 重写不能声明默认值）。
 */
interface FolderActions {
    fun setFolderExcluded(treeUri: String, excluded: Boolean)
    fun addFolder(uri: Uri, folderScopeOnly: Boolean = false)
    fun removeFolder(uriString: String)
    fun folderUris(): List<String>
    fun removeFolderScanResults(group: GroupInfo)
    fun refreshLyrics()
    fun deepScanSettingsIntent(): Intent?
    fun notifyScanPermissionDenied()
    fun updateDeepScanFlag()
}
