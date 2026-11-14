package com.anylyric.accessibility

/**
 * 媒体通知的文本抽取。
 *
 * 用途：车厂把「通知使用权」授权页拦掉时（DiLink5 →「多媒体不支持」），
 * 无障碍服务仍能收到 `TYPE_NOTIFICATION_STATE_CHANGED`。媒体通知里通常有
 * 歌名与「歌手 - 专辑」，够在线补词用；有些播放器（含 WindTone 的车载蓝牙歌词）
 * 会把当前歌词与后几句塞进大文本，那就是现成的逐行流。
 *
 * **只对媒体通知调用**：调用方必须先确认 `category == CATEGORY_TRANSPORT` 或
 * `extras` 里有 `EXTRA_MEDIA_SESSION`。这个类本身不读任何全局状态，
 * 纯函数、可单测 —— 通知文本的拼接规则很容易写出「标题重复一遍」这种小毛病。
 */
object MediaNotification {

    data class Content(val title: String?, val body: String?)

    /**
     * @param title 通知标题（`EXTRA_TITLE`，或事件自带的文本第一段）
     * @param text 单行正文（`EXTRA_TEXT`）
     * @param bigText 展开后的正文（`EXTRA_BIG_TEXT`），播放器把歌词放这里
     */
    fun split(title: CharSequence?, text: CharSequence?, bigText: CharSequence?): Content {
        val cleanTitle = clean(title)
        // 大文本优先：`EXTRA_TEXT` 往往只是「歌手」，大文本才是多行歌词。
        val body = clean(bigText) ?: clean(text)
        if (body == null) return Content(cleanTitle, null)
        if (body == cleanTitle) return Content(cleanTitle, null)

        // 「第一行 == 标题」是媒体通知的常见形态（标题歌名，正文重复歌名 + 歌手）。
        // 留着会让歌词层的第 0 行变成歌名，按行推进时先唱一遍歌名。
        val lines = body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val deduped = if (lines.size > 1 && lines.first() == cleanTitle) {
            lines.drop(1).joinToString("\n")
        } else {
            body
        }
        return Content(cleanTitle, deduped.ifBlank { null })
    }

    private fun clean(value: CharSequence?): String? {
        val text = value?.toString()?.trim() ?: return null
        return text.ifEmpty { null }
    }
}
