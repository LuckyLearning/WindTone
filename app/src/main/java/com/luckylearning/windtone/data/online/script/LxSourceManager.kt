package com.luckylearning.windtone.data.online.script

import android.content.Context
import android.util.Log
import com.luckylearning.windtone.data.online.OnlineKeys
import com.luckylearning.windtone.data.online.OnlineSourceChoice
import com.luckylearning.windtone.data.online.OnlineUrlMemo
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

    /**
     * 切换「某个平台用哪份脚本」。
     *
     * 只改注册表的优先顺序，**不重载脚本** —— 所有实现本来就在表里，切一下就是换个
     * 遍历起点的事。备忘录要清：里面存的是老脚本换来的直链与其档位标注，
     * 留着会让信息页继续显示上一个源的音质。
     */
    suspend fun chooseSource(context: Context, platform: String, ownerId: String?) {
        OnlineSourceChoice.set(context.applicationContext, platform, ownerId)
        OnlineSourceRegistry.setPreferred(OnlineSourceChoice.load(context.applicationContext))
        OnlineUrlMemo.clear()
    }

    private suspend fun doReload(context: Context): LxLoadReport = withContext(Dispatchers.IO) {
        runtimes.forEach { it.destroy() }
        runtimes.clear()
        // 注册表目前只有脚本源；内置搜索不走注册表（search 由 Kotlin 实现），
        // 所以这里整体清空是安全的。若将来加入 DirectSource，需改为只清脚本源。
        OnlineSourceRegistry.clear()
        // 用户手选的「平台 → 脚本」属于设置，重载后必须继续生效
        OnlineSourceRegistry.setPreferred(OnlineSourceChoice.load(context.applicationContext))

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
                // 同平台多脚本：都注册，按清单顺序排优先级（见 OnlineSourceRegistry.candidates）
                OnlineSourceRegistry.register(
                    LxScriptSource(runtime, declared, script.id, script.name),
                )
                if (declared.id !in sources) sources += declared.id
            }
            Log.i(TAG, "已加载 ${script.name}：${result.sources.map { it.id }}")
        }

        pruneChoice(context)

        LxLoadReport(scripts.size, sources, errors).also {
            lastReport = it
            ready = true
        }
    }

    /**
     * 清掉选择记录里指向「已经不存在 / 已停用」的脚本。
     *
     * 不做会怎样：用户删掉一份脚本后，那条 `{kw: custom:旧id}` 永远留在设置里。
     * 注册表其实会自动回落到清单顺序（找不到 owner），但音源管理里仍会显示成
     * 「自动」，而设置里留着一笔指向空气的记录 —— 下次再装同名脚本时又神秘生效。
     */
    private fun pruneChoice(context: Context) {
        val saved = OnlineSourceChoice.load(context)
        if (saved.isEmpty()) return
        val alive = saved.filter { (platform, owner) ->
            OnlineSourceRegistry.sourcesFor(platform).any { it.ownerId == owner }
        }
        if (alive.size == saved.size) return
        OnlineSourceChoice.replace(context.applicationContext, alive)
        OnlineSourceRegistry.setPreferred(alive)
    }
}
