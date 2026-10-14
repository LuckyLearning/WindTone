package com.luckylearning.windtone.data

import android.net.Uri

/**
 * SAF 路径小工具。
 *
 * 目录在数据层有两个身份：**树 URI**（拿权限、释放权限用）和**文档 ID**
 * （`primary:Music/华语`，分组键、去重键、与歌曲 `folderPath` 比对用）。
 * 这些换算散落在多处，集中在这里，迁移任务与界面共用同一套口径。
 */

/** 取 URI 的文档 ID（已解码）；拿不到返回 null */
internal fun docIdOf(uriString: String): String? = runCatching {
    Uri.decode(Uri.parse(uriString).lastPathSegment.orEmpty())
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * 目录的展示名：只取路径里**最后一个文件夹的名字**。
 * 「/storage/emulated/0/Music/华语」→「华语」；SAF 传进来的本来就是目录名，原样返回。
 */
internal fun folderDisplayName(path: String): String =
    path.trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { path }

/** SAF 树目录的展示名：`primary:Music/华语` → `华语`；解析不出退回原始串 */
internal fun treeDisplayName(treeUri: String): String =
    docIdOf(treeUri)?.substringAfter(':')?.let(::folderDisplayName) ?: folderDisplayName(treeUri)

/**
 * 分组键的父路径提示（同名目录消歧用）：
 * `primary:Music/华语` → `Music`；`/storage/emulated/0/Music` → `/storage/emulated/0`
 */
internal fun folderParentHint(label: String): String {
    val parent = label.substringBeforeLast('/', "")
    if (parent.isEmpty()) return label
    return parent.substringAfter(':', parent)
}

/** 文档 ID 里冒号后的路径部分：`primary:Music/华语` → `Music/华语` */
internal fun docIdFullPath(docId: String): String = docId.substringAfter(':', docId)

/**
 * 从 SAF 音频文档 URI 反推出所在的目录路径。
 * 示例：content://.../document/primary%3AMusic%2FTaylor%20Swift%2Fsong.mp3
 *       → primary:Music/Taylor Swift/song.mp3 → Music/Taylor Swift
 */
internal fun Uri.derivedFolderPath(): String? {
    val docId = lastPathSegment ?: return null
    val decoded = Uri.decode(docId)
    return docIdFullPath(decoded).substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
}
