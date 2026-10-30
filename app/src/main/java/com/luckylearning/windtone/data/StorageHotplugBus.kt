package com.luckylearning.windtone.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 存储热插拔事件总线。
 *
 * [StorageHotplugMonitor] 只负责听系统广播并往这里投递；
 * 曲库域收集 [rescanRequests] 调既有 `scan(auto = false)`（与手动扫描同管道、绕过冷启动节流）；
 * 播放域收集 [unmountEvents] 按 [StorageHotplugPolicy.decideUnmountPlayback] 处理正在播的曲。
 *
 * 进程级单例：BroadcastReceiver 可能在 UI / 播放引擎尚未就绪时先到，
 * SharedFlow 带缓冲，晚到的订阅者仍能吃到最近几次（瞬时事件，溢出丢最旧）。
 */
object StorageHotplugBus {

    private val _rescan = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rescanRequests: SharedFlow<String> = _rescan.asSharedFlow()

    private val _unmount = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** 载荷为卷根路径；解析不到路径时为空串（播放侧无法判定归属，只靠随后重扫软删） */
    val unmountEvents: SharedFlow<String> = _unmount.asSharedFlow()

    @Volatile
    var lastRescanRequestAtMs: Long = 0L
        private set

    /** 测试用：清节流时钟与（不重置 SharedFlow 订阅） */
    internal fun resetForTest() {
        lastRescanRequestAtMs = 0L
    }

    fun onMounted(volumePath: String?, nowMs: Long = System.currentTimeMillis()) {
        if (!StorageHotplugPolicy.shouldRequestRescan(lastRescanRequestAtMs, nowMs)) return
        lastRescanRequestAtMs = nowMs
        _rescan.tryEmit(volumePath.orEmpty())
    }

    fun onUnmounted(volumePath: String?, nowMs: Long = System.currentTimeMillis()) {
        val path = volumePath.orEmpty()
        _unmount.tryEmit(path)
        if (!StorageHotplugPolicy.shouldRequestRescan(lastRescanRequestAtMs, nowMs)) return
        lastRescanRequestAtMs = nowMs
        _rescan.tryEmit(path)
    }
}
