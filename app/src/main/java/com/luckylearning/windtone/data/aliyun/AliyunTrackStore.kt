package com.luckylearning.windtone.data.aliyun

import android.content.Context
import com.luckylearning.windtone.data.HOUR_MS
import com.luckylearning.windtone.data.ListenBucket
import com.luckylearning.windtone.data.model.TrackFragment
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阿里云盘曲目的本地记录：显示信息、收藏、播放统计、续播队列。
 *
 * 不进曲库的 `song_state`。键是盘编号加文件编号。
 */
data class AliyunTrackRecord(
    val driveId: String,
    val fileId: String,
    val parentFileId: String = "",
    val folderName: String = "",
    val fileName: String = "",
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val playedMs: Long = 0L,
) {
    val key: String get() = TrackFragment.cacheKey(TrackFragment.of(driveId, fileId)).orEmpty()
}

data class AliyunQueueItem(
    val driveId: String,
    val fileId: String,
    val name: String,
    val folderName: String,
    val parentFileId: String,
)

data class AliyunQueueSnapshot(
    val items: List<AliyunQueueItem>,
    val index: Int,
    val positionMs: Long,
)

class AliyunTrackStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("aliyun_tracks", Context.MODE_PRIVATE)

    fun get(driveId: String, fileId: String): AliyunTrackRecord? = all()[key(driveId, fileId)]

    fun favoriteKeys(): Set<String> = all().values.filter { it.favorite }.map { it.key }.toSet()

    fun favorites(): List<AliyunTrackRecord> =
        all().values.filter { it.favorite }.sortedByDescending { it.lastPlayedAt }

    fun recent(): List<AliyunTrackRecord> =
        all().values.filter { it.lastPlayedAt > 0L }.sortedByDescending { it.lastPlayedAt }.take(100)

    fun mostPlayed(): List<AliyunTrackRecord> =
        all().values.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(100)

    fun records(): List<AliyunTrackRecord> = all().values.toList()

    /** 在线收听的小时桶。和本地 `listen_log` 分开存，报告算法共用。 */
    fun listenBuckets(): List<ListenBucket> {
        val raw = sp.getString(HOURS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val ms = row.optLong("ms")
                    if (ms <= 0L) continue
                    add(ListenBucket(row.optLong("h"), ms))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 第一次见到这首歌时记下文件名和文件夹。已有记录只补空着的目录信息。 */
    fun remember(
        driveId: String,
        fileId: String,
        fileName: String,
        folderName: String?,
        parentFileId: String?,
    ) {
        val existing = get(driveId, fileId)
        if (existing == null) {
            val (title, artist) = AliyunRules.splitDisplayName(fileName)
            put(
                AliyunTrackRecord(
                    driveId = driveId,
                    fileId = fileId,
                    parentFileId = parentFileId.orEmpty(),
                    folderName = folderName.orEmpty(),
                    fileName = fileName,
                    title = title,
                    artist = artist,
                    album = AliyunRules.albumName(folderName),
                ),
            )
            return
        }
        val parent = parentFileId?.takeIf { it.isNotBlank() } ?: existing.parentFileId
        val folder = folderName?.takeIf { it.isNotBlank() } ?: existing.folderName
        if (parent != existing.parentFileId || folder != existing.folderName || existing.fileName.isBlank()) {
            put(existing.copy(parentFileId = parent, folderName = folder, fileName = existing.fileName.ifBlank { fileName }))
        }
    }

    /** 内嵌标签盖过文件名。空字段不覆盖已经有的值。 */
    fun applyTags(
        driveId: String,
        fileId: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
    ) {
        val existing = get(driveId, fileId) ?: AliyunTrackRecord(driveId, fileId)
        put(
            existing.copy(
                title = title?.takeIf { it.isNotBlank() } ?: existing.title,
                artist = artist?.takeIf { it.isNotBlank() } ?: existing.artist,
                album = album?.takeIf { it.isNotBlank() } ?: existing.album,
                durationMs = if (durationMs > 0L) durationMs else existing.durationMs,
            ),
        )
    }

    fun rememberDuration(driveId: String, fileId: String, durationMs: Long) {
        if (durationMs <= 0L) return
        val existing = get(driveId, fileId) ?: return
        if (existing.durationMs == durationMs) return
        put(existing.copy(durationMs = durationMs))
    }

    fun toggleFavorite(driveId: String, fileId: String): Boolean {
        val existing = get(driveId, fileId) ?: AliyunTrackRecord(driveId, fileId)
        val next = !existing.favorite
        put(existing.copy(favorite = next, lastPlayedAt = System.currentTimeMillis()))
        return next
    }

    fun recordPlay(mediaId: String, now: Long) {
        val parsed = parseMediaId(mediaId) ?: return
        val existing = get(parsed.first, parsed.second) ?: AliyunTrackRecord(parsed.first, parsed.second)
        put(existing.copy(playCount = existing.playCount + 1, lastPlayedAt = now))
    }

    fun addListenTime(mediaId: String, ms: Long, now: Long) {
        if (ms <= 0L) return
        val parsed = parseMediaId(mediaId) ?: return
        val existing = get(parsed.first, parsed.second) ?: AliyunTrackRecord(parsed.first, parsed.second)
        put(existing.copy(playedMs = existing.playedMs + ms, lastPlayedAt = now))
        addBucket(now / HOUR_MS, ms)
    }

    fun saveQueue(snapshot: AliyunQueueSnapshot?) {
        if (snapshot == null || snapshot.items.isEmpty()) {
            sp.edit().remove(QUEUE).apply()
            return
        }
        val arr = JSONArray()
        snapshot.items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("drive_id", item.driveId)
                    .put("file_id", item.fileId)
                    .put("name", item.name)
                    .put("folder", item.folderName)
                    .put("parent_file_id", item.parentFileId),
            )
        }
        sp.edit().putString(
            QUEUE,
            JSONObject()
                .put("index", snapshot.index)
                .put("position_ms", snapshot.positionMs)
                .put("items", arr)
                .toString(),
        ).apply()
    }

    fun loadQueue(): AliyunQueueSnapshot? {
        val raw = sp.getString(QUEUE, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("items") ?: return null
            val items = (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONObject(i) ?: return@mapNotNull null
                val driveId = row.optString("drive_id")
                val fileId = row.optString("file_id")
                if (driveId.isBlank() || fileId.isBlank()) return@mapNotNull null
                AliyunQueueItem(
                    driveId = driveId,
                    fileId = fileId,
                    name = row.optString("name"),
                    folderName = row.optString("folder"),
                    parentFileId = row.optString("parent_file_id"),
                )
            }
            if (items.isEmpty()) return null
            AliyunQueueSnapshot(
                items = items,
                index = o.optInt("index", 0).coerceIn(0, items.lastIndex),
                positionMs = o.optLong("position_ms", 0L).coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    private fun put(record: AliyunTrackRecord) {
        val map = all().toMutableMap()
        map[key(record.driveId, record.fileId)] = record
        val arr = JSONArray()
        map.values.forEach { arr.put(write(it)) }
        sp.edit().putString(TRACKS, arr.toString()).apply()
    }

    private fun all(): Map<String, AliyunTrackRecord> {
        val raw = sp.getString(TRACKS, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val row = read(arr.optJSONObject(i) ?: continue) ?: continue
                    put(key(row.driveId, row.fileId), row)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun write(r: AliyunTrackRecord) = JSONObject()
        .put("drive_id", r.driveId)
        .put("file_id", r.fileId)
        .put("parent_file_id", r.parentFileId)
        .put("folder", r.folderName)
        .put("file_name", r.fileName)
        .put("title", r.title)
        .put("artist", r.artist ?: "")
        .put("album", r.album ?: "")
        .put("duration_ms", r.durationMs)
        .put("favorite", r.favorite)
        .put("play_count", r.playCount)
        .put("last_played_at", r.lastPlayedAt)
        .put("played_ms", r.playedMs)

    private fun read(o: JSONObject): AliyunTrackRecord? {
        val driveId = o.optString("drive_id")
        val fileId = o.optString("file_id")
        if (driveId.isBlank() || fileId.isBlank()) return null
        return AliyunTrackRecord(
            driveId = driveId,
            fileId = fileId,
            parentFileId = o.optString("parent_file_id"),
            folderName = o.optString("folder"),
            fileName = o.optString("file_name"),
            title = o.optString("title"),
            artist = o.optString("artist").takeIf { it.isNotBlank() },
            album = o.optString("album").takeIf { it.isNotBlank() },
            durationMs = o.optLong("duration_ms", 0L),
            favorite = o.optBoolean("favorite", false),
            playCount = o.optInt("play_count", 0),
            lastPlayedAt = o.optLong("last_played_at", 0L),
            playedMs = o.optLong("played_ms", 0L),
        )
    }

    private fun parseMediaId(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith("aliyun:")) return null
        return TrackFragment.parse(mediaId.removePrefix("aliyun:"))
    }

    private fun key(driveId: String, fileId: String) = "$driveId/$fileId"

    private fun addBucket(hour: Long, ms: Long) {
        val kept = listenBuckets().associate { it.hourEpoch to it.ms }.toMutableMap()
        kept[hour] = (kept[hour] ?: 0L) + ms
        val cutoff = hour - RETENTION_DAYS * 24L
        val arr = JSONArray()
        kept.forEach { (h, value) ->
            if (h >= cutoff && value > 0L) arr.put(JSONObject().put("h", h).put("ms", value))
        }
        sp.edit().putString(HOURS, arr.toString()).apply()
    }

    private companion object {
        const val TRACKS = "tracks"
        const val QUEUE = "queue"
        const val HOURS = "listen_hours"
        /** 与本地收听报告同一保留期，避免在线小时桶无限涨 */
        const val RETENTION_DAYS = 400
    }
}
