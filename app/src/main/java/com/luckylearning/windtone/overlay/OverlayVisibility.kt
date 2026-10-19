package com.luckylearning.windtone.overlay

/**
 * 悬浮歌词「该不该显示」的判定（v1.1.22 从 [LyricOverlayService] 的收集循环里抽出来）。
 *
 * 抽出来的理由很实在：这段布尔逻辑连着出过两次真机 bug，而它此前埋在
 * `collect` 的 lambda 里，只能靠「装到车机上看一眼」来验证。
 *
 *  - v1.1.20：全屏调整态在 App 内也画，整屏浮层把设置页的「完成调整」整个吃掉；
 *  - v1.1.22：修好调整态，却漏了**预览**——改宽度 / 位置会自动给一次 6 秒预览，
 *    于是用户在设置页拖滑块时，整屏歌词压在自己的控件上（真机照片就是字压着按钮）。
 *
 * 规则本身不复杂，但四种例外叠在一起（仅后台 / 调整 / 预览 / 暂停隐藏），
 * 靠读代码很难确信没有漏，所以固定成纯函数并上单测。
 *
 * v1.1.29 新增第五条例外：**前台应用命中「不显示悬浮窗的应用」名单**
 * （[foregroundHidden]）—— 用户车机上 360 影像 / 视频类应用打开时，
 * 浮层会挡住画面，这时一律收起，退出自动恢复。
 */
object OverlayVisibility {

    /**
     * @param fullHeight 「全屏高度」形态（覆盖整屏）
     * @param foreground App 是否在前台
     * @param editing 是否处于「调整位置」编辑态
     * @param preview 是否处于开关 / 改配置后的 6 秒预览
     * @param playing 当前是否在播放（暂停表现要用）
     * @param pauseHide 暂停时是否隐藏（用户设置）
     * @param screenInteractive 屏幕是否亮着
     * @param hasContent 手上是否有一句可显示的内容
     * @param foregroundHidden 当前前台应用是否在「不显示悬浮窗的应用」名单里
     *   （v1.1.29；命中即收起，且**不被调整 / 预览豁免**——用户正在设置页里调
     *   悬浮窗时若恰好切到名单应用，继续画会盖住那个应用，调整可以等）
     */
    fun show(
        fullHeight: Boolean,
        foreground: Boolean,
        editing: Boolean,
        preview: Boolean,
        playing: Boolean,
        pauseHide: Boolean,
        screenInteractive: Boolean,
        hasContent: Boolean,
        foregroundHidden: Boolean,
    ): Boolean {
        // 全屏这一档在 App 前台一律不画：整屏浮层会盖住设置页上的宽度滑块与
        // 位置按钮，用户一边调一边看不见自己调的是什么；而且它吃触摸时连
        // 「完成调整」都点不到。设置页改用示意图（OverlayFullPreviewMock）代替。
        val fullInApp = fullHeight && foreground
        // 调整 / 预览是「仅后台显示」规则的例外，但压不过上面那条。
        val override = (editing || preview) && !fullInApp
        // 暂停隐藏：有例外态（调整 / 预览）时不隐藏，否则用户正在调却被藏掉
        val hideOnPause = !playing && !override && pauseHide
        // 名单命中：压过调整 / 预览（见 [foregroundHidden] 的说明），
        // 但压不过「本就没内容可显示」与灭屏 —— 那两种情况本来就不画。
        return !hideOnPause && !foregroundHidden && screenInteractive &&
            (override || !foreground) && (hasContent || override)
    }
}
