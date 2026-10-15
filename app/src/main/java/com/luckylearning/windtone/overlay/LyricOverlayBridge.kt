package com.luckylearning.windtone.overlay

import android.content.Context
import com.luckylearning.windtone.data.Prefs

/**
 * 悬浮歌词的一次性快照 —— 设置页「自检」用。
 *
 * 为什么需要它：悬浮歌词这条链路跨了 6 个环节（授权 → 开关 → 服务 → 挂窗 →
 * 内容 → 前后台），任何一环断掉，用户侧的表现都是同一个"没反应"。
 * 没有这个快照，只能靠猜；有了它，一眼就能看出断在哪。
 */
data class OverlaySnapshot(
    /** 系统「出现在其他应用上」授权 */
    val permissionGranted: Boolean,
    /** Prefs 里的开关 */
    val prefEnabled: Boolean,
    /** 服务是否活着 */
    val serviceAlive: Boolean,
    /** 窗口是否已挂到 WindowManager */
    val windowAttached: Boolean,
    /** 最近一次状态上报 */
    val health: LyricOverlayBus.Health,
    /** App 是否在前台（前台时按设计隐藏） */
    val appForeground: Boolean,
    /** 当前要显示的文字（空 = 没内容可显示） */
    val contentText: String,
    /** 当前歌曲是否在播放 */
    val contentPlaying: Boolean,
    /** 窗口在屏幕上的真实几何（挂上 ≠ 看得见） */
    val windowGeometry: String,
) {
    /** 窗口是否被判定为「几何上跑出屏幕」 */
    private val offScreen: Boolean get() = windowGeometry.contains("超出屏幕")

    /** 转成给人看的多行诊断报告 */
    fun report(): String = buildString {
        appendLine("① 系统授权：${if (permissionGranted) "已授权" else "未授权 ← 断点"}")
        appendLine("② 开关状态：${if (prefEnabled) "已开启" else "已关闭 ← 断点"}")
        appendLine("③ 服务进程：${if (serviceAlive) "运行中" else "未运行 ← 断点"}")
        appendLine("④ 窗口挂载：${if (windowAttached) "已挂载" else "未挂载 ← 断点"}")
        appendLine("⑤ 最近上报：${health.status}${if (health.detail.isNotBlank()) "（${health.detail}）" else ""}")
        appendLine("⑥ App 前后台：${if (appForeground) "前台（此时按设计隐藏）" else "后台"}")
        appendLine("⑦ 歌词内容：${if (contentText.isBlank()) "空（无歌词且无标题可显示）" else "「$contentText」"}")
        appendLine("⑧ 窗口位置：$windowGeometry")
        appendLine()
        appendLine(
            when {
                !permissionGranted -> "结论：缺系统授权。点下面的「去授权」，" +
                    "在系统页面允许「显示悬浮窗」（部分系统叫「出现在其他应用上层」）。"
                !prefEnabled -> "结论：开关是关的。打开开关后应能看到 6 秒预览。"
                !serviceAlive -> "结论：服务没起来。点「重新拉起」；若反复失败，" +
                    "多半是系统在限制本应用的后台服务，请看崩溃日志里的 LyricOverlay 记录。"
                !windowAttached -> "结论：服务活着但窗口没挂上，即挂窗被系统拒绝。" +
                    "这是厂商 ROM 的悬浮窗白名单问题，需在系统设置里放行；详情见崩溃日志。"
                offScreen -> "结论：窗口挂上了但**跑到屏幕外**了。点下面的「重置位置」把它拉回来。"
                appForeground -> "结论：前 7 项都正常。按设计，App 在前台时字幕是隐藏的——" +
                    "退到桌面（或点「显示预览」）即可看到。"
                contentText.isBlank() -> "结论：链路正常，但没有可显示的文字。" +
                    "播放一首有 .lrc 歌词的歌，或保持播放状态（无歌词时会显示歌名）。"
                else -> "结论：全链路正常，桌面上应当能看到这一行。若仍看不到，" +
                    "请检查系统「悬浮窗/后台弹出」类限制，或把本应用加入省电策略白名单。"
            },
        )
    }
}

/**
 * 悬浮歌词的界面侧门面。
 *
 * `PlayerViewModel` 与 `MainScreen` 只认这个对象，不直接碰 [LyricOverlayService] ——
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

    /** 采集一次全链路快照（设置页自检入口） */
    fun snapshot(context: Context): OverlaySnapshot = OverlaySnapshot(
        permissionGranted = canDraw(context),
        prefEnabled = Prefs(context).lyricOverlay,
        serviceAlive = LyricOverlayService.serviceAlive,
        windowAttached = LyricOverlayService.windowAttached,
        health = LyricOverlayBus.health.value,
        appForeground = LyricOverlayBus.foreground.value,
        contentText = LyricOverlayBus.content.value.text,
        contentPlaying = LyricOverlayBus.content.value.playing,
        windowGeometry = LyricOverlayService.windowGeometry,
    )

    /**
     * 把悬浮窗位置重置回默认（状态栏下方、水平居中）。
     *
     * 存在的意义：窗口一旦被推到屏幕外，用户在桌面上根本抓不到它，
     * 「调整位置」也就无从谈起——只能给一个不看位置也能点的复位入口。
     */
    fun resetPosition(context: Context) = LyricOverlayService.resetPosition(context)

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

    /** 悬浮窗运行状态（设置页展示 + 播放域失败回退） */
    val health get() = LyricOverlayBus.health

    /** 进入 / 退出「前台预览」窗口期（刚打开开关时用） */
    fun setPreview(value: Boolean) = LyricOverlayBus.setPreview(value)

    /** 上报运行状态 */
    fun report(status: LyricOverlayBus.Status, detail: String = "") =
        LyricOverlayBus.report(status, detail)
}
