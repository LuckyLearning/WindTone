package com.luckylearning.windtone.data

import com.luckylearning.windtone.R

/**
 * 音源模式：设置页的一个下拉框决定曲线库入口与内容页。
 *
 * 三档互斥，不是三个独立开关——用户实际的使用场景是「这段时间只听某一种」，
 * 三个开关会让抽屉里同时出现三条入口、谁高亮说不清（v1.1.75 云盘升一级时踩过）。
 */
enum class SourceMode {
    /** 本机曲库（默认） */
    LOCAL,

    /** 阿里云盘 */
    CLOUD,

    /** 洛雪源（自定义源脚本 + 内置搜索） */
    LX,
    ;

    /** 文案资源走 UiText 体系的同一约定，避免字符串散落在 Compose 里 */
    fun labelRes(): Int = when (this) {
        LOCAL -> R.string.source_mode_local
        CLOUD -> R.string.source_mode_cloud
        LX -> R.string.source_mode_lx
    }
}

/**
 * 收听报告的查看范围：全部合并 + 三种音源各自单看。
 *
 * 与 [SourceMode] 分开是因为报告多一个「全部」档，且它不写入任何偏好——
 * 只是当前这一屏的筛选，退出报告就回到默认。
 */
enum class ReportFilter {
    ALL, LOCAL, LX, CLOUD;

    fun labelRes(): Int = when (this) {
        ALL -> R.string.online_filter_all
        LOCAL -> R.string.source_mode_local
        LX -> R.string.source_mode_lx
        CLOUD -> R.string.source_mode_cloud
    }

    /** listen_log 的 source 列取值；ALL 表示不按来源过滤 */
    val sourceName: String? get() = when (this) {
        ALL -> null
        LOCAL -> SourceMode.LOCAL.name
        LX -> SourceMode.LX.name
        CLOUD -> SourceMode.CLOUD.name
    }
}
