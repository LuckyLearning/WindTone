package com.luckylearning.windtone.diag

import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 真机一键取证脚本（A1）。
 *
 * 把 logcat / dumpsys 等命令与当前包名、版本拼好，用户复制到电脑即可跑。
 * **不执行任何命令**，只生成文本；不读隐私、不上传。
 *
 * i18n 第 25 批：注释与短提示进 `fs_*`；adb 命令本身保持英文可执行。
 */
object ForensicsScript {

    /**
     * @param packageName 当前包名（debug 带 `.debug` 后缀）
     * @param versionName 如 `1.2.6`
     * @param versionCode 如 `116`
     */
    fun build(
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): String = buildString {
        appendLine(AppStrings.s(R.string.fs_title))
        appendLine(AppStrings.s(R.string.fs_app, versionName, versionCode, packageName))
        appendLine(AppStrings.s(R.string.fs_mapping, versionName))
        appendLine("#")
        appendLine(AppStrings.s(R.string.fs_usage1))
        appendLine(AppStrings.s(R.string.fs_usage2))
        appendLine("# ------------------------------------------------------------")
        appendLine("PKG='$packageName'")
        appendLine("OUT=\"windtone-forensics-v${versionName}\"")
        appendLine("mkdir -p \"\$OUT\"")
        appendLine("echo \"=== device ===\" | tee \"\$OUT/00-meta.txt\"")
        appendLine("adb devices -l | tee -a \"\$OUT/00-meta.txt\"")
        appendLine("adb shell getprop ro.build.version.release | tee -a \"\$OUT/00-meta.txt\"")
        appendLine("adb shell getprop ro.product.model | tee -a \"\$OUT/00-meta.txt\"")
        appendLine("echo \"pkg=\$PKG version=$versionName ($versionCode)\" | tee -a \"\$OUT/00-meta.txt\"")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_proc))
        appendLine("adb shell pidof -s \"\$PKG\" | tee \"\$OUT/01-pid.txt\"")
        appendLine("PID=\$(adb shell pidof -s \"\$PKG\" | tr -d '\\r')")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_pkg))
        appendLine("adb shell dumpsys package \"\$PKG\" > \"\$OUT/02-package.txt\"")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_act))
        appendLine("adb shell dumpsys activity activities | grep -A2 -E \"\$PKG|mResumedActivity|topResumed\" > \"\$OUT/03-activity.txt\" || true")
        appendLine("adb shell dumpsys activity services \"\$PKG\" > \"\$OUT/04-services.txt\"")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_win))
        appendLine("adb shell dumpsys window windows | grep -E \"WindTone|LyricOverlay|TYPE_APPLICATION_OVERLAY|\$PKG\" > \"\$OUT/05-window.txt\" || true")
        appendLine("adb shell settings get secure enabled_accessibility_services | tee \"\$OUT/06-a11y.txt\"")
        appendLine("adb shell dumpsys notification --noredact | grep -A20 \"\$PKG\" > \"\$OUT/07-notification.txt\" || true")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_audio))
        appendLine("adb shell dumpsys audio | grep -A30 -E \"AudioFocus|AudioPlaybackConfiguration|windtone|WindTone\" > \"\$OUT/08-audio.txt\" || true")
        appendLine("adb shell dumpsys media_session | grep -A40 -E \"\$PKG|WindTone\" > \"\$OUT/09-media-session.txt\" || true")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_log))
        appendLine("if [ -n \"\$PID\" ]; then")
        appendLine("  adb logcat -d -v threadtime --pid=\"\$PID\" > \"\$OUT/10-logcat.txt\"")
        appendLine("else")
        appendLine("  adb logcat -d -v threadtime | grep -iE \"windtone|LyricOverlay|Playback|ExoPlayer|Crash\" > \"\$OUT/10-logcat.txt\" || true")
        appendLine("fi")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_priv))
        appendLine("adb shell \"run-as \$PKG sh -c 'ls -la files/crash files/boot 2>/dev/null; echo ---; ls -la files/diagnostics 2>/dev/null'\" > \"\$OUT/11-files-ls.txt\" 2>&1 || true")
        appendLine("adb shell \"run-as \$PKG sh -c 'tar -cf - files/crash files/boot 2>/dev/null'\" > \"\$OUT/12-crash-boot.tar\" 2>/dev/null || true")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_sec_ext))
        appendLine("adb shell ls -la \"/sdcard/Android/data/\$PKG/files/diagnostics\" > \"\$OUT/13-ext-diag-ls.txt\" 2>&1 || true")
        appendLine("adb pull \"/sdcard/Android/data/\$PKG/files/diagnostics\" \"\$OUT/diagnostics\" 2>/dev/null || true")
        appendLine()
        appendLine(AppStrings.s(R.string.fs_done))
        appendLine(AppStrings.s(R.string.fs_retrace, versionName))
        appendLine("#   bash scripts/retrace.sh --from-log \$OUT/12-extracted/crash-xxx.txt .")
    }

    /** 诊断包内嵌的短版说明（不含完整脚本时也有入口提示） */
    fun shortHint(packageName: String, versionName: String): String =
        AppStrings.s(R.string.fs_hint, packageName, versionName)
}
