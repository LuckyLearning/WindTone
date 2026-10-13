package com.luckylearning.windtone.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/**
 * 基于系统 MediaMetadataRetriever 的标签读取（标题 / 歌手 / 专辑 / 时长 / 内嵌封面）。
 * MediaMetadataRetriever 支持 mp3 / flac / wav / m4a / aac / ogg / opus 等主流容器。
 */
object MetadataReader {

    data class Info(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
    )

    fun read(context: Context, uri: Uri): Info? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            Info(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "读取元数据失败: $uri", t)
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun embeddedPicture(context: Context, uri: Uri): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private const val TAG = "MetadataReader"
}

/** 支持播放的音频扩展名（ExoPlayer 原生解码） */
object AudioFormats {

    val SUPPORTED = setOf(
        "mp3", "mp2", "mpga", "flac", "wav", "wave",
        "m4a", "m4b", "aac", "ogg", "oga", "opus", "amr",
    )

    /** 已知但当前内核不支持的格式，扫描时跳过并统计 */
    val UNSUPPORTED = setOf("wma", "ape", "dsf", "dff", "tak", "tta", "m4p")

    private val SUPPORTED_MIME = setOf(
        "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3",
        "audio/flac", "audio/x-flac",
        "audio/wav", "audio/wave", "audio/x-wav",
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/aacp",
        "audio/ogg", "audio/x-ogg", "audio/vorbis", "audio/opus",
        "audio/amr", "audio/3gpp",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isSupported(name: String): Boolean = extensionOf(name) in SUPPORTED

    /** 部分老设备的 DISPLAY_NAME 为空，此时退化为按 MIME 判断 */
    fun isSupportedMime(mime: String?): Boolean =
        mime != null && mime.substringBefore(';').trim().lowercase() in SUPPORTED_MIME

    fun isKnownUnsupported(name: String): Boolean = extensionOf(name) in UNSUPPORTED
}
