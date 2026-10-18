package com.luckylearning.windtone.data

/**
 * 开机广播该做什么（v1.1.24 从 [com.luckylearning.windtone.CarBootReceiver] 里抽出的纯函数）。
 *
 * 为什么抽出来：这里的分支决定的是「上车后屏幕上会不会突然弹出一个全屏界面」，
 * 而这条只能在真车上验证一次。抽成纯函数后三种组合都能在单测里钉住：
 *
 *  - 开了「开机后台播放」→ **只起服务、不起界面**（车机用户要的是「通电就有歌」，
 *    弹界面会挡住导航 / 桌面；这也是用户 2026-10-18 明确提的诉求）；
 *  - 只开了车载模式 → 保持老行为：直接打开主界面（顺带进播放页）；
 *  - 都没开 → 什么都不做（手机用户零打扰）。
 *
 * 优先级说明：「后台播放」压过「打开界面」——两个都开着时，用户显然不想要一个
 * 自己会弹出来的窗口。
 */
object BootAction {

    enum class Kind {
        /** 什么都不做 */
        NONE,

        /** 打开主界面（车载模式的老行为） */
        OPEN_UI,

        /** 只拉起播放服务，在后台恢复上次队列并起播，不显示任何界面 */
        PLAY_IN_BACKGROUND,
    }

    fun decide(autoPlayOnBoot: Boolean, carMode: Boolean): Kind = when {
        autoPlayOnBoot -> Kind.PLAY_IN_BACKGROUND
        carMode -> Kind.OPEN_UI
        else -> Kind.NONE
    }
}
