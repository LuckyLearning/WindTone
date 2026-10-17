package com.luckylearning.windtone.playback

/**
 * 冷启动「继续上次播放」的判定（v1.1.16）。
 *
 * 抽成纯函数只为一件事：这条判定有三处容易各错各的
 * —— 开关没开、队列是空的、已经在播了。三种情况下 `play()` 都是错的：
 *  - 开关关：用户要的就是「恢复队列但停在原处」，播了等于替他做了决定；
 *  - 队列空（首次安装 / 曲库被清空）：没有可播的东西，`play()` 只会让界面
 *    出现一个「正在播放但什么都没响」的状态；
 *  - 已经在播：进程活着、只退过界面的情况。此时再 `play()` 虽然无害，
 *    但会让 `onIsPlayingChanged` 白跑一轮，也掩盖了「压根不需要动」的事实。
 *
 * 放在 `playback` 包顶层而不是 `PlaybackEngine` 私有，是为了能在 JVM 单测里
 * 直接跑 —— 引擎本身依赖 MediaController / Room，测试环境里起不来。
 *
 * @param enabled   设置里「打开应用继续上次播放」是否开启
 * @param queueSize 播放服务里当前队列长度（0 = 没有可恢复的内容）
 * @param isPlaying 播放服务此刻是否正在播
 */
internal fun shouldAutoResume(enabled: Boolean, queueSize: Int, isPlaying: Boolean): Boolean =
    enabled && queueSize > 0 && !isPlaying
