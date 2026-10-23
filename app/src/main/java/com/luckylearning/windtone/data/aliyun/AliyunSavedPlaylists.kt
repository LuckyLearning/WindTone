package com.luckylearning.windtone.data.aliyun

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 云盘扫描歌单的本地存档。
 *
 * 与「在线歌单」（OnlinePlaylists）同一形态：SP + JSON 的轻量存档，**不入 Room**——
 * 存的是「几份文件引用」而不是曲库实体，规模是个位数到两位数。
 *
 * 关键设计：歌单里存的是 **fileId 引用**，不是直链 —— 阿里的下载直链只有 4 小时
 * 有效期，存直链的歌单过夜就全失效。播放时按 fileId 现取直链（走 AliyunDrive.downloadUrl），
 * 稳定缓存键 `wt=<fileId>` 照旧命中，听过的歌二刷秒出。
 */
data class AliyunPlaylist(
    val id: String,
    val name: String,
    /** 扫描时用的盘（fileId 只在自己的盘里有意义，播放时必须回同一个盘取直链） */
    val driveId: String,
    /** 扫描根目录的展示名（如「周杰伦」），界面上标注来源 */
    val rootName: String,
    val files: List<AliyunScanRules.ScanHit>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val fileCount: Int get() = files.size
}

/** SP + JSON 存档（仿 OnlinePlaylistStore 的写法） */
internal class AliyunPlaylistStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("aliyun_playlists", Context.MODE_PRIVATE)

    fun all(): List<AliyunPlaylist> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { read(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun replace(list: List<AliyunPlaylist>) {
        val arr = JSONArray()
        list.forEach { arr.put(write(it)) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private fun write(p: AliyunPlaylist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("drive_id", p.driveId)
        put("root_name", p.rootName)
        put("created_at", p.createdAt)
        put("updated_at", p.updatedAt)
        val files = JSONArray()
        p.files.forEach { f ->
            files.put(
                JSONObject()
                    .put("file_id", f.fileId)
                    .put("name", f.name)
                    .put("parent_path", f.parentPath),
            )
        }
        put("files", files)
    }

    private fun read(o: JSONObject): AliyunPlaylist {
        val filesArr = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArr.length()).mapNotNull { i ->
            val f = filesArr.optJSONObject(i) ?: return@mapNotNull null
            val fileId = f.optString("file_id")
            if (fileId.isBlank()) return@mapNotNull null
            AliyunScanRules.ScanHit(
                fileId = fileId,
                name = f.optString("name"),
                parentPath = f.optString("parent_path"),
            )
        }
        return AliyunPlaylist(
            id = o.optString("id"),
            name = o.optString("name"),
            driveId = o.optString("drive_id"),
            rootName = o.optString("root_name"),
            files = files,
            createdAt = o.optLong("created_at", 0L),
            updatedAt = o.optLong("updated_at", 0L),
        )
    }

    private companion object {
        const val KEY = "playlists"
    }
}

/** 云盘歌单的存取门面（只读；增删改名在这里，播放时的直链解析交给调用方） */
class AliyunSavedPlaylists(context: Context) {

    private val store = AliyunPlaylistStore(context)

    fun all(): List<AliyunPlaylist> = store.all().sortedByDescending { it.updatedAt }

    fun find(id: String): AliyunPlaylist? = store.all().firstOrNull { it.id == id }

    /** 新增（同名不拦：用户有权存两份同名扫描结果） */
    fun add(name: String, driveId: String, rootName: String, files: List<AliyunScanRules.ScanHit>): AliyunPlaylist {
        val now = System.currentTimeMillis()
        val playlist = AliyunPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { rootName.ifBlank { "云盘歌单" } },
            driveId = driveId,
            rootName = rootName,
            files = files,
            createdAt = now,
            updatedAt = now,
        )
        store.replace(store.all() + playlist)
        return playlist
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        store.replace(
            store.all().map {
                if (it.id == id) it.copy(name = trimmed, updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    fun remove(id: String) {
        store.replace(store.all().filterNot { it.id == id })
    }
}
