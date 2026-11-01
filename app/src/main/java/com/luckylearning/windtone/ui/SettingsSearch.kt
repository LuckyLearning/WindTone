package com.luckylearning.windtone.ui

import androidx.annotation.StringRes
import com.luckylearning.windtone.R

/**
 * 设置页内搜索（G8 / v1.2.33）。
 *
 * 轻量本地过滤：条目表覆盖六大分区卡 + 各分区主要设置项的标题/分区名/关键词；
 * 不做全文索引、不改子页结构。空查询由 UI 显示原分类卡；无命中显示空态。
 *
 * 关键词进 `ss_kw_*`（i18n 第 23 批）：**中英同串**写在 values / values-zh，
 * 保证英文 ROM 也能用中文俗称命中、反之亦然。
 */
data class SettingsSearchEntry(
    @StringRes val titleRes: Int,
    val page: SettingsPage,
    @StringRes val sectionRes: Int? = null,
    /** 额外关键词资源（中英别名、俗称），仅参与匹配、不展示；0 = 无 */
    @StringRes val keywordsRes: Int = 0,
)

object SettingsSearchCatalog {
    fun entries(): List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry(R.string.appearance, SettingsPage.APPEARANCE, keywordsRes = R.string.ss_kw_appearance),
        SettingsSearchEntry(R.string.playback, SettingsPage.PLAYBACK, keywordsRes = R.string.ss_kw_playback),
        SettingsSearchEntry(R.string.lyrics, SettingsPage.LYRICS, keywordsRes = R.string.ss_kw_lyrics),
        SettingsSearchEntry(R.string.library_and_storage, SettingsPage.LIBRARY, keywordsRes = R.string.ss_kw_library),
        SettingsSearchEntry(R.string.about_updates, SettingsPage.ABOUT, keywordsRes = R.string.ss_kw_about),
        SettingsSearchEntry(R.string.set_lab_title, SettingsPage.LAB, keywordsRes = R.string.ss_kw_lab),

        SettingsSearchEntry(R.string.theme_mode, SettingsPage.APPEARANCE, sectionRes = R.string.theme, keywordsRes = R.string.ss_kw_theme_mode),
        SettingsSearchEntry(R.string.theme_follows_cover_colors, SettingsPage.APPEARANCE, sectionRes = R.string.theme, keywordsRes = R.string.ss_kw_theme_cover),
        SettingsSearchEntry(R.string.car_mode, SettingsPage.APPEARANCE, sectionRes = R.string.car, keywordsRes = R.string.ss_kw_car_mode),
        SettingsSearchEntry(R.string.bt_auto_car_enabled, SettingsPage.APPEARANCE, sectionRes = R.string.car, keywordsRes = R.string.ss_kw_bt_auto_car),

        SettingsSearchEntry(R.string.speed_and_pitch, SettingsPage.PLAYBACK, sectionRes = R.string.speed_and_pitch, keywordsRes = R.string.ss_kw_speed_pitch),
        SettingsSearchEntry(R.string.resume_last_playback_on_launch, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywordsRes = R.string.ss_kw_resume),
        SettingsSearchEntry(R.string.play_in_background_after_boot, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywordsRes = R.string.ss_kw_boot_bg),
        SettingsSearchEntry(R.string.skip_silence, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywordsRes = R.string.ss_kw_skip_silence),
        SettingsSearchEntry(R.string.skip_intro_seconds_title, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywordsRes = R.string.ss_kw_skip_intro),
        SettingsSearchEntry(R.string.skip_outro_seconds_title, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywordsRes = R.string.ss_kw_skip_outro),
        SettingsSearchEntry(R.string.gapless_playback, SettingsPage.PLAYBACK, sectionRes = R.string.playback_behavior, keywordsRes = R.string.ss_kw_gapless),
        SettingsSearchEntry(R.string.equalizer_and_effects, SettingsPage.PLAYBACK, keywordsRes = R.string.ss_kw_eq),

