package com.luckylearning.windtone.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 桌面长按快捷方式的契约回归（v1.1.12 修「点击提示未安装应用」后新增）。
 *
 * ## 为什么值得单独守一条
 *
 * 快捷方式是"几处字符串必须逐字一致 + 声明写错不报错"的典型：出错时应用
 * **能装、能编译、能启动**，只有用户长按图标点一下才知道坏了 —— 而且坏法还挺反直觉
 * （提示「未安装应用」，看着像包没装上）。
 *
 * 实证（2026-10-17 用户真机上报）：`res/xml/shortcuts.xml` 里三个 `<intent>` 只写了
 * `android:action` + `android:targetClass`、**没写 `android:targetPackage`**，于是三个
 * 入口点击**全部**提示「未安装应用」（v1.1.3 ~ v1.1.11 一直如此）。实测规则：
 *   · 只写 `targetClass` 不写 `targetPackage` → **所有 ROM 都失败**；
 *   · 两个都写 → 包名被写死，而 debug 的 applicationId 带 `.debug` 后缀，
 *     debug 包上会去拉正式版（能拉起来更糟：静默打开了另一个应用）；
 *   · 都**不写** + 在 `MainActivity` 上声明一条 action 相同、category 为 DEFAULT 的
 *     `intent-filter` → 走隐式解析，不写死包名，debug / release 都对。← 本版采用这条。
 *
 * 所以本测试盯三件事，缺一不可：
 *  1. `shortcuts.xml` 的 `shortcutId` / action / extra 与 [AppShortcut] 常量逐字一致，
 *     且**没有**任何 `targetClass` / `targetPackage`；
 *  2. `AndroidManifest.xml` 的 `MainActivity` 上确实有那条 `intent-filter`（含 `CATEGORY_DEFAULT`）；
 *  3. 第 2 条**由系统实查**：把 action 交给 Robolectric 的 PackageManager 解析 ——
 *     读到文本里有这行字不算数，能被解析到才算数。
 */
@RunWith(RobolectricTestRunner::class)
class AppShortcutContractTest {

