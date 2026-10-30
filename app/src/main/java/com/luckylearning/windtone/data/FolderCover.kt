package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import java.io.File

/**
 * 同目录文件夹封面回退（A6）。
 *
 * 当音频**没有内嵌封面**时，按固定优先级在同目录找常见文件名：
 * `folder.jpg` → `cover.jpg` → `AlbumArt.jpg`（大小写不敏感，命中后保留磁盘上的真实文件名）。
 *
 * 只走真实路径（[Song.path] 的父目录，其次 [Song.folderPath] 若本身是可列目录）。
 * SAF 未解析出路径、以及远程曲目一律不找 —— 与侧车歌词 [LyricsLoader.findSidecar] 同口径。
 *
 * ## 缓存口径（与 [CoverRepository] 共用键）
 *
 * - 读到的字节写入同一套「原始字节 / 磁盘解码」缓存，键仍是 `stableKey`，**不另开通道**。
 * - **内嵌优先**：有内嵌图时根本不会进本类。
 * - **负缓存**：内嵌与文件夹都没有时仍缓存空字节（既有行为），同进程内不会反复扫目录；
 *   用户事后往目录丢了 `folder.jpg`，需「清空封面缓存」或杀进程后才会出现。
 * - **换图**：替换同名 `folder.jpg` 后，位图/磁盘缓存可能仍是旧图，同样靠「清空封面缓存」失效；
 *   与内嵌封面被改写时的行为一致，不做文件 mtime 监视。
 */
object FolderCover {

    /** 候选名（按优先级）；匹配时忽略大小写 */
    val CANDIDATES: List<String> = listOf(
        "folder.jpg",
        "cover.jpg",
        "AlbumArt.jpg",
    )

    /** 单文件上限：与 [CoverRepository.loadRaw] 内嵌图一致（8MB） */
    const val MAX_BYTES: Int = 8 * 1024 * 1024

    /**
     * 在目录名列表里按 [CANDIDATES] 优先级挑一个真实文件名。
     * 大小写不敏感；同名不同大小写时保留列表里先出现的那份。
     */
    fun pickName(names: List<String>): String? {
        if (names.isEmpty()) return null
        val byLower = LinkedHashMap<String, String>()
        for (n in names) {
            val key = n.lowercase()
            if (key !in byLower) byLower[key] = n
        }
        for (cand in CANDIDATES) {
            byLower[cand.lowercase()]?.let { return it }
        }
        return null
    }

    /**
     * 解析同目录封面文件。找不到或不可读返回 null。
     * 纯文件 API，便于单测；真正读字节见 [readBytes]。
     */
    fun resolveFile(song: Song): File? {
        if (song.source == SongSource.REMOTE) return null
        val dirs = linkedSetOf<File>()
        song.path?.let { p ->
            File(p).parentFile?.let { dirs.add(it) }
        }
        song.folderPath?.let { fp ->
            val dir = File(fp)
            dirs.add(dir)
        }
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val names = runCatching { dir.list()?.toList().orEmpty() }.getOrDefault(emptyList())
            val hit = pickName(names) ?: continue
            val file = File(dir, hit)
            if (!file.isFile) continue
            val len = runCatching { file.length() }.getOrDefault(0L)
            if (len in 1L..MAX_BYTES.toLong()) return file
        }
        return null
    }

    /** 读同目录封面字节；失败或超限返回 null */
    fun readBytes(song: Song): ByteArray? {
        val file = resolveFile(song) ?: return null
        return runCatching {
            val len = file.length()
            if (len !in 1L..MAX_BYTES.toLong()) return null
            file.readBytes().takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        }.getOrNull()
    }
}
