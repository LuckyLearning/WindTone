package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 歌曲排序方式。
 *
 * 注意：这里是**全集**，具体某个浏览视图能用哪几项由
 * `LibraryView.sortOptions` 裁剪（v1.1.4 起排序下沉到各视图，
 * 专辑列表不该出现「按时长」这种对它没意义的选项）。
 */
enum class SortOrder(val label: String) {
    TITLE("按标题"),
    ARTIST("按歌手"),
    ALBUM("按专辑"),
    DURATION("按时长"),
    DATE_ADDED("按添加时间"),
    FILE_NAME("按文件名"),

    /** 文件夹视图专用：按目录完整路径排（同名目录靠它才能稳定区分） */
    FOLDER_PATH("按路径"),

    /** 歌单视图专用：保持 M3U 里写死的顺序（用户自己拖出来的那个顺序） */
    PLAYLIST_ORDER("按歌单内顺序"),
}

/**
 * 各设置项的**出厂默认值**，集中放这里。
 *
 * 为什么要集中：设置页从 v1.1.4 起每项右侧都有「↺ 恢复默认」，如果默认值散在
 * `Prefs` 的 getter 和设置页的常量里两处，迟早会出现「重置按钮写回的值」
 * 与「首次安装拿到的值」不一致 —— 那种 bug 用户很难描述清楚。
 * **改默认值只改这里**，`Prefs` 的 getter 与设置页的重置都引用它。
 */
object SettingsDefaults {
    /** 「跟随主题」哨兵：颜色设置取这个值表示不指定固定色，交由主题 / 默认色决定 */
    const val COLOR_FOLLOW_THEME = Int.MIN_VALUE

    const val DYNAMIC_THEME = true
    const val CAR_MODE = false

    const val SPEED = 1.0f

    /** 变调默认 = 原调（半音）。合法区间见 `playback/Pitch` */
    const val PITCH_SEMITONES = 0

    const val SKIP_SILENCE = false

    const val LYRIC_SCALE = 1.0f
    const val LYRIC_WORD_BY_WORD = true

    /** 播放页逐字歌词「已唱部分」的颜色；[COLOR_FOLLOW_THEME] = 跟随主题色 */
    const val LYRIC_SUNG_COLOR = COLOR_FOLLOW_THEME

    const val LYRIC_OVERLAY = false
    const val OVERLAY_LINES = 1
    const val OVERLAY_TEXT_SCALE = 1

    /** 悬浮歌词水平对齐：0 居中 / 1 居左（取值与 `overlay/OverlayPositioner` 的 ALIGN_* 一致） */
    const val OVERLAY_ALIGN = 0
    const val OVERLAY_BACKDROP = 1
    const val OVERLAY_PAUSE_MODE = 0

    /**
     * 悬浮窗宽度（屏幕宽度的百分比，1..100）。
     *
     * ⚠️ **默认给 80% 而不是 100%** —— 窗口宽度与「横向能拖多远」是同一枚硬币的两面：
     * `OverlayPositioner` 的横向夹取上限是「屏宽 − 窗口宽」，**100% 时这个上限正好是 0**，
     * 窗口在横向上被钉死在最左边。哪怕拖动接线完全正确，用户看到的也只会是
     * 「悬浮窗只能上下移动」—— 这不是 bug，是几何必然（2026-10-16 被用户当作 bug 报回来）。
     * 留出这 20% 才让「拖到屏幕任意位置」这件事成立。
     */
    const val OVERLAY_WIDTH_PERCENT = 80

    /** 悬浮窗里未唱部分的文字颜色（纯白） */
    const val OVERLAY_TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** 悬浮窗里已唱部分的颜色（暖黄） */
    const val OVERLAY_SUNG_COLOR = 0xFFFFCD56.toInt()

    const val MIN_DURATION_SEC = 60
    const val AUTO_UPDATE = true
    val UPDATE_ROUTE = DownloadRoute.AUTO

    /** 悬浮窗宽度百分比的合法区间 */
    val OVERLAY_WIDTH_RANGE = 1..100
}

