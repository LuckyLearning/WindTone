package com.luckylearning.windtone.data

/**
 * 扫描黑名单（竞品借鉴：Salt Player「文件夹黑名单」）。
 *
 * 需求：全盘扫描时把录音、短视频碎片、导航提示音这类「技术上合法但永远不想听」的
 * 目录整棵剪掉 —— 既能少读几百个文件的标签，曲库也不会被垃圾条目刷屏。
 *
 * 表达形式刻意保持最简：**一行一条规则**，规则可以是
 * - 目录名：`录音` —— 任何层级下叫这个名字的目录整棵剪掉；
 * - 路径片段：`DCIM/Camera` —— 完整路径包含该片段即剪掉（大小写不敏感）。
 *
 * 匹配语义（[normalize] 之后的规则）：
 * - 目录命中 = 目录名与规则**全等**，或规则在目录完整路径里作为**完整路径段**
 *   （含多段规则，如 `DCIM/Camera`）出现；
 * - 文件命中 = 文件完整路径里出现 `/规则/` 或以 `/规则` 结尾。
 * 判定一律是路径段级而非朴素 `contains`，目的是避免 `录音` 误伤 `录音棚精选`。
 *
 * 纯 Kotlin、零 Android 依赖，方便 JVM 单测。
 */
object ScanBlocklist {

    /** 规则规范化：去空白、去首尾斜杠、转小写、去重、剔除空规则 */
    fun normalize(entries: Collection<String>): List<String> =
        entries.asSequence()
            .map { it.trim().trim('/').lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /**
     * 目录是否应整棵剪掉（不再递归、目录内文件一律不收）。
     *
     * @param dirName 目录名（最后一段）
     * @param dirPath 目录完整路径；SAF 侧没有真实路径时传文档 ID（同样包含层级片段）
     */
    fun blocksDir(dirName: String, dirPath: String?, rules: List<String>): Boolean {
        if (rules.isEmpty()) return false
        val name = dirName.trim().trim('/').lowercase()
        val path = dirPath?.trim()?.trim('/')?.lowercase()
        return rules.any { r ->
            // 目录名全等，或规则在完整路径里作为**完整路径段**出现（首尾也要算边界）
            name == r ||
                (path != null && (path == r || path.contains("/$r/") || path.endsWith("/$r")))
        }
    }

    /**
     * 文件绝对路径是否命中黑名单（MediaStore 行过滤用 —— 那边的目录是摊平的行，
     * 没有「先目录后文件」的递归结构，只能按路径判定）。
     */
    fun blocksPath(filePath: String, rules: List<String>): Boolean {
        if (rules.isEmpty()) return false
        val p = filePath.trim().trim('/').lowercase()
        if (p.isEmpty()) return false
        return rules.any { r -> p.contains("/$r/") || p.endsWith("/$r") }
    }
}
