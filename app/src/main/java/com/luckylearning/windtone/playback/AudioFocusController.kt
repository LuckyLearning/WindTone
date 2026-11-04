package com.luckylearning.windtone.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音频焦点丢失策略的**服务端执行者**（竞品借鉴：Salt Player）。
 *
 * 职责边界：
 * - **STANDARD 档**：什么都不做 —— `handleAudioFocus = true` 交给 Media3 内建，
 *   行为与历史版本完全一致；
 * - **DUCK / KEEP_PLAYING 档**：接管焦点 —— 把播放器的 `handleAudioFocus` 关掉，
 *   自己持有 `AudioFocusRequest`，按 [AudioFocusPolicy.react] 的决策落动作
 *   （降音量走 [TrackVolume.setDucked]，暂停走 `player.pause()`）。
 *
 * 与 [TrackVolume] / [AudioFx] 同一套「UI 与服务同进程、单例共享」思路：
 * 设置页直接 `AudioFocusController.mode` + `setMode()`，不必穿透
 * `PlayerUiState` → `SettingsScreen` 的整条参数链。
 *
 * 线程：attach / setMode / 焦点回调都必须在主线程（`ExoPlayer` 非线程安全）。
 */
object AudioFocusController {

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var request26: AudioFocusRequest? = null

    private val _mode = MutableStateFlow(FocusLossMode.STANDARD)

    /** 当前档位（设置页展示用；STANDARD 时控制器实际上是旁路） */
    val mode: StateFlow<FocusLossMode> = _mode.asStateFlow()

    private val audioManager: AudioManager?
        get() = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        applyReaction(AudioFocusPolicy.react(change, _mode.value))
    }

    // ------------------------------------------------------------ 生命周期

    /** [PlaybackService.onCreate] 调用：读 prefs 初始化，并把档位套到播放器上 */
    fun attach(player: ExoPlayer, context: Context) {
        this.appContext = context.applicationContext
        this.player = player
        val stored = runCatching { Prefs(context).focusLossMode }.getOrDefault("")
        apply(
            runCatching { FocusLossMode.valueOf(stored) }.getOrDefault(FocusLossMode.STANDARD),
        )
    }

    /** [PlaybackService.onDestroy] 调用：归还焦点、撤请求 */
    fun release() {
        abandonFocus()
        player = null
    }

    // ------------------------------------------------------------ 设置入口

    /** 切换档位并立即生效（设置页调用；写 prefs + 重套播放器） */
    fun setMode(newMode: FocusLossMode) {
        val ctx = appContext ?: return
        Prefs(ctx).focusLossMode = newMode.name
        apply(newMode)
    }

    // ------------------------------------------------------------ 内部实现

    private fun apply(newMode: FocusLossMode) {
        val p = player ?: run {
            _mode.value = newMode
            return
        }
        // 先撤掉旧档的请求，再换档 —— 反过来会出现「Media3 与自管同时持焦点」
        abandonFocus()

        val custom = newMode != FocusLossMode.STANDARD
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ !custom,
        )

        _mode.value = newMode
        TrackVolume.setDucked(false)
        if (custom && p.isPlaying) requestFocus()
    }

    /** [PlaybackService] 的 `onIsPlayingChanged` 挂钩：自定义档位下起播要 / 停播不要焦点 */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (_mode.value == FocusLossMode.STANDARD) return
        if (isPlaying) requestFocus() else abandonFocus()
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        // ⚠️ 必须直接写 `Build.VERSION.SDK_INT >= O`：先取到局部变量再比，lint 的
        // NewApi 检查认不出来，会误报下面几行（项目 lint 要求 Error = 0）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = request26 ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { request26 = it }
            runCatching { am.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request26?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(focusListener) }
        }
        applyReaction(AudioFocusPolicy.Reaction.UNDUCK)
    }

    private fun applyReaction(reaction: AudioFocusPolicy.Reaction) {
        val p = player ?: return
        when (reaction) {
            AudioFocusPolicy.Reaction.DUCK -> TrackVolume.setDucked(true)
            AudioFocusPolicy.Reaction.UNDUCK -> TrackVolume.setDucked(false)
            AudioFocusPolicy.Reaction.PAUSE -> {
                TrackVolume.setDucked(false)
                runCatching { p.pause() }
            }
            AudioFocusPolicy.Reaction.NONE -> Unit
        }
    }
}
