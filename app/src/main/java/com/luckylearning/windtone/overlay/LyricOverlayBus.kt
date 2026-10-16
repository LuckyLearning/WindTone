package com.luckylearning.windtone.overlay

import com.luckylearning.windtone.lyrics.OverlayContentResolver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 悬浮歌词的状态总线（进程内单例）。
 *
 * 为什么要有这一层：
 * 悬浮窗服务在**独立于界面的生命周期**里活着——用户退到桌面 / 导航页时，
 * Activity 早就 `onStop` 了，`PlayerViewModel` 的 `viewModelScope` 也不一定还在跑。
 * 而悬浮窗要显示的是「当前歌词行」，这个信息只有 ViewModel 侧（订阅了播放进度和
 * 歌词数据）最清楚。所以约定：**播放域单向写入，服务单向读取**，
 * 彼此不持有引用，谁先退出都不会把对方拖死。
 *
 * 唯一的反向通道是 [exitEditingRequests]：服务判定「调整态长时间没人动」时
 * 请求播放域把开关状态收回去——服务不能直接改播放域的 `PlayerUiState`。
 *
 * 除了内容本身，还承载三个**开关型状态**（都不进 Prefs，是会话级的）：
 *  - [setEditing]：是否处于「调整位置」模式。此模式下窗口临时恢复触摸能力、可拖动；
 *  - [setForeground]：App 是否在前台。前台时窗口整体隐藏——此时用户正看着
 *    播放页的歌词，再叠一层桌面字幕纯属重复。
 *  - [bumpAppearance]：外观配置（字号 / 底板 / 暂停行为）变了的通知。
 *    服务是独立存活的，不能依赖界面把新配置推过来，所以它每帧直接读 Prefs；
 *    但「读」需要一个触发点，否则在设置页改完字号要等到下一句歌词才生效。
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
     * 内容侧的所有决策（前奏回落歌名、间奏回溯上一句、上下文行、双语副行）
     * 都由 [OverlayContentResolver] 算好，这里只负责搬运——**服务不做任何歌词判断**，
     * 它只决定「怎么画」。
     */
    data class Content(
        val resolved: OverlayContentResolver.Resolved = OverlayContentResolver.Resolved(),
        /** 歌曲是否在播放；暂停时淡出（或按设置隐藏），方便用户看清最后一句 */
        val playing: Boolean = false,
    ) {
        val visible: Boolean get() = resolved.visible
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

    /** 每次 [resolved] 变化递增；服务用它做去重 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 是否处于「调整位置」模式（可拖动、吃触摸、显示蓝色描边） */
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

    /** 外观配置（字号 / 底板 / 暂停行为）的版本号；服务把它并进 collect，配置改完立即重绘 */
    private val _appearance = MutableStateFlow(0)
    val appearance: StateFlow<Int> = _appearance.asStateFlow()

    /** 悬浮窗健康状态；服务在关键节点上报，界面与播放域据此反馈/回退 */
    private val _health = MutableStateFlow(Health())
    val health: StateFlow<Health> = _health.asStateFlow()

    /**
     * 服务 → 播放域的一次性请求：把「调整位置」模式收回去。
     *
     * 为什么需要反向通道：调整态长时间没人动意味着用户大概率忘了点「完成调整」，
     * 而此刻窗口是**吃触摸**的——退到桌面就会有一条挡操作的横条。
     * 服务只能判定「该收了」，但状态归播放域所有，所以发个请求让它来收。
     */
    private val _exitEditingRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitEditingRequests: SharedFlow<Unit> = _exitEditingRequests.asSharedFlow()

    /** 播放域侧调用：把解析好的当前内容送进悬浮窗 */
    fun publish(resolved: OverlayContentResolver.Resolved, playing: Boolean) {
        val old = _content.value
        if (old.resolved == resolved && old.playing == playing) return
        // 内容（或播放态）变了才算一次修订。
        //
        // ⚠️ 这里原先有一条「纯位置推进（逐字染色）不算修订」的特殊分支 ——
        // 位置在 v1.1.8 随逐字染色一起移除，于是**任何**变化都是内容变化，
        // 那条分支自然消失。新增高频字段进 [Content] 前想清楚这一点：
        // 只要它每 200ms 变一次，依赖 revision 的消费者就会被叫醒 5 次/秒。
        _content.value = Content(resolved, playing)
        _revision.update { it + 1 }
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

    /** 外观配置变化（设置页改字号 / 底板 / 暂停行为时调用），让服务立刻重绘 */
    fun bumpAppearance() {
        _appearance.update { it + 1 }
    }

    /** 服务在启动 / 挂窗成功 / 失败等节点调用 */
    fun report(status: Status, detail: String = "") {
        _health.value = Health(status, detail)
    }

    /** 服务请求退出调整态（长时间无操作）；由播放域收集处理 */
    fun requestExitEditing() {
        _exitEditingRequests.tryEmit(Unit)
    }

    /** 服务停止 / 功能关闭时清空，避免残留上一句 */
    fun clear() {
        _editing.value = false
        _preview.value = false
        if (_content.value.resolved.current.isEmpty()) return
        _content.value = Content()
        _revision.update { it + 1 }
    }
}
