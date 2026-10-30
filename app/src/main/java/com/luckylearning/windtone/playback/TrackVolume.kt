package com.luckylearning.windtone.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 播放过渡与响度平衡（v1.1.52）：切歌淡入淡出 + 曲间响度平衡的**服务端执行者**。
 *
 * 与 [AudioFx] 同一套「UI 与服务同进程、单例共享」的思路，但管的是
 * **播放器音量**（`player.volume`）而不是音频效果器：
 *  - **淡入淡出**：自动切歌时旧曲结尾线性淡出、新曲开头线性淡入（曲线见 [FadeRamp]）；
 *    手动操作（暂停 / 拖动进度 / 关开关）立即恢复基线音量，不跟用户抢手感。
 *  - **响度平衡**：开着的时候**先读曲目标签**（ReplayGain / iTunes Sound Check，
 *    见 [ReplayGainTags]），有标签就用标签；没有才按 RMS 把过响的歌压到统一响度
 *    （衰减制，数学见 [LoudnessScan]；分析见 [TrackLoudnessAnalyzer]，缓存见 [LoudnessCache]）。
 *    这和均衡器的「响度增强」（`eqLoudness` / `LoudnessEnhancer`）不是一回事，
 *    这里只乘 `player.volume`，不去动那条效果链。
 *
 * 两者的合成点只有一处：`volume = 响度因子 × 淡入淡出因子`。
 * 因子都落在 0..1，相乘不会有「抢音量」的问题。
 *
 * **为什么是进程内单例而不是走 MediaController**：`volume` 虽在 `Player`
 * 接口上，但斜坡需要毫秒级节奏且只在服务进程里有意义；本应用 UI 与服务
 * 同进程（`AudioFx` / `PlayerHandle` 已确立此先例），直接持引用最简单。
 */
object TrackVolume {

