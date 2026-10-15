package com.luckylearning.windtone.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 悬浮歌词的状态总线（进程内单例）。
 *
 * 为什么要有这一层：
 * 悬浮窗服务在**独立于界面的生命周期**里活着——用户退到车机桌面 / 导航页时，
 * Activity 早就 `onStop` 了，`PlayerViewModel` 的 `viewModelScope` 也不一定还在跑。
 * 而悬浮窗要显示的是「当前歌词行」，这个信息只有 ViewModel 侧（订阅了播放进度和
 * 歌词数据）最清楚。所以约定：**ViewModel 单向写入，服务单向读取**，
 * 彼此不持有引用，谁先退出都不会把对方拖死。
 *
 * 除了内容本身，还承载两个**开关型状态**（都不进 Prefs，是会话级的）：
 *  - [setEditing]：是否处于「调整位置」模式。此模式下窗口临时恢复触摸能力、可拖动；
 *  - [setForeground]：App 是否在前台。前台时窗口整体隐藏——此时用户正看着
 *    播放页的歌词，再叠一层桌面字幕纯属重复。
 *
 * 以及 [preview] 与 [health] 两个**排障用**状态：
 *  - [setPreview]：刚打开开关时的几秒「前台预览」。不预览的话，用户在设置页
 *    点完开关什么变化都没有（按设计前台就该隐藏），会直接判定功能坏了；
 *  - [report]：悬浮窗起没起来、失败在哪一步。没有它，`addView` 失败被
 *    catch 吞掉后用户侧是彻底的静默，只能靠猜。
 */
object LyricOverlayBus {

    /**
     * 一条要显示的悬浮歌词。
     *
     * @param text 当前歌词行；空串表示暂无可显示内容（窗口应隐藏）
     * @param prevLine 上一行（车载模式显示成暗色上下文，手机模式忽略）
     * @param nextLine 下一行（同上）
     * @param playing 歌曲是否在播放；暂停时淡出，不直接消失，方便用户看清最后一句
     */
    data class Content(
        val text: String = "",
        val prevLine: String = "",
        val nextLine: String = "",
        val playing: Boolean = false,
    ) {
        val visible: Boolean get() = text.isNotBlank()
    }

    /** 悬浮窗的运行状态（供设置页展示，也供播放域做失败回退） */
    enum class Status { IDLE, RUNNING, FAILED }

    /**
     * @param status 当前状态
     * @param detail 给人看的一句话说明（失败原因 / 当前环节）
     */
    data class Health(
        val status: Status = Status.IDLE,
        val detail: String = "",
    )

    private val _content = MutableStateFlow(Content())
    val content: StateFlow<Content> = _content.asStateFlow()

    /** 每次 [text] 变化递增；服务用它做去重 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 是否处于「调整位置」模式（可拖动、吃触摸、显示虚线边框） */
    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    /** 是否处于「刚打开开关的前台预览」窗口期（见 [setPreview]） */
    private val _preview = MutableStateFlow(false)
    val preview: StateFlow<Boolean> = _preview.asStateFlow()

    /**
     * App 是否在前台。
     *
     * 默认 false —— 服务刚起来时 App 通常已经被切走（用户回到桌面看字幕），
     * 若默认 true 会先隐藏一下再显示，闪一下反而难看。
     */
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    /** 悬浮窗健康状态；服务在关键节点上报，界面与播放域据此反馈/回退 */
    private val _health = MutableStateFlow(Health())
    val health: StateFlow<Health> = _health.asStateFlow()

    /** ViewModel 侧调用：把当前歌词行（及上下文行）送进悬浮窗 */
    fun publish(text: String, playing: Boolean, prevLine: String = "", nextLine: String = "") {
        val newContent = Content(text.trim(), prevLine.trim(), nextLine.trim(), playing)
        val changed = newContent != _content.value
        _content.value = newContent
        if (changed) _revision.update { it + 1 }
    }

    /** 进入 / 退出「调整位置」模式 */
    fun setEditing(value: Boolean) {
        if (_editing.value != value) _editing.value = value
    }

    /** 进入 / 退出「前台预览」窗口期 */
    fun setPreview(value: Boolean) {
        if (_preview.value != value) _preview.value = value
    }

    /** App 前后台切换时调用 */
    fun setForeground(value: Boolean) {
        if (_foreground.value != value) _foreground.value = value
    }

    /** 服务在启动 / 挂窗成功 / 失败等节点调用 */
    fun report(status: Status, detail: String = "") {
        _health.value = Health(status, detail)
    }

    /** 服务停止 / 功能关闭时清空，避免残留上一句 */
    fun clear() {
        _editing.value = false
        _preview.value = false
        if (_content.value.text.isEmpty()) return
        _content.value = Content()
        _revision.update { it + 1 }
    }
}
