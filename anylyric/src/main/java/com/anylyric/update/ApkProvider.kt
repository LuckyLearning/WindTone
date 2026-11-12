package com.anylyric.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 给系统安装器提供安装包 URI 的最小 ContentProvider。
 *
 * 为什么自己写一个而不是用 `androidx.core.content.FileProvider`：
 * AnyLyric 的硬约束是**零第三方运行时依赖**（APK 才 150 KB），而 FileProvider 在
 * androidx.core 里，为一个功能引一整个库不划算。这里只实现真正用到的 `openFile`，
 * 不到 60 行，行为可预期。
 *
 * 安全边界三条：
 *  · `exported=false`，只有被显式授权的 URI 才可读；
 *  · 只允许读 `cacheDir/updates/` 下的**直接子文件**，用 canonicalPath 校验防 `..` 穿越；
 *  · 只允许 `mode == "r"`，写/截断一律拒。
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("write not allowed: $mode")
        val context = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        val dir = dir(context).canonicalFile
        val file = File(dir, name).canonicalFile
        // canonicalFile 之后仍要判父目录：`..%2F` 之类的输入只有这一步能挡住
        if (file.parentFile != dir || !file.isFile) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** 安装包落盘目录（不含则自动创建） */
        fun dir(context: Context): File =
            File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }

        /** 授权给安装器读取的 URI */
        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                // 与 manifest 里的 authorities 占位符一致：applicationId + .apkfiles
                .authority("${context.packageName}.apkfiles")
                .appendPath("updates")
                .appendPath(file.name)
                .build()
    }
}
