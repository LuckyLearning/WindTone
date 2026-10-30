package com.luckylearning.windtone.playback

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider

/**
 * 通知栏文案：若 MediaMetadata.description 已写入当前歌词行（见 [com.luckylearning.windtone.lyrics.LockScreenLyricPublisher]），
 * 优先用它作为 content text；否则回落默认（艺人等）。
 *
 * 锁屏 / 车机面板仍直接读 description（SYNOPSIS）；本 Provider 只补齐
 * Media3 默认通知「第二行往往只显示艺人、不读 description」的缺口。
 */
@UnstableApi
class LyricMediaNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
        val desc = metadata.description
        if (!desc.isNullOrBlank()) return desc
        return super.getNotificationContentText(metadata)
    }
}
