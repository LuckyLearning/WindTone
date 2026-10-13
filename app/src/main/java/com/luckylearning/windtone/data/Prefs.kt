package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences

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

    private companion object {
        const val KEY_FOLDERS = "folders"
        const val KEY_SCAN_SCOPE = "scan_scope"
        const val KEY_DEEP_SCAN = "deep_scan"
        const val KEY_SORT = "sort"
        const val KEY_VIEW = "library_view"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_LAST_POS = "last_pos"
        const val KEY_LYRIC_SCALE = "lyric_scale"
        const val KEY_LYRIC_WORD_BY_WORD = "lyric_word_by_word"
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
    }
}
