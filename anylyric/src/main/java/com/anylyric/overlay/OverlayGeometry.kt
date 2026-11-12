package com.anylyric.overlay

/**
 * 悬浮层的位置计算。**纯函数、不依赖 Android**，因此可以直接跑 JVM 单测。
 *
 * 坐标模型：存「比例」而不是像素。原因有三：
 *  · 车机与手机的屏幕尺寸差 2~3 倍，存像素会把同一份默认值放歪；
 *  · 字号变化会改变窗口尺寸，像素坐标不会跟着修正，容易越界；
 *  · 拖动时先换算成比例再落盘，天然保证下次启动仍在屏内。
 */
object OverlayGeometry {

    /** 可用矩形（屏幕扣掉系统栏与额外留白后的区域）。 */
    data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    fun clampRatio(value: Float): Float = when {
        value.isNaN() -> 0f
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /**
     * 构造可用矩形。
     *
     * 系统栏 inset 与额外留白都按「向外收缩」处理；收缩后若出现负宽度，
     * 会把右/下边钳到左上，绝不返回 inverted 矩形（后续算 span 会变成负数）。
     */
    fun frame(
        screenW: Int,
        screenH: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        pad: Int
    ): Frame {
        val left = (insetLeft + pad).coerceIn(0, screenW)
        val top = (insetTop + pad).coerceIn(0, screenH)
        val right = (screenW - insetRight - pad).coerceAtLeast(left)
        val bottom = (screenH - insetBottom - pad).coerceAtLeast(top)
        return Frame(left, top, right, bottom)
    }

    /**
     * 比例 → 左上角像素。
     *
     * 当窗口比可用区还宽（超大字号 + 窄屏）时贴住左边缘：宁可溢出右侧，
     * 也不能算出负坐标把文字推到屏幕外。
     */
    fun originX(frame: Frame, viewWidth: Int, ratioX: Float): Int {
        val span = frame.width - viewWidth
        return if (span <= 0) frame.left else frame.left + (span * clampRatio(ratioX)).toInt()
    }

    fun originY(frame: Frame, viewHeight: Int, ratioY: Float): Int {
        val span = frame.height - viewHeight
        return if (span <= 0) frame.top else frame.top + (span * clampRatio(ratioY)).toInt()
    }

    /** 像素 → 比例。可用跨度为 0 时返回 0，避免除零得到 NaN 落盘。 */
    fun ratioOf(origin: Int, frameStart: Int, span: Int): Float =
        if (span <= 0) 0f else clampRatio((origin - frameStart).toFloat() / span)

    fun clampOrigin(origin: Int, frameStart: Int, span: Int): Int =
        if (span <= 0) frameStart else origin.coerceIn(frameStart, frameStart + span)

    /**
     * 松手吸附：贴近左/右（上/下）边一定距离就贴边。
     *
     * 只在释放时调用，拖动过程中不做动画 —— 车机 GPU 合成窗口位置的成本比想象中高。
     */
    fun snap(origin: Int, frameStart: Int, span: Int, threshold: Int): Int = when {
        span <= 0 -> frameStart
        origin - frameStart <= threshold -> frameStart
        frameStart + span - origin <= threshold -> frameStart + span
        else -> origin
    }

    /** 九宫格锚点：0 → 左/上，1 → 中，2 → 右/下。 */
    fun anchorRatio(index: Int): Float = when {
        index <= 0 -> 0f
        index == 1 -> 0.5f
        else -> 1f
    }

    /** 由比例反推最近的锚点序号，用于界面高亮。 */
    fun nearestAnchor(ratio: Float): Int = when {
        ratio <= 0.25f -> 0
        ratio < 0.75f -> 1
        else -> 2
    }
}
