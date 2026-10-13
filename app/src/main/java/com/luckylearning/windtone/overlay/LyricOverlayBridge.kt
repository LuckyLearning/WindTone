package com.luckylearning.windtone.overlay

import android.content.Context

/**
 * 悬浮歌词的界面侧门面。
 *
 * `MusicViewModel` 与 `MainScreen` 只认这个对象，不直接碰 [LyricOverlayService] ——
 * 这样界面层与 Service 之间只有一个极窄的契约，Service 后续要换实现
 * （例如改成 Compose 承载多行）也不必动界面代码。
 */
object LyricOverlayBridge {

    /** 是否已获得「出现在其他应用上」授权 */
    fun canDraw(context: Context): Boolean = LyricOverlayService.canDrawOverlay(context)

    /** 按开关状态起停悬浮窗服务（幂等） */
    fun sync(context: Context, enabled: Boolean) = LyricOverlayService.sync(context, enabled)

    /** 把当前歌词行推给悬浮窗 */
    fun publish(text: String, playing: Boolean) = LyricOverlayBus.publish(text, playing)

    /** 清空悬浮窗内容 */
    fun clear() = LyricOverlayBus.clear()

    // ---------------------------------------------------------------- 调整位置

    /**
     * 进入 / 退出「调整位置」模式。
     *
     * 进入后悬浮窗临时恢复触摸能力、可拖动；退出后位置落盘、窗口恢复点击穿透。
     * 只在开关已开启且已授权时才有意义，未满足时调用会被内部忽略。
     */
    fun setEditing(value: Boolean) = LyricOverlayBus.setEditing(value)

    /**
     * App 前后台切换。
     *
     * 前台时悬浮窗整体隐藏——用户正看着播放页歌词，桌面字幕是重复信息。
     */
    fun setAppForeground(value: Boolean) = LyricOverlayBus.setForeground(value)

    /** 会话级的调整模式标志（用于设置页按钮文案联动） */
    val editing get() = LyricOverlayBus.editing
}
