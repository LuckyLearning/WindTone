package com.luckylearning.windtone.overlay

/**
 * 悬浮窗「收起态」与侧钮几何（从 [LyricOverlayService] 抽出的纯函数）。
 *
 * 只算数字，不碰 WindowManager / View。Service 把结果写进 LayoutParams。
 * 这样收起宽度、高度固化规则、双钮锚点可以单测钉死，避免再靠真机猜。
 */
object OverlayCollapseHelper {

    /** 收起后窗口保留宽度（dp）：左侧一条，几乎不占地方（v1.1.91） */
    const val COLLAPSED_WIDTH_DP = 8

    /** 收起钮与播放页钮之间的竖向间隙（dp） */
    const val HANDLE_GAP_DP = 8

    /** 侧钮与歌词窗左缘的间隙（dp） */
    const val HANDLE_EDGE_GAP_DP = 4

    /** 收起时测不到高度时的回落（dp） */
    const val FALLBACK_COLLAPSE_HEIGHT_DP = 120

    /** 侧钮默认边长（dp） */
    const val DEFAULT_HANDLE_SIZE_DP = 48

    /** 算钮位时窗高未知的回落（dp） */
    const val FALLBACK_BOX_HEIGHT_DP = 96

    data class CollapsedWindow(
        val widthPx: Int,
        val heightPx: Int,
        /** 收起后贴左缘 */
        val x: Int = 0,
    )

    /**
     * 收起态窗口宽高。
     *
     * 高度必须先固化：自动高度（WRAP_CONTENT）下只改宽度不会触发重排，
     * 窗看起来「没收起来」（v1.1.96 真机结论）。
     *
     * @param wrapContentSentinel 与 [android.view.WindowManager.LayoutParams.WRAP_CONTENT] 相同（-2）
     */
    fun collapsedWindow(
        measuredHeightPx: Int,
        layoutHeightPx: Int,
        wrapContentSentinel: Int,
        collapsedWidthPx: Int,
        fallbackHeightPx: Int,
    ): CollapsedWindow {
        val height = measuredHeightPx.takeIf { it > 0 }
            ?: layoutHeightPx.takeIf { it > 0 && it != wrapContentSentinel }
            ?: fallbackHeightPx.coerceAtLeast(1)
        return CollapsedWindow(
            widthPx = collapsedWidthPx.coerceAtLeast(1),
            heightPx = height,
            x = 0,
        )
    }

    data class HandleAnchor(
        val anchorY: Int,
        val anchorFromWindowY: Int,
    )

    /**
     * 侧钮纵坐标锚点：只在歌词窗顶边 [windowY] 变化时重算，避免行数变化导致钮上下跳。
     */
    fun resolveHandleAnchor(
        windowY: Int,
        boxHeightPx: Int,
        totalHandlesHeightPx: Int,
        screenH: Int,
        currentAnchorY: Int,
        currentAnchorFromWindowY: Int,
    ): HandleAnchor {
        if (currentAnchorFromWindowY != windowY || currentAnchorY < 0) {
            val centered = windowY + (boxHeightPx - totalHandlesHeightPx) / 2
            val y = centered.coerceIn(0, (screenH - totalHandlesHeightPx).coerceAtLeast(0))
            return HandleAnchor(anchorY = y, anchorFromWindowY = windowY)
        }
        return HandleAnchor(
            anchorY = currentAnchorY,
            anchorFromWindowY = currentAnchorFromWindowY,
        )
    }

    data class HandlePositions(
        val x: Int,
        val collapseY: Int,
        val playY: Int,
    )

    /**
     * 两颗侧钮的坐标：竖排贴歌词窗左侧；横坐标与收起/展开无关（点一下不横跳）。
     */
    fun handlePositions(
        windowX: Int,
        handleSizePx: Int,
        gapPx: Int,
        edgeGapPx: Int,
        screenW: Int,
        anchorY: Int,
    ): HandlePositions {
        val size = handleSizePx.coerceAtLeast(1)
        val x = (windowX - size - edgeGapPx).coerceIn(0, (screenW - size).coerceAtLeast(0))
        return HandlePositions(
            x = x,
            collapseY = anchorY,
            playY = anchorY + size + gapPx,
        )
    }
}
