package com.anylyric.session

/**
 * 一个活跃媒体会话的展示快照（选择「播放器频道」用）。
 *
 * 从 `SessionHub` 里提出来放到顶层：它现在是 `SessionPin.collapse` 的入参，
 * 而 `SessionPin` 要能脱离 `MediaController` 单测 —— 嵌在 `SessionHub` 里
 * 会让纯逻辑文件反向依赖整个会话中枢。
 */
data class SessionInfo(
    val packageName: String,
    val title: String?,
    val playing: Boolean
)
