package com.luckylearning.windtone.overlay

import android.content.Context
import com.luckylearning.windtone.data.OverlayPresetParams
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.lyrics.OverlayContentResolver
import kotlinx.coroutines.flow.SharedFlow

/**
 * Prefs → 预设匹配参数快照（自检报告 FR-11 用）。
 *
 * 与 `PlayerUiState.overlayPresetParams()` 同构，但自检在服务侧跑、
 * 只拿得到 Prefs——两处字段一一对应，改任何一边都要同步另一边。
 */
private fun prefsOverlayParams(prefs: Prefs) = OverlayPresetParams(
    lines = prefs.lyricOverlayLines,
    widthPercent = prefs.lyricOverlayWidthPercent,
    boxHeightPercent = prefs.lyricOverlayBoxHeightPercent,
    xPercent = prefs.lyricOverlayXPercent,
    yPercent = prefs.lyricOverlayYPercent,
    textSp = prefs.lyricOverlayTextSp,
    align = prefs.lyricOverlayAlign,
    textColor = prefs.lyricOverlayTextColor,
    currentColor = prefs.lyricOverlayCurrentColor,
    dimPercent = prefs.lyricOverlayDimPercent,
    strokeWidthDp = prefs.lyricOverlayStrokeWidthDp,
    strokeColor = prefs.lyricOverlayStrokeColor,
    lineSpacingDp = prefs.lyricOverlayLineSpacingDp,
    showTitle = prefs.lyricOverlayShowTitle,
    titlePercent = prefs.lyricOverlayTitlePercent,
    titleMode = prefs.lyricOverlayTitleMode,
    bgAlphaPercent = prefs.lyricOverlayBgAlphaPercent,
    bgColor = prefs.lyricOverlayBgColor,
    cornerRadiusDp = prefs.lyricOverlayCornerRadiusDp,
    borderWidthDp = prefs.lyricOverlayBorderWidthDp,
    borderColor = prefs.lyricOverlayBorderColor,
    pauseMode = prefs.lyricOverlayPauseMode,
    fadeMs = prefs.lyricOverlayFadeMs,
)

