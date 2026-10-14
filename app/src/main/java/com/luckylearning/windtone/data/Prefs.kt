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

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    var folderUris: List<String>
        get() = sp.getStringSet(KEY_FOLDERS, emptySet())?.toList().orEmpty()
        set(value) {
            sp.edit().putStringSet(KEY_FOLDERS, value.toSet()).apply()
        }

    /** 扫描范围：默认未选择，装机后不自动扫描 */
    var scanScope: ScanScope
        get() = runCatching { ScanScope.valueOf(sp.getString(KEY_SCAN_SCOPE, ScanScope.UNSET.name)!!) }
            .getOrDefault(ScanScope.UNSET)
        set(value) = sp.edit().putString(KEY_SCAN_SCOPE, value.name).apply()

    /** 是否启用「深度扫描」（读取同目录 .lrc 歌词文件） */
    var deepScanEnabled: Boolean
        get() = sp.getBoolean(KEY_DEEP_SCAN, false)
        set(value) = sp.edit().putBoolean(KEY_DEEP_SCAN, value).apply()

    /**
     * 短音频过滤阈值（秒）。
     * 时长已知且小于该值的音频不会进入曲库（用于滤掉提示音、转场音效等）；
     * 0 表示不过滤。默认 60 秒。时长未知（如 WebDAV 扫描阶段拿不到时长）的歌曲不受影响。
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

    var lastSongUri: String?
        get() = sp.getString(KEY_LAST_URI, null)
        set(value) = sp.edit().putString(KEY_LAST_URI, value).apply()

    var lastPositionMs: Long
        get() = sp.getLong(KEY_LAST_POS, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_POS, value).apply()

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
     * 悬浮歌词的纵向偏移（像素，相对屏幕顶端）。
     *
     * `Int.MIN_VALUE` 表示未调整过，走默认的「距顶 48dp 让开状态栏」。
     */
    var lyricOverlayY: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_Y, Int.MIN_VALUE)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_Y, value).apply()

    // ---------------- 逐曲歌词偏移：key = 歌曲稳定标识 ----------------

    fun lyricOffsetFor(songKey: String): Int =
        sp.getInt(KEY_LYRIC_OFFSET_PREFIX + songKey.hashCode().toString(), 0)

    fun setLyricOffsetFor(songKey: String, offsetMs: Int) {
        val k = KEY_LYRIC_OFFSET_PREFIX + songKey.hashCode().toString()
        if (offsetMs == 0) sp.edit().remove(k).apply()
        else sp.edit().putInt(k, offsetMs.coerceIn(-20_000, 20_000)).apply()
    }

    /** 旧版全局偏移的迁移读取 */
    var legacyGlobalLyricOffset: Int
        get() = sp.getInt(KEY_LYRIC_OFFSET, 0)
        set(value) = sp.edit().putInt(KEY_LYRIC_OFFSET, value).apply()

    var shuffle: Boolean
        get() = sp.getBoolean(KEY_SHUFFLE, false)
        set(value) = sp.edit().putBoolean(KEY_SHUFFLE, value).apply()

    // ---------------- 播放队列持久化（整队列恢复，而非只恢复单曲） ----------------

    /** 队列中歌曲的稳定标识，顺序保存；上限 500 首避免 SharedPreferences 过大 */
    var queueKeys: List<String>
        get() = sp.getString(KEY_QUEUE, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
        set(value) = sp.edit().putString(KEY_QUEUE, value.take(500).joinToString("\n")).apply()

    /** 队列中当前播放到第几首 */
    var queueIndex: Int
        get() = sp.getInt(KEY_QUEUE_INDEX, 0)
        set(value) = sp.edit().putInt(KEY_QUEUE_INDEX, value.coerceAtLeast(0)).apply()

    // ---------------- 搜索历史 ----------------

    var searchHistory: List<String>
        get() = sp.getString(KEY_SEARCH_HISTORY, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
        set(value) = sp.edit()
            .putString(KEY_SEARCH_HISTORY, value.distinct().take(10).joinToString("\n"))
            .apply()

    // ---------------- 批次 6 新增 ----------------

    /** 通知栏/锁屏是否滚动显示当前歌词行（默认关闭） */
    var notifyLyrics: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_LYRICS, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIFY_LYRICS, value).apply()

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

    // ---------------- WebDAV 云盘 ----------------

    /**
     * WebDAV 服务器地址（如 https://nas.local:5244/dav 或 Alist/OpenList 挂载）。
     * 为空表示未配置。
     */
    var webdavUrl: String
        get() = sp.getString(KEY_WEBDAV_URL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_WEBDAV_URL, value.trim()).apply()

    var webdavUser: String
        get() = sp.getString(KEY_WEBDAV_USER, "").orEmpty()
        set(value) = sp.edit().putString(KEY_WEBDAV_USER, value).apply()

    /**
     * WebDAV 密码。Base64 混淆后存储——这不是加密（应用私有目录下
     * 防的是「一眼明文」，不是逆向），真正的凭证安全要等接入
     * androidx.security:security-crypto，见 docs/功能规划-第四期-云盘音乐.md §4.1。
     */
    var webdavPassword: String
        get() = runCatching {
            val raw = sp.getString(KEY_WEBDAV_PASS, "").orEmpty()
            if (raw.isEmpty()) "" else String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
        set(value) = sp.edit()
            .putString(
                KEY_WEBDAV_PASS,
                if (value.isEmpty()) "" else Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            )
            .apply()

    /** WebDAV 扫描根路径（"" = 站点根目录） */
    var webdavRoot: String
        get() = sp.getString(KEY_WEBDAV_ROOT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_WEBDAV_ROOT, value.trim().trimEnd('/')).apply()

    /** 是否把 WebDAV 歌曲合并进曲库（配置了服务器后默认合并） */
    var webdavEnabled: Boolean
        get() = sp.getBoolean(KEY_WEBDAV_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_WEBDAV_ENABLED, value).apply()

    private companion object {
        const val KEY_FOLDERS = "folders"
        const val KEY_SCAN_SCOPE = "scan_scope"
        const val KEY_DEEP_SCAN = "deep_scan"
        const val KEY_MIN_DURATION_SEC = "min_song_duration_sec"
        /** 短音频过滤默认阈值：60 秒 */
        const val DEFAULT_MIN_DURATION_SEC = 60
        const val KEY_SORT = "sort"
        const val KEY_VIEW = "library_view"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_LAST_POS = "last_pos"
        const val KEY_LYRIC_SCALE = "lyric_scale"
        const val KEY_LYRIC_WORD_BY_WORD = "lyric_word_by_word"
        const val KEY_CAR_MODE = "car_mode"
        const val KEY_LYRIC_OVERLAY = "lyric_overlay"
        const val KEY_LYRIC_OVERLAY_X = "lyric_overlay_x"
        const val KEY_LYRIC_OVERLAY_Y = "lyric_overlay_y"
        const val KEY_LYRIC_OFFSET = "lyric_offset"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_PERM_ASKED = "perm_asked"
        const val KEY_DYNAMIC_THEME = "dynamic_theme"
        const val KEY_SPEED = "playback_speed"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_EQ_BASS = "eq_bass"
        const val KEY_EQ_VIRTUAL = "eq_virtual"
        const val KEY_EQ_LOUDNESS = "eq_loudness"
        const val KEY_LYRIC_OFFSET_PREFIX = "lyric_offset_song_"
        const val KEY_QUEUE = "queue_keys"
        const val KEY_QUEUE_INDEX = "queue_index"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val KEY_NOTIFY_LYRICS = "notify_lyrics"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_AUTO_UPDATE = "auto_check_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USER = "webdav_user"
        const val KEY_WEBDAV_PASS = "webdav_password"
        const val KEY_WEBDAV_ROOT = "webdav_root"
        const val KEY_WEBDAV_ENABLED = "webdav_enabled"
    }
}
