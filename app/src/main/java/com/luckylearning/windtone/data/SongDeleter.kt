package com.luckylearning.windtone.data

import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import java.io.File

/**
 * 删除歌曲文件。
 *
 * 三种来源的删除通道完全不同：
 *  - [SongSource.MEDIA_STORE]：Android 11+ 必须走 [MediaStore.createDeleteRequest]
 *    （系统弹出确认框，用户同意后系统代删）；Android 10 及以下走传统的
 *    `contentResolver.delete`，失败再退化为直接删文件路径。
 *  - [SongSource.FOLDER]：SAF 文档 URI，走 [DocumentsContract.deleteDocument]，
 *    要求对该目录树持有写权限（导入文件夹时已一并申请）。
 *  - [SongSource.FILE]：深度扫描得到的绝对路径，深度扫描本身要求
 *    「所有文件访问权限」，直接 [File.delete]。
 *
 * 同名的 .lrc 歌词文件会尽力一并删除（best-effort，失败不影响主文件删除的结果）。
 */
object SongDeleter {

    /**
     * 若删除需要系统级确认（Android 11+ 媒体库文件），返回 [IntentSender] 交由
     * UI 层用 `StartIntentSenderForResult` 启动；否则返回 null，可直接调 [deleteNow]。
     */
    fun prepareSystemConfirm(context: Context, song: Song): IntentSender? {
        if (song.source != SongSource.MEDIA_STORE) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, listOf(song.uri)).intentSender
        }.getOrNull()
    }

    /**
     * 批量版本的 [prepareSystemConfirm]。
     *
     * `MediaStore.createDeleteRequest` 本身就接受 **URI 列表**（Android 11+），
     * 一次请求只弹**一个**系统确认框——所以多选删除不能退化成"逐首请求"，
     * 那会让用户点 N 次确认，还会在两次确认之间留下半删状态。
     *
     * 只在列表里确实有媒体库文件时才返回非 null；全是 SAF / 绝对路径的话
     * 系统确认框帮不上忙，调用方走 [deleteNow] 逐个删。
     */
    fun prepareSystemConfirmMany(context: Context, songs: List<Song>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = songs.filter { it.source == SongSource.MEDIA_STORE }.map { it.uri }
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.getOrNull()
    }

    /** 直接执行删除（阻塞，需在 IO 线程调用）。返回是否删除成功。 */
    fun deleteNow(context: Context, song: Song): Boolean {
        val ok = runCatching {
            when (song.source) {
                SongSource.FOLDER -> DocumentsContract.deleteDocument(context.contentResolver, song.uri)
                SongSource.MEDIA_STORE ->
                    context.contentResolver.delete(song.uri, null, null) > 0 ||
                        song.path?.let { File(it).delete() } == true
                SongSource.FILE -> song.path?.let { File(it).delete() } ?: false
            }
        }.getOrDefault(false)
        if (ok) deleteSidecarLyric(context, song)
        return ok
    }

    /** 尽力删除同名歌词文件（.lrc / .txt），失败静默 */
    private fun deleteSidecarLyric(context: Context, song: Song) {
        val lrc = song.lrcUri ?: return
        runCatching {
            when (lrc.scheme) {
                "file" -> File(lrc.path!!).delete()
                "content" ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        DocumentsContract.deleteDocument(context.contentResolver, lrc)
                    } else {
                        context.contentResolver.delete(lrc, null, null) > 0
                    }
                else -> false
            }
        }
    }
}