    data class State(
        val fadeEnabled: Boolean = false,
        val fadeDurationMs: Int = SettingsDefaults.FADE_DURATION_MS,
        val loudnessBalance: Boolean = false,
        /** 是否有曲目正在后台做响度分析（界面可不展示；保留给诊断） */
        val analyzing: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private const val TICK_MS = 100L
    private const val RAMP_STEP_MS = 40L

    /** 标签缓存里的「查过了，没有」。0 是合法的 0 dB 标签，不能拿来当缺失。 */
    private const val TAG_ABSENT = Int.MIN_VALUE

    private var prefs: Prefs? = null
    private var cache: LoudnessCache? = null
    /** 标签增益缓存。值为 [TAG_ABSENT] 表示查过、文件里没有标签。 */
    private var tagCache: LoudnessCache? = null
    private var appContext: Context? = null
    private var player: Player? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val analyzeScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var tickJob: Job? = null
    private var fadeJob: Job? = null

    /** 当前曲目的响度音量因子（0..1；1 = 无补偿 / 未分析 / 功能关） */
    @Volatile private var loudnessFactor = 1f

    /** 正在执行淡出（曲尾斜坡中）；用于挡住 tick 的重复触发 */
    @Volatile private var fadingOut = false

    private val tagPending = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val listener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            cancelFade()
            // 基线先按新曲重算（响度因子可能变化），再决定要不要淡入
            applyLoudnessFor(mediaItem?.mediaId)
            val p = prefs
            if (p != null && p.fadeEnabled &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null
            ) {
                startFadeIn()
            } else {
                setVolumeNow(loudnessFactor)
            }
            maybeAnalyzeCurrent()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTick()
            } else {
                // 暂停即收手：停在半坡的音量会让「按暂停」看起来像「声音坏了」
                stopTick()
                cancelFade()
                setVolumeNow(loudnessFactor)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // ⚠️ 自动切歌也会走这里（reason = AUTO_TRANSITION）：那一case由
            // onMediaItemTransition 负责淡入，这里再拉基线会把淡入踩掉。
            // 只处理真正的用户拖动与其余跳变：立即回到基线，不跟手过招。
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            cancelFade()
            setVolumeNow(loudnessFactor)
        }
    }

    // ------------------------------------------------------------ 生命周期

    /** UI / 引擎侧初始化（幂等）：只建 Prefs 与缓存，不碰播放器 */
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        prefs = Prefs(app)
        cache = LoudnessCache(File(app.filesDir, "loudness_cache.txt"))
        tagCache = LoudnessCache(File(app.filesDir, "replaygain_cache.txt"))
        refreshState()
    }

    /** 服务侧绑定（[PlaybackService.onCreate] 调用）；内部会兜一次 [init] */
    fun attach(player: Player, context: Context) {
        init(context)
        if (this.player === player) return
        this.player = player
        player.addListener(listener)
        // 服务刚起 / 换播放器实例：立刻对当前曲对齐基线
        applyLoudnessFor(player.currentMediaItem?.mediaId)
        setVolumeNow(loudnessFactor)
        maybeAnalyzeCurrent()
    }

    /** 服务销毁：停斜坡、停轮询；音量随播放器一起销毁，无需归位 */
    fun release() {
        tickJob?.cancel(); tickJob = null
        fadeJob?.cancel(); fadeJob = null
        player = null
    }

    // ------------------------------------------------------------ 设置入口（EqSheet 调用）

    fun setFadeEnabled(enabled: Boolean) {
        prefs?.fadeEnabled = enabled
        refreshState()
        if (!enabled) {
            cancelFade()
            setVolumeNow(loudnessFactor)
        }
    }

    fun setFadeDurationMs(ms: Int) {
        val range = SettingsDefaults.FADE_DURATION_RANGE
        prefs?.fadeDurationMs = ms.coerceIn(range.start, range.endInclusive)
        refreshState()
    }

    fun setLoudnessBalance(enabled: Boolean) {
        prefs?.loudnessBalance = enabled
        refreshState()
        // 开启：对当前曲立即生效（没分析过就排队分析）；关闭：回到原音量
        applyLoudnessFor(player?.currentMediaItem?.mediaId)
        if (!fadingOut && fadeJob == null) setVolumeNow(loudnessFactor)
        if (enabled) maybeAnalyzeCurrent()
    }

    // ------------------------------------------------------------ 内部实现

    private fun refreshState() {
        val p = prefs ?: return
        _state.update {
            it.copy(
                fadeEnabled = p.fadeEnabled,
                fadeDurationMs = p.fadeDurationMs,
                loudnessBalance = p.loudnessBalance,
            )
        }
    }

    /**
     * 按当前曲目重算响度因子。
     *
     * 标签优先：缓存里已有标签就用标签，不再看 RMS。还没查过标签时，先临时用
     * RMS（有的话），同时去读标签；确认没有标签之后才排队做 RMS 分析。
     * 不读 `eqLoudness`。
     */
    private fun applyLoudnessFor(stableKey: String?) {
        val p = prefs ?: run { loudnessFactor = 1f; return }
        if (!p.loudnessBalance || stableKey.isNullOrEmpty()) {
            loudnessFactor = 1f
            return
        }
        val storedTag = tagCache?.gainMb(stableKey)
        val rms = cache?.gainMb(stableKey)
        when (storedTag) {
            null -> {
                loudnessFactor = if (rms != null) LoudnessScan.attenuationFactorMb(rms) else 1f
                requestTagRead(stableKey)
            }
            TAG_ABSENT -> {
                val gain = ReplayGainTags.resolveGainMb(null, rms)
                loudnessFactor = if (gain != null) LoudnessScan.attenuationFactorMb(gain) else 1f
                if (rms == null) requestAnalyze(stableKey)
            }
            else -> {
                val gain = ReplayGainTags.resolveGainMb(storedTag, rms)
                loudnessFactor = if (gain != null) LoudnessScan.attenuationFactorMb(gain) else 1f
            }
        }
    }

    private fun maybeAnalyzeCurrent() {
        applyLoudnessFor(player?.currentMediaItem?.mediaId)
    }

    /** 读当前曲的音量标签。URI 在主线程取好；读完若还停在这首上，立刻重算音量。 */
    private fun requestTagRead(stableKey: String) {
        if (tagCache?.gainMb(stableKey) != null) return
        if (!tagPending.add(stableKey)) return
        val uri = player?.currentMediaItem?.localConfiguration?.uri
        val context = appContext
        if (uri == null || context == null) {
            tagPending.remove(stableKey)
            if (uri == null) tagCache?.put(stableKey, TAG_ABSENT)
            if (uri == null && cache?.gainMb(stableKey) == null) requestAnalyze(stableKey)
            return
        }
        analyzeScope.launch {
            val mb = ReplayGainReader.read(context, uri)
            tagCache?.put(stableKey, mb ?: TAG_ABSENT)
            tagPending.remove(stableKey)
            scope.launch {
                if (player?.currentMediaItem?.mediaId == stableKey && !fadingOut) {
                    applyLoudnessFor(stableKey)
                    if (fadeJob == null) setVolumeNow(loudnessFactor)
                }
            }
        }
    }

    /**
     * 后台分析当前曲。URI 必须在主线程先取好（[Player] 不是线程安全的）；
     * 分析完若用户还停在这首曲子上，直接把因子应用到音量。
     */
    private fun requestAnalyze(stableKey: String) {
        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: return
        val context = appContext ?: return
        if (_state.value.analyzing) return
        _state.update { it.copy(analyzing = true) }
        analyzeScope.launch {
            val gain = TrackLoudnessAnalyzer.analyze(context, uri)
            if (gain != null) cache?.put(stableKey, gain)
            _state.update { it.copy(analyzing = false) }
            if (gain == null) return@launch
            scope.launch {
                if (player?.currentMediaItem?.mediaId == stableKey && !fadingOut) {
                    applyLoudnessFor(stableKey)
                    if (fadeJob == null) setVolumeNow(loudnessFactor)
                }
            }
        }
    }

    /**
     * 曲尾轮询：只在「开着淡出、正在播、还有下一首、已进入淡出区间」时出手。
     * 平时这是一次 100ms 的空转（几次内存读），不在意。
     */
    private fun tick() {
        val p = prefs ?: return
        val pl = player ?: return
        if (!p.fadeEnabled || fadingOut) return
        if (!pl.isPlaying || !pl.hasNextMediaItem()) return
        val duration = pl.duration
        if (duration <= 0) return // 直播流等未知时长：不淡出
        val fadeMs = p.fadeDurationMs
        val remaining = duration - pl.currentPosition
        if (remaining in 1..fadeMs) startFadeOut(remaining)
    }

    private fun startTick() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel(); tickJob = null
    }

    /** 曲尾淡出：从当前音量线性降到 0，曲目自然结束时过渡到下一首 */
    private fun startFadeOut(totalMs: Long) {
        if (totalMs <= 0) return
        fadingOut = true
        fadeJob?.cancel()
        fadeJob = scope.launch {
            var elapsed = 0L
            while (elapsed < totalMs) {
                setVolumeNow(loudnessFactor * FadeRamp.fadeOutFactor(totalMs - elapsed, totalMs))
                delay(RAMP_STEP_MS)
                elapsed += RAMP_STEP_MS
            }
            setVolumeNow(0f)
        }
    }

    /** 新曲淡入：从 0 线性升到基线（响度因子） */
    private fun startFadeIn() {
        val fadeMs = prefs?.fadeDurationMs?.toLong() ?: return
        if (fadeMs <= 0) return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            var elapsed = 0L
            while (elapsed < fadeMs) {
                setVolumeNow(loudnessFactor * FadeRamp.fadeInFactor(elapsed, fadeMs))
                delay(RAMP_STEP_MS)
                elapsed += RAMP_STEP_MS
            }
            setVolumeNow(loudnessFactor)
        }
    }

    private fun cancelFade() {
        fadingOut = false
        fadeJob?.cancel()
        fadeJob = null
    }

    private fun setVolumeNow(factor: Float) {
        runCatching { player?.volume = factor.coerceIn(0f, 1f) }
    }
}
