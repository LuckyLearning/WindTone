package com.anylyric.diag

/**
 * 工作量计数器。
 *
 * 为什么需要它：`cpu=17%` 这种数字只能说明「在烧」，说明不了「烧在哪」。把
 * 「触发了几次刷新 / 真正跑了几次责任链 / 短路掉几次 / 重绘几次」摊开，才能一眼
 * 看出 CPU 是被事件量放大出来的，还是被某一段代码吃掉的 —— 本应用第一份真机报告
 * 就是靠这个对出来的（代理类应用每秒一条通知，把歌词链当 1Hz 轮询在跑）。
 *
 * 只做计数，不做计时：计数器本身的开销必须是「一个 int 自增」，否则观测手段自己
 * 就成了性能问题。
 */
object Perf {

    /** 进入刷新的次数（事件合并之后）。 */
    var refresh: Int = 0
        private set

    /** 真正重跑责任链（读元数据 + 解析歌词）的次数。 */
    var chain: Int = 0
        private set

    /** 直接短路掉的次数（与本应用无关的输入）。 */
    var skip: Int = 0
        private set

    /** 把文本推给悬浮层的次数（内容没变时悬浮层自己会再挡一层）。 */
    var render: Int = 0
        private set

    /** 定时推进 tick 的次数。 */
    var tick: Int = 0
        private set

    private var markRefresh = 0
    private var markChain = 0
    private var markSkip = 0
    private var markRender = 0
    private var markTick = 0

    fun onRefresh() {
        refresh++
    }

    fun onChain() {
        chain++
    }

    fun onSkip() {
        skip++
    }

    fun onRender() {
        render++
    }

    fun onTick() {
        tick++
    }

    /** 累计值，进报告。 */
    fun oneLine(): String = buildString(64) {
        append("refresh=$refresh chain=$chain skip=$skip render=$render tick=$tick")
    }

    /**
     * 距上次调用以来的增量，贴在每分钟的资源行后面。
     *
     * 这样一行里就能同时看到「这分钟烧了多少 CPU」与「这分钟干了多少活」，两者一起
     * 涨说明是事件量问题，只有 CPU 涨说明是单点开销。
     */
    fun sinceLast(): String = buildString(64) {
        append("+refresh=").append(refresh - markRefresh)
        append(" +chain=").append(chain - markChain)
        append(" +skip=").append(skip - markSkip)
        append(" +render=").append(render - markRender)
        append(" +tick=").append(tick - markTick)
        markRefresh = refresh
        markChain = chain
        markSkip = skip
        markRender = render
        markTick = tick
    }

    /** 单测与「清空日志」共用：把累计值也归零，否则增量会算出负数。 */
    fun reset() {
        refresh = 0
        chain = 0
        skip = 0
        render = 0
        tick = 0
        markRefresh = 0
        markChain = 0
        markSkip = 0
        markRender = 0
        markTick = 0
    }
}
