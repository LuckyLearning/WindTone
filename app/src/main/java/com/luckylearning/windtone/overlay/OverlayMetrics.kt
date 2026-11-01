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
 *  - **显隐判定次数**：状态流每帧跑一次规则判定的次数（A-OV2 新增）。
 *    「判定 → 重算 → 变化」三个数字连起来才看得懂：判定必然每帧跑，
 *    重算只在判定结果变化时跑，变化则更少 —— 后两者相等才是正常。
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

    private val visibilityEval = AtomicLong()
    private val visibilityApply = AtomicLong()
    private val visibilityChange = AtomicLong()
    private val handleRebuild = AtomicLong()
    private val a11yTicks = AtomicLong()
    private val a11yTickNanos = AtomicLong()
    private val renderApply = AtomicLong()
    private val renderSkip = AtomicLong()

    /** 首次判定时的开机毫秒数；0 = 还没判定过。用来把次数换算成「每分钟」 */
    private val evalStartedAt = AtomicLong()

    /**
     * 每帧判定一次显隐（A-OV2）。
     *
     * 这个计数本身没有优化含义，它的用途是**给另外两个数字提供分母**：
     * 只有知道「判定跑了 3000 次、重算 27 次、变化 26 次」，才能看出
     * 判定结果绝大多数时候没变（正常），以及重算没有在空转。
     */
    fun onVisibilityEval() {
        visibilityEval.incrementAndGet()
        evalStartedAt.compareAndSet(0L, System.nanoTime() / 1_000_000L)
    }

    /** 真正的 `applyVisibility()` 调用次数（只在判定结果变化时发生） */
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
        val eval = visibilityEval.get()
        val apply = visibilityApply.get()
        val changed = visibilityChange.get()
        val rebuild = handleRebuild.get()
        val ticks = a11yTicks.get()
        val draws = renderApply.get()
        val skips = renderSkip.get()
        if (eval == 0L && apply == 0L && rebuild == 0L && ticks == 0L && draws == 0L && skips == 0L) {
            return ""
        }

        val totalMs = a11yTickNanos.get() / 1_000_000.0
        val avgUs = if (ticks <= 0L) 0.0 else a11yTickNanos.get() / 1000.0 / ticks

        return buildString {
            append("开销（本进程累计）：显隐判定 $eval 次")
            append(" · 重算 $apply 次（实际变化 $changed 次）")
            append(" · 侧钮重建 $rebuild 次")
            append(" · 刷字 $draws 次（跳过 $skips 次）")
            append(" · 无障碍轮询 $ticks 次，累计 ")
            append(String.format(java.util.Locale.US, "%.0f ms（均 %.0f µs/次）", totalMs, avgUs))

            // ⚠️ 判据方向（2026-11-01 修正 A-OV2）：
            // v1.2.42 起 `applyVisibility` **只在判定结果变化时才调用**，所以
            // 「重算次数 ≈ 变化次数」是**最理想**的状态，不是抖动。
            // 旧判读写的是「变化占比高 = 状态在反复翻转」，方向正好相反 ——
            // v1.2.44 车机包（27 次重算 / 26 次变化）就因此被判成异常，实际很健康。
            // 真正该看的是**频率**：每分钟重算多少次。
            val startedAt = evalStartedAt.get()
            val elapsedMin = if (startedAt > 0L) {
                (System.nanoTime() / 1_000_000L - startedAt) / 60_000.0
            } else 0.0
            if (elapsedMin >= 0.5) {
                val perMin = apply / elapsedMin
                append('\n')
                if (perMin >= 20.0) {
                    append(
                        "判读：每分钟重算 " + String.format(java.util.Locale.US, "%.0f", perMin) +
                            " 次，显隐切换频繁 —— 查前台包名抖动（防抖窗口 / 名单应用是否在反复进出）",
                    )
                } else {
                    append(
                        "判读：每分钟重算 " + String.format(java.util.Locale.US, "%.0f", perMin) +
                            " 次；重算≈变化说明没有无效重算（v1.2.42 起显隐未变即跳过）",
                    )
                }
            }
        }
    }

    /** 单测用：清空计数 */
    internal fun reset() {
        visibilityEval.set(0)
        visibilityApply.set(0)
        visibilityChange.set(0)
        handleRebuild.set(0)
        a11yTicks.set(0)
        a11yTickNanos.set(0)
        renderApply.set(0)
        renderSkip.set(0)
        evalStartedAt.set(0)
    }
}
