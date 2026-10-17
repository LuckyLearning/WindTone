package com.luckylearning.windtone.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面入口的动作登记处（批次 9.4 启动器快捷方式 + 批次 9.5 小组件深链）。
 *
 * ## 三个静态快捷方式
 *
 * 长按图标 → 播放全部 / 随机播放 / 我的收藏，定义在 `res/xml/shortcuts.xml`。
 * 不做动态 shortcut —— 那套要维护"最近播放的歌单"，而本应用连"歌单最近打开时间"
 * 都没落盘，为了一个没被要求过的入口加一列数据不值得（见文档「批次 9 遗留」）。
 *
 * ## 小组件深链
 *
 * 桌面小组件（`MusicWidgetProvider`）刻意复用**同一条通道**：封面 → 播放页、
 * 歌名 → 曲库、歌手 → 歌单。复用而不是新开一套 `PendingIntent` → `Intent extra`
 * 解析的好处是只有一处"待处理动作"的消费逻辑，不会出现小组件能跳、快捷方式不能跳
 * 这类不一致。
 *
 * ## 为什么不直接在 Activity 里执行
 *
 * 冷启动时曲库还在从 Room 缓存往内存里异步装载，此刻 `playQueue` 拿到的是空列表 ——
 * 用户看到的现象是"点了快捷方式，应用开了，但什么都没播"，而且没有任何提示。
 * 所以这里只**记下意图**，由 `MainScreen` 在曲库就绪之后消费一次。
 *
 * 用 [StateFlow] 而不是普通变量：`onNewIntent`（应用已在前台/后台时再点快捷方式）
 * 发生在 Compose 之外，普通变量变了界面不会重组，那个 `LaunchedEffect` 就不会重跑。
 */
object AppShortcut {

    /** 入口专用 action；与 AndroidManifest 的 `<intent>`、`shortcuts.xml`、小组件三处必须一致 */
    const val ACTION = "com.luckylearning.windtone.SHORTCUT"

    /** `<extra name="shortcut">` 的键 */
    const val EXTRA = "shortcut"

    const val PLAY_ALL = "play_all"
    const val SHUFFLE = "shuffle"
    const val FAVORITES = "favorites"

    // ---- 小组件深链（批次 9.5） ----
    /** 点小组件封面 → 直接进播放页 */
    const val OPEN_PLAYER = "open_player"

    /** 点小组件歌名 → 曲库歌曲列表 */
    const val OPEN_LIBRARY = "open_library"

    /** 点小组件歌手 → 歌单列表 */
    const val OPEN_PLAYLISTS = "open_playlists"

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** 从 Intent 里解析动作名；不是本通道传入时返回 null */
    fun from(intent: Intent?): String? {
        val i = intent ?: return null
        if (i.action != ACTION) return null
        return i.getStringExtra(EXTRA)?.takeIf { it in VALID }
    }

    /** 记下一个待处理动作（`MainActivity.onCreate` / `onNewIntent` 调用） */
    fun put(action: String?) {
        if (action != null) _pending.value = action
    }

    /** 取走待处理动作；取走即清空，保证同一次点击只执行一次 */
    fun consumePending(): String? {
        val v = _pending.value
        _pending.value = null
        return v
    }

    /**
     * 这个动作现在能不能执行（曲库还没就绪时必须**先不消费**，等下一次状态变化再试）。
     *
     * 抽成纯函数是为了能单测：这条判据原先写在 `MainScreen` 的 `LaunchedEffect` 里，
     * 只看得到「已就绪」的几种正向信号，而漏掉了「还没开始」的负向信号，于是
     * v1.1.3 ~ v1.1.12 一直有个隐藏故障 —— 见下面那段。
     *
     * ## 判据只能用「结论」，不能用「还没发生」
     *
     * 早期判据是 `songs 非空 || 扫描范围未选 || 不在扫描`。看着挺全，但那三条在
     * **冷启动第一帧**居然同时"看着像"成立：`songs` 当然还是空的，
     * `scanning` 也还没开始扫（false），`scanScope` 是上次选过的值……第三条
     * （不在扫描）因此为真 → 判成"就绪" → 动作被消费掉 → `playQueue(空)` 被
     * `startQueue` 的 `if (queue.isEmpty()) return` 静默吞掉。
     * 用户看到的现象是「点了快捷方式，应用开了，什么也没播」，
     * 「打开我的收藏」还因为别的原因落到歌单列表页（见 `MainScreen` 那一段）。
     *
     * 所以现在只认曲库自己报出来的结论：[songsLoaded]（首批装载已结束）与
     * [playlistsLoaded]（虚拟歌单曲目已算过）都由 `LibraryCore` / `PlaylistStore`
     * 在**真的算完之后**才置位。
     *
     * @param songsLoaded `LibraryUiState.libraryLoaded`
     * @param hasSongs `songs` 非空
     * @param scanning 正在扫描
     * @param playlistsLoaded `LibraryUiState.playlistsLoaded`
     */
    fun canRun(
        action: String,
        songsLoaded: Boolean,
        hasSongs: Boolean,
        scanning: Boolean,
        playlistsLoaded: Boolean,
    ): Boolean = when (action) {
        // 小组件深链只是切界面，不碰曲库，随时都能跳
        OPEN_PLAYER, OPEN_LIBRARY, OPEN_PLAYLISTS -> true

        // 有歌就走；没歌则等装载给出结论（扫描中要继续等 —— 扫完可能就有了），
        // 结论是"确实没有"时才放行，让界面去弹那句提示。
        PLAY_ALL, SHUFFLE -> hasSongs || (songsLoaded && !scanning)

        // 「打开我的收藏」还要求虚拟歌单的曲目算完，否则会打开一个空收藏夹
        FAVORITES -> (hasSongs || (songsLoaded && !scanning)) && playlistsLoaded

        else -> true
    }

    private val VALID = setOf(
        PLAY_ALL, SHUFFLE, FAVORITES,
        OPEN_PLAYER, OPEN_LIBRARY, OPEN_PLAYLISTS,
    )
}

