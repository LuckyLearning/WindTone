package com.luckylearning.windtone.diag

/**
 * 真机一键取证脚本（A1）。
 *
 * 把 logcat / dumpsys 等命令与当前包名、版本拼好，用户复制到电脑即可跑。
 * **不执行任何命令**，只生成文本；不读隐私、不上传。
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
        appendLine("# WindTone 一键取证")
        appendLine("# 应用：v$versionName ($versionCode)    包名：$packageName")
        appendLine("# mapping：mapping-v$versionName（与 GitHub Release 资产同名）")
        appendLine("#")
        appendLine("# 用法：手机用 USB/无线调试连上电脑后，把下面整段粘到终端执行。")
        appendLine("# Windows 可用 Git Bash / WSL；PowerShell 请逐条跑（去掉注释行）。")
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
        appendLine("# 进程是否活着")
        appendLine("adb shell pidof -s \"\$PKG\" | tee \"\$OUT/01-pid.txt\"")
        appendLine("PID=\$(adb shell pidof -s \"\$PKG\" | tr -d '\\r')")
        appendLine()
        appendLine("# 包信息 / 权限 / 组件")
        appendLine("adb shell dumpsys package \"\$PKG\" > \"\$OUT/02-package.txt\"")
        appendLine()
        appendLine("# 前台 Activity / 服务")
        appendLine("adb shell dumpsys activity activities | grep -A2 -E \"\$PKG|mResumedActivity|topResumed\" > \"\$OUT/03-activity.txt\" || true")
        appendLine("adb shell dumpsys activity services \"\$PKG\" > \"\$OUT/04-services.txt\"")
        appendLine()
        appendLine("# 悬浮窗 / 无障碍 / 通知")
        appendLine("adb shell dumpsys window windows | grep -E \"WindTone|LyricOverlay|TYPE_APPLICATION_OVERLAY|\$PKG\" > \"\$OUT/05-window.txt\" || true")
        appendLine("adb shell settings get secure enabled_accessibility_services | tee \"\$OUT/06-a11y.txt\"")
        appendLine("adb shell dumpsys notification --noredact | grep -A20 \"\$PKG\" > \"\$OUT/07-notification.txt\" || true")
        appendLine()
        appendLine("# 音频焦点 / 会话（播放异常时有用）")
        appendLine("adb shell dumpsys audio | grep -A30 -E \"AudioFocus|AudioPlaybackConfiguration|windtone|WindTone\" > \"\$OUT/08-audio.txt\" || true")
        appendLine("adb shell dumpsys media_session | grep -A40 -E \"\$PKG|WindTone\" > \"\$OUT/09-media-session.txt\" || true")
        appendLine()
        appendLine("# logcat：有 PID 就按进程捞；没有就按标签粗滤")
        appendLine("if [ -n \"\$PID\" ]; then")
        appendLine("  adb logcat -d -v threadtime --pid=\"\$PID\" > \"\$OUT/10-logcat.txt\"")
        appendLine("else")
        appendLine("  adb logcat -d -v threadtime | grep -iE \"windtone|LyricOverlay|Playback|ExoPlayer|Crash\" > \"\$OUT/10-logcat.txt\" || true")
        appendLine("fi")
        appendLine()
        appendLine("# 应用私有目录里的崩溃 / 启动 trace / 诊断包（debug 可直接 run-as；release 需 debuggable）")
        appendLine("adb shell \"run-as \$PKG sh -c 'ls -la files/crash files/boot 2>/dev/null; echo ---; ls -la files/diagnostics 2>/dev/null'\" > \"\$OUT/11-files-ls.txt\" 2>&1 || true")
        appendLine("adb shell \"run-as \$PKG sh -c 'tar -cf - files/crash files/boot 2>/dev/null'\" > \"\$OUT/12-crash-boot.tar\" 2>/dev/null || true")
        appendLine()
        appendLine("# 外部诊断包目录（应用可写，不依赖 run-as）")
        appendLine("adb shell ls -la \"/sdcard/Android/data/\$PKG/files/diagnostics\" > \"\$OUT/13-ext-diag-ls.txt\" 2>&1 || true")
        appendLine("adb pull \"/sdcard/Android/data/\$PKG/files/diagnostics\" \"\$OUT/diagnostics\" 2>/dev/null || true")
        appendLine()
        appendLine("echo \"取证完成：\$OUT\"")
        appendLine("# 还原混淆栈（把 Release 上的 mapping-v$versionName.txt 放到当前目录后）：")
        appendLine("#   bash scripts/retrace.sh --from-log \$OUT/12-extracted/crash-xxx.txt .")
    }

    /** 诊断包内嵌的短版说明（不含完整脚本时也有入口提示） */
    fun shortHint(packageName: String, versionName: String): String =
        "设置 → 关于与诊断 →「一键取证」可复制完整 adb 脚本（包名 $packageName，mapping-v$versionName）。"
}
