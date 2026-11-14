package com.anylyric.util

import android.content.Context

/**
 * 包名 → 应用名，带缓存。
 *
 * 设置页的「按应用隐藏」「播放器频道」每秒都会被刷新读到，不该每次都问
 * PackageManager；悬浮窗在「锁定的播放器没在放」时也要显示应用名。
 * 包数量有限，缓存不做淘汰。
 */
object AppLabels {

    private val cache = HashMap<String, String>()

    fun of(context: Context, pkg: String): String {
        if (pkg.isBlank()) return pkg
        cache[pkg]?.let { return it }
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        cache[pkg] = label
        return label
    }
}
