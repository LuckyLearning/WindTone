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

    /**
     * 该显示哪几颗侧钮（v1.3.0 + a11y）：bit0 收起 / bit1 播放暂停 / bit2 打开播放页 / bit3 无障碍。
     *
     * 抽成纯函数是为了能测：这里一旦算错，用户看到的就是"关掉的那颗还在、
     * 或者点了第二颗触发的是第三颗"——窗口层没法靠肉眼定位。
     */
    fun handleMask(
        carMode: Boolean,
        handlesEnabled: Boolean,
        collapse: Boolean,
        playPause: Boolean,
        player: Boolean,
        a11y: Boolean = false,
    ): Int {
        if (!carMode || !handlesEnabled) return 0
        var mask = 0
        if (collapse) mask = mask or 1
        if (playPause) mask = mask or 2
        if (player) mask = mask or 4
        if (a11y) mask = mask or 8
        return mask
    }

    data class HandleColumn(val x: Int, val ys: List<Int>)

    /**
     * **任意颗**侧钮的坐标（v1.2.0：从两颗扩到三颗）。
     *
     * 竖排贴歌词窗左侧；横坐标与收起/展开无关（点一下不横跳）；
     * 纵坐标从 [anchorY] 起按「尺寸 + 间隙」依次下排。
     */
    fun handleColumn(
        windowX: Int,
        handleSizePx: Int,
        gapPx: Int,
        edgeGapPx: Int,
        screenW: Int,
        anchorY: Int,
        count: Int,
    ): HandleColumn {
        val size = handleSizePx.coerceAtLeast(1)
        val x = (windowX - size - edgeGapPx).coerceIn(0, (screenW - size).coerceAtLeast(0))
        val ys = (0 until count.coerceAtLeast(1)).map { i -> anchorY + i * (size + gapPx) }
        return HandleColumn(x = x, ys = ys)
    }


    /** 侧钮种类（与位掩码 bit0..3、设置页 kind 0..3 对齐） */
    enum class OverlayHandleKind(val id: String, val bit: Int, val kind: Int) {
        COLLAPSE("collapse", 1, 0),
        PLAY_PAUSE("play_pause", 2, 1),
        PLAYER("player", 4, 2),
        /** 无障碍状态 / 跳系统设置（不能进程内假开关） */
        A11Y("a11y", 8, 3),
        ;

        companion object {
            fun fromId(id: String): OverlayHandleKind? = entries.firstOrNull { it.id == id }
        }
    }

    /** 出厂上下顺序：收起 → 播放暂停 → 打开播放页 → 无障碍（前三项与改前一致） */
    val DEFAULT_HANDLE_ORDER: List<OverlayHandleKind> = listOf(
        OverlayHandleKind.COLLAPSE,
        OverlayHandleKind.PLAY_PAUSE,
        OverlayHandleKind.PLAYER,
        OverlayHandleKind.A11Y,
    )

    /**
     * 合并用户保存的侧钮顺序：未知 id 丢掉，缺的按默认顺序插回。
     * 与 [com.luckylearning.windtone.ui.SettingsMenuOrder.merge] 同口径。
     */
    fun mergeHandleOrder(savedIds: List<String>): List<OverlayHandleKind> {
        val defaultIds = DEFAULT_HANDLE_ORDER.map { it.id }
        val known = defaultIds.toSet()
        val out = savedIds.filter { it in known }.distinct().toMutableList()
        for (id in defaultIds) {
            if (id in out) continue
            val later = defaultIds.drop(defaultIds.indexOf(id) + 1).firstOrNull { it in out }
            val at = if (later == null) out.size else out.indexOf(later)
            out.add(at, id)
        }
        return out.mapNotNull { OverlayHandleKind.fromId(it) }
    }

    fun handleOrderCsv(order: List<OverlayHandleKind> = DEFAULT_HANDLE_ORDER): String =
        order.joinToString(",") { it.id }

    /** 按用户顺序筛出当前掩码下可见的侧钮 */
    fun visibleHandles(order: List<OverlayHandleKind>, mask: Int): List<OverlayHandleKind> =
        order.filter { mask and it.bit != 0 }

}
