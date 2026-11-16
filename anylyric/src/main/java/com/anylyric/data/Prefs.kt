package com.anylyric.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.anylyric.overlay.Align
import com.anylyric.overlay.LineMode
import com.anylyric.overlay.OverlayDefaults
import com.anylyric.overlay.OverlayStyle
import com.anylyric.overlay.Preset
import com.anylyric.overlay.TitlePos
import com.anylyric.lyrics.online.OnlineSource
import com.anylyric.session.LyricOffsetStore
import com.anylyric.theme.ThemeMode
import com.anylyric.update.DownloadRoute

/**
 * 全部本地偏好。只有显示参数与调试开关，没有业务数据。
 *
 * 两条约定：
 *  · 位置存**比例**不存像素（换分辨率、改字号都不会把窗口丢到屏幕外）；
 *  · 样式默认值只在 [OverlayDefaults] 定义一次，这里只做读写与钳制。
 */
class Prefs(context: Context) {

    private val appContext: Context = context.applicationContext

    private val sp: SharedPreferences =
        appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 调试开关：默认关闭，关闭时 Probe 零采集、零分配。 */
    var debugEnabled: Boolean
        get() = sp.getBoolean(KEY_DEBUG, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG, value).apply()

    /** 播放器没暴露歌词时是否直接隐藏。默认 false：显示提示，让用户知道应用在工作。 */
    var hideWhenNoLyrics: Boolean
        get() = sp.getBoolean(KEY_HIDE_NO_LYRICS, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_NO_LYRICS, value).apply()

    /**
     * 「按应用隐藏」总开关。默认关闭 —— 关闭时无障碍事件一次早退，连包名都不解析。
     *
     * 单独一个开关而不是「名单非空即生效」：用户可能想临时停用（排查时、换车机时），
     * 又不想把辛苦勾好的名单清空。
     */
    var hideByAppEnabled: Boolean
        get() = sp.getBoolean(KEY_HIDE_BY_APP, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_BY_APP, value).apply()

    /**
     * 用户是否**曾经**开启过无障碍服务。
     *
     * 只为一件事存在：判断「服务从没开过」还是「开过、后来被系统关掉了」。
     * 前者不该替他做主（自动恢复等于越过用户决定），后者才值得尝试写回 ——
     * 而且写回还需要 `WRITE_SECURE_SETTINGS`，绝大多数设备根本没有。
     *
     * 由两处置位：打开「按应用隐藏」开关时、服务成功连接时。
     */
    var accessibilityRequested: Boolean
        get() = sp.getBoolean(KEY_A11Y_REQUESTED, false)
        set(value) = sp.edit().putBoolean(KEY_A11Y_REQUESTED, value).apply()

    /**
     * 命中即隐藏悬浮窗的应用包名。
     *
     * 缓存理由：每次前台切换都要读一次（无障碍事件驱动，低频但不该白读），
     * 而 `getStringSet` 每次返回的集合必须复制后才能安全使用 —— 缓存住副本，
     * 读路径就完全无分配。任何写入都同步刷新缓存。
     */
    var hiddenApps: Set<String>
        get() = hiddenAppsCache ?: synchronized(hiddenAppsLock) {
            hiddenAppsCache ?: run {
                val stored = sp.getStringSet(KEY_HIDDEN_APPS, null)
                val copy = if (stored.isNullOrEmpty()) emptySet() else LinkedHashSet(stored)
                hiddenAppsCache = copy
                copy
            }
        }
        set(value) = synchronized(hiddenAppsLock) {
            val copy = LinkedHashSet(value)
            hiddenAppsCache = copy
            sp.edit().putStringSet(KEY_HIDDEN_APPS, copy).apply()
        }

    private var hiddenAppsCache: Set<String>? = null
    private val hiddenAppsLock = Any()

    /** 名单是否真的要参与判定（开关开着且至少勾了一个应用）。 */
    val hideByAppActive: Boolean
        get() = hideByAppEnabled && hiddenApps.isNotEmpty()

    /**
     * 锁定的播放器包名（空串 = 自动选择）。
     *
     * 车机上音乐一般固定走同一个播放器，但「自动挑」在以下场景会跳：
     * 导航语音临时发布会话、上一首的残留会话、某个 App 抢了前台。
     * 锁定后只认这一个包，目标没有活跃会话时**宁可不出歌词**，也不去读别人的。
     */
    var pinnedSessionPackage: String
        get() = sp.getString(KEY_PINNED_SESSION, "").orEmpty()
        set(value) = sp.edit().putString(KEY_PINNED_SESSION, value.orEmpty()).apply()

