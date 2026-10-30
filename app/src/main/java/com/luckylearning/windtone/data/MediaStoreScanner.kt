package com.luckylearning.windtone.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import java.io.File

/** 通过系统媒体库快速扫描全部音频 */
class MediaStoreScanner(private val context: Context) {

    data class Result(val songs: List<Song>, val skippedUnsupported: Int)

    fun scan(deepAccess: Boolean): Result {
        val songs = ArrayList<Song>()
        var skipped = 0

        val collection = audioCollection()

        // RELATIVE_PATH 是 API 29 才有的列，老系统查询会直接抛异常，必须按版本拼装
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.YEAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
            }
        }.toTypedArray()

        val selection = audioSelection()

        // 同一个目录里通常有好几首歌：目录列举是一次系统调用，逐首 list 同一目录纯属浪费。
        // 3000 首 / 200 个目录时，这一步从 3000 次降到 200 次（深扫模式下尤其明显）。
        val dirNamesCache = HashMap<String, List<String>>()
        fun namesOf(dir: File): List<String> =
            dirNamesCache.getOrPut(dir.absolutePath) { dir.list()?.toList().orEmpty() }

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.DEFAULT_SORT_ORDER,
            )
        }.getOrNull()?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iAlbumArtist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            } else -1
            val iGenre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else -1
            val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: "track_$id"
                val mime = c.getString(iMime)
                val supported = AudioFormats.isSupported(name) || AudioFormats.isSupportedMime(mime)
                if (!supported) {
                    if (AudioFormats.isKnownUnsupported(name)) skipped++
                    continue
                }
                val duration = c.getLong(iDur)
                if (duration in 1..2500) continue // 过滤系统提示音

                val uri = ContentUris.withAppendedId(collection, id)
                val path = if (iData >= 0) c.getString(iData) else null
                val parentDir = path?.let { File(it).parent }

                var lrcUri: android.net.Uri? = null
                if (deepAccess && parentDir != null) {
                    val base = name.substringBeforeLast('.')
                    val dir = File(parentDir)
                    val hit = pickBestLyric(namesOf(dir), base)
                    if (hit != null) lrcUri = android.net.Uri.fromFile(File(dir, hit))
                }

                songs.add(
                    Song(
                        id = id,
                        uri = uri,
                        title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                        artist = c.getString(iArtist),
                        album = c.getString(iAlbum),
                        albumId = c.getLong(iAlbumId),
                        durationMs = duration,
                        sizeBytes = c.getLong(iSize),
                        mimeType = mime,
                        displayName = name,
                        path = if (deepAccess) path else null,
                        folderPath = parentDir,
                        source = SongSource.MEDIA_STORE,
                        trackNo = c.getInt(iTrack),
                        lrcUri = lrcUri,
                        dateAdded = c.getLong(iDate) * 1000L,
                        genre = if (iGenre >= 0) normalizeGenre(c.getString(iGenre)) else null,
                        albumArtist = if (iAlbumArtist >= 0) normalizeAlbumArtist(c.getString(iAlbumArtist)) else null,
                        year = parseYearInt(c.getInt(iYear)),
                    )
                )
            }
        }

        return Result(songs, skipped)
    }

    private fun pickBestLyric(names: List<String>, baseName: String): String? =
        names.mapNotNull { n -> LyricsMatcher.score(n, baseName).takeIf { it >= 0 }?.let { it to n } }
            .maxByOrNull { it.first }?.second

    /**
     * 曲库指纹：`最新一条 _ID : 总条数`。
     *
     * 只用来回答一个问题 —— **曲库变了没有**。冷启动的自动扫描据此做节流：
     * 指纹没变且距上次扫描不久，就没有必要把整个存储再走一遍。
     *
     * 之所以不用 `COUNT(*) + MAX(date_modified)`：`date_modified` 在部分 ROM 上
     * 对「只改了标签」的文件也会变，而那种变化本来就要重扫，两者语义不同；
     * `_ID` 是插入序，新歌必然更大，删歌必然改变总数 —— 判断「有没有新东西」
     * 比时间戳更直接。
     *
     * 查询失败（权限被回收 / ROM 异常）返回空串，调用方必须把它当成「指纹未知」，
     * 走全量扫描，宁可慢也不能漏歌。
     */
    fun fingerprint(): String = runCatching {
        context.contentResolver.query(
            audioCollection(),
            arrayOf(MediaStore.Audio.Media._ID),
            audioSelection(),
            null,
            "${MediaStore.Audio.Media._ID} DESC",
        )?.use { c ->
            val newest = if (c.moveToFirst()) c.getLong(0) else 0L
            "$newest:${c.count}"
        }
    }.getOrNull().orEmpty()

    /** 与 [scan] 完全一致的筛选条件：媒体库标记，或 MIME 是音频 */
    private fun audioSelection(): String =
        "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
            "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%')"

    private fun audioCollection(): android.net.Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
}