/**
 * 悬浮歌词的一次性快照 —— 设置页「自检」用。
 *
 * 为什么需要它：悬浮歌词这条链路跨了 8 个环节（授权 → 开关 → 服务 → 挂窗 →
 * 上报 → 前后台 → 内容 → 几何），任何一环断掉，用户侧的表现都是同一个"没反应"。
 * 没有这个快照，只能靠猜；有了它，一眼就能看出断在哪。
 *
 * 还有一类更隐蔽的故障：**每一项都"正常"，但用户就是看不见**——
 * 窗口被推到屏幕外、文字 View 被隐藏、内容恰好是空行，都属于这一类。
 * 所以快照不只报"有没有"，还报"是什么、在哪儿"。
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
    /** 窗口 View 当前是否 VISIBLE（前台按设计为 false，不算故障） */
    val windowVisible: Boolean,
    /** 最近一次状态上报 */
    val health: LyricOverlayBus.Health,
    /** App 是否在前台（前台时按设计隐藏） */
    val appForeground: Boolean,
    /** 当前解析出的内容（含上下文行与 idle 标记） */
    val resolved: OverlayContentResolver.Resolved,
    /** 当前歌曲是否在播放 */
    val contentPlaying: Boolean,
    /** 窗口与文字 View 的真实几何（挂上 ≠ 看得见） */
    val windowGeometry: String,
    /** 外观配置摘要（行数 / 字号 / 对齐 / 底板 / 暂停） */
    val appearance: String,
) {
    /** 窗口是否被判定为「几何上跑出屏幕」 */
    private val offScreen: Boolean get() = windowGeometry.contains("超出屏幕")

    /** 窗口挂上了但文字 View 被隐藏 / 未测量 —— 这类问题外行看不出来，必须显式点名 */
    private val textViewBroken: Boolean get() = windowGeometry.contains("异常")

    /** 转成给人看的多行诊断报告 */
    fun report(): String = buildString {
        appendLine("① 系统授权：${if (permissionGranted) "已授权" else "未授权 ← 断点"}")
        appendLine("② 开关状态：${if (prefEnabled) "已开启" else "已关闭 ← 断点"}")
        appendLine("③ 服务进程：${if (serviceAlive) "运行中" else "未运行 ← 断点"}")
        appendLine("④ 窗口挂载：${if (windowAttached) "已挂载" else "未挂载 ← 断点"}")
        appendLine("⑤ 最近上报：${health.status}${if (health.detail.isNotBlank()) "（${health.detail}）" else ""}")
        appendLine("⑥ App 前后台：${if (appForeground) "前台（此时按设计隐藏）" else "后台"}")
        appendLine("⑦ 窗口可见：${if (windowVisible) "显示中" else "隐藏中（前台 / 无内容 / 已锁定）"}")
        appendLine("⑧ 窗口位置：$windowGeometry")
        appendLine(
            "⑨ 下发内容：" + if (resolved.current.isBlank()) {
                "空（无歌词且取不到歌名）"
            } else {
                buildString {
                    append("「${resolved.current}」")
                    if (resolved.idle) append(" [非演唱行·压暗]")
                    if (resolved.prev.isNotBlank()) append("  ↑「${resolved.prev}」")
                    if (resolved.translation.isNotBlank()) append("  ⇩双语「${resolved.translation}」")
                    if (resolved.next.isNotBlank()) append("  ↓「${resolved.next}」")
                }
            },
        )
        appendLine("⑩ 外观配置：$appearance")
        appendLine("⑪ 播放状态：${if (contentPlaying) "播放中" else "已暂停"}")
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
                textViewBroken -> "结论：窗口在，但**文字 View 被隐藏或没测量**。" +
                    "这属于程序缺陷，请把这份报告反馈给开发者。"
                appForeground -> "结论：前几项都正常。按设计，App 在前台时字幕是隐藏的——" +
                    "退到桌面（或点「显示预览」）即可看到。"
                resolved.current.isBlank() -> "结论：链路正常，但没有可显示的文字。" +
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

    /** 把解析好的当前内容推给悬浮窗 */
    fun publish(
        resolved: OverlayContentResolver.Resolved,
        playing: Boolean,
        title: String = "",
        artist: String = "",
    ) = LyricOverlayBus.publish(resolved, playing, title, artist)

    /** 清空悬浮窗内容 */
    fun clear() = LyricOverlayBus.clear()

    /** 从服务侧拉一次快照（服务已在运行、只是想知道当前状态时用） */
    fun snapshot(context: Context): OverlaySnapshot {
        val prefs = Prefs(context)
        return OverlaySnapshot(
            permissionGranted = canDraw(context),
            prefEnabled = prefs.lyricOverlay,
            serviceAlive = LyricOverlayService.serviceAlive,
            windowAttached = LyricOverlayService.windowAttached,
            windowVisible = LyricOverlayService.windowVisible,
            health = LyricOverlayBus.health.value,
            appForeground = LyricOverlayBus.foreground.value,
            resolved = LyricOverlayBus.content.value.resolved,
            contentPlaying = LyricOverlayBus.content.value.playing,
            windowGeometry = LyricOverlayService.windowGeometry,
            appearance = buildString {
                // 自检里先说形态：全屏高度下行数按面板高度自动适配，
                // 直接写用户那档会让人误以为设置没生效，所以两处都写清楚。
                if (prefs.lyricOverlayFullHeight) {
                    append("全屏高度 ")
                    append(prefs.lyricOverlayFullWidthPercent).append("% 宽 / ")
                    append(OverlayPositioner.offsetLabel(prefs.lyricOverlayFullOffsetPercent))
                    append(" / 自动行数")
                } else {
                    if (prefs.lyricOverlayLines <= 0) {
                        append("自动行数")
                    } else {
                        append(prefs.lyricOverlayLines).append(" 行")
                    }
                    append(" / 宽").append(prefs.lyricOverlayWidthPercent).append("%")
                }
                append(" / 字号").append(prefs.lyricOverlayTextSp).append("sp")
                    .append("（标题 ").append(prefs.lyricOverlayTitlePercent).append("%）")
                append(" / 对齐").append(if (prefs.lyricOverlayAlign == 1) "居左" else "居中")
                append(" / 背景").append(prefs.lyricOverlayBgAlphaPercent).append("%")
                append(" / 暂停").append(if (prefs.lyricOverlayPauseMode == 1) "隐藏" else "淡出")
                // v1.1.78（FR-11）：预设名 + 触摸模式。预设名由参数反推（R5），
                // 与设置页 / 歌词菜单显示的是同一个函数算出来的同一个名字，
                // 三处永远一致；「自定义」就是真自定义，不存在状态漂移。
                append(" / 预设：").append(
                    com.luckylearning.windtone.data.overlayPresetLabel(prefsOverlayParams(prefs)),
                )
                append(" / 触摸：穿透（锁定态零干扰）")
            },
        )
    }

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

    /** 服务请求退出调整态（长时间无操作）；由播放域收集 */
    val exitEditingRequests: SharedFlow<Unit> get() = LyricOverlayBus.exitEditingRequests

    /** 悬浮窗「播放 / 暂停」按钮的请求流（v1.2.0） */
    val togglePlayRequests: SharedFlow<Unit> get() = LyricOverlayBus.togglePlayRequests

    /** 服务在桌面拖动落盘后的通知；由播放域回读位置设置，保证设置页滑块跟手 */
    val positionSaves: SharedFlow<Unit> get() = LyricOverlayBus.positionSaves

    /** 服务侧：位置已落盘 */
    fun notifyPositionSaved() = LyricOverlayBus.notifyPositionSaved()

    /** 进入 / 退出「前台预览」窗口期（刚打开开关时用） */
    fun setPreview(value: Boolean) = LyricOverlayBus.setPreview(value)

    /** 外观配置变了（字号 / 底板 / 暂停行为），通知服务立刻重绘 */
    fun bumpAppearance() = LyricOverlayBus.bumpAppearance()

    /** 上报运行状态 */
    fun report(status: LyricOverlayBus.Status, detail: String = "") =
        LyricOverlayBus.report(status, detail)
}
