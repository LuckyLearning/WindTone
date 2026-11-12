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

    /**
     * 收起把手的落点。
     *
     * @param inside 当前是否已处于「塞进歌词窗」的形态；传入上一次的结果，用于迟滞
     * @param x 把手的左上角横坐标
     *
     * 两种形态：
     *  · **挂在窗外**（常规）：贴歌词窗左缘外侧，不与窗口有任何重叠；
     *  · **塞进窗内**（歌词窗已经贴到屏幕左边、外侧放不下）：落进渲染器让出的左侧内衬里，
     *    仍然不压字 —— 宁可让歌词整体右移一条把手宽，也不能把按钮盖在字上，
     *    更不能把按钮推到屏幕外点不到。
     *
     * 迟滞窗口是必须的：塞进去会让窗口变宽，窗口按比例定位又会左移半个内衬宽，
     * 于是「放不放得下」的判断值本身会跟着变。没有迟滞就会在两种形态之间来回抖，
     * 表现为悬浮窗每次刷新都跳一下。
     */
    fun handlePlacement(
        frame: Frame,
        windowLeft: Int,
        handleWidth: Int,
        gap: Int,
        inside: Boolean,
        hysteresis: Int
    ): HandlePlacement {
        val outside = windowLeft - handleWidth - gap
        val nextInside = if (inside) {
            // 已经塞进去了：要退出来，必须有明确的富余量，不能卡在边界上
            outside < frame.left + hysteresis
        } else {
            outside < frame.left - hysteresis
        }
        val x = if (nextInside) windowLeft + gap / 2 else outside
        return HandlePlacement(nextInside, x)
    }

    /** 把手落点：是否塞进歌词窗 + 横坐标。 */
    data class HandlePlacement(val inside: Boolean, val x: Int)

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
