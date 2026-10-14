package com.luckylearning.windtone.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局一次性提示（Snackbar）总线。
 *
 * 曲库、播放、维护、更新各域都会产生「已收藏」「播放失败」这类瞬时提示。
 * 以前它们统一写回 `LibraryUiState.message`，于是**播放域也被迫依赖曲库状态**
 * （`MusicViewModel.pushMessage` 是曲库/播放/更新三个域之间唯一的反向依赖）。
 *
 * 现在统一发到这里，由界面收集展示，跨域耦合随之消失：
 * 任何一层都可以 `post`，不需要知道界面长什么样。
 */
class MessageBus {

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 界面侧收集它来弹 Snackbar；无订阅者时事件被丢弃（瞬时提示本就不需要补发） */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun post(text: String) {
        if (text.isNotBlank()) _messages.tryEmit(text)
    }
}
