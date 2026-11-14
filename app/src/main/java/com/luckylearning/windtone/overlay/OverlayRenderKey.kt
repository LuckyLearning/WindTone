package com.luckylearning.windtone.overlay

/**
 * 悬浮窗「这一帧要不要刷字」的去重键（纯函数）。
 *
 * 进度 tick 仍可按时解析当前行；但 [LyricOverlayService.renderLines] 在键未变时
 * 跳过 Spanned 组装与 TextView.setText —— 跟词只在行/角色/样式入参真变时动 Window。
 *
 * 键覆盖：形态（全屏/单条）+ 强调（播放/调整/预览）+ 正文色/底板/当前行色/压暗
 * + 当前行与其他行的字号百分比、其他行颜色（v1.2.54）+ 各行角色与文案。
 * 缺任何一项都会在设置改色 / 改字号后被误跳过。
 */
internal object OverlayRenderKey {

    fun build(
        lines: List<OverlayTextComposer.StyledLine>,
        fullHeight: Boolean,
        opaque: Boolean,
        baseColor: Int,
        noBackdrop: Boolean,
        currentColor: Int,
        dimPercent: Int,
        currentScalePercent: Int = 100,
        contextScalePercent: Int = 100,
        contextColor: Int = 0,
    ): String = buildString(64 + lines.size * 24) {
        append(if (fullHeight) "F|" else "S|")
        append(if (opaque) "1|" else "0|")
        append(baseColor).append('|')
        append(if (noBackdrop) "N|" else "B|")
        append(currentColor).append('|')
        append(dimPercent).append('|')
        append(currentScalePercent).append('|')
        append(contextScalePercent).append('|')
        append(contextColor).append('|')
        for (line in lines) {
            append(line.role.name).append(':').append(line.text).append('\n')
        }
    }
}
