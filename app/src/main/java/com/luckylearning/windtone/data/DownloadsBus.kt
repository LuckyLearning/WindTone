package com.luckylearning.windtone.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「下载目录内容变了」的轻量通知。
 *
 * 边播边存是播放层（无界面依赖），我的列表是曲库域；两者不直接互相持有，
 * 用一个进程内版本号通知曲库重新扫一次下载目录即可。只影响「边播边存」列表的
 * 计数与内容，不触发本地曲库扫描。
 */
object DownloadsBus {

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun notifyChanged() {
        _changes.value = System.currentTimeMillis()
    }
}
