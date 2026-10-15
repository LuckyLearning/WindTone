package com.luckylearning.windtone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.overlay.LyricOverlayBus
import com.luckylearning.windtone.playback.PlayerActions
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayerUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 播放域 ViewModel：界面与 [com.luckylearning.windtone.playback.PlaybackEngine] 之间的那一层。
 *
 * 它自己不持有播放状态——状态在引擎里（应用级单例，生命周期长于界面）。
 * 动作面用 Kotlin 的 `by` 整块委托出去，所以这里没有四十个转发方法，
 * 只有「引擎之上还需要界面在场」的那点逻辑（目前只有歌词导出）。
 */
class PlayerViewModel(private val app: Application) :
    AndroidViewModel(app),
    PlayerActions by (app as MusicApp).container.playback {

    private val container = (app as MusicApp).container
    private val engine = container.playback
    private val messages = container.messages

    val player: StateFlow<PlayerUiState> = engine.player

    val progress: StateFlow<PlaybackProgress> = engine.progress

    /** 封面主色（动态主题的种子色）；未开启动态主题时为 null */
    val themeSeed: StateFlow<Int?> = engine.themeSeed

    /** 桌面悬浮窗的运行状态：设置页据此显示「运行中 / 启动失败原因」 */
    val overlayHealth: StateFlow<LyricOverlayBus.Health> = container.playback.overlayHealth

    /** 把当前歌词导出成 LRC 文件（带翻译行的双语歌词会同时导出） */
    fun exportLyrics(target: Uri) {
        viewModelScope.launch {
            val state = engine.player.value
            val lyrics = state.lyrics
            if (lyrics.isEmpty) {
                messages.post("当前歌曲没有歌词")
                return@launch
            }
            val text = buildString {
                appendLine("[ti:${state.current?.title.orEmpty()}]")
                appendLine("[ar:${state.current?.artistOrUnknown.orEmpty()}]")
                if (!lyrics.synced) {
                    lyrics.lines.forEach { appendLine(it.text) }
                } else {
                    lyrics.lines.forEach { line ->
                        val t = line.timeMs.coerceAtLeast(0)
                        val mm = t / 60_000
                        val ss = (t % 60_000) / 1000
                        val xx = (t % 1000) / 10
                        appendLine("[%02d:%02d.%02d]%s".format(mm, ss, xx, line.text))
                        line.translation?.let { tr ->
                            appendLine("[%02d:%02d.%02d]%s".format(mm, ss, xx, tr))
                        }
                    }
                }
            }
            runCatching {
                app.contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                messages.post("歌词已导出为 LRC")
            }.onFailure { messages.post("导出失败：${it.message}") }
        }
    }
}
