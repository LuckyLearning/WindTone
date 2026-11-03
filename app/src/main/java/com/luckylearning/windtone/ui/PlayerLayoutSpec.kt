package com.luckylearning.windtone.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.ui.theme.UiDensity
import com.luckylearning.windtone.ui.theme.UiFormFactor

/**
 * 播放页中部的手势语义（UI v2 · 阶段五）。
 *
 * ## 为什么上下滑不能「切歌词/封面视图」
 *
 * 设计稿初版曾写「左右 = 切歌、上下 = 切视图，全端一致」。落地前核对代码发现
 * 这条与物理交互冲突：歌词 `LazyColumn` 本身就是**纵向滚动**的，把上下滑拿去切视图
 * 会直接和滚歌词抢手势。现有实现之所以是「歌词态只在 overscroll 边缘才认上下滑」，
 * 正是为了避开这个冲突。
 *
 * 所以真正可统一的语义是：**「切歌」在两端都可用且方向一致（上下）；
 * 「切面板」只发生在有多余面板可切的形态（手机左右）**。车机是歌词 + 黑胶双栏同场，
 * 没有面板可切，于是左右也用来切歌 —— 这不是「语义相反」，而是「同一个手势在
 * 有第二面板时优先切面板」。本表把这个隐性约定写成可断言的数据。
 */
internal enum class HorizontalSwipe { SWITCH_PANE, SKIP_TRACK }

internal enum class VerticalSwipe { SKIP_TRACK, NONE }

internal data class PlayerGestureSemantics(
    val horizontal: HorizontalSwipe,
    val vertical: VerticalSwipe,
    /** 长按 = 歌词行菜单 / 列表多选，全端开放（车机用大目标，不再整块禁用）。 */
    val longPressMenu: Boolean = true,
)

/**
 * 播放页布局决策表（UI v2 · 阶段五）。
 *
 * 重构前 `PlayerScreen` 有 28 处 `if (state.carMode)`，同一个「是不是车机版式」
 * 的问题被回答了 28 遍，还散着 40/20dp、34/36dp、66/88dp 等尺寸三元式。
 * 这里把**所有布尔策略**收进一次查表；尺寸数值仍沿用已验证过的 `CarMetrics`，
 * 保证接线时真机视觉零回归（数值收敛留给阶段七）。
 *
 * [from] 是纯函数，可在 JVM 单测里逐字段断言。
 */
internal data class PlayerLayoutSpec(
    val isCar: Boolean,
    /** 来自统一密度表的最小触控目标；阶段七把散落尺寸收敛到它时直接可用。 */
    val touchMin: Dp,
    val gesture: PlayerGestureSemantics,
    /** 外层 Column：车机只撑满高度（宽度由 align 决定），手机撑满全屏。 */
    val fillHeightOnly: Boolean,
    val horizontalPadding: Dp,
    /** 歌词与黑胶双栏同场。 */
    val vinylAlongsideLyrics: Boolean,
    /** 收藏 / 队列 / 设置收进最左侧竖排动作列。 */
    val sideActions: Boolean,
    /** 歌名常显在进度条上方（车机完整态放顶栏，仅沉浸态落到歌名行）。 */
    val nowRowAlways: Boolean,
    /** ±10/15s 快进快退（车机走方控 / 系统通知 seek，不重复占位）。 */
    val seekJumpButtons: Boolean,
    /** 场景芯片（大触控目标）。 */
    val sceneChips: Boolean,
    /** 底部状态行（歌词源 / 偏移 / 队列数）。 */
    val statusRow: Boolean,
    /** 控制行右槽的播放队列入口（车机已移进侧动作列，但槽位仍要留空占位）。 */
    val queueInControls: Boolean,
    // ---- 控制行尺寸（阶段七）：此前 9 处 `if (carMode) A.dp else B.dp` 散在 PlayerScreen。
    // null = 沿用 Compose 默认（不额外套 .size()），避免把手机默认 24dp 图标硬写成某个数。
    val secondaryButton: Dp?,
    val sideButton: Dp?,
    val mainButton: Dp,
    val modeIcon: Dp?,
    val sideIcon: Dp,
    val mainIcon: Dp,
    val controlSpacing: Dp,
) {
    companion object {
        /**
         * @param carMetrics 车机档尺码表（由 `rememberCarMetrics()` 提供）；
         *                   非车机传 null。数值沿用已验证的真机值，本层只负责集中定义。
         */
        fun from(
            formFactor: UiFormFactor,
            density: UiDensity,
            carMetrics: CarMetrics? = null,
        ): PlayerLayoutSpec {
            val car = formFactor.isCar
            // 非车机形态即使被传入了尺码表也一律忽略，保证手机尺寸不被车机值污染。
            val m = if (car) carMetrics else null
            return PlayerLayoutSpec(
                isCar = car,
                touchMin = density.touchMin,
                gesture = PlayerGestureSemantics(
                    horizontal = if (car) HorizontalSwipe.SKIP_TRACK
                    else HorizontalSwipe.SWITCH_PANE,
                    vertical = VerticalSwipe.SKIP_TRACK,
                ),
                fillHeightOnly = car,
                horizontalPadding = if (car) 40.dp else 20.dp,
                vinylAlongsideLyrics = car,
                sideActions = car,
                nowRowAlways = !car,
                // 阶段八 功能对等：±10/15s 快进快退与场景芯片改为全端可用。
                // 车机走方控 seek 不冲突，多一个就地入口不影响驾驶安全。
                seekJumpButtons = true,
                sceneChips = true,
                // 状态行（歌词源 / 偏移 / 队列数）仍按用户 2026-10-18 明确要求
                // 在车机不显示：它占 30dp 高度，挤掉的是歌词区。
                statusRow = !car,
                queueInControls = !car,
                secondaryButton = m?.secondaryButton,
                sideButton = m?.sideButton,
                mainButton = m?.mainButton ?: 66.dp,
                modeIcon = if (car) 34.dp else null,
                sideIcon = if (car) 36.dp else 34.dp,
                mainIcon = if (car) 40.dp else 34.dp,
                controlSpacing = if (car) 40.dp else 20.dp,
            )
        }
    }
}