/**
 * 音乐库扫描范围。
 *
 * 默认 [UNSET]：安装后**不主动扫描任何位置**，等用户在界面上明确选择。
 *  - [FULL]：扫描全盘（系统媒体库 + 已授权时附带深度文件扫描）
 *  - [FOLDERS]：只扫描用户导入的目录（SAF）
 */
enum class ScanScope(val label: String) {
    UNSET("未选择"),
    FULL("全盘扫描"),
    FOLDERS("指定目录"),
}

/**
 * 更新包下载线路。
 *
 * 检查更新永远只访问 GitHub 官方地址（镜像有缓存延迟，会把刚发布的版本当成「已是最新」），
 * 只有**下载安装包**才允许走第三方加速镜像 —— 该选择由用户决定：
 *  - [AUTO]：直连优先，起步太慢自动切到镜像（默认，适合大多数网络）；
 *  - [DIRECT]：只用 GitHub 官方直链（最权威、最及时，但国内可能很慢）；
 *  - [GHPROXY_NET] / [GH_PROXY_COM]：固定走某条公共加速镜像（镜像只转发字节，不改内容）。
 *
 * [prefix] 为 `null` 表示「自动」（直链 + 全部镜像）；空串表示只走直链；
 * 其余为镜像前缀（给原始 github.com 地址拼前缀即生效）。
 */
