package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.ScanScope

/**
 * 主界面的**内容区路由**（P2-4）。
 *
 * 原来这段判断直接写在 `MainScreen` 的 `when { ... }` 里，条件与界面代码缠在一起，
 * 想验证「什么时候该显示扫描引导 / 详情 / 列表」只能靠读代码。抽成一个纯函数后：
 *  - 主界面只负责「按路由渲染」，不再散落条件；
 *  - 路由优先级可以单测钉死，后续再加页面也不必改一屏的 `if`。
 *
 * 只描述**主内容区**。设置页、播放页、各类弹窗是覆盖层，不在这条路由里。
 */
internal enum class MainRoute {
    /** 首次进入 / 移除全部目录后：等待用户指定扫描范围 */
    SCAN_SETUP,

    /** 正在扫描且曲库为空 */
    SCANNING,

    /** 歌单详情 */
    PLAYLIST_DETAIL,

    /** 专辑 / 歌手 / 文件夹详情 */
    GROUP_DETAIL,

    /** 一级列表 */
    LIBRARY,
}

/**
 * 按优先级决定主内容区显示什么。
 *
 * 顺序即优先级：未选扫描范围且曲库为空 > 首次扫描中 > 歌单详情 > 分组详情 > 一级列表。
 */
internal fun mainRouteOf(
    scanScope: ScanScope,
    songsEmpty: Boolean,
    scanning: Boolean,
    hasSelectedPlaylist: Boolean,
    hasSelectedGroup: Boolean,
): MainRoute = when {
    scanScope == ScanScope.UNSET && songsEmpty -> MainRoute.SCAN_SETUP
    scanning && songsEmpty -> MainRoute.SCANNING
    hasSelectedPlaylist -> MainRoute.PLAYLIST_DETAIL
    hasSelectedGroup -> MainRoute.GROUP_DETAIL
    else -> MainRoute.LIBRARY
}
