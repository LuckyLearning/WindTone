package com.luckylearning.windtone.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 车机「7 分屏」接入契约（2026-11-14）。
 *
 * ## 为什么这类声明值得单独守一条
 *
 * 分屏能不能被车机列出来，**只看清单里一条厂商私有元数据**：
 *
 * ```xml
 * <meta-data android:name="BYD_SUPPORT_SPLIT_ACTIVITY"
 *            android:value="@integer/byd_support_split_activity"/>
 * ```
 *
 * 它没有任何编译期校验：写错名字、少写 `@integer/` 前缀、把 values-v32 的分档
 * 删掉 —— 全部照常编译、照常安装、照常运行，只有上车点「7 分屏」才发现列表里没有
 * 本应用。这类「静默失效」正是契约测试该守的东西。
 *
 * ## 判据来源
 *
 * 反编译 Cardash 2.33（`source1/decompiled-cardash233`，同为侧载第三方应用）：
 * 其 `<application>` 上就是这条元数据 + `android:resizeableActivity="true"`，
 * 且 `res/values/integers.xml` 为 0、`res/values-v32/integers.xml` 为 1。
 * 相同的三件套在本项目里逐字复刻，因此测试也逐条锁住。
 *
 * 为什么用源码扫描而不是 Robolectric 查 `ApplicationInfo.metaData`：
 * 后者在 Robolectric 里读 `@integer/` 引用是否被正确解析依赖资源装配细节，
 * 而我们要防的是「**写法**写错了」（名字拼错 / 前缀丢了 / 分档没了）——
 * 直接读源文件既明确又不脆弱。
 */
class SplitScreenContractTest {

    private fun sourceFile(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("找不到 $relative（当前目录 ${File(".").absolutePath}）")

    private fun manifest(): String = sourceFile("src/main/AndroidManifest.xml").readText()

    /** 只取 `<application ...>` 的起始标签区间，避免把 activity 上的同名属性也算进来。 */
    private fun applicationTag(): String {
        val text = manifest()
        val start = text.indexOf("<application")
        assertTrue("<application> 必须存在", start >= 0)
        val end = text.indexOf('>', start) + 1
        return text.substring(start, end)
    }

    private fun activityTag(name: String): String {
        val text = manifest()
        val start = text.indexOf("android:name=\"$name\"")
        assertTrue("$name 必须存在", start >= 0)
        val open = text.lastIndexOf('<', start)
        val close = text.indexOf('>', start) + 1
        return text.substring(open, close)
    }

    @Test
    fun `application 声明 BYD 分屏元数据并引用分档整数值`() {
        val text = manifest()
        val index = text.indexOf("BYD_SUPPORT_SPLIT_ACTIVITY")
        assertTrue("缺少 BYD_SUPPORT_SPLIT_ACTIVITY 元数据，车机不会把本应用列进 7 分屏", index >= 0)

        // 名字必须逐字一致（车机按字符串匹配，大小写也不能改）
        assertTrue(text.contains("android:name=\"BYD_SUPPORT_SPLIT_ACTIVITY\""))

        // 取值必须是资源引用，不能写死 true / 1：分档逻辑靠 values-v32 覆盖
        val block = text.substring(index, minOf(text.length, index + 200))
        assertTrue(
            "android:value 必须引用 @integer/byd_support_split_activity（实际：$block）",
            block.contains("android:value=\"@integer/byd_support_split_activity\""),
        )
    }

    @Test
    fun `分屏开关按系统版本分档 —— 12L 才置 1`() {
        val base = sourceFile("src/main/res/values/integers.xml").readText()
        val v32 = sourceFile("src/main/res/values-v32/integers.xml").readText()

        assertTrue(
            "默认档必须为 0：老系统读到 1 会以为该按分屏适配，实际没有入口",
            Regex("""<integer name="byd_support_split_activity">\s*0\s*</integer>""").containsMatchIn(base),
        )
        assertTrue(
            "values-v32 必须为 1：Android 12L 才有 7 分屏选择器",
            Regex("""<integer name="byd_support_split_activity">\s*1\s*</integer>""").containsMatchIn(v32),
        )
    }

    @Test
    fun `application 与 MainActivity 都可缩放，且允许双侧同时 resumed`() {
        assertTrue(
            "application 必须声明 android:resizeableActivity=\"true\"（车机选择器会核查这条）",
            applicationTag().contains("android:resizeableActivity=\"true\""),
        )
        assertTrue(
            "MainActivity 必须声明 android:resizeableActivity=\"true\"",
            activityTag(".MainActivity").contains("android:resizeableActivity=\"true\""),
        )
        assertTrue(
            "缺少 android.allow_multiple_resumed_activities，分屏点另一侧会让我们进 paused，" +
                "歌词自动滚动与封面动画会停住",
            manifest().contains("android.allow_multiple_resumed_activities"),
        )
    }

    @Test
    fun `MainActivity 自己处理尺寸与密度变化，分屏拖动分隔条不重建界面`() {
        val changes = activityTag(".MainActivity")
            .substringAfter("android:configChanges=\"")
            .substringBefore('"')
            .split('|')
            .map { it.trim() }
            .toSet()

        // screenSize / smallestScreenSize / screenLayout：窗口尺寸变化（分屏拖动、进入分屏）
        // density：车机可能按窗口下发不同密度；不自己处理就会重建 Activity，播放页状态虽在
        //         引擎里，但界面闪一下很难看。
        listOf("screenSize", "smallestScreenSize", "screenLayout", "density", "uiMode").forEach {
            assertTrue("android:configChanges 缺少 $it（实际：$changes）", it in changes)
        }

        // orientation 也必须在：车机固定横屏，但手机横置当车机用时不能重建
        assertTrue("android:configChanges 缺少 orientation", "orientation" in changes)
    }

    @Test
    fun `values-v32 只覆盖整数分档，不要把别的 strings 复制一份`() {
        // 复制整份 strings.xml 到 values-v32 会让 API 32+ 的中英文案静默走另一份，
        // 以后改文案只改一份就出现「车机与手机不一致」。这里只允许 integers。
        val dir = sourceFile("src/main/res/values-v32/integers.xml").parentFile
        val names = dir?.listFiles()?.map { it.name }?.sorted().orEmpty()
        assertFalse("values-v32 下不应出现其他资源文件：$names", names.any { it != "integers.xml" })
    }
}
