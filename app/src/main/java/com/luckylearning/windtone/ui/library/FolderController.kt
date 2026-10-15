package com.luckylearning.windtone.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.db.FolderEntity
import com.luckylearning.windtone.data.docIdFullPath
import com.luckylearning.windtone.data.docIdOf
import com.luckylearning.windtone.data.folderDisplayName
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.treeDisplayName
import com.luckylearning.windtone.state.FolderItem
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件夹域的**实现**（SAF 目录导入 / 排除 / 移除、歌词刷新、扫描权限、移除分组扫描结果）。
 *
 * 从 [LibraryCore] 里按分节边界拆出来的一摊：它持有「导入目录」的内存副本
 * （[folders]）以及围绕这份副本的几个查询/刷新辅助方法，曲库域（扫描、应用歌曲列表）
 * 需要读目录时直接调这里的 `internal` 方法，不再各自持有第二份。
 */
internal class FolderController(private val session: LibrarySession) : FolderActions {

    private val app = session.app
    private val prefs = session.prefs
    private val repository = session.repository
    private val db = session.db
    private val index = session.index
    private val playback = session.playback
    private val _library = session._library

    private val scope get() = session.scope

    /**
     * 导入目录的内存副本。
     *
     * 目录的唯一事实源是 Room 的 `folders` 表（顺序、叶名、完整路径、歌曲数都在那里），
     * 但界面要同步读（范围抽屉、扫描前判断），所以启动时装载一份、每次变更后刷新。
     */
    internal var folders: List<FolderEntity> = emptyList()

    internal fun folderUriList(): List<String> = folders.filterNot { it.excluded }.map { it.treeUri }

    /** 目录明细（设置页展示与「排除」开关用；被排除的目录也在列表里，只是带标记） */
    internal fun folderItemList(): List<FolderItem> = folders.map {
        FolderItem(
            treeUri = it.treeUri,
            name = it.name,
            fullPath = it.fullPath,
            songCount = it.songCount,
            excluded = it.excluded,
        )
    }

    /**
     * 排除 / 取消排除某个导入目录。
     *
     * 与「移除目录」的区别：
     *  - 移除目录：连同已经导入的歌一起从曲库摘掉；
     *  - 排除目录：**保留已导入的歌**，只是以后不再扫这棵树。
     * 所以这里只写一个标记（外加刷新界面），不做任何删歌动作。
     */
    override fun setFolderExcluded(treeUri: String, excluded: Boolean) {
        val target = folders.firstOrNull { it.treeUri == treeUri } ?: return
        if (target.excluded == excluded) return
        scope.launch {
            withContext(Dispatchers.IO) {
                docIdOf(treeUri)?.let { db.folderDao().setExcluded(it, excluded) }
                reloadFolders()
            }
            pushMessage(
                if (excluded) "已排除「${target.name}」：以后不再扫描，已导入的 ${target.songCount} 首仍在曲库"
                else "已取消排除「${target.name}」，下次扫描会重新收录"
            )
            if (!excluded) session.core.scan(false)
        }
    }

    // ---------------------------------------------------------- 文件夹 / 歌词 / 权限

