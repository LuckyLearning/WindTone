package com.luckylearning.windtone.lyrics

/**
 * 锁屏 / 通知栏「这一刻该显示哪一行」的纯函数。
 *
 * 时间轴决策全部复用 [OverlayContentResolver]——前奏回落、间奏回溯、双语主行
 * 与悬浮歌词同一口径，避免两套引擎各算各的。本对象只多做两件事：
 *  1. 无歌词 / 前奏（idle 且内容就是歌名）时回退成「标题 · 艺人」；
 *  2. 把结果标成 [fromLyrics]，便于发布侧决定要不要写入 MediaMetadata.description。
 */
object LockScreenLyrics {

    data class Display(
        /** 写入通知栏 / 锁屏 SYNOPSIS 的文案；空串表示清空 description、回落系统默认艺人行 */
        val text: String,
        /** true = 时间轴上的歌词行；false = 标题/艺人回退（或不显示） */
        val fromLyrics: Boolean,
    )

    /**
     * @param title 歌名（与悬浮歌词同一份）
     * @param artist 艺人；空白或 `<unknown>` 时不拼进回退文案
     */
    fun current(
        lyrics: Lyrics,
        positionMs: Long,
        offsetMs: Int,
        title: String,
        artist: String,
    ): Display {
        if (lyrics.isEmpty || !lyrics.synced) {
            return fallback(title, artist)
        }
        val resolved = OverlayContentResolver.resolve(
            lyrics = lyrics,
            positionMs = positionMs,
            offsetMs = offsetMs,
            title = title,
        )
        // 前奏：resolver 用歌名占位并标 idle —— 锁屏上仍回退标题/艺人，不把歌名再写一遍进 SYNOPSIS
        if (resolved.idle && (resolved.current.isBlank() || resolved.current == title)) {
            return fallback(title, artist)
        }
        if (resolved.current.isNotBlank()) {
            return Display(text = resolved.current, fromLyrics = true)
        }
        return fallback(title, artist)
    }

    fun fallback(title: String, artist: String): Display {
        val a = artist.trim().takeIf { it.isNotEmpty() && it != "<unknown>" }.orEmpty()
        val t = title.trim()
        val text = when {
            t.isNotEmpty() && a.isNotEmpty() -> "$t · $a"
            t.isNotEmpty() -> t
            a.isNotEmpty() -> a
            else -> ""
        }
        return Display(text = text, fromLyrics = false)
    }
}
