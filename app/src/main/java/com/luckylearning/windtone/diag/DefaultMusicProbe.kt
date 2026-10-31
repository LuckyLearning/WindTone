package com.luckylearning.windtone.diag

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 「默认音乐候选资格」自检（2026-10-27）。
 *
 * ## 为什么需要这一段
 *
 * 车机把应用列进「默认音乐」时看的是 intent-filter，各家实现还不一样（比亚迪 DiLink
 * 没有公开文档）。用户能看到的只有两种结果：列表里有 / 没有。列表里没有时，
 * 到底是「没声明」「声明了但系统不认」，还是「声明了、也能点，只是没人告诉用户去哪设」，
 * 光看现象分不出来 —— 这一段把三条依据逐条实测打出来。
 *
 * ## 三条依据（对应清单里的 intent-filter）
 *
 *  1. `CATEGORY_APP_MUSIC`：启动器「音乐」分类；
 *  2. `ACTION_VIEW` + 音频通配类型：文件管理器「用什么打开」；
 *  3. `MEDIA_BUTTON`：方控 / 蓝牙耳机按键是否会被系统投递给本应用。
 *
 * 另附 `MEDIA_PLAY_FROM_SEARCH`（语音点歌）的解析结果。**是否已是系统默认音乐
 * 没有标准 API 可查** —— 那一步只能去车机设置里看，这里只证明「我们是合格的候选」。
 *
 * i18n 第 24 批：用户可见句进 `dmp_*`。
 */
object DefaultMusicProbe {

    /** 供诊断包直接粘贴的一段文本。 */
    fun buildReport(context: Context): String {
        val pm = context.packageManager
        val self = context.packageName
        val sb = StringBuilder()
        sb.appendLine(AppStrings.s(R.string.dmp_sec))

        val appMusic = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MUSIC)
        sb.appendLine(AppStrings.s(R.string.dmp_cat_music, resolveSelf(pm, appMusic, self)))

        val viewAudio = Intent(Intent.ACTION_VIEW).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        sb.appendLine(AppStrings.s(R.string.dmp_view_audio, resolveSelf(pm, viewAudio, self)))

        val mediaButton = Intent(Intent.ACTION_MEDIA_BUTTON)
        sb.appendLine(AppStrings.s(R.string.dmp_media_button, resolveSelf(pm, mediaButton, self)))

        val voiceSearch = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
        sb.appendLine(AppStrings.s(R.string.dmp_voice, resolveSelf(pm, voiceSearch, self)))

        sb.appendLine(AppStrings.s(R.string.dmp_note1))
        sb.appendLine(AppStrings.s(R.string.dmp_note2))
        sb.appendLine(AppStrings.s(R.string.dmp_note3))
        return sb.toString().trimEnd()
    }

    /**
     * 本应用是否在这条 intent 的解析结果里。
     *
     * 只报「本应用是否命中」，不列别的应用叫什么 —— 别家包名对排查无用，
     * 还会让诊断包多出一串与听风无关的信息。
     */
    private fun resolveSelf(pm: PackageManager, intent: Intent, self: String): String {
        val result = runCatching {
            when {
                // 广播类（MEDIA_BUTTON）要用 queryBroadcastReceivers，用 resolveActivity 查不到
                intent.action == Intent.ACTION_MEDIA_BUTTON ->
                    pm.queryBroadcastReceivers(intent, 0)
                        .any { it.activityInfo?.packageName == self }

                else -> pm.queryIntentActivities(intent, 0)
                    .any { it.activityInfo?.packageName == self }
            }
        }
        return when {
            result.isSuccess && result.getOrNull() == true -> AppStrings.s(R.string.dmp_hit)
            result.isSuccess -> AppStrings.s(R.string.dmp_miss)
            else -> AppStrings.s(
                R.string.dmp_fail,
                result.exceptionOrNull()?.javaClass?.simpleName
                    ?: AppStrings.s(R.string.dmp_unknown),
            )
        }
    }
}
