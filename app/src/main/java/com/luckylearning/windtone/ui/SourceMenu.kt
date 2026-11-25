package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.SourceMode
import com.luckylearning.windtone.state.LibraryView

/**
 * 抽屉 / 车机左导航的菜单。按音源模式整组切换，不是在同一份全量菜单上打隐藏开关。
 *
 * 每个模式只放自己的内容项。设置对三种模式都在（它是换模式的地方）。
 * 扫描范围只属于本地曲库：洛雪和云盘没有「扫盘」这回事。
 * 跨模式入口（本地里挂洛雪、洛雪里挂云盘和「歌曲 / 我的列表」）不放进来 ——
 * 那些入口会让用户在洛雪里点一下就掉进另一个模式的页面。
 */
sealed class SourceMenuItem {
    abstract val id: String

    data class Library(val view: LibraryView) : SourceMenuItem() {
        override val id: String = "view:${view.name}"
    }

    data class Lx(val section: LxSection) : SourceMenuItem() {
        override val id: String = "lx:${section.name}"
    }

    data object Aliyun : SourceMenuItem() {
        override val id: String = "aliyun"
    }

    data object ScanScope : SourceMenuItem() {
        override val id: String = "scan"
    }

    data object Settings : SourceMenuItem() {
        override val id: String = "settings"
    }
}

data class SourceMenuGroup(
    val labelRes: Int,
    val items: List<SourceMenuItem>,
)

object SourceMenus {

    fun groups(mode: SourceMode): List<SourceMenuGroup> = when (mode) {
        SourceMode.LOCAL -> listOf(
            SourceMenuGroup(
                R.string.drawer_group_library,
                LibraryView.entries.map { SourceMenuItem.Library(it) },
            ),
            SourceMenuGroup(
                R.string.drawer_group_more,
                listOf(SourceMenuItem.ScanScope, SourceMenuItem.Settings),
            ),
        )
        SourceMode.CLOUD -> listOf(
            SourceMenuGroup(
                R.string.drawer_group_online,
                listOf(SourceMenuItem.Aliyun),
            ),
            SourceMenuGroup(
                R.string.drawer_group_more,
                listOf(SourceMenuItem.Settings),
            ),
        )
        SourceMode.LX -> listOf(
            SourceMenuGroup(
                R.string.drawer_group_lx,
                LxSection.entries.map { SourceMenuItem.Lx(it) },
            ),
            SourceMenuGroup(
                R.string.drawer_group_more,
                listOf(SourceMenuItem.Settings),
            ),
        )
    }

    fun subtitleRes(mode: SourceMode): Int = when (mode) {
        SourceMode.LOCAL -> R.string.drawer_brand_subtitle
        SourceMode.CLOUD -> R.string.drawer_brand_cloud
        SourceMode.LX -> R.string.drawer_brand_lx
    }

    fun isSelected(
        item: SourceMenuItem,
        mode: SourceMode,
        currentView: LibraryView,
        aliyunActive: Boolean,
        lxSection: LxSection,
    ): Boolean = when (item) {
        is SourceMenuItem.Library ->
            mode == SourceMode.LOCAL && !aliyunActive && item.view == currentView
        is SourceMenuItem.Lx ->
            mode == SourceMode.LX && item.section == lxSection
        SourceMenuItem.Aliyun ->
            mode == SourceMode.CLOUD && aliyunActive
        SourceMenuItem.ScanScope, SourceMenuItem.Settings -> false
    }

    fun ids(mode: SourceMode): List<String> = groups(mode).flatMap { g -> g.items.map { it.id } }
}