        SettingsSearchEntry(R.string.tap_lyrics_to_seek, SettingsPage.LYRICS, sectionRes = R.string.player_lyrics, keywordsRes = R.string.ss_kw_tap_lyrics),
        SettingsSearchEntry(R.string.home_screen_floating_lyrics, SettingsPage.OVERLAY, sectionRes = R.string.floating_lyrics, keywordsRes = R.string.ss_kw_home_overlay),
        SettingsSearchEntry(R.string.floating_lyrics, SettingsPage.OVERLAY, keywordsRes = R.string.ss_kw_floating),

        SettingsSearchEntry(R.string.playback_stats, SettingsPage.LIBRARY, sectionRes = R.string.stats, keywordsRes = R.string.ss_kw_playback_stats),
        SettingsSearchEntry(R.string.library_stats, SettingsPage.LIBRARY, sectionRes = R.string.stats, keywordsRes = R.string.ss_kw_library_stats),
        SettingsSearchEntry(R.string.short_audio_filter, SettingsPage.LIBRARY, sectionRes = R.string.local_library, keywordsRes = R.string.ss_kw_short_audio),
        SettingsSearchEntry(R.string.clean_up_missing_songs, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywordsRes = R.string.ss_kw_clean_missing),
        SettingsSearchEntry(R.string.restore_songs_removed_from_library, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywordsRes = R.string.ss_kw_restore),
        SettingsSearchEntry(R.string.duplicate_cleanup, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywordsRes = R.string.ss_kw_duplicate),
        SettingsSearchEntry(R.string.clear_cover_cache, SettingsPage.LIBRARY, sectionRes = R.string.data_storage, keywordsRes = R.string.ss_kw_cover_cache),

        SettingsSearchEntry(R.string.check_for_updates, SettingsPage.UPDATE, sectionRes = R.string.about, keywordsRes = R.string.ss_kw_check_updates),
        SettingsSearchEntry(R.string.diagnostics_troubleshooting, SettingsPage.DIAGNOSIS, sectionRes = R.string.about, keywordsRes = R.string.ss_kw_diagnostics),
        SettingsSearchEntry(R.string.check_for_updates_on_launch, SettingsPage.UPDATE, sectionRes = R.string.update, keywordsRes = R.string.ss_kw_update_on_launch),
        SettingsSearchEntry(R.string.download_route, SettingsPage.UPDATE, sectionRes = R.string.update, keywordsRes = R.string.ss_kw_download_route),

        SettingsSearchEntry(R.string.set_lab_online_title, SettingsPage.ONLINE_HUB, sectionRes = R.string.set_lab_title, keywordsRes = R.string.ss_kw_lab_online),
        SettingsSearchEntry(R.string.set_lab_menu_order_title, SettingsPage.MENU_ORDER, sectionRes = R.string.set_lab_title, keywordsRes = R.string.ss_kw_menu_order),
        SettingsSearchEntry(R.string.online_playlists_m3u, SettingsPage.ONLINE, sectionRes = R.string.sources, keywordsRes = R.string.ss_kw_online_m3u),
        SettingsSearchEntry(R.string.cache_limit, SettingsPage.ONLINE_HUB, sectionRes = R.string.playback_cache, keywordsRes = R.string.ss_kw_cache_limit),
        SettingsSearchEntry(R.string.online_cache_prefetch, SettingsPage.ONLINE_HUB, sectionRes = R.string.playback_cache, keywordsRes = R.string.ss_kw_online_cache_prefetch),
        SettingsSearchEntry(R.string.online_parallel_download, SettingsPage.ONLINE_HUB, sectionRes = R.string.playback_cache, keywordsRes = R.string.ss_kw_online_parallel_download),
        SettingsSearchEntry(R.string.lib_listening_report, SettingsPage.LIBRARY, sectionRes = R.string.playback_history, keywordsRes = R.string.ss_kw_listening_report),
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
            val keywords = if (entry.keywordsRes != 0) resolve(entry.keywordsRes) else ""
            val haystack = "$title $section $keywords".lowercase()
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