    /** 是否处于手动锁定模式。 */
    val sessionPinned: Boolean
        get() = pinnedSessionPackage.isNotBlank()

    /**
     * 设置页上次停留的标签页（存枚举名，认不出就回「概览」）。
     *
     * 车机上翻到「外观」调字号是常事，下次进来直接回到那一页，省一次点击；
     * 存字符串而不是序号，将来标签顺序调整也不会串页。
     */
    var settingsTab: String
        get() = sp.getString(KEY_SETTINGS_TAB, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SETTINGS_TAB, value).apply()

    /**
     * 界面明暗：自动 / 白天 / 夜间。默认跟随系统。
     *
     * 在 `attachBaseContext` 里读，所以写入必须用**同步可见**的方式 ——
     * SharedPreferences 的 `apply()` 对同进程读是立即可见的，改完直接 recreate 即可。
     */
    var themeMode: ThemeMode
        get() = ThemeMode.of(sp.getString(KEY_THEME_MODE, null))
        set(value) = sp.edit().putString(KEY_THEME_MODE, value.key).apply()

    /** 悬浮层输出到哪块屏幕。默认主屏。 */
    var displayId: Int
        get() = sp.getInt(KEY_DISPLAY_ID, DEFAULT_DISPLAY_ID)
        set(value) = sp.edit().putInt(KEY_DISPLAY_ID, value).apply()

    /** 左侧收起/展开把手。默认开：没有它就没办法临时让出屏幕中间的位置。 */
    var handleEnabled: Boolean
        get() = sp.getBoolean(KEY_HANDLE, true)
        set(value) = sp.edit().putBoolean(KEY_HANDLE, value).apply()

    /** 更新线路：检查更新与安装包下载共用。默认自动（镜像优先、官方兜底）。 */
    var updateRoute: DownloadRoute
        get() = DownloadRoute.of(sp.getString(KEY_UPDATE_ROUTE, null))
        set(value) = sp.edit().putString(KEY_UPDATE_ROUTE, value.name).apply()

    /**
     * 开机自启：开机广播到达后主动请求系统绑定通知使用权。
     *
     * 通知使用权本身重启后仍然有效，但不少车机 ROM 要么延迟绑定、要么干脆忘记绑 ——
     * 表现是「开机后歌词一直不出现」。打开此开关后由我们自己补一脚。
     */
    var bootAutostart: Boolean
        get() = sp.getBoolean(KEY_BOOT_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_AUTOSTART, value).apply()

    /**
     * 静默掉线看门狗：每 15 分钟检查一次心跳，过期就 requestRebind。
     *
     * 默认关闭。通知使用权被 ROM 省电策略杀掉时不一定回调 onListenerDisconnected，
     * 表现是「歌词忽然不动了，打开一次应用才好」——这个开关就是为这种场景准备的。
     */
    var watchdogEnabled: Boolean
        get() = sp.getBoolean(KEY_WATCHDOG, false)
        set(value) = sp.edit().putBoolean(KEY_WATCHDOG, value).apply()

    /**
     * 在线补词（默认关闭）。
     *
     * 打开后，本地三个通道全 miss 时才按「歌名 + 歌手」去在线歌词库找一份；
     * 歌词链路因此会联网 —— 这是用户显式打开才生效的可选能力，默认零联网。
     */
    var onlineLyrics: Boolean
        // 默认开启（0.2.23）：车机上多数播放器根本不把歌词写进通知 / 会话，
        // 在线补词是唯一能出词的路。只发歌名与歌手，且取到一次就落盘，可随时关。
        get() = sp.getBoolean(KEY_ONLINE_LYRICS, true)
        set(value) = sp.edit().putBoolean(KEY_ONLINE_LYRICS, value).apply()

    /**
     * 优先使用在线歌词（0.2.27）。
     *
     * 用户原话：「可以使用在线歌词替代本地歌词，做个开关直接用在线解析到的歌词」。
     * 开着的语义很绝对：只要在线那份存在，就不看本地通道 —— 哪怕本地给的是完整带轴的。
     * 关着保持旧行为（本地**强**命中优先，只有本地弱命中或全 miss 才用在线）。
     *
     * 默认关闭：本地词（尤其播放器自己给的那份）通常与音频同源，时间轴最贴；
     * 在线那份再准也是别人的版本，不该默认反客为主。
     */
    var onlineOverridesLocal: Boolean
        get() = sp.getBoolean(KEY_ONLINE_OVERRIDE, false)
        set(value) = sp.edit().putBoolean(KEY_ONLINE_OVERRIDE, value).apply()

