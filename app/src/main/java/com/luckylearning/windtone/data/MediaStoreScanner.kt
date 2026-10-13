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

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
            "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%')"

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
                    val hit = dir.list()?.toList()?.let { names -> pickBestLyric(names, base) }
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
                    )
                )
            }
        }

        return Result(songs, skipped)
    }

    private fun pickBestLyric(names: List<String>, baseName: String): String? =
        names.mapNotNull { n -> LyricsMatcher.score(n, baseName).takeIf { it >= 0 }?.let { it to n } }
            .maxByOrNull { it.first }?.second
}
