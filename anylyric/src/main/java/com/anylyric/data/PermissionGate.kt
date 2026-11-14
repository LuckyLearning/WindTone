package com.anylyric.data

/**
 * 「跳去系统设置又回来，权限还是没开」的判定。
 *
 * 为什么需要它：`startActivity` 不抛异常**不等于**页面真能打开。DiLink5 会把
 * 「通知使用权」列表页拦成一句「多媒体不支持」，Intent 却成功返回 —— 老代码据此
 * 认为跳转成功直接 return，用户点完什么都没发生，也没有任何解释。
 *
 * 纯函数：`elapsed` 太短时不能判（有些 ROM 起设置页前会先让我们 resume 一次），
 * 会误弹向导；只有「真的往返过且仍未授权」才值得打断用户。
 */
object PermissionGate {

    /**
     * 从设置页返回的宽限期。
     *
     * 真机上设置页启动到本页 resume 一般 300~900ms；低于这个值多半是
     * 「点了但没走」（系统只弹了个 toast），由延迟探测那条路负责。
     */
    const val RETURN_GRACE_MS = 900L

    /** 延迟兜底探测的等待时间：设置页冷启动最慢约 2s，再久就会误判。 */
    const val PROBE_DELAY_MS = 3000L

    fun shouldGuide(elapsedMs: Long, granted: Boolean): Boolean =
        !granted && elapsedMs >= RETURN_GRACE_MS
}