    /** 单测的工作目录是模块根（`app/`），但兼容从仓库根跑的情况（与 AppIconsPathTest 同款） */
    private fun sourceFile(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("找不到 $relative（当前目录 ${File(".").absolutePath}）")

    private fun shortcutsXml(): String = sourceFile("src/main/res/xml/shortcuts.xml").readText()

    private fun manifest(): String = sourceFile("src/main/AndroidManifest.xml").readText()

    private val shortcutBlock = Regex("""<shortcut\b[\s\S]*?</shortcut>""")
    private val shortcutId = Regex("""android:shortcutId="([^"]+)"""")
    private val intentAction = Regex("""<intent\s+android:action="([^"]+)"""")
    private val intentExtra = Regex(
        """<extra\s+android:name="([^"]+)"\s+android:value="([^"]+)"\s*/>""",
    )

    /**
     * shortcutId 的字面量 → 它应该对应的 [AppShortcut] 常量。
     *
     * 之所以把字面量写死在这里：这三个名字同时出现在 shortcuts.xml 的 `shortcutId`、
     * extra 取值、`AppShortcut` 常量三处，本测试就是用来钉住"三处同名"的。
     * 改动其中任意一处（包括重命名常量）都会立刻失败。
     */
    private val expectedByShortcutId = mapOf(
        "play_all" to AppShortcut.PLAY_ALL,
        "shuffle" to AppShortcut.SHUFFLE,
        "favorites" to AppShortcut.FAVORITES,
    )

    @Test
    fun shortcutsXmlMatchesConstants() {
        val blocks = shortcutBlock.findAll(shortcutsXml()).map { it.value }.toList()

        // 正则失效时必须响，不能静默变成空测试
        assertEquals(
            "没从 shortcuts.xml 里扫到 3 个 <shortcut> —— 正则已失效或快捷方式被删，请更新本测试",
            3,
            blocks.size,
        )

        val problems = mutableListOf<String>()
        for (block in blocks) {
            val id = shortcutId.find(block)?.groupValues?.get(1)
            val action = intentAction.find(block)?.groupValues?.get(1)
            // groupValues = [整段, 组1, 组2]
            val extra = intentExtra.find(block)?.groupValues?.let { it[1] to it[2] }
            val name = id ?: "（读不到 shortcutId）"

            val expectedAction = expectedByShortcutId[id]
            if (expectedAction == null) {
                problems += "$name：shortcutId 不是已知的三个入口之一"
            }
            if (action != AppShortcut.ACTION) {
                problems += "$name：action=$action，应为 ${AppShortcut.ACTION}（与 AppShortcut.ACTION 不一致）"
            }
            when {
                extra == null ->
                    problems += """$name：读不到 <extra android:name="${AppShortcut.EXTRA}" .../>"""
                extra.first != AppShortcut.EXTRA ->
                    problems += "$name：extra 键是 ${extra.first}，应为 ${AppShortcut.EXTRA}"
                extra.second != id ->
                    problems += "$name：extra 取值是 ${extra.second}，与 shortcutId 不一致（必须是同一个名字）"
                expectedAction != null && extra.second != expectedAction ->
                    problems += "$name：extra 取值 ${extra.second}，应为 $expectedAction（AppShortcut 常量）"
            }
            if (block.contains("android:targetClass") || block.contains("android:targetPackage")) {
                problems += "$name：<intent> 里不该出现 targetClass / targetPackage" +
                    "（只写 targetClass → 所有 ROM 点击都提示「未安装应用」；两个都写 → debug 包会拉到正式版）"
            }
        }
        assertTrue("shortcuts.xml 与 AppShortcut 不一致：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun mainActivityDeclaresShortcutActionWithDefaultCategory() {
        val block = mainActivityBlock()
        assertTrue(
            "MainActivity 的 android:exported 必须是 true —— 启动器是另一个进程，非导出就起不来",
            block.contains("""android:exported="true""""),
        )

        val filters = Regex("""<intent-filter>[\s\S]*?</intent-filter>""").findAll(block).map { it.value }.toList()
        val matched = filters.filter { it.contains("""android:name="${AppShortcut.ACTION}"""") }
        assertTrue(
            "MainActivity 上没有声明 action=${AppShortcut.ACTION} 的 <intent-filter>：" +
                "shortcuts.xml 里那三个 <intent> 只有 action、没有组件，缺了这条 filter 就会提示「未安装应用」",
            matched.isNotEmpty(),
        )
        assertTrue(
            "声明了 action 却没有 CATEGORY_DEFAULT —— 隐式意图（startActivity）解析不到，等于没写",
            matched.any { it.contains("android.intent.category.DEFAULT") },
        )
    }

    /** 真·实查：把 action 交给系统的 PackageManager，看能不能解析到本应用的 Activity */
    @Test
    fun shortcutActionResolvesToAnActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(AppShortcut.ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(
            "PackageManager 解析不了 action=${AppShortcut.ACTION}：长按图标点快捷方式就会" +
                "提示「未安装应用」（解析到 ${resolved.size} 个 Activity）",
            resolved.isNotEmpty(),
        )
    }

    @Test
    fun fromIntentOnlyAcceptsTheShortcutChannel() {
        assertNull("不是本通道的 action 必须返回 null", AppShortcut.from(Intent(Intent.ACTION_MAIN)))
        assertNull("没有 extra 必须返回 null", AppShortcut.from(Intent(AppShortcut.ACTION)))
        assertNull(
            "extra 不在合法动作里必须返回 null（否则会当成一次空动作被消费掉）",
            AppShortcut.from(Intent(AppShortcut.ACTION).putExtra(AppShortcut.EXTRA, "not_a_real_action")),
        )
        assertEquals(
            AppShortcut.FAVORITES,
            AppShortcut.from(Intent(AppShortcut.ACTION).putExtra(AppShortcut.EXTRA, AppShortcut.FAVORITES)),
        )

        // consumePending 取走即清空：同一次点击只能执行一次
        AppShortcut.put(AppShortcut.SHUFFLE)
        assertEquals(AppShortcut.SHUFFLE, AppShortcut.consumePending())
        assertNull("取走后必须为空（否则同一个动作会被执行两次）", AppShortcut.consumePending())
    }

    /** MainActivity 那一整段（从它自己的 `<activity` 到 `</activity>`） */
    private fun mainActivityBlock(): String {
        val text = manifest()
        val nameIdx = text.indexOf("""android:name=".MainActivity"""")
        check(nameIdx >= 0) { """AndroidManifest.xml 里找不到 android:name=".MainActivity"，测试需要更新""" }
        val start = text.lastIndexOf("<activity", nameIdx)
        val end = text.indexOf("</activity>", nameIdx)
        check(start >= 0 && end > nameIdx) { "读不出 MainActivity 的 <activity> 段落" }
        return text.substring(start, end)
    }
}
