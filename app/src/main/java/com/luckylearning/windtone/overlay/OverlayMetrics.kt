package com.luckylearning.windtone.overlay

import java.util.concurrent.atomic.AtomicLong

/**
 * 悬浮窗自身的开销计量（2026-10-31）。
 *
 * ## 为什么单独统计
 *
 * 车机诊断包里 native 232 MB / Java 231 MB，但悬浮窗是**常驻**的：只要开着，它就在
 * 后台跟着播放循环跑。所以「占用到底是不是悬浮窗造成的」必须能直接回答，
 * 而不是让人从总账里猜。
 *
 * 这里只记几件事，全都是「次数 / 累计耗时」，不存时间序列、不落盘：
 *
 *  - **可见性重算次数**：`applyVisibility` 被状态流驱动调用了几次。
 *    它跟着播放进度走，所以这个数字应该 ≈ 播放时长 / 200ms 量级；
 *  - **实际可见性变化次数**：上面那些调用里真正改了 View 可见性的次数。
 *    与上一个数字的比值才是关键 —— 若两者接近，说明状态在**反复翻转**（有抖）；
 *    若比值很小，说明重算是幂等的，代价只是空转几次 if；
 *  - **侧钮重建次数**：`syncHandlesVisibility` 真正摘掉再挂的次数。重建要
 *    `addView`/`removeView`，是这里最贵的操作，正常应该只有个位数；
 *  - **刷字 / 跳过**：真写 TextView 的次数，与「同句未刷」次数。
 *    跳过三路互斥：`LyricOverlayBus.publish` 内容未变、binder paint 指纹未变、
 *    `OverlayRenderDeduper` 键未变（后两路都不再 setText）；
 *  - **无障碍轮询**次数与累计耗时：侧钮无障碍状态刷新。它的单次成本里
 *    含一次 `Settings.Secure` 读取（跨进程），所以只有实测才能说它便宜还是贵。
 *
 * 全部用 `AtomicLong`：写入点在主线程与服务协程两头，读点在诊断导出时。
 */
object OverlayMetrics {

    private val visibilityApply = AtomicLong()
    private val visibilityChange = AtomicLong()
    private val handleRebuild = AtomicLong()
    private val a11yTicks = AtomicLong()
    private val a11yTickNanos = AtomicLong()
    private val renderApply = AtomicLong()
    private val renderSkip = AtomicLong()

    fun onVisibilityApply() {
        visibilityApply.incrementAndGet()
    }

    /** 有 View 的可见性真的被改了 */
    fun onVisibilityChanged() {
        visibilityChange.incrementAndGet()
    }

    fun onHandleRebuild() {
        handleRebuild.incrementAndGet()
    }

    fun onA11yTick(elapsedNanos: Long) {
        a11yTicks.incrementAndGet()
        a11yTickNanos.addAndGet(elapsedNanos.coerceAtLeast(0L))
    }

    /** 文案/样式真的写进了 TextView */
    fun onRenderApply() {
        renderApply.incrementAndGet()
    }

    /** 同句未刷：Bus 去重 / paint 指纹 / RenderKey 任一跳过 setText 时调用 */
    fun onRenderSkip() {
        renderSkip.incrementAndGet()
    }

    /** 诊断包 `[悬浮窗]` 段的补充行；没有任何计数时返回空串，由调用方跳过 */
    fun summary(): String {
        val apply = visibilityApply.get()
        val changed = visibilityChange.get()
        val rebuild = handleRebuild.get()
        val ticks = a11yTicks.get()
        val draws = renderApply.get()
        val skips = renderSkip.get()
        if (apply == 0L && rebuild == 0L && ticks == 0L && draws == 0L && skips == 0L) return ""

        val totalMs = a11yTickNanos.get() / 1_000_000.0
        val avgUs = if (ticks <= 0L) 0.0 else a11yTickNanos.get() / 1000.0 / ticks

        return buildString {
            append("开销（本进程累计）：可见性重算 $apply 次（实际变化 $changed 次）")
            append(" · 侧钮重建 $rebuild 次")
            append(" · 刷字 $draws 次（跳过 $skips 次）")
            append(" · 无障碍轮询 $ticks 次，累计 ")
            append(String.format(java.util.Locale.US, "%.0f ms（均 %.0f µs/次）", totalMs, avgUs))
            if (apply > 0 && changed * 3 > apply) {
                append('\n')
                append("判读：可见性变化占比偏高（>1/3），状态在反复翻转，值得查驱动源")
            }
        }
    }

    /** 单测用：清空计数 */
    internal fun reset() {
        visibilityApply.set(0)
        visibilityChange.set(0)
        handleRebuild.set(0)
        a11yTicks.set(0)
        a11yTickNanos.set(0)
        renderApply.set(0)
        renderSkip.set(0)
    }
}
