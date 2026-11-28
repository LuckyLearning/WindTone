package com.luckylearning.windtone.data

import android.content.Context

/**
 * 用户手动选定的歌词（按 [Song.stableKey]）。
 *
 * 只存 LRC / 纯文本正文，不存候选 id：平台歌词接口随时可能改版，存正文最稳；
 * 一首通常 2~5KB，按用户实际搜过的歌增长，不预置、不主动清理。
 */
object LyricsOverrideStore {

    private const val PREFS = "lyrics_override"
    private const val MAX_BYTES = 256 * 1024

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, stableKey: String): String? =
        prefs(context).getString(stableKey, null)?.takeIf { it.isNotBlank() }

    fun put(context: Context, stableKey: String, text: String) {
        if (text.isBlank()) return
        val body = text.take(MAX_BYTES)
        prefs(context).edit().putString(stableKey, body).apply()
    }

    fun clear(context: Context, stableKey: String) {
        prefs(context).edit().remove(stableKey).apply()
    }

    /** 「清空全部用户数据」时一并清掉，避免用户以为已经清干净 */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
