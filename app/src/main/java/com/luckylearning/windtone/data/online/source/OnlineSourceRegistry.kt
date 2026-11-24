package com.luckylearning.windtone.data.online.source

import android.util.Log

/**
 * 已加载音源的进程内注册表。
 *
 * 为什么是一个 `object`：注册在应用启动时由「音源管理」统一完成，消费方分散在
 * 搜索页、播放服务、车机浏览树三处（后者在 MediaSession 回调里，拿不到任何 UI 作用域）。
 * 传一个实例下去意味着每处都要多一个构造参数，而它本来就只有一份。
 *
 * 线程约定：读多写极少（只在导入 / 启停 / 删除音源时变），整体加锁替换。
 * 不做「按 id 分别加锁」这种精细活 —— 一天改不到两次的东西，简单正确优先。
 */
object OnlineSourceRegistry {

    private const val TAG = "OnlineSources"

    @Volatile
    private var map: Map<String, MusicSource> = emptyMap()

    /** 按 id 注册（同 id 覆盖：重新导入同一份脚本时就是覆盖语义） */
    @Synchronized
    fun register(source: MusicSource) {
        if (source.id.isBlank()) {
            Log.w(TAG, "忽略 id 为空的音源：${source.name}")
            return
        }
        map = LinkedHashMap(map).apply { put(source.id, source) }
    }

    @Synchronized
    fun unregister(id: String) {
        if (!map.containsKey(id)) return
        map = LinkedHashMap(map).apply { remove(id) }
    }

    @Synchronized
    fun clear() {
        map = emptyMap()
    }

    fun find(id: String?): MusicSource? = id?.let { map[it] }

    /** 全部已加载音源，保持注册顺序（界面按此顺序展示） */
    val all: List<MusicSource> get() = map.values.toList()

    val isEmpty: Boolean get() = map.isEmpty()

    fun ids(): List<String> = map.keys.toList()
}
