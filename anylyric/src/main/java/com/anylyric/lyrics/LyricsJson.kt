package com.anylyric.lyrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 歌词解包。
 *
 * 有些播放器把歌词塞进元数据 / 播放状态扩展字段时不是裸 LRC，而是 JSON：
 *  · `{"lyrics":"[00:01.00]..."}`、`{"lrc":"..."}`、`{"data":{"lyric":"..."}}`；
 *  · 甚至数组套对象（`[{"content":"..."}]`）。
 *
 * 直接交给 [LrcParser] 会解析出 0 行 —— 表现就是「播放器明明给了歌词，我们却显示暂无」。
 * 这里只做**结构解包**，不判断内容像不像歌词：采纳与否仍由调用方的「解析出 ≥2 行」把关，
 * 避免把一段普通 JSON 文本误当成歌词。
 *
 * 性能约定：
 *  · 只有文本以 `{` / `[` 开头时才尝试，普通 LRC 不付任何额外成本；
 *  · 递归深度上限 [MAX_DEPTH]，异常结构不会把栈打穿；
 *  · 解析失败一律返回原文，交给后续解析器兜底。
 */
object LyricsJson {

    /** 歌词正文最常出现的键名（顺序即优先级）。 */
    private val TEXT_KEYS = arrayOf(
        "lyrics", "lyric", "lrc", "lyric_content", "lrc_content", "content"
    )

    /** 常见的外层包装键。 */
    private val WRAP_KEYS = arrayOf("data", "result", "payload")

    private const val MAX_DEPTH = 4

    /**
     * 尝试把 JSON 包装的歌词解出来。
     *
     * @return 解包后的歌词原文；不是 JSON 或取不到时**原样返回** `raw`。
     */
    fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trimStart()
        // LRC 本身就以 `[` 开头（`[00:01.00]`），不能见到 `[` 就当 JSON 数组去解析：
        // 那会让每一段正常歌词都白白抛一次解析异常。只有 `{` 或「像 JSON 数组」才试。
        if (!looksLikeJson(trimmed)) return raw
        return runCatching { extract(trimmed) }.getOrNull() ?: raw
    }

    private fun looksLikeJson(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text[0] == '{') return true
        if (text[0] != '[') return false
        // `[{"...`、`["..."`、`[[...`、`[]` 才算 JSON 数组；`[00:01.00]` 不是。
        var i = 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return false
        return when (text[i]) {
            '{', '"', '[', ']' -> true
            else -> false
        }
    }

    private fun extract(text: String): String? {
        val node: Any = when {
            text.startsWith('{') -> JSONObject(text)
            text.startsWith('[') -> JSONArray(text)
            else -> return null
        }
        return extractJson(node, 0)
    }

    private fun extractJson(node: Any?, depth: Int): String? {
        if (node == null || depth > MAX_DEPTH) return null
        when (node) {
            is String -> return node.takeIf { it.isNotBlank() }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = extractJson(node.opt(i), depth + 1)
                    if (found != null) return found
                }
            }
            is JSONObject -> {
                for (key in TEXT_KEYS) {
                    val value = node.opt(key)
                    if (value != null) {
                        val found = extractJson(value, depth + 1)
                        if (found != null) return found
                    }
                }
                for (key in WRAP_KEYS) {
                    val value = node.opt(key)
                    if (value != null) {
                        val found = extractJson(value, depth + 1)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }
}
