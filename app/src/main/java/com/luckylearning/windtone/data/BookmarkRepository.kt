package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 长曲播放书签一条记录（H3）。
 *
 * 不用 Room：MVP 跟近期发版口径，**避免**为新表升 schema 触发 destructive 清库。
 * 键按曲目 [Song.stableKey]（uri / 云盘 cacheKey），与收藏索引一致。
 */
data class PlaybackBookmark(
    val id: String,
    val trackKey: String,
    val positionMs: Long,
    val note: String = "",
    val createdAt: Long,
)

/**
 * 长曲播放书签仓库（Prefs JSON）。
 *
 * 存储：`bookmarks_json` → `{"<trackKey>":[{"id","positionMs","note","createdAt"}, ...]}`
 * 一首歌可挂多条；列表按 positionMs 升序。
 */
class BookmarkRepository(
    private val prefs: SharedPreferences,
    private val messages: MessageBus,
) {
    constructor(context: Context, messages: MessageBus) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        messages,
    )

    suspend fun listForTrack(trackKey: String): List<PlaybackBookmark> = withContext(Dispatchers.IO) {
        load()[trackKey].orEmpty().sortedWith(compareBy({ it.positionMs }, { it.createdAt }))
    }

    suspend fun listForSong(song: Song): List<PlaybackBookmark> = listForTrack(song.stableKey)

    /**
     * 在当前位置记一条。
     * @return 新行；无有效 trackKey 时 null。
     */
    suspend fun addAt(
        song: Song,
        positionMs: Long,
        note: String = "",
        now: Long = System.currentTimeMillis(),
        announce: Boolean = true,
    ): PlaybackBookmark? {
        val key = song.stableKey.trim()
        if (key.isEmpty()) {
            if (announce) messages.post(AppStrings.s(R.string.bmk_fail).ifEmpty { "无法记书签" })
            return null
        }
        val pos = positionMs.coerceAtLeast(0L)
        val row = PlaybackBookmark(
            id = newId(),
            trackKey = key,
            positionMs = pos,
            note = note.trim(),
            createdAt = now,
        )
        withContext(Dispatchers.IO) {
            val all = load().toMutableMap()
            val list = all[key].orEmpty().toMutableList()
            list.add(row)
            all[key] = list
            save(all)
        }
        if (announce) messages.post(AppStrings.s(R.string.bmk_saved, formatClock(pos)).ifEmpty { "已记书签 · ${formatClock(pos)}" })
        return row
    }

    suspend fun delete(id: String, announce: Boolean = true) {
        withContext(Dispatchers.IO) {
            val all = load().toMutableMap()
            var changed = false
            for (key in all.keys.toList()) {
                val next = all[key].orEmpty().filterNot { it.id == id }
                if (next.size != all[key].orEmpty().size) {
                    changed = true
                    if (next.isEmpty()) all.remove(key) else all[key] = next
                }
            }
            if (changed) save(all)
        }
        if (announce) messages.post(AppStrings.s(R.string.bmk_deleted).ifEmpty { "已删除书签" })
    }

    /** 测试 / 诊断：清空全部书签 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY).apply()
    }

    private fun load(): Map<String, List<PlaybackBookmark>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = root.optJSONArray(k) ?: continue
                    val list = buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            add(
                                PlaybackBookmark(
                                    id = o.optString("id").ifBlank { newId() },
                                    trackKey = k,
                                    positionMs = o.optLong("positionMs", 0L),
                                    note = o.optString("note", ""),
                                    createdAt = o.optLong("createdAt", 0L),
                                ),
                            )
                        }
                    }
                    if (list.isNotEmpty()) put(k, list)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun save(all: Map<String, List<PlaybackBookmark>>) {
        val root = JSONObject()
        for ((k, list) in all) {
            val arr = JSONArray()
            for (b in list) {
                arr.put(
                    JSONObject()
                        .put("id", b.id)
                        .put("positionMs", b.positionMs)
                        .put("note", b.note)
                        .put("createdAt", b.createdAt),
                )
            }
            root.put(k, arr)
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    companion object {
        /** 与 [Prefs] 同一份 SharedPreferences 文件，避免另起名分裂数据 */
        const val PREFS_NAME = "music_player_prefs"
        const val KEY = "bookmarks_json"
        private val seq = AtomicLong(0L)

        fun newId(): String = "bm-${System.currentTimeMillis()}-${seq.incrementAndGet()}"

        /** 进度毫秒 → `h:mm:ss` 或 `m:ss`。 */
        fun formatClock(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
