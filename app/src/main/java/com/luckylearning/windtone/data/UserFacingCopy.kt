package com.luckylearning.windtone.data

/**
 * A4 用户可见提示：统一成「短原因 + 一条可操作路径」。
 *
 * 播放失败、权限缺失、云盘断流、扫描失败等高流量路径都经这里拼装，
 * 避免各处各写一套「请稍后重试」却不告诉用户下一步去哪。
 *
 * Snackbar / Toast 一次读完，用句号衔接；若原因里已含「下一步」则不再重复拼接。
 */
object UserFacingCopy {

    const val NEXT_MARK = "下一步："

    /** 原因 + 一条可操作路径（句号衔接，便于 Snackbar 一次读完）。 */
    fun withNext(reason: String, nextStep: String): String {
        val r = reason.trim()
        val n = nextStep.trim()
        if (r.isEmpty()) return if (n.isEmpty()) "" else "$NEXT_MARK$n"
        if (n.isEmpty()) return r
        if (NEXT_MARK in r) return r
        return "$r。$NEXT_MARK$n"
    }

    /**
     * 若 [text] 已含「下一步」则原样返回；否则补上 [nextStep]。
     * 用于外层包装（如「已跳到下一首」）包住已带路径的 [PlaybackErrorMessages] 结果时，
     * 不要丢掉已有路径，也不要叠两句「下一步」。
     */
    fun ensureNext(text: String, nextStep: String): String {
        val t = text.trim()
        if (t.isEmpty()) return withNext("", nextStep)
        if (NEXT_MARK in t) return t
        return withNext(t, nextStep)
    }
}
