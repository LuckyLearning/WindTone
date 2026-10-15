package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

enum class SortOrder(val label: String) {
    TITLE("按标题"),
    ARTIST("按歌手"),
    ALBUM("按专辑"),
    DURATION("按时长"),
    DATE_ADDED("按添加时间"),
    FILE_NAME("按文件名"),
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

    var sortOrder: SortOrder
        get() = runCatching { SortOrder.valueOf(sp.getString(KEY_SORT, SortOrder.TITLE.name)!!) }
            .getOrDefault(SortOrder.TITLE)
        set(value) = sp.edit().putString(KEY_SORT, value.name).apply()

    /** 上次停留的浏览视图（歌曲 / 专辑 / 歌手 / 文件夹 / 歌单），重启后恢复 */
    var libraryView: String
        get() = sp.getString(KEY_VIEW, "SONGS").orEmpty()
        set(value) = sp.edit().putString(KEY_VIEW, value).apply()

    /** 歌词字号缩放 */
    var lyricScale: Float
        get() = sp.getFloat(KEY_LYRIC_SCALE, 1.0f)
        set(value) = sp.edit().putFloat(KEY_LYRIC_SCALE, value).apply()

    /** 逐字歌词：当前行有逐字时间轴时按字染色（默认开启） */
    var lyricWordByWord: Boolean
        get() = sp.getBoolean(KEY_LYRIC_WORD_BY_WORD, true)
        set(value) = sp.edit().putBoolean(KEY_LYRIC_WORD_BY_WORD, value).apply()

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
     * 用 `Int.MIN_VALUE` 表示**尚未调整过**，此时服务走默认的「水平铺满居中」。
     * 一旦用户在调整模式里拖过，就存下一个具体值，下次启动沿用。
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

    // ---------------- 批次 6 新增 ----------------

    /** 跳过音频文件里的静音段（整轨/现场录音常用） */
    var skipSilence: Boolean
        get() = sp.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) = sp.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()

    // ---------------- 权限 ----------------

    /** 存储权限是否已被用户拒绝过一次（用于提示去设置页） */

    var permissionAsked: Boolean
        get() = sp.getBoolean(KEY_PERM_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_PERM_ASKED, value).apply()

    // ---------------- 批次 2/3 新增 ----------------

    /** 主题是否跟随封面取色 */
    var dynamicTheme: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_THEME, true)
        set(value) = sp.edit().putBoolean(KEY_DYNAMIC_THEME, value).apply()

    /** 播放速度 */
    var playbackSpeed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(value) = sp.edit().putFloat(KEY_SPEED, value).apply()

    // 均衡器
    var eqEnabled: Boolean
        get() = sp.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

    var eqPreset: Int
        get() = sp.getInt(KEY_EQ_PRESET, -1)
        set(value) = sp.edit().putInt(KEY_EQ_PRESET, value).apply()

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

    // ================================================================ 变更通知（P4）

    /**
     * 注册一个「偏好项发生变化」的监听，返回取消函数。
     *
     * 需要实时响应的界面用它替代轮询；不需要的继续同步读属性即可（SharedPreferences
     * 读本身极轻，这里只解决「另一处改了值，这一处要跟着更新」的场景）。
     */
    fun onChanged(listener: (key: String) -> Unit): () -> Unit {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) listener(key)
        }
        sp.registerOnSharedPreferenceChangeListener(l)
        return { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }

    private companion object {
        const val KEY_SCAN_SCOPE = "scan_scope"
        const val KEY_SCAN_SCOPE_CHOSEN = "scan_scope_chosen"
        const val KEY_DEEP_SCAN = "deep_scan"
        const val KEY_MIN_DURATION_SEC = "min_song_duration_sec"
        /** 短音频过滤默认阈值：60 秒 */
        const val DEFAULT_MIN_DURATION_SEC = 60
        const val KEY_SORT = "sort"
        const val KEY_VIEW = "library_view"
        const val KEY_LYRIC_SCALE = "lyric_scale"
        const val KEY_LYRIC_WORD_BY_WORD = "lyric_word_by_word"
        const val KEY_CAR_MODE = "car_mode"
        const val KEY_LYRIC_OVERLAY = "lyric_overlay"
        const val KEY_LYRIC_OVERLAY_X = "lyric_overlay_x"
        const val KEY_LYRIC_OVERLAY_Y = "lyric_overlay_y"
        const val KEY_LYRIC_OVERLAY_LINES = "lyric_overlay_lines"
        const val KEY_LYRIC_OVERLAY_TEXT_SCALE = "lyric_overlay_text_scale"
        const val KEY_LYRIC_OVERLAY_BACKDROP = "lyric_overlay_backdrop"
        const val KEY_LYRIC_OVERLAY_PAUSE_MODE = "lyric_overlay_pause_mode"
        const val KEY_PERM_ASKED = "perm_asked"
        const val KEY_DYNAMIC_THEME = "dynamic_theme"
        const val KEY_SPEED = "playback_speed"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
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
