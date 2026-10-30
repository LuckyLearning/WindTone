package com.luckylearning.windtone.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import com.luckylearning.windtone.data.model.TrackFragment
import java.io.File

/**
 * N1「分享当前曲」：系统分享面板。
 *
 * **策略（偏稳妥 / 权限合规）**
 * - **永远**带文本：标题 / 艺人 / 专辑 +「来自听风 WindTone」。
 * - **本地可读**时附带音频流（`EXTRA_STREAM`）：`content://` 直接授出；
 *   绝对路径经 [FileProvider]（`external-path` / `cache-path`）授出只读 URI。
 * - **云盘 / 在线 / http(s)**：只发文本，**绝不**下载或导出远端文件。
 *
 * 不选「仅文本 + 封面」的原因：用户分享当前曲时更常见的是把曲目信息
 * （必要时连同本地文件）交给微信 / 文件管理等；封面单独分享价值低，
 * 且仍要碰缓存字节。云盘不强导文件是硬边界。
 */
object ShareCurrentSong {

    const val CHOOSER_TITLE = "分享当前曲"

    /** 分享正文：三行固定结构，单测锁死。 */
    fun textOf(song: Song): String = buildString {
        append(song.title.trim().ifBlank { song.displayName.ifBlank { "未知歌曲" } })
        append('\n')
        append(song.artistOrUnknown)
        append(" · ")
        append(song.albumOrUnknown)
        append('\n')
        append("来自听风 WindTone")
    }

    /**
     * 云盘 / 在线条目：只允许文本分享。
     *
     * - [SongSource.REMOTE]：M3U / 电台等 http(s) 项
     * - 阿里云盘：fragment 带 `ay=`（直链会过期，不能当文件授出）
     * - uri 本身是 http(s)
     */
    fun isCloudOrRemote(song: Song): Boolean {
        if (song.source == SongSource.REMOTE) return true
        if (TrackFragment.parse(song.uri.fragment) != null) return true
        val scheme = song.uri.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    /**
     * 能否附带音频。云盘一律否；本地需 content URI 或可读绝对路径。
     * 纯逻辑，不碰磁盘（路径存在性留给 [resolveAudioUri]）。
     */
    fun canAttachAudio(song: Song): Boolean {
        if (isCloudOrRemote(song)) return false
        val scheme = song.uri.scheme?.lowercase()
        if (scheme == "content") return true
        if (scheme == "file") return true
        if (!song.path.isNullOrBlank()) return true
        return false
    }

    data class Plan(
        val text: String,
        /** null = 仅文本 */
        val streamUri: Uri? = null,
        val mime: String = "text/plain",
    )

    /**
     * 解析分享计划。失败附带时降级为纯文本（不抛）。
     */
    fun plan(context: Context, song: Song): Plan {
        val text = textOf(song)
        if (!canAttachAudio(song)) return Plan(text = text)
        val mime = song.mimeType?.takeIf { it.isNotBlank() } ?: guessMime(song)
        val uri = resolveAudioUri(context, song) ?: return Plan(text = text)
        return Plan(text = text, streamUri = uri, mime = mime)
    }

    fun start(context: Context, song: Song) {
        val p = plan(context, song)
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (p.streamUri != null) {
                type = p.mime
                putExtra(Intent.EXTRA_STREAM, p.streamUri)
                putExtra(Intent.EXTRA_TEXT, p.text)
                putExtra(Intent.EXTRA_SUBJECT, song.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 部分 ROM（车机居多）只认 clipData 里的 grant
                clipData = ClipData.newUri(context.contentResolver, "audio", p.streamUri)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, p.text)
                putExtra(Intent.EXTRA_SUBJECT, song.title)
            }
        }
        val chooser = Intent.createChooser(intent, CHOOSER_TITLE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun resolveAudioUri(context: Context, song: Song): Uri? {
        val scheme = song.uri.scheme?.lowercase()
        when (scheme) {
            "content" -> return song.uri
            "file" -> {
                val path = song.uri.path ?: return null
                return fileProviderUri(context, File(path))
            }
        }
        val path = song.path?.takeIf { it.isNotBlank() } ?: return null
        return fileProviderUri(context, File(path))
    }

    private fun fileProviderUri(context: Context, file: File): Uri? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull()
    }

    private fun guessMime(song: Song): String {
        val name = song.displayName.ifBlank { song.path.orEmpty() }.lowercase()
        return when {
            name.endsWith(".flac") -> "audio/flac"
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".m4a") || name.endsWith(".aac") -> "audio/mp4"
            name.endsWith(".ogg") || name.endsWith(".opus") -> "audio/ogg"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".ape") -> "audio/*"
            else -> "audio/*"
        }
    }
}
