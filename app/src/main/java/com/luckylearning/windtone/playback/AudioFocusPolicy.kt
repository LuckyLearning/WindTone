package com.luckylearning.windtone.playback

import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults

/**
 * 音频焦点丢失策略（竞品借鉴：Salt Player v12.2.1「音频焦点丢失策略」）。
 *
 * 场景：车载导航播报、来电、其它应用抢焦点时，音乐怎么办。三档：
 * - [STANDARD] **标准（默认）**：交给 Media3 内建处理 —— 瞬时丢失暂停、
 *   可闪避（can-duck）自动降音量、焦点归还自动恢复、永久丢失暂停。
 *   与既有行为完全一致（`handleAudioFocus = true`），不引入任何新风险。
 * - [DUCK] **降音量让行**：瞬时丢失（含播报类 can-duck）不暂停，只把音量
 *   乘上 [Prefs] 无关的 [DUCK_VOLUME]；焦点归还自动恢复原音量；**永久**丢失
 *   仍然暂停（别的应用已长期接管，死扛只会两边都不出声）。
 *   车机导航播报体验强相关：播报完音乐无缝回来，而不是「停一下再 resume」。
 * - [KEEP_PLAYING] **继续播放**：完全忽略焦点丢失（不降音量也不暂停）。
 *   适合「我就是要盖过一切」的场景；注意部分系统 / 车机可能因此直接压制音频流。
 *
 * 实现形态：STANDARD 档沿用 Media3 内建焦点管理；另两档由
 * [AudioFocusController] 自管 `AudioFocusRequest`（服务侧把
 * `handleAudioFocus` 关掉），决策集中在本纯函数，可 JVM 单测。
 */
enum class FocusLossMode {

    /** 标准：Media3 内建（暂停 / 降音量 / 自动恢复） */
    STANDARD,

    /** 瞬时丢失降音量、归还包括原音量；永久丢失暂停 */
    DUCK,

    /** 忽略焦点丢失，继续播放 */
    KEEP_PLAYING,
}

/**
 * 焦点变化 → 动作 的纯决策。
 *
 * [AudioFocusController] 只负责把 [Reaction] 落到播放器上；「什么变化该做什么」
 * 全部在这里，方便单测覆盖。
 */
object AudioFocusPolicy {

    /** Media3 / ExoPlayer 内建闪避音量（AudioFocusManager 用的就是 0.2） */
    const val DUCK_VOLUME = 0.2f

    /** 焦点变化后应该做的事 */
    enum class Reaction {
        /** 什么都不做 */
        NONE,

        /** 进入降音量（瞬时丢失） */
        DUCK,

        /** 恢复原音量（焦点归还） */
        UNDUCK,

        /** 暂停（永久丢失，DUCK 档也认） */
        PAUSE,
    }

    /** @param focusChange `AudioManager` 的 AUDIOFOCUS_* 常量 */
    fun react(focusChange: Int, mode: FocusLossMode): Reaction {
        if (mode == FocusLossMode.STANDARD) return Reaction.NONE // 该档由 Media3 内建处理，控制器不接管
        return when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_GAIN -> Reaction.UNDUCK
            android.media.AudioManager.AUDIOFOCUS_LOSS ->
                if (mode == FocusLossMode.DUCK) Reaction.PAUSE else Reaction.NONE
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            ->
                if (mode == FocusLossMode.DUCK) Reaction.DUCK else Reaction.NONE
            else -> Reaction.NONE
        }
    }
}
