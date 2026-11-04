package com.luckylearning.windtone.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频焦点丢失策略决策（竞品借鉴：Salt Player v12.2.1）。
 *
 * 关键契约：
 * - STANDARD 档永远返回 NONE（控制器旁路，Media3 内建接管，行为与历史版本一致）；
 * - DUCK 档：瞬时丢失（含 can-duck）降音量、归还恢复、永久丢失暂停；
 * - KEEP_PLAYING 档：一切丢失都忽略，只有归还恢复音量（防御性，正常不会 duck 过）。
 */
class AudioFocusPolicyTest {

    @Test
    fun standardModeIsFullyDelegatedToMedia3() {
        AudioManager.AUDIOFOCUS_GAIN.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.STANDARD)) }
        AudioManager.AUDIOFOCUS_LOSS.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.STANDARD)) }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.STANDARD)) }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.STANDARD)) }
    }

    @Test
    fun duckModeDucksOnTransientLossAndRestoresOnGain() {
        assertEquals(
            AudioFocusPolicy.Reaction.DUCK,
            AudioFocusPolicy.react(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, FocusLossMode.DUCK),
        )
        // 导航播报属 can-duck：也是降音量而不是暂停
        assertEquals(
            AudioFocusPolicy.Reaction.DUCK,
            AudioFocusPolicy.react(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, FocusLossMode.DUCK),
        )
        assertEquals(
            AudioFocusPolicy.Reaction.UNDUCK,
            AudioFocusPolicy.react(AudioManager.AUDIOFOCUS_GAIN, FocusLossMode.DUCK),
        )
    }

    @Test
    fun duckModePausesOnPermanentLoss() {
        assertEquals(
            AudioFocusPolicy.Reaction.PAUSE,
            AudioFocusPolicy.react(AudioManager.AUDIOFOCUS_LOSS, FocusLossMode.DUCK),
        )
    }

    @Test
    fun keepPlayingModeIgnoresEveryLoss() {
        AudioManager.AUDIOFOCUS_LOSS.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.KEEP_PLAYING)) }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.KEEP_PLAYING)) }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK.let { assertEquals(AudioFocusPolicy.Reaction.NONE, AudioFocusPolicy.react(it, FocusLossMode.KEEP_PLAYING)) }
        // 归还仍要恢复音量：万一先前处于 duck 态再切到本档，焦点归还把它拉回来
        assertEquals(
            AudioFocusPolicy.Reaction.UNDUCK,
            AudioFocusPolicy.react(AudioManager.AUDIOFOCUS_GAIN, FocusLossMode.KEEP_PLAYING),
        )
    }

    @Test
    fun duckVolumeMatchesMedia3BuiltIn() {
        // 与 ExoPlayer AudioFocusManager 的闪避音量一致，用户从 STANDARD 切 DUCK 听感连续
        assertEquals(0.2f, AudioFocusPolicy.DUCK_VOLUME, 0f)
    }
}
