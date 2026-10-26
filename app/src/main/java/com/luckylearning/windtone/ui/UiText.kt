package com.luckylearning.windtone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.ThemeMode
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlayMode

/**
 * 枚举 → 展示文案的**唯一解析处**（i18n 批次）。
 *
 * 为什么不在枚举里直接写中文：`enum class X(val label: String)` 会把界面文案
 * 钉死在数据层，既没法走 `values-zh` / 后续多语言，也让 `data/` 包沾上 UI 关注点。
 * 这里用「枚举值 → 资源 id」的**纯函数** [labelRes] 收口映射（JVM 单测可直接断言，
 * 不需要 Android 资源环境），再用 `@Composable` 的 `displayName()` 取字符串。
 *
 * 新增语言时只需改 `res/values-xx/strings.xml`，本文件不用动。
 */

internal fun LibraryView.labelRes(): Int = when (this) {
    LibraryView.SONGS -> R.string.view_songs
    LibraryView.ALBUM -> R.string.view_albums
    LibraryView.ARTIST -> R.string.view_artists
    LibraryView.FOLDER -> R.string.view_folders
    LibraryView.PLAYLIST -> R.string.view_playlists
}

/**
 * 排序项在某个视图下的资源 id（同一个枚举在不同视图里叫法不同，如文件夹视图下
 * TITLE 显示成「按文件夹名」）。
 */
internal fun SortOrder.labelRes(view: LibraryView): Int = when {
    view == LibraryView.FOLDER && this == SortOrder.TITLE -> R.string.sort_folder_name
    view == LibraryView.ALBUM && this == SortOrder.ALBUM -> R.string.sort_album_name
    view == LibraryView.ARTIST && this == SortOrder.ARTIST -> R.string.sort_artist_name
    else -> when (this) {
        SortOrder.TITLE -> R.string.sort_title
        SortOrder.ARTIST -> R.string.sort_artist
        SortOrder.ALBUM -> R.string.sort_album
        SortOrder.DURATION -> R.string.sort_duration
        SortOrder.DATE_ADDED -> R.string.sort_date_added
        SortOrder.FILE_NAME -> R.string.sort_file_name
        SortOrder.FOLDER_PATH -> R.string.sort_folder_path
        SortOrder.PLAYLIST_ORDER -> R.string.sort_playlist_order
    }
}

internal fun PlayMode.labelRes(): Int = when (this) {
    PlayMode.SEQUENTIAL -> R.string.play_mode_sequential
    PlayMode.REPEAT_ALL -> R.string.play_mode_repeat_all
    PlayMode.REPEAT_ONE -> R.string.play_mode_repeat_one
    PlayMode.SHUFFLE -> R.string.play_mode_shuffle
}

internal fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

@Composable
internal fun LibraryView.displayName(): String = stringResource(labelRes())

@Composable
internal fun SortOrder.displayName(view: LibraryView): String = stringResource(labelRes(view))

@Composable
internal fun PlayMode.displayName(): String = stringResource(labelRes())

@Composable
internal fun ThemeMode.displayName(): String = stringResource(labelRes())
