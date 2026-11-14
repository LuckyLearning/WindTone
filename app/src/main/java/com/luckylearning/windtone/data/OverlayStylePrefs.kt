package com.luckylearning.windtone.data

import android.content.SharedPreferences

/**
 * 让【悬浮窗外观】的几个键自动按主题分流到白天 / 夜间两套的 [SharedPreferences] 包装（v1.2.52）。
 *
 * ## 为什么要包一层，而不是把 90 多处 getter / setter 逐个改成双键
 * `Prefs` 里悬浮窗外观有 27 个属性、近百处 `sp.getXxx(KEY, ...)` 调用，其中还夹着
 * 三段「新键没写过就读旧档位换算」的迁移逻辑（字号 / 标题比例 / 底板不透明度）。
 * 逐个改键名意味着：任何一处漏改，那个设置就变成「两套里只有一套生效」，
 * 而这种 bug 只在切到夜间时才显形 —— 真机上极难发现。迁移逻辑若写错，
 * 还会把老用户的字号读成默认值。
 *
 * 包一层则只有一条规则：**读写发生在白天走原键，发生在夜间走 `d_` 前缀**。
 * 上层代码完全不知道有两套，`Prefs` 的公开属性、调用方、设置页、悬浮窗服务一行不动。
 *
 * ## 逐键继承
 * 夜间读过 `d_` 那个键就用它，没写过就**回落读白天那套**（见各 `getXxx`）。
 * 这是「只想在夜里改一个字」能成立的前提 —— 用户不必把 27 项重配一遍。
 * `remove(d_xxx)` 即「这项回到跟随白天」，比「复制一份再改回去」直观得多。
 *
 * ## 边界
 *  - **只有** `Prefs.STYLE_KEYS` 里的键会分流，其余（主题模式本身、播放、库、开关类）
 *    一律原样透传 —— 否则判「该用哪套」时要读 `theme_mode`，就会自己调自己。
 *  - `all` 透传原始表的全部键值（含 `d_` 前缀），预热 / 存量搬运要的就是这个全集。
 *  - 监听器原样透传：本类只改键名解析，不改变「谁在监听」。
 *  - ⚠️ **DataStore 镜像（`SettingsDataStore`）不在本包装内**：那批写是异步派发的，
 *    执行时主题可能已经变了，套用同一份映射反而会写错套。它现在**只写不读**，
 *    键名不分流不影响任何可见行为；等真正切读路径时，必须在 setter 里
 *    用 `OverlayStyleScope.key(base, 当前是否夜间)` 落明确键名。
 */
internal class OverlayStylePrefs(
    private val base: SharedPreferences,
    private val styleKeys: Set<String>,
    private val isDark: () -> Boolean,
) : SharedPreferences {

    /** 白天返回 `null`（原键直读）；夜间返回 `d_` 键名 */
    private fun nightKey(key: String): String? =
        if (key in styleKeys && isDark()) OverlayStyleScope.key(key, true) else null

    /** 夜间读过自己的值就用，否则回落白天的值 */
    private inline fun <T> scoped(key: String, read: (String) -> T): T {
        val night = nightKey(key) ?: return read(key)
        return if (base.contains(night)) read(night) else read(key)
    }

    override fun getAll(): MutableMap<String, *> = base.all

    override fun getString(key: String, defValue: String?): String? =
        scoped(key) { base.getString(it, defValue) }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        scoped(key) { base.getStringSet(it, defValues) }

    override fun getInt(key: String, defValue: Int): Int = scoped(key) { base.getInt(it, defValue) }

    override fun getLong(key: String, defValue: Long): Long = scoped(key) { base.getLong(it, defValue) }

    override fun getFloat(key: String, defValue: Float): Float = scoped(key) { base.getFloat(it, defValue) }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        scoped(key) { base.getBoolean(it, defValue) }

    /**
     * 「这套里写过吗」= 夜间自有值 ∪ 白天值。
     *
     * `Prefs` 里几段迁移逻辑用它判断「新键是否写过」，语义正好落在这里：
     * 夜间没单独写过，但白天写过 → 迁移结果就该与白天一致，而不是回到出厂值。
     */
    override fun contains(key: String): Boolean {
        val night = nightKey(key) ?: return base.contains(key)
        return base.contains(night) || base.contains(key)
    }

    override fun edit(): SharedPreferences.Editor =
        OverlayStyleEditor(base.edit(), styleKeys, isDark)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = base.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = base.unregisterOnSharedPreferenceChangeListener(listener)

    /**
     * 写编辑器：`putXxx` 时就把键名解析定死。
     *
     * 为什么在 `put` 里解析而不是在 `apply` 里统一解析：调用方可以连着 `put` 好几个键
     * 再 `apply`，中间若发生主题切换（极罕见但可能），统一解析会让前半截写到另一套去。
     * 每个 `put` 各自定名，语义就是「这一下点击写进点下去时的那一套」。
     */
    private class OverlayStyleEditor(
        private val base: SharedPreferences.Editor,
        private val styleKeys: Set<String>,
        private val isDark: () -> Boolean,
    ) : SharedPreferences.Editor {

        private fun target(key: String): String =
            if (key in styleKeys) OverlayStyleScope.key(key, isDark()) else key

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { base.putString(target(key), value) }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { base.putStringSet(target(key), values) }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { base.putInt(target(key), value) }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { base.putLong(target(key), value) }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { base.putFloat(target(key), value) }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { base.putBoolean(target(key), value) }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { base.remove(target(key)) }

        override fun clear(): SharedPreferences.Editor = apply { base.clear() }

        override fun commit(): Boolean = base.commit()

        override fun apply() {
            base.apply()
        }
    }
}
