package com.luckylearning.windtone.data.aliyun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * 云盘目录**递归扫描成歌单**的规则与执行（深度 2 层）。
 *
 * 需求语义：以选中目录为根，把「根 + 根的直接子目录 + 子目录的子目录」里的音频
 * 全部收进一份歌单；**第三层及更深不再进入**（用户拍板的固定深度，不做成设置项）。
 *
 * 为什么独立成一个文件：扫描的编排规则（层深、过滤、排序、失败目录跳过）都是
 * 真行为但不需要联网才能验 —— 层深与排序做成纯函数，由 [AliyunScanRulesTest] 钉住；
 * 网络编排放 [scanForPlaylist]，构造时注入列表函数即可离线单测。
 */
object AliyunScanRules {

    /** 最大递归深度：根目录算第 0 层，往下最多进入第 [SCAN_DEPTH] 层 */
    const val SCAN_DEPTH = 2

    /**
     * 位于 [depth] 层的目录还能不能往下进子目录。
     *
     * 根（0）可以进第 1 层；第 1 层可以进第 2 层；第 2 层的目录**不再下钻**。
     */
    fun canDescend(depth: Int): Boolean = depth < SCAN_DEPTH

    /** 一个被收进歌单的音频文件：fileId + 它在云盘里的相对位置（展示与排序用） */
    data class ScanHit(
        val fileId: String,
        val name: String,
        /** 从根到该文件所在目录的路径，如 `"周杰伦/范特西"`；根目录下的文件为空串 */
        val parentPath: String = "",
    ) {
        /** 展示标题：目录名参与展示（同一专辑名的歌集中在一起看） */
        val displayTitle: String
            get() = AliyunRules.displayTitle(name)
    }

    /** 扫描用的目录任务 */
    data class DirTask(
        val fileId: String,
        val path: String,
        val depth: Int,
    )

    /**
     * 收集结果的统一排序：先按所在目录路径（不区分大小写）、再按文件名。
     * 同一目录的歌自然相邻，整体接近文件管理器的观感。
     */
    fun sortCollected(files: List<ScanHit>): List<ScanHit> =
        files.sortedWith(
            compareBy<ScanHit> { it.parentPath.lowercase() }
                .thenBy { it.name.lowercase() },
        )

    /** 列目录失败时的兜底：跳过该目录继续扫（结果里少几首比整单失败强），错误原因返回给调用方展示 */
    fun skipReason(path: String, message: String?): String =
        "跳过「${path.ifBlank { "根目录" }}」：${message ?: "列目录失败"}"

    /**
     * 扫描执行：从 [rootId] 开始广度优先下钻，最多 [SCAN_DEPTH] 层。
     *
     * [listDir] 是「列一个目录（内部处理分页）」的实现，注入它是为了离线单测；
     * [onProgress] 每列完一个目录回调一次（已扫目录数 / 已收文件数）。
     * 协程取消（用户点停止）会在每个目录之间响应。
     *
     * 返回按 [sortCollected] 排好的文件列表 + 被跳过目录的原因列表。
     */
    suspend fun scan(
        rootId: String,
        rootPath: String = "",
        listDir: suspend (String) -> List<AliyunEntry>,
        onProgress: suspend (dirsDone: Int, filesFound: Int) -> Unit = { _, _ -> },
    ): ScanOutcome {
        val files = mutableListOf<ScanHit>()
        val skipped = mutableListOf<String>()
        var dirsDone = 0
        val queue = ArrayDeque<DirTask>()
        queue.add(DirTask(rootId, rootPath, 0))

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val task = queue.removeFirst()
            val entries = try {
                listDir(task.fileId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped += skipReason(task.path, e.message)
                dirsDone++
                onProgress(dirsDone, files.size)
                continue
            }
            entries.forEach { entry ->
                when {
                    entry.isPlayableAudio -> files += ScanHit(entry.fileId, entry.name, task.path)
                    entry.isDir && canDescend(task.depth) ->
                        queue.add(
                            DirTask(
                                fileId = entry.fileId,
                                path = if (task.path.isBlank()) entry.name else "${task.path}/${entry.name}",
                                depth = task.depth + 1,
                            ),
                        )
                }
            }
            dirsDone++
            onProgress(dirsDone, files.size)
        }
        return ScanOutcome(sortCollected(files), skipped)
    }

    data class ScanOutcome(
        val files: List<ScanHit>,
        /** 被跳过的目录及原因（空 = 全部成功） */
        val skipped: List<String>,
    )
}
