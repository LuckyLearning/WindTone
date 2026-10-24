package com.luckylearning.windtone.ui

import android.content.Context

/** 设置主页分类卡的默认顺序与「新页面插回默认位置」的合并。 */
internal object SettingsMenuOrder {

    /**
     * 主页分类卡默认顺序（v1.1.80 重排）。
     *
     * 这一版把「悬浮歌词」「更新」「在线播放」三张卡降级成上层页面里的入口行，
     * 「调整菜单顺序」收进实验室 —— 主页回到 **6 张卡、一屏放下**。
     * 老用户存档里那三个已不是分类的 id 会在 [merge] 里被过滤掉。
     */
    val DEFAULT = listOf(
        "APPEARANCE",
        "PLAYBACK",
        "LYRICS",
        "LIBRARY",
        "DIAGNOSTICS",
        "LAB",
    )

    fun merge(saved: List<String>, defaultIds: List<String> = DEFAULT): List<String> {
        val known = defaultIds.toSet()
        val out = saved.filter { it in known }.distinct().toMutableList()
        for (id in defaultIds) {
            if (id in out) continue
            val later = defaultIds.drop(defaultIds.indexOf(id) + 1).firstOrNull { it in out }
            val at = if (later == null) out.size else out.indexOf(later)
            out.add(at, id)
        }
        return out
    }

    fun pages(saved: List<String>): List<SettingsPage> =
        merge(saved).mapNotNull { name -> SettingsPage.entries.firstOrNull { it.name == name } }
}

internal class SettingsMenuStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings_menu", Context.MODE_PRIVATE)

    fun load(): List<String> = SettingsMenuOrder.merge(
        sp.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
    )

    fun save(ids: List<String>) {
        sp.edit().putString(KEY, SettingsMenuOrder.merge(ids).joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "order"
    }
}
