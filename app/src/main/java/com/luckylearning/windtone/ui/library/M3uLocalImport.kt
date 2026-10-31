package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.model.Song

/**
 * 本地曲库 M3U 导入：纯解析/匹配（无 Context / 无磁盘）。
 *
 * 性能约束：
 * - 按条目数 / 命中数设硬上限，避免巨型清单 OOM 或主线程卡死
 * - 路径索引 O(1)；禁止对整库反复 `endsWith` 扫描
 * - 调用方须在 IO 调度器读文件后再调 [match]
 */
object M3uLocalImport {

    /** 清单正文上限（字节，UTF-8）；超出拒读，防 OOM */
    const val MAX_BYTES = 1_048_576

    /** 参与匹配的最大物理行数 */
    const val MAX_LINES = 20_000

    /** 写入新歌单的最大命中曲目 */
    const val MAX_MATCHED = 2_000

    data class Outcome(
        val matched: List<Song>,
        /** 因 [MAX_MATCHED] / [MAX_LINES] 提前停下 */
        val truncated: Boolean,
        val lineCount: Int,
    )

    /**
     * 将 M3U 正文与本机曲库对齐。
     *
     * 匹配顺序：完整 path → 文件名（path / uri 末段）→ EXTINF 标题（去「歌手 - 」后半）。
     * 相对路径不擅自拼盘符（本机导入以导出的绝对 path / content uri 为主）。
     */
    fun match(text: String, library: List<Song>): Outcome {
        val byPath = HashMap<String, Song>(library.size.coerceAtLeast(16))
        val byFile = HashMap<String, Song>(library.size.coerceAtLeast(16))
        val byTitle = HashMap<String, Song>(library.size.coerceAtLeast(16))
        for (s in library) {
            val p = s.path
            if (p != null) {
                byPath[p] = s
                val name = fileNameOf(p)
                if (name.isNotEmpty()) byFile.putIfAbsent(name, s)
            }
            val uriTail = fileNameOf(s.stableKey)
            if (uriTail.isNotEmpty()) byFile.putIfAbsent(uriTail, s)
            byTitle.putIfAbsent(s.title.lowercase(), s)
        }

        val matched = ArrayList<Song>(minOf(MAX_MATCHED, 64))
        val seen = HashSet<String>(minOf(MAX_MATCHED, 64))
        var pendingTitle: String? = null
        var truncated = false
        var lineCount = 0

        for (raw in text.lineSequence()) {
            lineCount++
            if (lineCount > MAX_LINES) {
                truncated = true
                break
            }
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF", ignoreCase = true)) {
                pendingTitle = l.substringAfter(':', "").trim()
                continue
            }
            if (l.startsWith("#")) continue

            val clean = when {
                l.startsWith("file://", ignoreCase = true) -> l.substring(7)
                else -> l
            }
            val song = byPath[clean]
                ?: byFile[fileNameOf(clean)]
                ?: pendingTitle?.let { t ->
                    val name = t.substringAfter(" - ").ifBlank { t }
                    byTitle[name.lowercase()]
                }
            pendingTitle = null
            if (song == null) continue
            if (!seen.add(song.stableKey)) continue
            matched.add(song)
            if (matched.size >= MAX_MATCHED) {
                truncated = true
                break
            }
        }
        return Outcome(matched = matched, truncated = truncated, lineCount = lineCount)
    }

    fun fileNameOf(pathOrUri: String): String {
        val cut = pathOrUri.substringAfterLast('/').substringAfterLast('\\')
        return cut
    }

    /** 有上限地读入 UTF-8 文本；超过 [MAX_BYTES] 返回 null（调用方提示过大）。 */
    fun decodeUtf8Capped(bytes: ByteArray, maxBytes: Int = MAX_BYTES): String? {
        if (bytes.size > maxBytes) return null
        return bytes.toString(Charsets.UTF_8)
    }

    /** 从流按上限读字节；若流更长则 [Pair.second]=true。 */
    fun readCapped(stream: java.io.InputStream, maxBytes: Int = MAX_BYTES): Pair<ByteArray, Boolean> {
        val buf = ByteArray(maxBytes + 1)
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        val truncated = off > maxBytes
        val len = minOf(off, maxBytes)
        return buf.copyOf(len) to truncated
    }
}
