package com.luckylearning.windtone.data.online.script

import android.content.Context
import android.util.Log
import com.luckylearning.windtone.data.online.OnlineKeys
import com.luckylearning.windtone.data.online.source.OnlineSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LxLoadReport(
    val scripts: Int,
    val sources: List<String>,
    val errors: List<String>,
)

/**
 * 脚本 → 音源的加载总控。
 *
 * 生命周期：应用启动 / 用户导入 / 启停 / 删除时调用 [reload]，整体替换注册表。
 * 不做增量注册——两份脚本声明同一个源 id 时，增量会让「谁生效」取决于调用顺序，
 * 而整体替换的顺序就是清单顺序，UI 上可以按「后面的覆盖前面的」解释清楚。
 */
object LxSourceManager {

    private const val TAG = "LxSources"

    private val runtimes = mutableListOf<LxRuntime>()

    @Volatile
    private var lastReport = LxLoadReport(0, emptyList(), emptyList())

    /** 启动后后台 reload 是否已经跑完（哪怕结果是 0 个源也算跑完） */
    @Volatile
    private var ready = false

    val report: LxLoadReport get() = lastReport

    /** reload 串行锁：启动后台加载与 UI 主动 reload 不能并发，否则互相 destroy runtime */
    private val mutex = Mutex()

    /** 在线页首次进入时用：等启动加载跑完，避免「冷启动立刻点播 → 注册表为空 → 取址失败」 */
    suspend fun awaitReady(context: Context): LxLoadReport {
        if (ready) return lastReport
        return mutex.withLock {
            // 双重检查：等锁期间启动的后台 reload 可能已经跑完
            if (ready) lastReport else doReload(context)
        }
    }

    suspend fun reload(context: Context): LxLoadReport = mutex.withLock { doReload(context) }

    private suspend fun doReload(context: Context): LxLoadReport = withContext(Dispatchers.IO) {
        runtimes.forEach { it.destroy() }
        runtimes.clear()
        // 注册表目前只有脚本源；内置搜索不走注册表（search 由 Kotlin 实现），
        // 所以这里整体清空是安全的。若将来加入 DirectSource，需改为只清脚本源。
        OnlineSourceRegistry.clear()

        val errors = mutableListOf<String>()
        val sources = mutableListOf<String>()
        val scripts = LxScriptStore(context).all().filter { it.enabled }

        for (script in scripts) {
            val runtime = LxRuntime(context.applicationContext, script)
            val result = runtime.load()
            if (!result.ok) {
                errors += "${script.name}: ${result.error ?: "未知错误"}"
                Log.w(TAG, "加载失败 ${script.name}: ${result.error}")
                runtime.destroy()
                continue
            }
            runtimes += runtime
            for (declared in result.sources) {
                if ("musicUrl" !in declared.actions) continue
                if (!OnlineKeys.isValidSourceId(declared.id)) {
                    errors += "${script.name}: 非法源 id ${declared.id}"
                    continue
                }
                OnlineSourceRegistry.register(LxScriptSource(runtime, declared, script.name))
                if (declared.id !in sources) sources += declared.id
            }
            Log.i(TAG, "已加载 ${script.name}：${result.sources.map { it.id }}")
        }

        LxLoadReport(scripts.size, sources, errors).also {
            lastReport = it
            ready = true
        }
    }
}
