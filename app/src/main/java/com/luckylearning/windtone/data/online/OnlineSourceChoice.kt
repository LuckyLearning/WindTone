package com.luckylearning.windtone.data.online

import android.content.Context
import com.luckylearning.windtone.data.Prefs
import org.json.JSONObject

/**
 * 「这个平台用哪份音源脚本」的持久化（平台 id → 脚本 `ownerId`，JSON 存在 [Prefs]）。
 *
 * 为什么不进 [com.luckylearning.windtone.data.online.script.LxScriptStore]：
 * 脚本清单是「用户导入的东西」，这里是「用户做的选择」。两者生命周期不同 ——
 * 删掉一份脚本时清单要变，但选择记录不必跟着重写（找不到 owner 时注册表自动回落到清单顺序）。
 *
 * 空 JSON / 缺项 = 自动（按脚本清单顺序）。这也是「恢复默认」要写回的值。
 */
object OnlineSourceChoice {

    fun load(context: Context): Map<String, String> {
        val raw = Prefs(context).onlineSourceChoice
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) {
                    val owner = obj.optString(key)
                    if (owner.isNotBlank()) put(key, owner)
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** 选定某个平台的脚本；[ownerId] 为 null = 恢复自动 */
    fun set(context: Context, platform: String, ownerId: String?) {
        val next = LinkedHashMap(load(context))
        if (ownerId.isNullOrBlank()) next.remove(platform) else next[platform] = ownerId
        if (next.isEmpty()) {
            Prefs(context).onlineSourceChoice = ""
            return
        }
        val obj = JSONObject()
        next.forEach { (key, owner) -> obj.put(key, owner) }
        Prefs(context).onlineSourceChoice = obj.toString()
    }

    fun clear(context: Context) {
        Prefs(context).onlineSourceChoice = ""
    }

    /** 整体替换（清理失效项时用，避免逐条写盘） */
    fun replace(context: Context, choice: Map<String, String>) {
        if (choice.isEmpty()) {
            clear(context)
            return
        }
        val obj = JSONObject()
        choice.forEach { (platform, owner) -> obj.put(platform, owner) }
        Prefs(context).onlineSourceChoice = obj.toString()
    }
}
