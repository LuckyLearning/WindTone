package com.anylyric.lyrics

/**
 * 本地 LRC 文件的读取辅助（0.2.27）。
 *
 * 编码是这里唯一真正的坑：车机上用 U 盘拷来的 `.lrc` 大量是 GBK / GB18030，
 * 直接按 UTF-8 读会得到一串乱码，解析出来是零行 —— 用户看到的只是「导入失败」，
 * 完全不知道其实是编码问题。
 *
 * 判定顺序有讲究：**先试 UTF-8**，出现替换字符 U+FFFD 才退回 GB18030。
 * 反过来不行 —— 合法 UTF-8 用 GB18030 解同样不会报错，但会把中文解坏。
 */
object LrcFile {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // BOM 必须先剥：带 BOM 的首行会变成 `\uFEFF[00:12.00]`，时间标签一个都匹配不上。
        val body = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val utf8 = String(body, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(body, charset("GB18030")) }.getOrDefault(utf8)
    }

    /** 从显示名或路径里取文件名（不含扩展名），用于提示「导入的是哪一份」。 */
    fun displayName(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val name = path.trim().substringAfterLast('/').substringAfterLast(':')
        val base = name.substringBeforeLast('.')
        return base.ifBlank { name }
    }
}
