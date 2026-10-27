package com.luckylearning.windtone.diag

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

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
 */
object DefaultMusicProbe {

    /** 供诊断包直接粘贴的一段文本。 */
    fun buildReport(context: Context): String {
        val pm = context.packageManager
        val self = context.packageName
        val sb = StringBuilder()
        sb.appendLine("[默认音乐候选资格]")

        // ① 音乐分类（启动器）
        val appMusic = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MUSIC)
        sb.appendLine("① 音乐分类（CATEGORY_APP_MUSIC）：${resolveSelf(pm, appMusic, self)}")

        // ② 文件管理器点歌（audio/*）
        val viewAudio = Intent(Intent.ACTION_VIEW).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        sb.appendLine("② 打开音频（VIEW audio/*）：${resolveSelf(pm, viewAudio, self)}")

        // ③ 媒体按键（方控 / 耳机键）
        val mediaButton = Intent(Intent.ACTION_MEDIA_BUTTON)
        sb.appendLine("③ 媒体按键（MEDIA_BUTTON）：${resolveSelf(pm, mediaButton, self)}")

        // ④ 语音点歌
        val voiceSearch = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
        sb.appendLine("④ 语音点歌（MEDIA_PLAY_FROM_SEARCH）：${resolveSelf(pm, voiceSearch, self)}")

        sb.appendLine("说明：本段只证明「有资格被列为音乐应用」。是否已被设为默认音乐由车机设置决定，")
        sb.appendLine("      没有可查询的标准接口；若前三项都是「已声明」，列表里仍看不到本应用，")
        sb.appendLine("      说明该车机用的是自带白名单（见 docs/竞品调研-车机音乐应用.md）。")
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
            result.isSuccess && result.getOrNull() == true -> "已声明（系统可解析到本应用）"
            result.isSuccess -> "未命中（本条依据本机解析不到本应用）"
            else -> "查询失败（${result.exceptionOrNull()?.javaClass?.simpleName ?: "未知"}）"
        }
    }
}
