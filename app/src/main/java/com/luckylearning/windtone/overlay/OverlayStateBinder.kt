package com.luckylearning.windtone.overlay

import android.text.TextUtils
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.luckylearning.windtone.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 悬浮窗「状态接线」：把引擎的各路 StateFlow（内容 / 编辑态 / 前台态 / 预览态 / 外观版本 /
 * 前台包名）合并后，逐帧重算「显不显示 / 长什么样」，并驱动宿主（[LyricOverlayService]）的
 * 窗口外观与可见性。
 *
 * 原本这段 ~128 行的接线埋在服务里，和窗口生命周期 / 手势 / 拖拽混在一起，既不好读也难测。
 * 抽到本类后，[LyricOverlayService] 只负责窗口的创建 / 销毁 / 手势，状态接线全部交给这里；
 * 所有对宿主的操作都走 [Host] 接口，便于在单测里用替身驱动。
 */
@OptIn(FlowPreview::class)
class OverlayStateBinder(
    private val host: Host,
    private val scope: CoroutineScope,
    /**
     * 前台包名稳定窗口（v1.2.43）。生产默认 [ForegroundPkgStabilizer.STABLE_MS]；
     * 单测传 0，避免 Unconfined 下 debounce 等不到时间。
     */
    private val foregroundStableMs: Long = ForegroundPkgStabilizer.STABLE_MS,
) {
    /** 暂停表现：隐藏（另一档是淡出） */
    private val PAUSE_HIDE = 1

    /**
     * 窗口最多画几行（含折行）—— 只是折行额度，不限制内容行数。
     *
     * 16 → 56（v1.1.50）：行数自动档放宽到 25 行后，加上双语副行是 26 句，
     * 每句按折成两行算要 52 行额度 —— 留在 16 会把末尾十几句静默省略掉。
     */
    private val MAX_LINES_CAP = 56

    /**
     * 全屏高度模式下每行都可能折行，上限更宽。
     * 25 行 × 3 = 75：全屏宽度调到 50% 以下时，长句会折成三行，按 2 倍截断
     * 的话末尾几句会静默消失 —— 宁可多给几行额度。
     */
    private val MAX_FULL_LINES_CAP = 80

    /** 调整态 / 预览态在无歌词时显示的提示行（运行时取串，避免早于 AppStrings.init） */
    private fun hintEditing() = OverlayTextComposer.StyledLine(
        AppStrings.s(R.string.ovs_hint_edit).ifEmpty { "拖动到合适位置，松手即保存" },
        OverlayTextComposer.Role.CONTEXT,
    )
    private fun hintEditingFull() = OverlayTextComposer.StyledLine(
        AppStrings.s(R.string.ovs_hint_edit_full).ifEmpty { "左右拖动调整位置，双击锁定" },
        OverlayTextComposer.Role.CONTEXT,
    )
    private fun hintPreview() = OverlayTextComposer.StyledLine(
        AppStrings.s(R.string.ovs_hint_preview).ifEmpty { "悬浮歌词已开启，退到桌面后持续显示" },
        OverlayTextComposer.Role.CONTEXT,
    )

    /** 订阅各路状态并逐帧重算窗口外观 / 可见性；重复调用会先取消上一次订阅 */
    fun observe() {
        job?.cancel()
        visibilitySyncedOnce = false
        lastPaintKey = null
        job = scope.launch {
            // 内容 + 编辑态 + 前台态 + 预览态 + 外观版本 五路合并：
            // 任一变化都重新决定「显不显示 / 长什么样」。
            // 外观那一路是给设置页用的——服务独立存活，改完字号得有人把它叫醒。
            val visuals = combine(
                LyricOverlayBus.content,
                LyricOverlayBus.editing,
                LyricOverlayBus.foreground,
                LyricOverlayBus.preview,
                LyricOverlayBus.appearance,
            ) { content, isEditing, isForeground, isPreview, appearanceRev ->
                OverlayVisualState(content, isEditing, isForeground, isPreview, appearanceRev)
            }
            // ⚠️ **第六路：前台应用包名必须订阅，不能只在判可见性时读一次**
            //（v1.1.29 真机回归：名单勾了没生效）。检测端（无障碍服务 / UsageStats 轮询）
            // 只往 ForegroundAppState 写值；「写」不产生任何既有通道的事件，
            // 于是切到名单应用后没有任何东西触发重判 —— 只能等下一句歌词或暂停恢复
            // 碰巧重算，暂停 / 无歌词变化时就是永远不收起。
            // v1.2.43：前台包名走滞回再参与隐藏判定——短时闪 avc/gesture/sr
            // 不立刻改显隐；诊断用的 currentPkg 仍即时（见 ForegroundPkgStabilizer）。
            val stableForegroundPkg = ForegroundAppState.currentPkg
                .debounce(foregroundStableMs)
            combine(visuals, stableForegroundPkg) { state, pkg ->
                state to pkg
            }.collect { (visual, foregroundPkg) ->
                val (content, isEditing, isForeground, isPreview, appearanceRev) = visual
                val box = host.container ?: return@collect
                val tv = host.lyricView ?: return@collect
                val prefs = host.prefs()
                if (host.editing != isEditing) {
                    host.editing = isEditing
                    if (!isEditing) {
                        // 退出编辑态时固化坐标——但只在本次真的拖动过时。
                        // 没拖过就落盘的话，等于「随手点一下就改了位置」。
                        if (host.pendingPositionSave) {
                            host.layoutParams?.let { p ->
                                host.prefs().lyricOverlayX = p.x
                                host.prefs().lyricOverlayY = p.y
                            }
                        }
                        host.pendingPositionSave = false
                        host.cancelEditingTimeout()
                    } else {
                        host.armEditingTimeout()
                    }
                    host.refreshModeChrome()
                }

                // 外观（字号 / 底板 / 暂停表现）每帧对一次账：
                // 用户改完设置，下一帧就生效，不必重启服务、也不必等下一句歌词。
                host.applyAppearance(prefs)

                // 显不显示交给 OverlayVisibility 判（v1.1.22 抽出并上单测）：
                // 规则里叠着「仅后台 / 调整 / 预览 / 暂停隐藏」四种例外，
                // 而全屏形态在 App 前台必须一律不画 —— 否则整屏歌词会压在
                // 设置页的宽度滑块和位置按钮上，一边调一边看不见（真机照片为证）。
                // v1.1.29 加第五种：前台应用命中「不显示悬浮窗的应用」名单。
                val visible = OverlayVisibility.show(
                    fullHeight = prefs.lyricOverlayFullHeight,
                    foreground = isForeground,
                    editing = isEditing,
                    preview = isPreview,
                    playing = content.playing,
                    pauseHide = prefs.lyricOverlayPauseMode == PAUSE_HIDE,
                    screenInteractive = host.screenInteractive,
                    hasContent = content.visible,
                    foregroundHidden = overlayHiddenForForeground(
                        pkg = foregroundPkg,
                        hiddenApps = prefs.lyricOverlayHiddenApps,
                    ),
                )
                // 名单或记录开着才需要知道前台是谁（否则一个字节都不查）；
                // 两者都关了就撤掉轮询协程，并把残留包名清掉。
                host.syncForegroundTracking(
                    hiddenApps = prefs.lyricOverlayHiddenApps,
                    logEnabled = prefs.foregroundLogEnabled,
                )
                if (host.desiredVisible != visible) {
                    com.luckylearning.windtone.diag.DiagLog.log(
                        "OVERLAY",
                        "可见性 ${if (visible) "显示" else "隐藏"}（前台=${foregroundPkg ?: "?"}）",
                    )
                }
                val visibilityChanged = host.desiredVisible != visible
                host.desiredVisible = visible
                if (!visible) {
                    // 藏着时歌词推进不必反复 apply；首帧仍要刷，避免默认 VISIBLE 漏藏
                    if (visibilityChanged || !visibilitySyncedOnce) {
                        host.applyVisibility()
                        visibilitySyncedOnce = true
                    }
                    return@collect
                }

                // 行数：全屏按面板高度自适应，单条字幕按用户档位（或「自动」按高度反推），
                // 具体像素换算在宿主里做（那里才有 dp / 字号 / 屏幕尺寸）。
                // 标题显隐会改变窗口高度，需要重新夹一次位置（否则窗口变高后可能出屏）。
                val titleChanged = host.applyTitle(content, prefs)
                if (titleChanged && !host.editing) {
                    host.container?.post { host.applyPosition() }
                }
                val full = prefs.lyricOverlayFullHeight
                val lineCount = host.resolveLineCount(prefs, full)
                val emphasize = content.playing || isEditing || isPreview
                // 指纹：内容 + 行数 + 强调 + 全屏 + 外观版本。包名防抖重入且字未变时跳过刷字。
                val r = content.resolved
                val paintKey = buildString(96) {
                    append(appearanceRev).append('|')
                    append(if (full) 'F' else 'S').append('|')
                    append(lineCount).append('|')
                    append(if (emphasize) '1' else '0').append('|')
                    append(content.playing).append('|')
                    append(content.title).append('|')
                    append(content.artist).append('|')
                    append(r.current).append('|')
                    append(r.translation).append('|')
                    append(r.idle).append('|')
                    append(r.prevLines).append('|')
                    append(r.nextLines)
                }
                val paintChanged = paintKey != lastPaintKey
                var rendered = false
                if (paintChanged) {
                    lastPaintKey = paintKey
                    val lines = when {
                        content.visible -> OverlayTextComposer.compose(
                            resolved = content.resolved,
                            lineCount = lineCount,
                            // 双语副行不设开关：它是**同一句**的翻译，属于内容完整性
                            // 而不是排版选择——能拿到双语歌词的用户本来就想要它。
                            showTranslation = true,
                        )
                        isEditing -> listOf(if (full) hintEditingFull() else hintEditing())
                        else -> listOf(hintPreview())
                    }
                    // 行数上限按实际要画的行数放宽（每行都可能折成两行）；
                    // 全屏模式窗口更窄时可能折三行，额度按 3 倍给。
                    val cap = if (full) MAX_FULL_LINES_CAP else MAX_LINES_CAP
                    val maxLines = (lines.size * if (full) 3 else 2).coerceIn(2, cap)
                    if (tv.maxLines != maxLines) {
                        tv.maxLines = maxLines
                        tv.ellipsize = TextUtils.TruncateAt.END
                    }
                    // 「强调当前行」在播放时、以及调整 / 预览态下都要给 ——
                    // 调整态可能没在放歌，但用户正看着它摆位置。
                    rendered = host.renderLines(tv, lines, emphasize, full)
                }
                // 显隐未变时跳过：render 已更新文字；Secure/侧钮坐标已在 applyVisibility 收口
                if (visibilityChanged || !visibilitySyncedOnce) {
                    host.applyVisibility()
                    visibilitySyncedOnce = true
                }
                // 几何上报跟「刷字 / 显隐 / 标题」走，避免包名防抖空转时 getLocationOnScreen
                if (visibilityChanged || titleChanged || rendered || paintChanged) {
                    host.updateGeometryReport()
                }
            }
        }
    }

    /** 取消订阅（服务销毁时调用） */
    fun dispose() {
        job?.cancel()
        job = null
        visibilitySyncedOnce = false
        lastPaintKey = null
    }

    private var job: Job? = null

    /** 首帧必须 apply 一次，避免默认 VISIBLE 窗口在 desired=false 时漏藏 */
    private var visibilitySyncedOnce = false

    /**
     * 上一帧「画什么」指纹：行文案 + 行数 + 强调 + 全屏 + 外观版本。
     * 进度 tick 若内容未变（Bus 已去重）仍可能因前台包名防抖等重入 collect；
     * 指纹相同则跳过 compose 后的 render / 几何上报。
     */
    private var lastPaintKey: String? = null

    /**
     * 状态接线需要宿主（[LyricOverlayService]）提供的窗口与外观操作面。
     * 所有对窗口的读写都收口在这里，便于单测用替身驱动整条接线。
     */
    interface Host {
        val container: FrameLayout?
        val lyricView: TextView?
        val layoutParams: WindowManager.LayoutParams?
        val screenInteractive: Boolean
        var editing: Boolean
        var pendingPositionSave: Boolean
        var desiredVisible: Boolean

        fun prefs(): Prefs
        fun applyAppearance(prefs: Prefs)

        /**
         * 这次要画几行（v1.1.49）。
         *
         * 行数可以是「自动」——那就得按窗口高度反推，而高度、字号、密度只有宿主
         * 知道。纯算法在 [OverlayLineFit]，宿主负责把 dp / sp 换成像素。
         */
        fun resolveLineCount(prefs: Prefs, full: Boolean): Int

        fun syncForegroundTracking(hiddenApps: Set<String>, logEnabled: Boolean)
        fun applyVisibility()
        fun applyTitle(content: LyricOverlayBus.Content, prefs: Prefs): Boolean
        fun applyPosition()
        /** @return true 若文案/样式真的写进了 TextView（字未变则 false） */
        fun renderLines(tv: TextView, lines: List<OverlayTextComposer.StyledLine>, emphasize: Boolean, full: Boolean): Boolean
        fun updateGeometryReport()
        fun refreshModeChrome()
        fun armEditingTimeout()
        fun cancelEditingTimeout()
    }
}

/** 五路视觉状态合并后的快照：内容 + 编辑态 + 前台态 + 预览态 */
internal data class OverlayVisualState(
    val content: LyricOverlayBus.Content,
    val isEditing: Boolean,
    val isForeground: Boolean,
    val isPreview: Boolean,
    /** 外观版本：改字号/颜色等会 bump，须参与 paint 指纹以免漏刷样式 */
    val appearanceRev: Int = 0,
)
