package com.luckylearning.windtone.ui

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图标路径规范回归（v1.1.3 起）。
 *
 * ## 为什么要单独守一条
 *
 * 从 `docs/ui/index.html` 的 `<symbol>` 抄路径时，浏览器允许 **arc flag 连写**：
 * 效果图里写 `a7.5 7.5 0 00-14.3-1.6`，按 SVG 规范这是合法的 —— large-arc 与
 * sweep 两个 flag **各只占一个字符**，所以 `00` 表示「两个 0」，其后 `-14.3 -1.6`
 * 是终点坐标。
 *
 * 但 Compose 的 `PathParser` 是按数字**贪婪读取**的：它把 `00` 当成一个数字 `0`，
 * 于是 arc 的 7 个参数少读两个 → 参数错位 → **整条路径的后续部分被静默丢弃**，
 * 既不抛异常也不打日志，`ImageVector` 照样构建成功，只是画出来缺一大块。
 *
 * 实证（2026-10-15）：`#i-cloud` 连写时只解析出 **3 个** PathNode（补空格后 6 个），
 * 云朵只剩左上角一小段弧、在抽屉里看起来就是空白 —— 用户报的就是
 * 「检查更新前面缺个图标」。同批抄进来的 `#i-palette`（8→10）、
 * `#i-lyrics`（7→10）也都被吞掉了尾巴。
 *
 * ## 判据为什么可靠
 *
 * 「**路径里每个命令字母至少产生 1 个 PathNode**」——`PathParser` 对每条命令产出
 * 一个节点，坐标省略时的**隐式重复**只会让节点更多（如 `...a1.5 1.5 0 1 1 0 -3
 * 1.5 1.5 0 0 1 0 3z` 里第二条弧没有字母），因此
 * `节点数 >= 命令字母数` 恒成立；一旦参数被吞导致解析提前结束，节点数就会**小于**
 * 命令数，断言立刻失败。
 *
 * 这条判据与具体写法无关（不需要维护期望节点数表），所以**新增图标自动纳入**检查。
 */
class AppIconsPathTest {

    private val commands = "MmLlHhVvCcSsQqTtAaZz"

    /** `build("Name", "path")`：名字 + 全部字符串字面量（跨行拼接的也拼回来） */
    private val buildCall = Regex(
        """build\(\s*"([A-Za-z][A-Za-z0-9]*)"\s*,\s*((?:"[^"]*"\s*\+?\s*)+)""",
    )
    private val stringLiteral = Regex(""""([^"]*)"""")

    @Test
    fun everyIconPathParsesCompletely() {
        val source = appIconsSource().readText()
        val icons = buildCall.findAll(source).map { m ->
            val name = m.groupValues[1]
            val path = stringLiteral.findAll(m.groupValues[2]).joinToString("") { it.groupValues[1] }
            name to path
        }.toList()

        // 正则失效（比如 AppIcons 被重构、路径换了存放方式）时必须响，不能静默变成空测试
        assertTrue(
            "没从 AppIcons.kt 里扫到任何图标路径 —— 正则已失效，请更新本测试",
            icons.size >= 20,
        )

        val broken = mutableListOf<String>()
        for ((name, path) in icons) {
            val nodes = runCatching { PathParser().parsePathString(path).toNodes().size }
            val expected = path.count { it in commands }
            when {
                nodes.isFailure -> broken += "$name：解析抛异常 ${nodes.exceptionOrNull()?.message}"
                nodes.getOrThrow() < expected -> broken +=
                    "$name：只解析出 ${nodes.getOrThrow()} 个节点，路径里有 $expected 个命令" +
                        "（arc flag 很可能连写了，改成 `a rx ry rot laf sf x y`）"
            }
        }
        assertTrue("以下图标路径解析不完整：\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    /** 单测的工作目录是模块根（`app/`），但兼容从仓库根跑的情况 */
    private fun appIconsSource(): File {
        val relative = "src/main/java/com/luckylearning/windtone/ui/AppIcons.kt"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?: error("找不到 AppIcons.kt（已找过 ${listOf(relative, "app/$relative")}，当前目录 ${File(".").absolutePath}）")
    }
}
