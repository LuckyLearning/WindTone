package com.luckylearning.windtone.data.online.source

import android.util.Log

/**
 * 已加载音源的进程内注册表。
 *
 * 为什么是一个 `object`：注册在应用启动时由「音源管理」统一完成，消费方分散在
 * 搜索页、播放服务、车机浏览树三处（后者在 MediaSession 回调里，拿不到任何 UI 作用域）。
 * 传一个实例下去意味着每处都要多一个构造参数，而它本来就只有一份。
 *
 * ## 一个平台可以有多个实现（v1.2.62）
 *
 * 用户导入两份都声明 `kw` 的脚本时，**不是后者覆盖前者**，而是两份都留在表里：
 *  - 取址用的第一份由 [preferred]（用户手选）决定，没选就按脚本清单顺序；
 *  - 第一份取不到时，[candidates] 里后面的实现还能兜底 —— 这是「同一首歌换个人要」
 *    唯一不花钱的做法，因为内置搜索产出的 `raw` 是通用的，同平台的任何脚本都认。
 *
 * 线程约定：读多写极少（只在导入 / 启停 / 删除 / 切换音源时变），整体加锁替换。
 */
object OnlineSourceRegistry {

    private const val TAG = "OnlineSources"

    /** 平台 id → 该平台的实现列表（越靠前越优先） */
    @Volatile
    private var map: Map<String, List<MusicSource>> = emptyMap()

    /**
     * 用户手选的「这个平台用哪份脚本」（平台 id → `ownerId`）。
     *
     * 与 [map] 分开存：`map` 每次重新加载都会重建，而用户的选择属于设置，
     * 必须在重载后仍然生效（否则「换了个音源，重启又变回去」）。
     */
    @Volatile
    private var preferred: Map<String, String> = emptyMap()

    /** 按 id 追加注册（同 owner + 同平台覆盖：重新导入同一份脚本时就是覆盖语义） */
    @Synchronized
    fun register(source: MusicSource) {
        if (source.id.isBlank()) {
            Log.w(TAG, "忽略 id 为空的音源：${source.name}")
            return
        }
        val kept = map[source.id].orEmpty().filter { it.ownerId != source.ownerId }
        map = LinkedHashMap(map).apply { put(source.id, kept + source) }
    }

    @Synchronized
    fun unregister(id: String) {
        if (!map.containsKey(id)) return
        map = LinkedHashMap(map).apply { remove(id) }
    }

    @Synchronized
    fun clear() {
        map = emptyMap()
        // preferred 不清：它对应「用户设置」，重载脚本不该把选择一起丢掉
    }

    /** 该平台首选的实现（用户指定 > 清单顺序）；没有实现时 null */
    fun find(id: String?): MusicSource? = candidates(id).firstOrNull()

    /**
     * 该平台的全部实现，**按优先级排序**：用户手选的排第一，其余保持脚本顺序。
     *
     * 取址失败换下一个脚本就靠这个顺序；UI 的「音源选择」也读它。
     */
    fun candidates(id: String?): List<MusicSource> {
        val key = id ?: return emptyList()
        val list = map[key] ?: return emptyList()
        if (list.size < 2) return list
        val chosen = preferred[key]
            ?.let { owner -> list.firstOrNull { it.ownerId == owner } }
            ?: return list
        return listOf(chosen) + list.filter { it !== chosen }
    }

    /** 某个平台的全部实现（不排序，供音源管理展示候选） */
    fun sourcesFor(id: String): List<MusicSource> = map[id].orEmpty()

    /** 用户手选的 ownerId；null = 自动（按清单顺序） */
    fun preferredFor(id: String): String? = preferred[id]

    /** 已被脚本接管的平台 id（界面按此顺序展示） */
    val platformIds: List<String> get() = map.keys.toList()

    @Synchronized
    fun setPreferred(choice: Map<String, String>) {
        preferred = choice.filterValues { it.isNotBlank() }
    }

    /** 全部已加载实现（一个平台可能有多条），保持注册顺序 */
    val all: List<MusicSource> get() = map.values.flatten()

    val isEmpty: Boolean get() = map.isEmpty()

    fun ids(): List<String> = map.keys.toList()
}
