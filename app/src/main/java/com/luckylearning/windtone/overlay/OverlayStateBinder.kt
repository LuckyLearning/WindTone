package com.luckylearning.windtone.overlay

import android.text.TextUtils
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.luckylearning.windtone.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 悬浮窗「状态接线」：把引擎的各路 StateFlow（内容 / 编辑态 / 前台态 / 预览态 / 外观版本 /
 * 前台包名）合并后，逐帧重算「显不显示 / 长什么样」，并驱动宿主（[LyricOverlayService]）的
 * 窗口外观与可见性。
 *
 * 原本这段 ~128 行的接线埋在服务里，和窗口生命周期 / 手势 / 拖拽混在一起，既不好读也难测。
 * 抽到本类后，[LyricOverlayService] 只负责窗口的创建 / 销毁 / 手势，状态接线全部交给这里；
 * 所有对宿主的操作都走 [Host] 接口，便于在单测里用替身驱动。
 */
class OverlayStateBinder(
    private val host: Host,
    private val scope: CoroutineScope,
) {
    /** 暂停表现：隐藏（另一档是淡出） */
    private val PAUSE_HIDE = 1

    /**
     * 窗口最多画几行（含折行）：再多就不是「悬浮字幕」而是一块遮屏的板子了。
     *
     * 12 → 16（v1.1.49）：行数可以「自动」到 7 行，加上双语副行就是 8 句，
     * 每句按折成两行算要 16 行额度 —— 留在 12 会把末尾几句静默省略掉。
     */
    private val MAX_LINES_CAP = 16

    /**
     * 全屏高度模式下每行都可能折行，上限放宽。
     * 11 行 × 3 = 33：全屏宽度调到 50% 以下时，长句会折成三行，按 2 倍（22）截断
     * 的话末尾几句会静默消失 —— 宁可多给几行额度。
     */
    private val MAX_FULL_LINES_CAP = 36

    /** 调整态 / 预览态在无歌词时显示的提示行 */
    private val HINT_EDITING =
        OverlayTextComposer.StyledLine("拖动到合适位置，松手即保存", OverlayTextComposer.Role.CONTEXT)
    private val HINT_EDITING_FULL =
        OverlayTextComposer.StyledLine("左右拖动调整位置，双击锁定", OverlayTextComposer.Role.CONTEXT)
    private val HINT_PREVIEW =
        OverlayTextComposer.StyledLine("悬浮歌词已开启，退到桌面后持续显示", OverlayTextComposer.Role.CONTEXT)

    /** 订阅各路状态并逐帧重算窗口外观 / 可见性；重复调用会先取消上一次订阅 */
    fun observe() {
        job?.cancel()
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
            ) { content, isEditing, isForeground, isPreview, _ ->
                OverlayVisualState(content, isEditing, isForeground, isPreview)
            }
            // ⚠️ **第六路：前台应用包名必须订阅，不能只在判可见性时读一次**
            //（v1.1.29 真机回归：名单勾了没生效）。检测端（无障碍服务 / UsageStats 轮询）
            // 只往 ForegroundAppState 写值；「写」不产生任何既有通道的事件，
            // 于是切到名单应用后没有任何东西触发重判 —— 只能等下一句歌词或暂停恢复
            // 碰巧重算，暂停 / 无歌词变化时就是永远不收起。
            combine(visuals, ForegroundAppState.currentPkg) { state, pkg ->
                state to pkg
            }.collect { (visual, foregroundPkg) ->
                val (content, isEditing, isForeground, isPreview) = visual
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
                host.desiredVisible = visible
                if (!visible) {
                    host.applyVisibility()
                    return@collect
                }

                // 行数：全屏按面板高度自适应，单条字幕按用户档位（或「自动」按高度反推），
                // 具体像素换算在宿主里做（那里才有 dp / 字号 / 屏幕尺寸）。
                // 标题显隐会改变窗口高度，需要重新夹一次位置（否则窗口变高后可能出屏）。
                if (host.applyTitle(content, prefs) && !host.editing) {
                    host.container?.post { host.applyPosition() }
                }
                val full = prefs.lyricOverlayFullHeight
                val lineCount = host.resolveLineCount(prefs, full)
                val lines = when {
                    content.visible -> OverlayTextComposer.compose(
                        resolved = content.resolved,
                        lineCount = lineCount,
                        // 双语副行不设开关：它是**同一句**的翻译，属于内容完整性
                        // 而不是排版选择——能拿到双语歌词的用户本来就想要它。
                        showTranslation = true,
                    )
                    isEditing -> listOf(if (full) HINT_EDITING_FULL else HINT_EDITING)
                    else -> listOf(HINT_PREVIEW)
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
                host.renderLines(tv, lines, content.playing || isEditing || isPreview, full)
                host.applyVisibility()
                host.updateGeometryReport()
            }
        }
    }

    /** 取消订阅（服务销毁时调用） */
    fun dispose() {
        job?.cancel()
        job = null
    }

    private var job: Job? = null

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
        fun renderLines(tv: TextView, lines: List<OverlayTextComposer.StyledLine>, emphasize: Boolean, full: Boolean)
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
)