    /**
     * 悬浮窗总开关（0.2.25）。
     *
     * 用户原话：「增加个开关，打开软件时悬浮窗显示隐藏通过开关控制」。关掉后
     * **什么都不画** —— 歌词照旧在后台取、责任链照旧跑，只是不建窗口。
     * 与「收起」的区别：收起是临时让位（点把手就回来、不落盘），这里是持久状态
     * （下次打开软件仍然关着），所以它必须落盘。
     */
    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    /**
     * 歌词磁盘缓存上限（按「首」计）。0 = 不缓存。
     *
     * 缓存本身就是「同一首歌第二次不用再联网」，但它要占盘 —— 车机存储小，
     * 所以给一个可调上限（[CACHE_LIMITS]），默认 [CACHE_LIMIT_DEFAULT] 首。
     */
    var lyricCacheLimit: Int
        get() = sp.getInt(KEY_LYRIC_CACHE, CACHE_LIMIT_DEFAULT).coerceIn(0, CACHE_LIMIT_MAX)
        set(value) = sp.edit().putInt(KEY_LYRIC_CACHE, value.coerceIn(0, CACHE_LIMIT_MAX)).apply()

    /**
     * 手动指定的在线歌词源；null = 自动（按 [onlineSourceOrder] 依次尝试）。
     *
     * 用户原话：「在线源可以手动指定」。车机上各家的强弱很不一样 —— 同一首歌
     * 可能只有某一家有正确版本，自动模式再聪明也猜不到用户要哪一家。
     * 指定后**只跑这一家**：它取不到就宁可没有，也不偷偷换别家给一份错的。
     */
    var onlineSource: OnlineSource?
        get() = OnlineSource.of(sp.getString(KEY_ONLINE_SOURCE, null))
        set(value) = sp.edit().putString(KEY_ONLINE_SOURCE, value?.id.orEmpty()).apply()

    /**
     * 自动模式下的查找顺序（0.2.27）。
     *
     * 用户原话：「可以自由定义自动时的查找顺序」。存 id 串而不是序号，
     * 将来新增/下线源时老配置不会串位（见 [OnlineSource.parseOrder]）。
     */
    var onlineSourceOrder: List<OnlineSource>
        get() = OnlineSource.parseOrder(sp.getString(KEY_ONLINE_ORDER, null))
        set(value) = sp.edit().putString(KEY_ONLINE_ORDER, OnlineSource.encodeOrder(value)).apply()

    /** 实际要跑的源列表：手动指定时只有一个元素，自动时按用户顺序。 */
    val onlineSourcePlan: List<OnlineSource>
        get() = onlineSource?.let { listOf(it) } ?: onlineSourceOrder

    // ------------------------------------------------------------ 时间校准（0.2.27）

    /**
     * 全局默认偏移。没单独调过的歌都用它。
     *
     * 存在意义：用户的听感偏好是稳定的（有人天生觉得歌词慢半拍），
     * 逐首调太累；先把默认调对，个别歌再微调。
     */
    var defaultLyricOffsetMs: Int
        get() = LyricOffsetStore.clamp(sp.getInt(KEY_OFFSET_DEFAULT, 0))
        set(value) = sp.edit()
            .putInt(KEY_OFFSET_DEFAULT, LyricOffsetStore.clamp(value))
            .apply()

    /** 整张偏移表的原始串（`LyricOffsetStore` 负责编解码与淘汰）。 */
    private var lyricOffsetsEncoded: String
        get() = sp.getString(KEY_LYRIC_OFFSETS, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LYRIC_OFFSETS, value).apply()

    /**
     * 这首歌实际生效的偏移。
     *
     * @param pkg 播放器包名 —— 必须参与，同一首歌在不同播放器上的进度偏差不同。
     */
    fun lyricOffsetMs(pkg: String?, title: String?, artist: String?): Int {
        val key = LyricOffsetStore.keyOf(pkg, title, artist)
        if (key.isEmpty()) return defaultLyricOffsetMs
        return offsets()[key] ?: defaultLyricOffsetMs
    }

    /** 这首歌是否单独调过（界面上用来区分「跟随默认」和「已自定义」）。 */
    fun hasOwnLyricOffset(pkg: String?, title: String?, artist: String?): Boolean {
        val key = LyricOffsetStore.keyOf(pkg, title, artist)
        return key.isNotEmpty() && offsets().containsKey(key)
    }

