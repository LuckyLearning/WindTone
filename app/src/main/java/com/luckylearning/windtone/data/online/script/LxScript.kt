package com.luckylearning.windtone.data.online.script

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一份用户导入的洛雪 v2 自定义源脚本。
 *
 * [id] 一律是 `custom:<uuid>`：洛雪内置源的 id（kw/wy/kg/tx/mg）被内置搜索实现占用，
 * 自定义源若用同名会把两套实现混在一个 stableKey 命名空间里，取直链时取到错误的源。
 */
data class LxScript(
    val id: String,
    val name: String,
    val code: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val homepage: String = "",
    val addedAt: Long = 0L,
    val enabled: Boolean = true,
) {
    /** 脚本头部注释里的 @name，导入时用；拿不到就退回文件名 */
    companion object {
        fun parseMeta(code: String): Pair<String, String> {
            val name = Regex("""@name\s+([^\r\n*]+)""").find(code)?.groupValues?.get(1)?.trim()
            val version = Regex("""@version\s+([^\r\n*]+)""").find(code)?.groupValues?.get(1)?.trim()
            return (name?.take(80) ?: "") to (version?.take(40) ?: "")
        }
    }
}

/**
 * 脚本清单：JSON 存档，与既有 `OnlinePlaylistStore` 同一取舍。
 *
 * 为什么不进 Room：脚本全文平均 600KB，进 SQLite 会让每次曲库查询都拖着这一列；
 * 而它需要事务的场景只有一个「整体替换」。临时文件 + rename 已经是原子写。
 */
class LxScriptStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "lx_scripts.json")

    @Synchronized
    fun all(): List<LxScript> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LxScript(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    code = o.optString("code"),
                    version = o.optString("version"),
                    author = o.optString("author"),
                    description = o.optString("description"),
                    homepage = o.optString("homepage"),
                    addedAt = o.optLong("addedAt"),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(list: List<LxScript>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("code", s.code)
                put("version", s.version); put("author", s.author)
                put("description", s.description); put("homepage", s.homepage)
                put("addedAt", s.addedAt); put("enabled", s.enabled)
            })
        }
        val tmp = File(file.parentFile, "lx_scripts.json.tmp")
        tmp.writeText(arr.toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    @Synchronized
    fun upsert(script: LxScript) {
        val list = all().filter { it.id != script.id }.toMutableList()
        list += script
        save(list)
    }

    @Synchronized
    fun remove(id: String) = save(all().filter { it.id != id })

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) =
        save(all().map { if (it.id == id) it.copy(enabled = enabled) else it })

    @Synchronized
    fun clear() = save(emptyList())
}
