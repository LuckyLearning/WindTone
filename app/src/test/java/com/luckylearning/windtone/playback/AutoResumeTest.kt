package com.luckylearning.windtone.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「打开应用继续上次播放」的判定（v1.1.16）。
 *
 * 这四行逻辑看起来不值得测，但它有两个特点：
 *  - 出错时**没有任何可见信号**：开关开着却没续播（分支写漏），或者用户明明关了
 *    却在打开应用时突然出声（判断条件取反），都只能靠人去听；
 *  - 判定点在引擎里，引擎依赖 MediaController 与 Room，在 JVM 测试里起不来 ——
 *    所以判定被抽成了 [shouldAutoResume]，这里测的就是线上跑的那一份。
 */
class AutoResumeTest {

    /** 开关开 + 有队列 + 当前是暂停 = 该续播（这是用户要的正常路径） */
    @Test
    fun resumesWhenEnabledQueueExistsAndPaused() {
        assertTrue(shouldAutoResume(enabled = true, queueSize = 1, isPlaying = false))
        assertTrue(shouldAutoResume(enabled = true, queueSize = 320, isPlaying = false))
    }

    /**
     * 开关关着时绝不替他做决定：队列与进度照样恢复，但停在暂停态。
     *
     * 这条是本功能的「反向契约」—— 老版本行为（恢复队列、不自动播）必须仍然可达，
     * 否则相当于把一个默认行为强加给了所有不需要它的人。
     */
    @Test
    fun staysPausedWhenDisabled() {
        assertFalse(shouldAutoResume(enabled = false, queueSize = 320, isPlaying = false))
    }

    /** 队列空（首次安装 / 曲库被清空）：没有可播的东西，不能进「在播但没声音」的状态 */
    @Test
    fun doesNothingWithEmptyQueue() {
        assertFalse(shouldAutoResume(enabled = true, queueSize = 0, isPlaying = false))
    }

    /**
     * 已经在播：播放服务还活着（只退过界面 / Activity 被回收），不该再 play 一次。
     *
     * 虽然重复 `play()` 本身不会中断播放，但会白跑一轮 `onIsPlayingChanged`，
     * 也掩盖了「这次恢复压根不需要动播放器」这个事实。
     */
    @Test
    fun doesNotDisturbOngoingPlayback() {
        assertFalse(shouldAutoResume(enabled = true, queueSize = 10, isPlaying = true))
        assertFalse(shouldAutoResume(enabled = false, queueSize = 10, isPlaying = true))
    }
}