    fun setLyricOffsetMs(pkg: String?, title: String?, artist: String?, ms: Int) {
        val key = LyricOffsetStore.keyOf(pkg, title, artist)
        if (key.isEmpty()) return
        synchronized(offsetLock) {
            val encoded = LyricOffsetStore.put(lyricOffsetsEncoded, key, ms)
            lyricOffsetsEncoded = encoded
            offsetCache = LyricOffsetStore.decode(encoded)
        }
    }

    /** 抹掉这首歌的偏移，回到「跟随默认」。 */
    fun clearLyricOffset(pkg: String?, title: String?, artist: String?) {
        val key = LyricOffsetStore.keyOf(pkg, title, artist)
        if (key.isEmpty()) return
        synchronized(offsetLock) {
            val encoded = LyricOffsetStore.remove(lyricOffsetsEncoded, key)
            lyricOffsetsEncoded = encoded
            offsetCache = LyricOffsetStore.decode(encoded)
        }
    }

    /** 已经调过的曲目数（设置页显示用）。 */
    fun lyricOffsetCount(): Int = offsets().size

    /**
     * 全部清空：默认值也回 0。
     *
     * 「一键归零」是排查「到底是不是偏移导致对不上」的最快手段，
     * 所以连默认值一起清，否则用户清了之后还差半拍会以为是没生效。
     */
    fun clearAllLyricOffsets() {
        synchronized(offsetLock) {
            lyricOffsetsEncoded = ""
            offsetCache = LinkedHashMap()
        }
        sp.edit().putInt(KEY_OFFSET_DEFAULT, 0).apply()
    }

    private fun offsets(): MutableMap<String, Int> {
        offsetCache?.let { return it }
        synchronized(offsetLock) {
            offsetCache?.let { return it }
            val loaded = LyricOffsetStore.decode(sp.getString(KEY_LYRIC_OFFSETS, null))
            offsetCache = loaded
            return loaded
        }
    }

    /**
     * 双语歌词：把**同一时间戳的第二行**当作译文（0.2.27）。
     *
     * 默认关。很多 LRC 在同一时间戳写了两行，多数是「原文 + 译文」，
     * 但也可能是合唱声部；不分青红皂白合并会把歌词吃掉，所以必须用户显式打开。
     */
    var showTranslation: Boolean
        get() = sp.getBoolean(KEY_SHOW_TRANSLATION, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_TRANSLATION, value).apply()

    /**
     * 逐字歌词（0.2.28）。
     *
     * 默认关：它只有在**有字级时间轴**或行时长足够长时才好看，短句（「哦」「耶」）
     * 逐字扫过去只剩闪烁。打开后没有字级信息的部分会按行时长均分，保证不漏。
     *
     * 放在 Prefs 而不是 OverlayStyle：样式是「日/夜两套」存的，混进去会出现
     * 「切到夜间逐字自己关了」。
     */
    var wordByWord: Boolean
        get() = sp.getBoolean(KEY_WORD_BY_WORD, false)
        set(value) = sp.edit().putBoolean(KEY_WORD_BY_WORD, value).apply()

    /**
     * 播放中打开 App 时默认进入「时间校准」页（0.2.27）。
     *
     * 用户 2026-11-16 的明确场景：悬浮窗投在**仪表盘**（副屏，不可触摸），
     * 主屏用来操作 —— 所以调整入口只能在这里，且应该一进来就是它。
     * 默认开；关掉后恢复「上次停留的那页」。
     */
    var calibrateOnLaunch: Boolean
        get() = sp.getBoolean(KEY_CALIBRATE_ON_LAUNCH, true)
        set(value) = sp.edit().putBoolean(KEY_CALIBRATE_ON_LAUNCH, value).apply()

    /** 最近一次「确认服务活着」的心跳时间，供看门狗判断是否掉线。 */
    var lastAliveAt: Long        get() = sp.getLong(KEY_LAST_ALIVE, 0L)
        private set(value) = sp.edit().putLong(KEY_LAST_ALIVE, value).apply()

    /** 进程内心跳节流：最多 [ALIVE_WRITE_INTERVAL_MS] 写一次盘。 */
    private var aliveWrittenAt = 0L

