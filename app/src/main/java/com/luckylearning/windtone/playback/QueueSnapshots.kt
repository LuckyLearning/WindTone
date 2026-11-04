package com.luckylearning.windtone.playback

/**
 * 多播放队列：把当前队列（顺序 + 当前曲 + 进度）存成**命名快照**，随时切回
 * （竞品借鉴：Musicolet 的 20 路队列 / Symfonium 的多队列）。
 *
 * 设计取舍：
 * - **只存歌曲主键**（`SongEntity.id`），不存 URI 列表 —— 主键短、天然去重，
 *   读写都省一半体积；恢复时按主键回表，顺带自动过滤「已移出曲库 / 文件丢失」的歌。
 * - 序列化用**纯文本块**：一行一个快照，`名称\t当前序号\t进度毫秒\tid,id,id`。
 *   不做 JSON：项目没有 JSON 依赖，且这层只需要 JVM 单测能断言，纯文本最省事。
 * - 上限 [MAX_SNAPSHOTS]：快照是「常用几套听法」，不是收藏夹；无上限会长成第二个歌单系统。
 *
 * 纯 Kotlin，无 Android 依赖。
 */
object QueueSnapshots {

    const val MAX_SNAPSHOTS = 20

    data class Snapshot(
        val name: String,
        /** 队列里的歌曲主键，按播放顺序 */
        val songIds: List<Long>,
        /** 恢复后停在第几首（已按列表长度做夹取） */
        val index: Int,
        /** 恢复后跳到该进度（毫秒） */
        val positionMs: Long,
        /** 保存时间（毫秒），用于列表排序展示 */
        val savedAt: Long = 0L,
    )

    /** 名称里的分隔符与换行会被压成空格 —— 否则一行记录会被拆坏 */
    private fun sanitize(name: String): String =
        name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun encode(list: List<Snapshot>): String =
        list.asSequence()
            .filter { it.name.isNotBlank() && it.songIds.isNotEmpty() }
            .joinToString("\n") { s ->
                "${sanitize(s.name)}\t${s.index}\t${s.positionMs}\t${s.savedAt}\t" +
                    s.songIds.joinToString(",")
            }

    fun decode(raw: String): List<Snapshot> =
        raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 5) return@mapNotNull null
            val name = parts[0].trim()
            if (name.isEmpty()) return@mapNotNull null
            val ids = parts[4].split(',').mapNotNull { it.trim().toLongOrNull() }
            if (ids.isEmpty()) return@mapNotNull null
            Snapshot(
                name = name,
                songIds = ids,
                index = parts[1].trim().toIntOrNull()?.coerceIn(0, ids.size - 1) ?: 0,
                positionMs = parts[2].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                savedAt = parts[3].trim().toLongOrNull() ?: 0L,
            )
        }

    /**
     * 保存（同名覆盖）并保持「最近保存的排最前」，超出上限丢最旧的。
     * 同名覆盖是刻意的：用户重存一次就是想更新那套队列，不是想留两个同名项。
     */
    fun upsert(list: List<Snapshot>, snapshot: Snapshot): List<Snapshot> {
        if (snapshot.name.isBlank() || snapshot.songIds.isEmpty()) return list
        val rest = list.filterNot { it.name == snapshot.name }
        return (listOf(snapshot) + rest).take(MAX_SNAPSHOTS)
    }

    fun remove(list: List<Snapshot>, name: String): List<Snapshot> = list.filterNot { it.name == name }
}
