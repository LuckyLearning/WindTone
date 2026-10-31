package com.luckylearning.windtone.data

import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * A4 用户可见提示：统一成「短原因 + 一条可操作路径」。
 *
 * 播放失败、权限缺失、云盘断流、扫描失败等高流量路径都经这里拼装，
 * 避免各处各写一套「请稍后重试」却不告诉用户下一步去哪。
 *
 * Snackbar / Toast 一次读完；拼装模板进 `ufc_*`（i18n 第 21 批）。
 * 若原因里已含「下一步」/「Next:」则不再重复拼接（兼容尚未抽完的硬编码路径）。
 */
object UserFacingCopy {

    /** @deprecated 用 [nextMark]；保留常量名给旧调用点编译期发现。 */
    const val NEXT_MARK = "下一步："

    fun nextMark(): String {
        val s = AppStrings.s(R.string.ufc_next_mark)
        return s.ifEmpty { NEXT_MARK }
    }

    /** 原因 + 一条可操作路径（便于 Snackbar 一次读完）。 */
    fun withNext(reason: String, nextStep: String): String {
        val r = reason.trim()
        val n = nextStep.trim()
        if (r.isEmpty()) {
            if (n.isEmpty()) return ""
            val only = AppStrings.s(R.string.ufc_next_only, n)
            return only.ifEmpty { "$NEXT_MARK$n" }
        }
        if (n.isEmpty()) return r
        if (hasNextMark(r)) return r
        val joined = AppStrings.s(R.string.ufc_with_next, r, n)
        return joined.ifEmpty { "$r。$NEXT_MARK$n" }
    }

    /**
     * 若 [text] 已含下一步标记则原样返回；否则补上 [nextStep]。
     * 用于外层包装（如「已跳到下一首」）包住已带路径的 [com.luckylearning.windtone.playback.PlaybackErrorMessages] 结果时，
     * 不要丢掉已有路径，也不要叠两句「下一步」。
     */
    fun ensureNext(text: String, nextStep: String): String {
        val t = text.trim()
        if (t.isEmpty()) return withNext("", nextStep)
        if (hasNextMark(t)) return t
        return withNext(t, nextStep)
    }

    /** 中英两种标记都认，避免半改路径叠两句。 */
    fun hasNextMark(text: String): Boolean {
        if (NEXT_MARK in text) return true
        if ("Next: " in text) return true
        val live = nextMark()
        return live.isNotEmpty() && live in text
    }
}
