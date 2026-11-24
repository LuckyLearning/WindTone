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