    /**
     * 记录心跳。[force] 用于「刚连上」这种必须落盘的时刻。
     *
     * 刷新一秒可能来好几次，每次都写 SharedPreferences 是纯浪费 —— 进程内先挡一道。
     */
    fun markAlive(nowMs: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!force && nowMs - aliveWrittenAt < ALIVE_WRITE_INTERVAL_MS) return
        aliveWrittenAt = nowMs
        lastAliveAt = nowMs
    }

    /**
     * 当前生效的歌词外观属于「夜间」那一套吗。
     *
     * AUTO 时看系统 uiMode；LIGHT/DARK 是用户显式选择，直接照它算 —— 不能只读
     * Context 的 uiMode，因为 `applicationContext` 永远是系统值，而 Activity 那份
     * 已被 `ThemeContext.wrap` 换掉了，两者会给出不同答案（界面说白天、悬浮窗说夜间）。
     */
    fun styleIsDark(): Boolean = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> (appContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 当前主题那套外观是否被单独改过。没改过时继承另一套，切主题不会「设置全丢」。 */
    fun hasOwnStyle(): Boolean = sp.contains(styleKey(styleIsDark(), KEY_FONT))

    /**
     * 读当前主题对应的那套外观。
     *
     * 外观与主题绑定（0.2.23）：日间一套、夜间一套，切主题自动换，改参数只改当前那套。
     * 夜间那套**从未写过**时以日间那套为种子 —— 用户第一次切到夜间看到的是自己调好的
     * 排版与字号，而不是一堆出厂值；一旦在夜间改过任意一项就固化，之后各管各的。
     */
    fun style(): OverlayStyle = style(styleIsDark())

    /**
     * 读指定那套外观。
     *
     * @param dark 由调用方显式给出，用于「配置变化回调里判暗」—— 回调早于 Resources
     *             更新，此刻 [styleIsDark] 读 `appContext.resources` 会拿到旧值。
     */
    fun style(dark: Boolean): OverlayStyle {
        val cached = if (dark) darkStyleCache else lightStyleCache
        if (cached != null) return cached
        val seed = if (dark) readStyle(dark = false, seed = null) else null
        val loaded = readStyle(dark = dark, seed = seed)
        if (dark) darkStyleCache = loaded else lightStyleCache = loaded
        return loaded
    }

    private fun styleKey(dark: Boolean, base: String): String = if (dark) "d_$base" else base

    private fun readStyle(dark: Boolean, seed: OverlayStyle?): OverlayStyle {
        val align = Align.of(sp.getString(styleKey(dark, KEY_ALIGN), seed?.align?.id))
        // 第二行的对齐（0.2.25）：老版本只有一份对齐，读不到就跟着第一行 ——
        // 升级后不该突然出现两种对齐（那看起来就是「位置自己变了」）。
        // 本套单独改过第一行时也必须跟本套，不能跟继承来的种子，否则改完第一行第二行不动。
        val storedSecondary = sp.getString(styleKey(dark, KEY_SECONDARY_ALIGN), null)
        val secondaryAlign = when {
            storedSecondary != null -> Align.of(storedSecondary)
            sp.contains(styleKey(dark, KEY_ALIGN)) -> align
            else -> seed?.secondaryAlign ?: align
        }
        val titleAlign = Align.of(
            sp.getString(styleKey(dark, KEY_TITLE_ALIGN), seed?.titleAlign?.id)
        )
        return OverlayDefaults.sanitize(
        OverlayStyle(
            preset = Preset.of(
                sp.getString(styleKey(dark, KEY_PRESET), seed?.preset?.id)
            ),
            fontSp = sp.getFloat(
                styleKey(dark, KEY_FONT), seed?.fontSp ?: OverlayDefaults.FONT_SP
            ),
            bold = sp.getBoolean(styleKey(dark, KEY_BOLD), seed?.bold ?: OverlayDefaults.BOLD),
            maxLines = sp.getInt(
                styleKey(dark, KEY_LINES), seed?.maxLines ?: OverlayDefaults.MAX_LINES
            ),
            lineMode = LineMode.of(
                sp.getString(styleKey(dark, KEY_LINE_MODE), seed?.lineMode?.id)
            ),
            opacity = sp.getFloat(
                styleKey(dark, KEY_OPACITY), seed?.opacity ?: OverlayDefaults.OPACITY
            ),
            cornerDp = sp.getFloat(
                styleKey(dark, KEY_CORNER), seed?.cornerDp ?: OverlayDefaults.CORNER_DP
            ),
            stroke = sp.getBoolean(
                styleKey(dark, KEY_STROKE), seed?.stroke ?: OverlayDefaults.STROKE
            ),
            secondaryAlpha = sp.getFloat(
                styleKey(dark, KEY_SECONDARY_ALPHA),
                seed?.secondaryAlpha ?: OverlayDefaults.SECONDARY_ALPHA
            ),
            align = align,
            secondaryAlign = secondaryAlign,
            lineOffsetDp = sp.getFloat(
                styleKey(dark, KEY_LINE_OFFSET),
                seed?.lineOffsetDp ?: OverlayDefaults.LINE_OFFSET_DP
            ),
            fadeMs = sp.getInt(styleKey(dark, KEY_FADE), seed?.fadeMs ?: OverlayDefaults.FADE_MS),
            primaryColor = sp.getInt(
                styleKey(dark, KEY_PRIMARY_COLOR),
                seed?.primaryColor ?: OverlayDefaults.PRIMARY_COLOR
            ),
            secondaryColor = sp.getInt(
                styleKey(dark, KEY_SECONDARY_COLOR),
                seed?.secondaryColor ?: OverlayDefaults.SECONDARY_COLOR
            ),
            bgColor = sp.getInt(
                styleKey(dark, KEY_BG_COLOR), seed?.bgColor ?: OverlayDefaults.BG_COLOR
            ),
            strokeColor = sp.getInt(
                styleKey(dark, KEY_STROKE_COLOR),
                seed?.strokeColor ?: OverlayDefaults.STROKE_COLOR
            ),
            widthRatio = sp.getFloat(
                styleKey(dark, KEY_WIDTH_RATIO),
                seed?.widthRatio ?: OverlayDefaults.WIDTH_RATIO
            ),
            heightRatio = sp.getFloat(
                styleKey(dark, KEY_HEIGHT_RATIO),
                seed?.heightRatio ?: OverlayDefaults.HEIGHT_RATIO
            ),
            titleEnabled = sp.getBoolean(
                styleKey(dark, KEY_TITLE_ENABLED),
                seed?.titleEnabled ?: OverlayDefaults.TITLE_ENABLED
            ),
            titleFontSp = sp.getFloat(
                styleKey(dark, KEY_TITLE_FONT),
                seed?.titleFontSp ?: OverlayDefaults.TITLE_FONT_SP
            ),
            titleColor = sp.getInt(
                styleKey(dark, KEY_TITLE_COLOR),
                seed?.titleColor ?: OverlayDefaults.TITLE_COLOR
            ),
            titlePos = TitlePos.of(
                sp.getString(styleKey(dark, KEY_TITLE_POS), seed?.titlePos?.id)
            ),
            titleAlign = titleAlign,
            // 0.2.28：三行可调项对齐（副行补字号/加粗，标题补透明度/加粗）+ 逐字两项。
            secondaryFontSp = sp.getFloat(
                styleKey(dark, KEY_SECONDARY_FONT),
                seed?.secondaryFontSp ?: OverlayDefaults.SECONDARY_FONT_SP
            ),
            secondaryBold = sp.getBoolean(
                styleKey(dark, KEY_SECONDARY_BOLD),
                seed?.secondaryBold ?: OverlayDefaults.SECONDARY_BOLD
            ),
            titleAlpha = sp.getFloat(
                styleKey(dark, KEY_TITLE_ALPHA), seed?.titleAlpha ?: OverlayDefaults.TITLE_ALPHA
            ),
            titleBold = sp.getBoolean(
                styleKey(dark, KEY_TITLE_BOLD), seed?.titleBold ?: OverlayDefaults.TITLE_BOLD
            ),
            wordSungColor = sp.getInt(
                styleKey(dark, KEY_WORD_SUNG_COLOR),
                seed?.wordSungColor ?: OverlayDefaults.WORD_SUNG_COLOR
            ),
            wordUnsungAlpha = sp.getFloat(
                styleKey(dark, KEY_WORD_UNSUNG_ALPHA),
                seed?.wordUnsungAlpha ?: OverlayDefaults.WORD_UNSUNG_ALPHA
            )
        )
        )
    }

    /** 写当前主题那一套；另一套保持原样。 */
    fun saveStyle(style: OverlayStyle) {
        val safe = OverlayDefaults.sanitize(style)
        val dark = styleIsDark()
        sp.edit()
            .putString(styleKey(dark, KEY_PRESET), safe.preset.id)
            .putFloat(styleKey(dark, KEY_FONT), safe.fontSp)
            .putBoolean(styleKey(dark, KEY_BOLD), safe.bold)
            .putInt(styleKey(dark, KEY_LINES), safe.maxLines)
            .putString(styleKey(dark, KEY_LINE_MODE), safe.lineMode.id)
            .putFloat(styleKey(dark, KEY_OPACITY), safe.opacity)
            .putFloat(styleKey(dark, KEY_CORNER), safe.cornerDp)
            .putBoolean(styleKey(dark, KEY_STROKE), safe.stroke)
            .putFloat(styleKey(dark, KEY_SECONDARY_ALPHA), safe.secondaryAlpha)
            .putString(styleKey(dark, KEY_ALIGN), safe.align.id)
            .putString(styleKey(dark, KEY_SECONDARY_ALIGN), safe.secondaryAlign.id)
            .putFloat(styleKey(dark, KEY_LINE_OFFSET), safe.lineOffsetDp)
            .putInt(styleKey(dark, KEY_FADE), safe.fadeMs)
            .putInt(styleKey(dark, KEY_PRIMARY_COLOR), safe.primaryColor)
            .putInt(styleKey(dark, KEY_SECONDARY_COLOR), safe.secondaryColor)
            .putInt(styleKey(dark, KEY_BG_COLOR), safe.bgColor)
            .putInt(styleKey(dark, KEY_STROKE_COLOR), safe.strokeColor)
            .putFloat(styleKey(dark, KEY_WIDTH_RATIO), safe.widthRatio)
            .putFloat(styleKey(dark, KEY_HEIGHT_RATIO), safe.heightRatio)
            .putBoolean(styleKey(dark, KEY_TITLE_ENABLED), safe.titleEnabled)
            .putFloat(styleKey(dark, KEY_TITLE_FONT), safe.titleFontSp)
            .putInt(styleKey(dark, KEY_TITLE_COLOR), safe.titleColor)
            .putString(styleKey(dark, KEY_TITLE_POS), safe.titlePos.id)
            .putString(styleKey(dark, KEY_TITLE_ALIGN), safe.titleAlign.id)
            .putFloat(styleKey(dark, KEY_SECONDARY_FONT), safe.secondaryFontSp)
            .putBoolean(styleKey(dark, KEY_SECONDARY_BOLD), safe.secondaryBold)
            .putFloat(styleKey(dark, KEY_TITLE_ALPHA), safe.titleAlpha)
            .putBoolean(styleKey(dark, KEY_TITLE_BOLD), safe.titleBold)
            .putInt(styleKey(dark, KEY_WORD_SUNG_COLOR), safe.wordSungColor)
            .putFloat(styleKey(dark, KEY_WORD_UNSUNG_ALPHA), safe.wordUnsungAlpha)
            .apply()
        // 两套一起失效：夜间没单独设置过时是「继承日间」，只清一边会读到过期值。
        lightStyleCache = null
        darkStyleCache = null
    }

    fun resetStyle() {
        saveStyle(OverlayDefaults.STYLE)
    }

    fun ratioX(landscape: Boolean): Float = sp.getFloat(
        if (landscape) KEY_RATIO_X_LAND else KEY_RATIO_X_PORT,
        OverlayDefaults.RATIO_X
    )

    fun ratioY(landscape: Boolean): Float = sp.getFloat(
        if (landscape) KEY_RATIO_Y_LAND else KEY_RATIO_Y_PORT,
        OverlayDefaults.RATIO_Y
    )

    fun setRatio(x: Float, y: Float, landscape: Boolean) {
        sp.edit()
            .putFloat(if (landscape) KEY_RATIO_X_LAND else KEY_RATIO_X_PORT, x)
            .putFloat(if (landscape) KEY_RATIO_Y_LAND else KEY_RATIO_Y_PORT, y)
            .apply()
    }

    fun resetPosition() {
        setRatio(OverlayDefaults.RATIO_X, OverlayDefaults.RATIO_Y, landscape = false)
        setRatio(OverlayDefaults.RATIO_X, OverlayDefaults.RATIO_Y, landscape = true)
    }

    companion object {
        /**
         * 样式缓存：读一次要碰 20 个 key，而渲染、设置页每一行、每次刷新都会读。
         * 写入口只有 [saveStyle]，写完就失效。放在 companion 里是因为服务与设置页
         * 各持有一个 Prefs 实例，缓存必须共用同一份，否则一边改了另一边看不到。
         *
         * 日间 / 夜间各一份：两套外观是独立事实，共用一份必然在切主题时读到对面那套。
         */
        @Volatile
        private var lightStyleCache: OverlayStyle? = null

        @Volatile
        private var darkStyleCache: OverlayStyle? = null

        /**
         * 偏移表缓存：换歌、渲染、校准页刷新都会读。
         *
         * 与样式缓存同理放在 companion —— 服务与设置页各持有一个 [Prefs] 实例，
         * 缓存不共用的话，校准页调完偏移服务那边读到的还是旧值。
         */
        @Volatile
        private var offsetCache: MutableMap<String, Int>? = null

        private val offsetLock = Any()

        private const val NAME = "anylyric"
        private const val KEY_DEBUG = "debug_enabled"
        private const val KEY_HIDE_NO_LYRICS = "hide_when_no_lyrics"
        private const val KEY_HIDE_BY_APP = "hide_by_app_enabled"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val KEY_A11Y_REQUESTED = "a11y_requested"
        private const val KEY_PINNED_SESSION = "pinned_session_package"
        private const val KEY_SETTINGS_TAB = "settings_tab"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DISPLAY_ID = "display_id"

        private const val KEY_PRESET = "style_preset"
        private const val KEY_FONT = "font_sp"
        private const val KEY_BOLD = "style_bold"
        private const val KEY_LINES = "style_lines"
        private const val KEY_LINE_MODE = "style_line_mode"
        private const val KEY_OPACITY = "style_opacity"
        private const val KEY_CORNER = "style_corner_dp"
        private const val KEY_STROKE = "style_stroke"
        private const val KEY_SECONDARY_ALPHA = "style_secondary_alpha"
        private const val KEY_ALIGN = "style_align"
        private const val KEY_SECONDARY_ALIGN = "style_secondary_align"
        private const val KEY_TITLE_ALIGN = "style_title_align"
        private const val KEY_LINE_OFFSET = "style_line_offset_dp"
        private const val KEY_FADE = "style_fade_ms"
        private const val KEY_PRIMARY_COLOR = "style_primary_color"
        private const val KEY_SECONDARY_COLOR = "style_secondary_color"
        private const val KEY_BG_COLOR = "style_bg_color"
        private const val KEY_STROKE_COLOR = "style_stroke_color"
        private const val KEY_WIDTH_RATIO = "style_width_ratio"
        private const val KEY_HEIGHT_RATIO = "style_height_ratio"
        private const val KEY_TITLE_ENABLED = "style_title_enabled"
        private const val KEY_TITLE_FONT = "style_title_font_sp"
        private const val KEY_TITLE_COLOR = "style_title_color"
        private const val KEY_TITLE_POS = "style_title_pos"
        private const val KEY_SECONDARY_FONT = "style_secondary_font_sp"
        private const val KEY_SECONDARY_BOLD = "style_secondary_bold"
        private const val KEY_TITLE_ALPHA = "style_title_alpha"
        private const val KEY_TITLE_BOLD = "style_title_bold"
        private const val KEY_WORD_SUNG_COLOR = "style_word_sung_color"
        private const val KEY_WORD_UNSUNG_ALPHA = "style_word_unsung_alpha"
        private const val KEY_HANDLE = "overlay_handle_enabled"
        private const val KEY_UPDATE_ROUTE = "update_route"
        private const val KEY_BOOT_AUTOSTART = "boot_autostart"
        private const val KEY_WATCHDOG = "watchdog_enabled"
        private const val KEY_ONLINE_LYRICS = "online_lyrics_enabled"
        private const val KEY_ONLINE_OVERRIDE = "online_overrides_local"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_LYRIC_CACHE = "lyric_cache_limit"
        private const val KEY_ONLINE_SOURCE = "online_source_pinned"
        private const val KEY_ONLINE_ORDER = "online_source_order"
        private const val KEY_LYRIC_OFFSETS = "lyric_offsets"
        private const val KEY_OFFSET_DEFAULT = "lyric_offset_default"
        private const val KEY_SHOW_TRANSLATION = "show_translation"
        private const val KEY_WORD_BY_WORD = "word_by_word"
        private const val KEY_CALIBRATE_ON_LAUNCH = "calibrate_on_launch"
        private const val KEY_LAST_ALIVE = "last_alive_at"

        /** 心跳落盘间隔：看门狗的过期阈值是 20 分钟，5 分钟一次足够，且写盘量很小。 */
        private const val ALIVE_WRITE_INTERVAL_MS = 5 * 60_000L

        private const val KEY_RATIO_X_PORT = "ratio_x_port"
        private const val KEY_RATIO_Y_PORT = "ratio_y_port"
        private const val KEY_RATIO_X_LAND = "ratio_x_land"
        private const val KEY_RATIO_Y_LAND = "ratio_y_land"

        const val DEFAULT_DISPLAY_ID = 0

        /** 缓存上限可选项（首）。0 = 不缓存；界面上按这个顺序排胶囊。 */
        val CACHE_LIMITS = intArrayOf(0, 120, 240, 480, 1000)
        const val CACHE_LIMIT_DEFAULT = 240
        const val CACHE_LIMIT_MAX = 2000
    }
}
