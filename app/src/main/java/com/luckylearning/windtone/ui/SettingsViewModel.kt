package com.luckylearning.windtone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.DataMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 / 诊断域 ViewModel：数据自检、清理、重置，以及崩溃日志。
 *
 * 这些操作有个共同点——**改的是库里已经存下的数据，而不是本次会话的状态**，
 * 所以界面必须能主动触发一次「重新对账」。曲库列表本身的刷新由
 * [LibraryViewModel.reloadAll] 负责，界面在调用本类的危险操作后顺带调一次。
 */
class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {

    private val container = (app as MusicApp).container
    private val maintenance = container.maintenance
    private val messages = container.messages
    private val index = container.songIndex
    private val playback = container.playback

    /** 自检报告；null 表示还没加载过 */
    private val _maintenanceReport = MutableStateFlow<DataMaintenance.Report?>(null)
    val maintenanceReport: StateFlow<DataMaintenance.Report?> = _maintenanceReport.asStateFlow()

    fun refreshMaintenanceReport() {
        viewModelScope.launch {
            _maintenanceReport.value = withContext(Dispatchers.IO) {
                runCatching { maintenance.report() }.getOrNull()
            }
        }
    }

    /** 清理「文件已不在本机」的歌曲记录（连带清掉它们的收藏 / 统计 / 歌单关联） */
    fun cleanupMissingSongs() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { maintenance.cleanupMissingSongs() }.getOrDefault(0)
            }
            refreshMaintenanceReport()
            messages.post(if (n > 0) "已清理 $n 首失效歌曲" else "没有需要清理的歌曲")
        }
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { maintenance.clearCoverCache() } }
            refreshMaintenanceReport()
            messages.post("封面缓存已清空")
        }
    }

    /**
     * 清空全部用户数据：收藏 / 播放统计 / 歌词偏移 / 标签编辑 / 歌单 / 队列 / 搜索历史。
     * **保留曲库与导入目录**（重扫同一批文件即可恢复听歌能力）。危险操作，界面需二次确认。
     *
     * 调用方随后应调一次 [LibraryViewModel.reloadAll]，否则列表里还挂着已清掉的红心与歌单。
     */
    fun resetAllUserData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { maintenance.resetUserData() } }
            // 内存副本同步清空，避免为一次重置重查整张状态表
            playback.resetListenAccumulator()
            index.clearStates()
            refreshMaintenanceReport()
            messages.post("已清空全部用户数据（收藏 / 统计 / 歌单 / 队列）")
        }
    }

    /**
     * 清空曲库与导入目录，**保留收藏 / 播放统计 / 歌单**。
     *
     * 与 [resetAllUserData] 恰好互补：那个是「留曲库、清数据」，这个是「清曲库、留数据」。
     * 收藏与统计按 `stableKey` 与歌关联，所以重扫同一批文件后红心、播放次数、
     * 歌单条目会自动接上——换了一批音乐时不必再手工点一遍。
     *
     * 「移出曲库」的排除标记随整表清空一并消失（否则重扫后会有歌莫名不出现）。
     *
     * 调用方随后应调 [LibraryViewModel.onLibraryCleared] 而不是 `reloadAll()`：
     * 后者会在扫描范围仍是「全盘」时立刻重扫，把刚清空的曲库又填满。
     */
    fun resetLibrary() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { maintenance.resetLibrary() } }
            refreshMaintenanceReport()
            messages.post("已清空曲库与导入目录，请重新选择扫描范围")
        }
    }

    // ---------------------------------------------------------------- 崩溃日志

    fun crashLogCount(): Int = CrashLog.count(app)

    fun crashLogText(): String = CrashLog.allText(app)

    fun clearCrashLog() {
        CrashLog.clear(app)
        messages.post("崩溃日志已清空")
    }

    fun exportCrashLog(target: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { CrashLog.allText(app) }
            runCatching {
                app.contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                messages.post("崩溃日志已导出")
            }.onFailure { messages.post("导出失败：${it.message}") }
        }
    }
}
