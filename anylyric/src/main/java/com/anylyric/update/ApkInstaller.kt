package com.anylyric.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * 车机上「安装器一闪而过 / 一直转圈」的常见原因是只给了
 * `FLAG_GRANT_READ_URI_PERMISSION`：部分 ROM 的安装器只认 `clipData` 里的授权，
 * 所以两者都写（WindTone 主体踩过这个坑，这里直接带上）。
 */
object ApkInstaller {

    /** 是否已允许「安装未知应用」（Android 8+ 必须由用户手动打开一次） */
    fun canInstall(context: Context): Boolean =
        try {
            context.packageManager.canRequestPackageInstalls()
        } catch (_: Throwable) {
            false
        }

    /** 打开「安装未知应用」授权页 */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 拉起系统安装器；失败返回带原因的 [Result] */
    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        val uri = ApkProvider.uriFor(context, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("apk", uri)
        }
        context.startActivity(intent)
    }
}
