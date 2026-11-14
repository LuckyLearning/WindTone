package com.anylyric.overlay

/**
 * 宽高滑杆的「进度 ↔ 像素」换算。
 *
 * 进度 0 单独表示**自适应**（窗口按文字收窄），1..[STEPS] 线性铺满
 * `minPx..maxPx`。之所以不用比例做单位：界面上要显示「620 px」这种用户看得懂的数，
 * 而比例在不同分辨率的机器上是两个数，滑到最后到底多大反而说不清。
 *
 * 抽成纯函数是因为两件事都必须准：反算（进设置页要把已存比例摆回滑杆位置）和
 * round-trip（拖到某档再进来还是同一档）。真机上这类「拖一下跳两档」极难复现，只能靠单测钉住。
 */
object SizeSlider {

    /** 除「自适应」外的档位数。100 档在手机与车机上都够细，又不至于拖半天。 */
    const val STEPS = 100

    /** 自适应档。 */
    const val AUTO = 0

    /** 像素 → 进度（1..[STEPS]）。`maxPx <= minPx` 时退化成最小档。 */
    fun progressOf(px: Int, minPx: Int, maxPx: Int): Int {
        if (maxPx <= minPx) return 1
        val t = ((px - minPx).toFloat() / (maxPx - minPx)).coerceIn(0f, 1f)
        return 1 + Math.round(t * (STEPS - 1))
    }

    /** 进度 → 像素（`minPx..maxPx`）。[AUTO] 或越界都夹到最小可用值。 */
    fun pxOf(progress: Int, minPx: Int, maxPx: Int): Int {
        if (maxPx <= minPx) return minPx
        val step = progress.coerceIn(1, STEPS) - 1
        val t = step.toFloat() / (STEPS - 1)
        return (minPx + (maxPx - minPx) * t).toInt().coerceIn(minPx, maxPx)
    }
}
