package com.luckylearning.windtone.ui

import androidx.annotation.StringRes
import com.luckylearning.windtone.R

/**
 * 设置页内搜索（G8 / v1.2.33）。
 *
 * 轻量本地过滤：条目表覆盖六大分区卡 + 各分区主要设置项的标题/分区名/关键词；
 * 不做全文索引、不改子页结构。空查询由 UI 显示原分类卡；无命中显示空态。
 */
data class SettingsSearchEntry(
    @StringRes val titleRes: Int,
    val page: SettingsPage,
    @StringRes val sectionRes: Int? = null,
    /** 额外关键词（中英别名、俗称），仅参与匹配、不展示 */
    val keywords: String = "",
)

object SettingsSearchCatalog {
    fun entries(): List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry(R.string.appearance, SettingsPage.APPEARANCE, keywords = "外观 主题 theme appearance"),
        SettingsSearchEntry(R.string.playback, SettingsPage.PLAYBACK, keywords = "播放 playback 速度 变调"),
        SettingsSearchEntry(R.string.lyrics, SettingsPage.LYRICS, keywords = "歌词 lyrics 字号"),
        SettingsSearchEntry(R.string.library_and_storage, SettingsPage.LIBRARY, keywords = "曲库 音乐库 存储 library storage 清理"),
        SettingsSearchEntry(R.string.about_updates, SettingsPage.ABOUT, keywords = "关于 更新 about update 版本"),
        SettingsSearchEntry(R.string.set_lab_title, SettingsPage.LAB, keywords = "实验室 lab 实验"),

        SettingsSearchEntry(R.string.theme_mode, SettingsPage.APPEARANCE, sectionRes = R.string.theme, keywords = "明暗 深色 浅色 跟随系统 dark light"),
        SettingsSearchEntry(R.string.theme_follows_cover_colors, SettingsPage.APPEARANCE, sectionRes = R.string.theme, keywords = "取色 封面 动态色 dynamic"),
        SettingsSearchEntry(R.string.car_mode, SettingsPage.APPEARANCE, sectionRes = R.string.car, keywords = "车载 车机 car driving"),

        SettingsSearchEntry(R.string.speed_and_pitch, SettingsPage.PLAYBACK, sectionRes = R.string.speed_and_pitch, keywords = "速度 变调 pitch tempo 倍速"),
        SettingsSearchEntry(R.string.resume_last_playback_on_launch, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywords = "续播 恢复 上次 auto resume"),
        SettingsSearchEntry(R.string.play_in_background_after_boot, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywords = "开机 自启 boot background"),
        SettingsSearchEntry(R.string.skip_silence, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywords = "静音 跳过 silence"),
        SettingsSearchEntry(R.string.skip_intro_seconds_title, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywords = "片头 intro 固定秒 跳过片头"),
        SettingsSearchEntry(R.string.skip_outro_seconds_title, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywords = "片尾 outro 固定秒 跳过片尾"),
        SettingsSearchEntry(R.string.gapless_playback, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywords = "无缝 gapless 连续"),
        SettingsSearchEntry(R.string.equalizer_and_effects, SettingsPage.PLAYBACK, keywords = "均衡器 音效 EQ equalizer 低音 混响 ReplayGain"),

        SettingsSearchEntry(R.string.tap_lyrics_to_seek, SettingsPage.LYRICS, sectionRes = R.string.player_lyrics, keywords = "点歌词 跳转 seek"),
        SettingsSearchEntry(R.string.home_screen_floating_lyrics, SettingsPage.OVERLAY, sectionRes = R.string.floating_lyrics, keywords = "悬浮 桌面歌词 overlay floating"),
        SettingsSearchEntry(R.string.floating_lyrics, SettingsPage.OVERLAY, keywords = "悬浮歌词 overlay"),

        SettingsSearchEntry(R.string.playback_stats, SettingsPage.LIBRARY, sectionRes = R.string.stats, keywords = "播放统计 stats"),
        SettingsSearchEntry(R.string.library_stats, SettingsPage.LIBRARY, sectionRes = R.string.stats, keywords = "格式 容量 无损 有损"),
        SettingsSearchEntry(R.string.short_audio_filter, SettingsPage.LIBRARY, sectionRes = R.string.local_library, keywords = "短音频 过滤 秒"),
        SettingsSearchEntry(R.string.clean_up_missing_songs, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywords = "清理 缺失 失效"),
        SettingsSearchEntry(R.string.restore_songs_removed_from_library, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywords = "恢复 移出"),
        SettingsSearchEntry(R.string.duplicate_cleanup, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywords = "重复 去重 duplicate"),
        SettingsSearchEntry(R.string.clear_cover_cache, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywords = "封面 缓存 cache"),

        SettingsSearchEntry(R.string.check_for_updates, SettingsPage.UPDATE, sectionRes = R.string.about, keywords = "检查更新 update"),
        SettingsSearchEntry(R.string.diagnostics_troubleshooting, SettingsPage.DIAGNOSIS, sectionRes = R.string.about, keywords = "诊断 排障 调试 debug"),
        SettingsSearchEntry(R.string.check_for_updates_on_launch, SettingsPage.UPDATE, sectionRes = R.string.update, keywords = "启动检查 自动更新"),
        SettingsSearchEntry(R.string.download_route, SettingsPage.UPDATE, sectionRes = R.string.update, keywords = "下载 线路 CDN"),

        SettingsSearchEntry(R.string.set_lab_online_title, SettingsPage.ONLINE_HUB, sectionRes = R.string.set_lab_title, keywords = "在线播放 缓存"),
        SettingsSearchEntry(R.string.set_lab_menu_order_title, SettingsPage.MENU_ORDER, sectionRes = R.string.set_lab_title, keywords = "菜单顺序 分类卡 排序"),
        SettingsSearchEntry(R.string.online_playlists_m3u, SettingsPage.ONLINE, sectionRes = R.string.sources, keywords = "在线歌单 m3u m3u8 pls"),
        SettingsSearchEntry(R.string.cache_limit, SettingsPage.ONLINE_HUB, sectionRes = R.string.playback_cache, keywords = "缓存上限"),
        SettingsSearchEntry(R.string.lib_listening_report, SettingsPage.LIBRARY, sectionRes = R.string.playback_history, keywords = "收听报告 听歌"),
    )
}

object SettingsSearch {
    fun filter(
        entries: List<SettingsSearchEntry>,
        query: String,
        resolve: (Int) -> String,
    ): List<SettingsSearchEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val title = resolve(entry.titleRes)
            val section = entry.sectionRes?.let(resolve).orEmpty()
            val haystack = "$title $section ${entry.keywords}".lowercase()
            haystack.contains(q)
        }
    }

    /** 片头/片尾标题带 %d，搜索展示用 0 占位。 */
    fun resolveTitle(context: android.content.Context, @StringRes resId: Int): String {
        return try {
            when (resId) {
                R.string.skip_intro_seconds_title,
                R.string.skip_outro_seconds_title,
                -> context.getString(resId, 0)
                else -> context.getString(resId)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
