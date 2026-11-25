package com.luckylearning.windtone.ui

import com.luckylearning.windtone.R

/**
 * 洛雪音源的一级分区。手机抽屉与车机左导航共用这一份，页面不再用顶栏 Tab。
 *
 * 顺序就是抽屉里的顺序，也是「同层切换时往哪边滑」的依据。
 */
enum class LxSection(val labelRes: Int) {
    SEARCH(R.string.online_tab_search),
    SHEETS(R.string.online_tab_sheets),
    BOARDS(R.string.online_tab_boards),
    SOURCES(R.string.online_manage_sources),
}

/**
 * 分区上的子页。
 *
 * 搜索结果、歌单详情、榜单详情都是子页：返回先退子页，再退上一个分区。
 * 音源管理本身是分区，没有再套一层子页。
 */
enum class LxDetail {
    NONE,
    RESULTS,
    SHEET,
    BOARD,
}

data class LxFrame(
    val section: LxSection,
    val detail: LxDetail = LxDetail.NONE,
)

/**
 * 洛雪返回栈。纯函数，手机系统返回、顶栏返回、抽屉点选走同一份。
 *
 * - 打开子页：改当前帧的 [LxFrame.detail]，返回先把 detail 清掉。
 * - 抽屉点另一个分区：压一帧。点栈里已经有的分区：退回到那一帧
 *   （再点当前分区则只退出它的子页），避免「搜索→歌单→搜索→歌单」堆出一条退不完的历史。
 * - 底下那一帧的子页保留。从搜索结果进歌单再返回，回到的是搜索结果，而不是空搜索框。
 * - 只剩搜索根帧时 [back] 返回 null：这一下不再由洛雪消费，Activity 回到桌面。
 *   不能在这里把在线页关掉换成本地曲库 —— 那会跳过用户以为还在的这一层。
 */
object LxBackStack {

    fun root(): List<LxFrame> = listOf(LxFrame(LxSection.SEARCH))

    fun select(stack: List<LxFrame>, section: LxSection): List<LxFrame> {
        val base = if (stack.isEmpty()) root() else stack
        val idx = base.indexOfLast { it.section == section }
        if (idx >= 0) {
            val frame = base[idx]
            val shown = if (idx == base.lastIndex) frame.copy(detail = LxDetail.NONE) else frame
            return base.subList(0, idx) + shown
        }
        return base + LxFrame(section)
    }

    fun openDetail(stack: List<LxFrame>, detail: LxDetail): List<LxFrame> {
        if (detail == LxDetail.NONE) return if (stack.isEmpty()) root() else stack
        val base = if (stack.isEmpty()) root() else stack
        val top = base.last()
        if (top.detail == detail) return base
        return base.dropLast(1) + top.copy(detail = detail)
    }

    /** null = 已经在根上，调用方不要再消费这次返回。 */
    fun back(stack: List<LxFrame>): List<LxFrame>? {
        val base = if (stack.isEmpty()) return null else stack
        val top = base.last()
        if (top.detail != LxDetail.NONE) {
            return base.dropLast(1) + top.copy(detail = LxDetail.NONE)
        }
        if (base.size > 1) return base.dropLast(1)
        return null
    }

    fun canPop(stack: List<LxFrame>): Boolean = back(stack) != null

    /**
     * 页面切换方向，给 [SettingsMotion] 用。
     * 同一分区钻进子页 = 向前；退出子页或退回更早的分区 = 向后。
     */
    fun forward(from: LxFrame, to: LxFrame): Boolean {
        if (from.section == to.section) {
            return to.detail != LxDetail.NONE && from.detail == LxDetail.NONE
        }
        return to.section.ordinal > from.section.ordinal
    }
}