enum class DownloadRoute(val label: String, val hint: String, val prefix: String?) {
    AUTO("自动", "直连优先，太慢会自动切换镜像", null),
    DIRECT("直连 GitHub", "只用官方地址，最及时；国内可能较慢", ""),
    GHPROXY_NET("ghproxy.net", "公共加速镜像，速度较快", "https://ghproxy.net/"),
    GH_PROXY_COM("gh-proxy.com", "公共加速镜像，ghproxy 不通时备用", "https://gh-proxy.com/"),
}

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    /** 扫描范围：默认未选择，装机后不自动扫描 */
    var scanScope: ScanScope
        get() = runCatching { ScanScope.valueOf(sp.getString(KEY_SCAN_SCOPE, ScanScope.UNSET.name)!!) }
            .getOrDefault(ScanScope.UNSET)
        set(value) = sp.edit().putString(KEY_SCAN_SCOPE, value.name).apply()

    /**
     * 用户是否**主动选过**扫描范围（含「移除全部目录后回到未选择」这种显式选择）。
     *
     * 用来区分两种 UNSET：
     *  - 从未选过（全新安装）→ 老版本升级兼容逻辑可以把「有缓存但没范围」当作全盘；
     *  - 主动清空（用户把目录都移除了）→ 绝不能再被兼容逻辑拽成全盘扫描。
     * 旧数据兼容：只要存过 scan_scope，就说明用户选过。
     */
    var scanScopeChosen: Boolean
        get() = sp.getBoolean(KEY_SCAN_SCOPE_CHOSEN, sp.contains(KEY_SCAN_SCOPE))
        set(value) = sp.edit().putBoolean(KEY_SCAN_SCOPE_CHOSEN, value).apply()

    /** 是否启用「深度扫描」（读取同目录 .lrc 歌词文件） */
    var deepScanEnabled: Boolean
        get() = sp.getBoolean(KEY_DEEP_SCAN, false)
        set(value) = sp.edit().putBoolean(KEY_DEEP_SCAN, value).apply()

    /**
     * 短音频过滤阈值（秒）。
     * 时长已知且小于该值的音频不会进入曲库（用于滤掉提示音、转场音效等）；
     * 0 表示不过滤。默认 60 秒。时长未知的歌曲不受影响。
     */
    var minSongDurationSec: Int
        get() = sp.getInt(KEY_MIN_DURATION_SEC, DEFAULT_MIN_DURATION_SEC)
        set(value) = sp.edit().putInt(KEY_MIN_DURATION_SEC, value.coerceIn(0, 3600)).apply()

    /**
     * 某个浏览视图的排序方式 —— **v1.1.4 起按视图各存一份**。
     *
     * 之前是全局一份：在「专辑」里改成按歌手，切回「歌曲」也跟着变了。两个视图的
     * 排序诉求本来就不同（歌曲看标题、专辑看专辑名），共用一份只会互相打架。
     *
     * 旧的全局键 [KEY_SORT] 作兼容回落：新键还没写过时读它，老用户既有选择会被各
     * 视图继承一次，之后各自独立。默认值由调用方按视图给（见 `LibraryView.defaultSort`）。
     */
    fun sortOrder(viewName: String, default: SortOrder): SortOrder {
        val raw = sp.getString(KEY_SORT_PREFIX + viewName, null)
            ?: sp.getString(KEY_SORT, null)
            ?: return default
        return runCatching { SortOrder.valueOf(raw) }.getOrDefault(default)
    }

    fun setSortOrder(viewName: String, order: SortOrder) {
        sp.edit().putString(KEY_SORT_PREFIX + viewName, order.name).apply()
    }

    /** 清掉某个视图的排序覆盖，让它回到 [LibraryView.defaultSort] */
    fun resetSortOrder(viewName: String) {
        sp.edit().remove(KEY_SORT_PREFIX + viewName).apply()
    }

    /** 上次停留的浏览视图（歌曲 / 专辑 / 歌手 / 文件夹 / 歌单），重启后恢复 */
    var libraryView: String
        get() = sp.getString(KEY_VIEW, "SONGS").orEmpty()
        set(value) = sp.edit().putString(KEY_VIEW, value).apply()

    /** 歌词字号缩放 */
    var lyricScale: Float
        get() = sp.getFloat(KEY_LYRIC_SCALE, SettingsDefaults.LYRIC_SCALE)
        set(value) = sp.edit().putFloat(KEY_LYRIC_SCALE, value).apply()

    /** 逐字歌词：当前行有逐字时间轴时按字染色（默认开启） */
    var lyricWordByWord: Boolean
        get() = sp.getBoolean(KEY_LYRIC_WORD_BY_WORD, SettingsDefaults.LYRIC_WORD_BY_WORD)
        set(value) = sp.edit().putBoolean(KEY_LYRIC_WORD_BY_WORD, value).apply()

    /**
     * 播放页逐字歌词里**已唱部分**的颜色（ARGB）。
     *
     * 默认 [SettingsDefaults.COLOR_FOLLOW_THEME] = 跟随主题色（`colorScheme.primary`）——
     * 主题会随封面取色变化，默认跟随才能跟封面配得上。
     * 用户显式选了固定色后就用固定色，不再随主题漂移。
     */
    var lyricSungColor: Int
        get() = sp.getInt(KEY_LYRIC_SUNG_COLOR, SettingsDefaults.LYRIC_SUNG_COLOR)
        set(value) = sp.edit().putInt(KEY_LYRIC_SUNG_COLOR, value).apply()

    /**
     * 播放页中部显示的是歌词（true，默认）还是封面（false）。
     *
     * 落盘而不是用 `remember`：播放页是覆盖层，收起后整棵子树离开组合，
     * `remember` 的状态会一起丢——用户每次打开播放页都被拨回歌词态，
     * 对习惯"封面党"的人来说就是每次都要多点一下。
     */
    var lyricsPaneVisible: Boolean
        get() = sp.getBoolean(KEY_LYRICS_PANE, true)
        set(value) = sp.edit().putBoolean(KEY_LYRICS_PANE, value).apply()

    /**
     * 车载模式（默认关闭）。
     * 开启后：启动直进播放页；播放页 5 秒无操作收起全部控件只留歌词与进度。
     */
    var carMode: Boolean
        get() = sp.getBoolean(KEY_CAR_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_CAR_MODE, value).apply()

    /**
     * 桌面悬浮歌词（默认关闭）。
     *
     * 车机上用：退到桌面 / 导航页时，在最上层挂一条只有文字的单行字幕，
     * 持续显示当前歌词行。需要 [android.provider.Settings.canDrawOverlays] 授权
     * 「出现在其他应用上」才能生效——比亚迪 DiLink 车机该权限在
     * 设置 → 应用 → 权限 → 出现在其他应用上。
     */
    var lyricOverlay: Boolean
        get() = sp.getBoolean(KEY_LYRIC_OVERLAY, false)
        set(value) = sp.edit().putBoolean(KEY_LYRIC_OVERLAY, value).apply()

    /**
     * 悬浮歌词的横向偏移（像素，相对屏幕左边缘）。
     *
     * 2026-10-16 起重新启用：窗口位置完全由拖动决定（此前横向曾归
     * [lyricOverlayAlign] 管、此键一度废弃）。`Int.MIN_VALUE` 表示未拖过，
     * 走默认的水平居中。
     */
    var lyricOverlayX: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_X, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_X, value).apply()

    /**
     * 桌面悬浮歌词一次显示几行：1（默认，只显示当前行）或 3（上下各带一行上下文）。
     *
     * 3 行模式下当前行白色高亮、上下两行压暗，视线自然落在当前行——
     * 车机 / 大屏上尤其有用，不必等下一句冒出来才知道上下文。
     * 只存 1 或 3 两个值，其它值一律按 1 处理（防旧数据 / 手改）。
     */
    var lyricOverlayLines: Int
        get() = if (sp.getInt(KEY_LYRIC_OVERLAY_LINES, 1) == 3) 3 else 1
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_LINES, if (value == 3) 3 else 1).apply()

    /**
     * 悬浮歌词的纵向偏移（像素，相对屏幕顶端）。
     *
     * `Int.MIN_VALUE` 表示未调整过，走默认的「距顶 48dp 让开状态栏」。
     */
    var lyricOverlayY: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_Y, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_Y, value).apply()

    /**
     * 悬浮歌词字号档位：0 小 / 1 中（默认）/ 2 大。
     *
     * 存档位而不是存裸的 sp 值，是为了**改基准字号时老用户跟着走**——
     * 裸值一旦存下来，日后调整基准就只对新用户生效了。
     */
    var lyricOverlayTextScale: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_TEXT_SCALE, 1).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_TEXT_SCALE, value.coerceIn(0, 2)).apply()

    /**
     * 悬浮歌词底板：0 无底板 / 1 半透明深色（默认）/ 2 深色实底。
     *
     * 默认为 1 而不是 0：锁定态曾经是「纯白字 + 投影、无底板」，
     * 在亮色壁纸 / 桌面白色搜索框上白字直接糊掉——那是被真机证实过的坑。
     * 允许选 0 是尊重「壁纸很干净、不想有块底色」的需求，但设置页要写明代价。
     */
    var lyricOverlayBackdrop: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_BACKDROP, 1).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_BACKDROP, value.coerceIn(0, 2)).apply()

    /** 暂停时的表现：0 淡出（默认，保留最后一句便于看清唱到哪）/ 1 隐藏 */
    var lyricOverlayPauseMode: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_PAUSE_MODE, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_PAUSE_MODE, value.coerceIn(0, 1)).apply()

    /**
     * 悬浮歌词窗内文字的对齐：0 居中（默认）/ 1 居左。
     *
     * 2026-10-16 起语义收窄：**只管歌词文字在窗口内部怎么排**
     * （居中 = 折行后各行也居中；居左 = 每行从左沿同一条竖线起排），
     * 与窗口摆在屏幕哪儿无关 —— 窗口位置完全由拖动决定（[lyricOverlayX] / [lyricOverlayY]）。
     */
    var lyricOverlayAlign: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_ALIGN, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_ALIGN, value.coerceIn(0, 1)).apply()

    // ---------------- v1.1.4 新增：悬浮窗宽度与歌词颜色 ----------------

    /**
     * 悬浮歌词窗口的宽度，**按屏幕宽度的百分比**（1..100，默认 100）。
     *
     * 此前窗口宽度是 `WRAP_CONTENT`（内容多宽就多宽），长句会一路顶到屏幕边缘、
     * 短句又只有一小条 —— 同一首歌里宽度不停跳。改成固定百分比后宽度稳定，
     * 文字超出就折行。
     *
     * ⚠️ 宽度变了必须重新摆一次窗口：`OverlayPositioner` 的夹取上限是
     * 「屏宽 − 窗口宽」，宽窗口留的空间更小（见 `LyricOverlayService.applyAppearance`）。
     */
    var lyricOverlayWidthPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_WIDTH, SettingsDefaults.OVERLAY_WIDTH_PERCENT)
            .coerceIn(SettingsDefaults.OVERLAY_WIDTH_RANGE.first, SettingsDefaults.OVERLAY_WIDTH_RANGE.last)
        set(value) = sp.edit().putInt(
            KEY_LYRIC_OVERLAY_WIDTH,
            value.coerceIn(SettingsDefaults.OVERLAY_WIDTH_RANGE.first, SettingsDefaults.OVERLAY_WIDTH_RANGE.last),
        ).apply()

    /** 悬浮窗里**未唱部分**的文字颜色（ARGB，默认纯白） */
    var lyricOverlayTextColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, SettingsDefaults.OVERLAY_TEXT_COLOR)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, value).apply()

    /** 悬浮窗里**已唱部分**的颜色（ARGB，默认暖黄 `#FFCD56`） */
    var lyricOverlaySungColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_SUNG_COLOR, SettingsDefaults.OVERLAY_SUNG_COLOR)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_SUNG_COLOR, value).apply()

    // ---------------- 批次 6 新增 ----------------

    /** 跳过音频文件里的静音段（整轨/现场录音常用） */
    var skipSilence: Boolean
        get() = sp.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) = sp.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()

    // ---------------- 批次 2/3 新增 ----------------

    /** 主题是否跟随封面取色 */
    var dynamicTheme: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_THEME, true)
        set(value) = sp.edit().putBoolean(KEY_DYNAMIC_THEME, value).apply()

    /** 播放速度 */
    var playbackSpeed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(KEY_SPEED, value).apply()

    /**
     * 变调（半音，-12..+12；0 = 原调）。
     *
     * 存半音而不是倍率：界面上用户看到的、能理解的单位就是「半音」，
     * 倍率只在写进 `PlaybackParameters` 那一刻由 [com.luckylearning.windtone.playback.Pitch] 换算。
     * 存倍率的话，界面每次都要反算 `12*log2(f)`，浮点误差会让「+0 半音」显示成「-0 半音」。
     */
    var playbackPitchSemitones: Int
        get() = sp.getInt(KEY_PITCH, 0)
        set(value) = sp.edit().putInt(KEY_PITCH, value).apply()

    // 均衡器
    var eqEnabled: Boolean
        get() = sp.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

    /**
     * 当前均衡器预设 = [com.luckylearning.windtone.playback.EqPresets] 的 id。
     * 空串 = 自定义（用户手动拖过频段滑块）。
     *
     * 2026-10-16 起不再是平台预设的索引（Int）：平台预设名各 ROM 不一致且无法
     * 本地化，改为内置曲线（[EqPresets]）。旧 key `eq_preset`（Int）残留在
     * SharedPreferences 里，不再读取。
     */
    var eqPresetId: String
        get() = sp.getString(KEY_EQ_PRESET_ID, "").orEmpty()
        set(value) = sp.edit().putString(KEY_EQ_PRESET_ID, value).apply()

    var eqBands: String
        get() = sp.getString(KEY_EQ_BANDS, "").orEmpty()
        set(value) = sp.edit().putString(KEY_EQ_BANDS, value).apply()

    var eqBassBoost: Int
        get() = sp.getInt(KEY_EQ_BASS, 0)
        set(value) = sp.edit().putInt(KEY_EQ_BASS, value).apply()

    var eqVirtualizer: Int
        get() = sp.getInt(KEY_EQ_VIRTUAL, 0)
        set(value) = sp.edit().putInt(KEY_EQ_VIRTUAL, value).apply()

    /** 响度增强（LoudnessEnhancer 目标增益，单位 mB） */
    var eqLoudness: Int
        get() = sp.getInt(KEY_EQ_LOUDNESS, 0)
        set(value) = sp.edit().putInt(KEY_EQ_LOUDNESS, value.coerceIn(0, 1500)).apply()

    // ---------------- 应用内更新 ----------------

    /**
     * 启动时自动检查更新。
     *
     * 默认开启——这是唯一会联网的功能，且只访问 GitHub 公开接口。
     * 用户可以在设置页一键关闭，关闭后应用完全不联网。
     */
    var autoCheckUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    /** 上次检查更新的时间戳（毫秒），用于限制自动检查频率 */
    var lastUpdateCheckAt: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /**
     * 下载新版本安装包时使用的线路。默认 [DownloadRoute.AUTO]。
     * 只影响 APK 下载，不影响「检查更新」（后者永远走 GitHub 官方地址）。
     */
    var updateDownloadRoute: DownloadRoute
        get() = runCatching {
            DownloadRoute.valueOf(sp.getString(KEY_UPDATE_ROUTE, DownloadRoute.AUTO.name)!!)
        }.getOrDefault(DownloadRoute.AUTO)
        set(value) = sp.edit().putString(KEY_UPDATE_ROUTE, value.name).apply()

    // ================================================================ 私有

    private companion object {
        const val KEY_SCAN_SCOPE = "scan_scope"
        const val KEY_SCAN_SCOPE_CHOSEN = "scan_scope_chosen"
        const val KEY_DEEP_SCAN = "deep_scan"
        const val KEY_MIN_DURATION_SEC = "min_song_duration_sec"
        /** 短音频过滤默认阈值：60 秒 */
        const val DEFAULT_MIN_DURATION_SEC = 60
        /** 旧的**全局**排序键：v1.1.4 起排序按视图各存一份，它只作老数据兼容回落 */
        const val KEY_SORT = "sort"

        /** 各视图的排序键前缀：`sort_SONGS` / `sort_ALBUM` / `sort_ARTIST` / `sort_FOLDER` / `sort_PLAYLIST` */
        const val KEY_SORT_PREFIX = "sort_"
        const val KEY_VIEW = "library_view"
        const val KEY_LYRIC_SCALE = "lyric_scale"
        const val KEY_LYRIC_WORD_BY_WORD = "lyric_word_by_word"
        const val KEY_LYRICS_PANE = "lyrics_pane_visible"
        const val KEY_CAR_MODE = "car_mode"
        const val KEY_LYRIC_OVERLAY = "lyric_overlay"
        const val KEY_LYRIC_OVERLAY_X = "lyric_overlay_x"
        const val KEY_LYRIC_OVERLAY_Y = "lyric_overlay_y"
        const val KEY_LYRIC_OVERLAY_LINES = "lyric_overlay_lines"
        const val KEY_LYRIC_OVERLAY_TEXT_SCALE = "lyric_overlay_text_scale"
        const val KEY_LYRIC_OVERLAY_BACKDROP = "lyric_overlay_backdrop"
        const val KEY_LYRIC_OVERLAY_PAUSE_MODE = "lyric_overlay_pause_mode"
        const val KEY_LYRIC_OVERLAY_ALIGN = "lyric_overlay_align"
        const val KEY_LYRIC_OVERLAY_WIDTH = "lyric_overlay_width_percent"
        const val KEY_LYRIC_OVERLAY_TEXT_COLOR = "lyric_overlay_text_color"
        const val KEY_LYRIC_OVERLAY_SUNG_COLOR = "lyric_overlay_sung_color"
        const val KEY_LYRIC_SUNG_COLOR = "lyric_sung_color"
        const val KEY_DYNAMIC_THEME = "dynamic_theme"
        const val KEY_SPEED = "playback_speed"
        const val KEY_PITCH = "playback_pitch_semitones"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET_ID = "eq_preset_id"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_EQ_BASS = "eq_bass"
        const val KEY_EQ_VIRTUAL = "eq_virtual"
        const val KEY_EQ_LOUDNESS = "eq_loudness"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_AUTO_UPDATE = "auto_check_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        const val KEY_UPDATE_ROUTE = "update_download_route"
    }
}