    override fun addFolder(uri: Uri, folderScopeOnly: Boolean) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "持久化文件夹权限失败", it) }
        // 写权限是「文件夹视图删除歌曲文件」的前提，拿不到也不影响读
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        scope.launch {
            withContext(Dispatchers.IO) { addFolderRow(uri.toString()) }
            // 首次导入目录即视为选定「指定目录」范围；用户明确选择「只看指定目录」时也强制切换
            if (folderScopeOnly || prefs.scanScope == ScanScope.UNSET) {
                prefs.scanScope = ScanScope.FOLDERS
                prefs.scanScopeChosen = true
            }
            _library.update {
                it.copy(
                    scanScope = prefs.scanScope,
                    folderCount = folders.size,
                    folderUris = folderUriList(),
                    folders = folderItemList(),
                )
            }
            session.core.scan(false)
        }
    }

    /**
     * 把一个树 URI 登记成导入目录（已存在则只刷新内存副本）。
     * 目录表是唯一事实源，`sort` 取当前最大值 +1，保持"用户添加顺序"。
     */
    private suspend fun addFolderRow(uriString: String) {
        val docId = docIdOf(uriString)
        if (docId != null) {
            val dao = db.folderDao()
            if (dao.byDocId(docId) == null) {
                val fullPath = docIdFullPath(docId)
                dao.insert(
                    FolderEntity(
                        treeUri = uriString,
                        docId = docId,
                        name = folderDisplayName(fullPath),
                        fullPath = fullPath,
                        sort = dao.maxSort() + 1,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        reloadFolders()
    }

    /** 重新装载目录列表（表 → 内存 → 界面状态） */
    internal suspend fun reloadFolders() {
        folders = withContext(Dispatchers.IO) { db.folderDao().all() }
        _library.update {
            it.copy(
                folderCount = folders.size,
                folderUris = folderUriList(),
                folders = folderItemList(),
            )
        }
    }

    /** 目录歌曲数冗余列：扫描后重算一次，避免分组时再聚合 */
    internal suspend fun refreshFolderCounts() {
        val dao = db.folderDao()
        val counts = db.songDao().countsByFolder().associate { it.folderId to it.songCount }
        for (folder in dao.all()) {
            val c = counts[folder.id] ?: 0
            if (folder.songCount != c) dao.setSongCount(folder.id, c)
        }
    }

    /** 扫描后刷新目录的内存副本（目录的歌曲数在这一步重算） */
    internal suspend fun rememberScannedFolders() {
        withContext(Dispatchers.IO) { refreshFolderCounts() }
        reloadFolders()
    }

    /**
     * 移除一个已导入目录。
     *
     * 两条硬规则：
     *  1. **绝不因为移除目录而自动扫全盘**。以往在「全盘扫描」范围下移除目录会走
     *     `scan()`，也就是把整块存储重扫一遍，又慢又没道理；现在只更新配置。
     *  2. 移除最后一个目录时，如果当前范围是「指定目录」，范围回退到「未选择」，
     *     清空曲库与扫描缓存并提示重新选择（同样不扫描任何位置）。
     */
    override fun removeFolder(uriString: String) {
        if (folders.none { it.treeUri == uriString }) return
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // 目录配置变了：先作废正在跑的扫描，避免它把过期结果写回曲库/缓存
        session.core.cancelScan()

        // 立刻把「这个目录」带来的歌从曲库摘掉（只动应用内数据，不碰存储里的文件）
        val stale = songsFromTree(uriString)
        scope.launch {
            withContext(Dispatchers.IO) {
                docIdOf(uriString)?.let { db.folderDao().deleteByDocIds(listOf(it)) }
                reloadFolders()
            }
            if (stale.isNotEmpty()) dropSongs(stale)

            if (folders.isEmpty()) {
                if (prefs.scanScope == ScanScope.FOLDERS) {
                    prefs.scanScope = ScanScope.UNSET
                    prefs.scanScopeChosen = true
                    session.core.clearLibraryForScopeReset()
                    pushMessage("已移除全部目录，请重新选择扫描范围")
                } else {
                    // 全盘扫描范围：曲库由系统媒体库决定，导入的目录只是附加来源，无需重扫
                    pushMessage("已移除全部目录")
                }
                return@launch
            }

            when (prefs.scanScope) {
                // 「指定目录」范围里目录就是唯一来源，剩下的目录要重扫（只扫这几个，很快）
                ScanScope.FOLDERS -> session.core.scan(false)
                // 「全盘扫描」范围：为了一个目录重扫整块盘不值得，等用户手动重扫
                else -> pushMessage("已移除目录「${treeDisplayName(uriString)}」")
            }
        }
    }

    /**
     * 找出曲库里来自某个 SAF 树目录的歌曲。
     * 用文档 ID 前缀比对（`primary:Music/a.mp3` 是否在 `primary:Music` 下），
     * 这样能精确到「这一棵导入的目录树」，不会误伤同名的其他目录。
     */
    private fun songsFromTree(treeUri: String): List<Song> =
        _library.value.songs.filter { it.belongsToTree(treeUri) }

    /** 歌曲是否来自某个 SAF 树目录（文档 ID 前缀比对，与 songsFromTree 同口径） */
    private fun Song.belongsToTree(treeUri: String): Boolean {
        val treeDocId = docIdOf(treeUri) ?: return false
        val docId = docIdOf(uri.toString()) ?: return false
        return docId == treeDocId || docId.startsWith("$treeDocId/")
    }

    /** 从内存曲库与数据库中摘掉指定歌曲（不删除存储中的文件；收藏/歌单/统计随曲库行级联清理） */
    private fun dropSongs(removed: List<Song>) {
        session.core.applyRemovedFromMemory(removed)
        scope.launch {
            val ids = removed.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) { if (ids.isNotEmpty()) db.songDao().deleteByIds(ids) }
            index.removeUris(removed.map { it.stableKey })
            index.dropStates(ids.toSet())
        }
    }

    override fun folderUris(): List<String> = folderUriList()

    /** 重新为缺少歌词文件的歌曲匹配同目录 .lrc */
    override fun refreshLyrics() {
        scope.launch {
            _library.update { it.copy(statusText = "正在重新匹配歌词…") }
            val updated = repository.refreshSidecarLyrics(_library.value.songs)
            playback.setLibrarySongs(updated)
            val query = _library.value.query
            val visible = session.core.searchSongs(updated, query)
            _library.update {
                it.copy(
                    songs = updated,
                    visible = visible,
                    letterIndex = session.core.buildLetterIndex(visible),
                    lyricsCount = updated.count { s -> s.lrcUri != null },
                    statusText = "",
                )
            }
            pushMessage("已重新匹配歌词文件：${updated.count { it.lrcUri != null }} 首")
        }
    }

    override fun deepScanSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", app.packageName, null)
            }
        } else null

    /** 用户拒绝音频读取权限时的提示（不影响「指定目录」方式） */
    override fun notifyScanPermissionDenied() {
        pushMessage("没有读取音频的权限。可以改为「选择音乐目录」，用文件选择器授权指定文件夹。")
    }

    override fun updateDeepScanFlag() {
        _library.update { it.copy(deepScan = repository.deepScanEnabled()) }
    }

    /**
     * 移除文件夹视图中某个分组下的全部扫描结果。
     *
     * 分组按「添加的目录」展示，所以移除时**同步把对应的导入目录从扫描列表移除**
     * （释放 SAF 持久化权限、更新 folderUris），否则下次扫描同样的歌又会回来。
     * 只从应用内部状态与数据库中清除，**不会删除存储中的原始音频文件**。
     * 会从播放队列、歌单、收藏、播放统计、歌词/封面缓存中一并清理。
     */
    override fun removeFolderScanResults(group: GroupInfo) {
        if (_library.value.view != LibraryView.FOLDER) return
        val key = group.key
        val removed = _library.value.songs.filter { it.folderLabel == key }
        if (removed.isEmpty()) return
        scope.launch {
            // 1) 从播放器队列移除
            playback.removeFromQueue(removed)
            // 2) 批量删除曲库行：收藏 / 统计 / 歌单关联 / 队列条目由外键级联处理
            val removedIds = removed.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) {
                if (removedIds.isNotEmpty()) db.songDao().deleteByIds(removedIds)
                removed.forEach { CoverRepository.evict(it) }
            }
            index.removeUris(removed.map { it.stableKey })
            index.dropStates(removedIds.toSet())
            // 3) 更新内存状态（移除的正是当前打开的分组时，顺手把详情页关掉）
            if (_library.value.selectedGroup?.key == key) session.core.closeGroup()
            session.core.applyRemovedFromMemory(removed)
            session.core.refreshStats()

            // 4) 分组按「添加的目录」展示：把这些歌所属的导入目录从扫描列表一并移除，
            //    否则下次扫描（或冷启动自动扫描）同样的歌又会回来。
            val matchedTrees = folders.map { it.treeUri }.filter { tree -> removed.any { it.belongsToTree(tree) } }
            if (matchedTrees.isNotEmpty()) {
                matchedTrees.forEach { tree ->
                    runCatching {
                        app.contentResolver.releasePersistableUriPermission(
                            Uri.parse(tree),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    db.folderDao().deleteByDocIds(matchedTrees.mapNotNull { docIdOf(it) })
                    reloadFolders()
                }
                // 目录配置变了：作废正在跑的扫描，避免它把刚删掉的结果又写回来
                session.core.cancelScan()
                if (folders.isEmpty() && prefs.scanScope == ScanScope.FOLDERS) {
                    // 最后一个目录被移除：与 removeFolder 相同的兜底 —— 范围回退「未选择」
                    prefs.scanScope = ScanScope.UNSET
                    prefs.scanScopeChosen = true
                    session.core.clearLibraryForScopeReset()
                    pushMessage("已移除「${group.title}」，导入目录已清空，请重新选择扫描范围")
                    return@launch
                }
            }
            val dirNote = if (matchedTrees.isEmpty()) ""
            else "，导入目录「${matchedTrees.joinToString("、") { treeDisplayName(it) }}」已一并移除"
            pushMessage("已移除「${group.title}」的 ${removed.size} 首歌曲$dirNote")
        }
    }

    private fun pushMessage(text: String) = session.pushMessage(text)

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    private fun idOf(song: Song): Long? = session.idOf(song)

    // ---------------------------------------------------------------- 常量

    companion object {
        private const val TAG = "LibraryViewModel"
    }
}
