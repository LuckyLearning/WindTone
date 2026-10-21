package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 设置域的 DataStore（Preferences 型）封装（v1.1.37）。
 *
 * ## 它在迁移里扮演什么角色
 * 这是「SharedPreferences → DataStore」双写迁移的**新存储侧**，且**只写不读**：
 *  - **写**：`Prefs` 的每个 setter 都同时写 SharedPreferences 与这里（双写），
 *    所以无论调用方走哪条路落盘，新存储永远不落后于旧存储。
 *  - **搬存量**：首次启动时 [backfillFrom] 把 SharedPreferences 的全部键值一次性灌进来。
 *  - **读**：暂不参与。见 [Prefs] 类文档「为什么这一步不切读路径」——
 *    DataStore 的写是异步的，此时切读会破坏「写完立刻回读」的语义。
 *
 * ## 写失败不当回事
 * 所有写都走 [safeEdit]：DataStore 写不进去只记一条日志，不往外抛。
 * 迁移期 SharedPreferences 仍是读的唯一来源，且已由 setter 同步写好，
 * 所以新存储侧的失败对用户完全无感 —— 代价只是下一次 backfill 前它落后一点。
 * （协程被取消是另一回事，必须照常抛出去，否则破坏结构化并发。）
 *
 * ## 键名与 SharedPreferences 完全相同
 * 直接用 `Prefs.KEY_*` 的字符串做 `xxxPreferencesKey`，不另造一套键名 ——
 * 双写与回退都依赖「同一个键在两边是同一个键」。
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "windtone_settings")

class SettingsDataStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.settingsDataStore

    // ---------------------------------------------------------------- 写（异步，内部切 IO）

    suspend fun putBoolean(key: String, value: Boolean) = safeEdit { it[booleanPreferencesKey(key)] = value }

    suspend fun putInt(key: String, value: Int) = safeEdit { it[intPreferencesKey(key)] = value }

    suspend fun putLong(key: String, value: Long) = safeEdit { it[longPreferencesKey(key)] = value }

    suspend fun putFloat(key: String, value: Float) = safeEdit { it[floatPreferencesKey(key)] = value }

    suspend fun putString(key: String, value: String) = safeEdit { it[stringPreferencesKey(key)] = value }

    suspend fun putStringSet(key: String, value: Set<String>) =
        safeEdit { it[stringSetPreferencesKey(key)] = value }

    /**
     * 删除指定键。
     *
     * 六种类型的 `Preferences.Key` 都试一遍：调用方（`Prefs.resetSortOrder`）只拿得到键名，
     * 不知道当初是以什么类型写进去的。`remove` 对不存在的键是 no-op，所以全试不会误伤。
     */
    suspend fun remove(key: String) = safeEdit { prefs ->
        prefs.remove(booleanPreferencesKey(key))
        prefs.remove(intPreferencesKey(key))
        prefs.remove(longPreferencesKey(key))
        prefs.remove(floatPreferencesKey(key))
        prefs.remove(stringPreferencesKey(key))
        prefs.remove(stringSetPreferencesKey(key))
    }

    /**
     * 统一的写入入口：DataStore 的 `edit` + 「失败只记日志」。
     *
     * 注意 `CancellationException` 必须原样抛出去 —— 吞掉它会让协程的取消语义失效。
     */
    private suspend fun safeEdit(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit { prefs ->
                block(prefs)
                prefs
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "写入设置失败（不影响使用：读仍走 SharedPreferences）", t)
        }
    }

    // ---------------------------------------------------------------- 迁移

    /**
     * 一次性把 SharedPreferences 的存量搬进 DataStore。
     *
     * 做法是**按运行时类型通盘搬**（遍历 `sp.all`），而不是列一张键名清单：
     *  - 排序键是动态的（`sort_<视图名>`），列清单必然漏；
     *  - 日后新增设置不用回来补这段代码；
     *  - 老用户遗留的三个逐字歌词死键也会被一起搬过来 —— 无害，且将来切读时反正要能读到它们。
     *
     * ## 读存量与写 DataStore 必须在同一个 edit 事务里
     * `sp.all` 的读取**不能**提到 `dataStore.edit` 之外。否则会出现这种交错：
     * 搬存量读到旧值 → 用户改设置（SharedPreferences 立刻是新值）→ 双写把新值送进 DataStore
     * → 搬存量的 edit 随后落盘，把刚改的新值**覆盖回旧值**（用户设置凭空倒退）。
     * 放进同一个事务后，读到的 SharedPreferences 状态与写进去的值永远来自同一时刻，
     * 而 setter 的 SharedPreferences 写是同步的、必早于它的 DataStore 写被派发，
     * 所以任何交错下 DataStore 最终都收敛到最新值。
     *
     * 幂等：搬完在同一个事务里落一个 [KEY_MIGRATED] 标记，重复调用直接返回。
     * 双写保证此后两边一致，所以即使标记丢失重搬一次，灌进去的也是同样的值。
     */
    suspend fun backfillFrom(sp: SharedPreferences) {
        if (dataStore.data.first()[KEY_MIGRATED] == true) return
        safeEdit { prefs ->
            sp.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> prefs[booleanPreferencesKey(k)] = v
                    is Int -> prefs[intPreferencesKey(k)] = v
                    is Long -> prefs[longPreferencesKey(k)] = v
                    is Float -> prefs[floatPreferencesKey(k)] = v
                    is String -> prefs[stringPreferencesKey(k)] = v
                    is Set<*> -> prefs[stringSetPreferencesKey(k)] = v.filterIsInstance<String>().toSet()
                    // null 与其它类型：SharedPreferences 里不该有，跳过
                }
            }
            prefs[KEY_MIGRATED] = true
        }
    }

    /**
     * 预热：搬存量。目前只做这一件事（读路径尚未切换，无需装快照）。
     *
     * 名字留着是为了让调用点（`Prefs.init`）表达意图，也为第二步切读时留好位置。
     */
    suspend fun warmUp(sp: SharedPreferences) {
        backfillFrom(sp)
    }

    /** 供测试：直接拿到底层 DataStore 的数据流 */
    internal fun flowForTest(): Flow<Preferences> = dataStore.data

    /**
     * 供测试：清空（测试之间隔离用；生产永不调用）。
     *
     * 故意不走 [safeEdit]：测试辅助手段要失败得响亮，悄悄吞掉只会让后面的断言莫名其妙地红。
     */
    internal suspend fun clearForTest() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        const val TAG = "SettingsDataStore"

        /** 「存量已搬完」标记；故意用一个 SharedPreferences 里不存在的键名，避免撞车 */
        val KEY_MIGRATED = booleanPreferencesKey("__ds_migrated_v1")
    }
}
