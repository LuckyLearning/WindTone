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
    const val DYNAMIC_THEME = true
    const val CAR_MODE = false

    const val SPEED = 1.0f

    /** 变调默认 = 原调（半音）。合法区间见 `playback/Pitch` */
    const val PITCH_SEMITONES = 0

    const val SKIP_SILENCE = false

    /**
     * 打开应用时是否从上次的进度接着播（v1.1.16）。
     *
     * 默认**开启**：本项目一直只做到「恢复队列但停在原处」，用户每次上车
     * 都得再点一次播放键 —— 车机上那一下恰好是最不顺手的（要看路）。
     * 关掉它的语义回到老行为：队列与进度照旧恢复，只是不自动起播。
     */
    const val AUTO_RESUME = true

    const val LYRIC_SCALE = 1.0f
    /** 点击歌词行是否跳转进度：默认关闭（拖动歌词找人声段落时误跳很烦） */
    const val LYRIC_TAP_SEEKS = false
    /** 开机后台自启播放：默认关（要用户明确打开，避免一上车就突然出声） */
    const val AUTO_PLAY_ON_BOOT = false

    /**
     * 双语歌词显示模式（v1.1.18）：0 中英都显示 / 1 只显示中文 / 2 只显示英文。
     *
     * 默认 0 = 与历史行为完全一致：老用户升级后不会突然少掉译文那一行。
     * 取值与 `lyrics/BilingualMode.index` 一一对应。
     */
    const val BILINGUAL = 0

    const val LYRIC_OVERLAY = false
    const val OVERLAY_LINES = 1
    const val OVERLAY_TEXT_SCALE = 1

    /**
     * 悬浮窗最上面是否显示「歌名 + 歌手」两行小字（v1.1.15）。
     *
     * 默认关闭：老用户看到的还是原来那块纯歌词字幕，不会因为升级突然多出两行。
     */
    const val OVERLAY_SHOW_TITLE = false

    /** 歌名 / 歌手两行小字的字号档位：0 小（默认）/ 1 中 / 2 大 */
    const val OVERLAY_TITLE_SCALE = 0

    /** 悬浮歌词正文行数的合法区间（1..5）：行数越多占屏越高，5 行是上限 */
    val OVERLAY_LINES_RANGE = 1..5

    /**
     * 悬浮歌词「全屏高度」（v1.1.19，默认关闭）。
     *
     * 打开后窗口铺满屏宽、高度 = 屏高 − [OVERLAY_DOCK_RESERVED_DP]，内容改按
     * **播放页歌词区**的排版：当前行居中放大高亮，上下各带若干句压暗的上下文。
     * 默认关闭是为了不影响老用户：原来的单条字幕仍然默认行为。
     */
    const val OVERLAY_FULL_HEIGHT = false

    /**
     * 全屏高度模式下底部让出的高度（dp）。
     *
     * 车机（DiLink 等）底部有一条系统固定的 Dock / 按钮栏，悬浮窗压上去会盖住
     * 返回、空调这些常驻键 —— v1.1.14 的「设置点不到」就是这么来的。
     * 手机上没有这条 Dock，让出 56dp 也只是避开了手势条，不碍事。
     */
    const val OVERLAY_DOCK_RESERVED_DP = 56

    /**
     * 全屏模式下的窗口宽度（屏宽百分比，默认 100）。
     *
     * 与单条字幕的 [OVERLAY_WIDTH_PERCENT] **分开存**：两种形态在用户心里是两件事，
     * 全屏歌词面板通常要铺满整块屏，单条字幕则要留出能拖动的余量。
     * 合用一个键的话，调窄单条字幕会顺手把整屏歌词也缩窄，反之亦然。
     */
    const val OVERLAY_FULL_WIDTH_PERCENT = 100

    /** 全屏宽度百分比的合法区间：再窄放不下 11 行歌词 */
    val OVERLAY_FULL_WIDTH_RANGE = 30..100

    /**
     * 全屏模式下的水平位置，存的是「可移动余量的百分比」（0 = 最左 / 50 = 居中 / 100 = 最右）。
     *
     * 不存像素：全屏窗口的宽度会变，余量跟着变；存百分比才能让「居中」永远是居中。
     */
    const val OVERLAY_FULL_OFFSET_PERCENT = 50

    /** 水平位置百分比的合法区间 */
    val OVERLAY_FULL_OFFSET_RANGE = 0..100

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

    /**
     * 悬浮窗里的歌词文字颜色（纯白）。
     *
     * ⚠️ 这里原先是「**未唱**部分的文字颜色」，与「已唱部分的颜色」成对存在。
     * 逐字染色移除后（v1.1.8）它就是悬浮窗文字的唯一颜色 ——
     * 当前行与其它行的区分交给「最亮最粗 vs 压暗」，不再靠颜色。
     */
    const val OVERLAY_TEXT_COLOR = 0xFFFFFFFF.toInt()

    const val MIN_DURATION_SEC = 60
    const val AUTO_UPDATE = true
    val UPDATE_ROUTE = DownloadRoute.AUTO

    /** 悬浮窗宽度百分比的合法区间 */
    val OVERLAY_WIDTH_RANGE = 1..100

    /**
     * 「不显示悬浮窗的应用」出厂名单：**空**。
     *
     * 空 = 功能完全不介入（检测通道都不启动）。勾选入口在
     * 设置 → 歌词 → 桌面悬浮歌词 →「不显示悬浮窗的应用」弹窗里，
     * 用户自己在车机上勾（设计见 docs/设计-悬浮窗按应用隐藏.md）。
     */
    val OVERLAY_HIDDEN_APPS: Set<String> = emptySet()

    /** 「前台应用记录」出厂值：关（行为记录要用户显式开启） */
    const val FOREGROUND_LOG_ENABLED = false
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

    /**
     * 开机后台自启播放（v1.1.24，默认关）。
     *
     * 打开后：开机广播里**不打开主界面**，只把播放服务拉起来，从数据库恢复上次队列并直接起播。
     * 车机场景的诉求是「上车通电就有歌」，而弹一个全屏界面会挡住导航 / 桌面。
     * 与「车载模式 + 启动直进播放页」的区别就在这里：那条要界面，这条不要。
     */
    var autoPlayOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTO_PLAY_ON_BOOT, SettingsDefaults.AUTO_PLAY_ON_BOOT)
        set(value) = sp.edit().putBoolean(KEY_AUTO_PLAY_ON_BOOT, value).apply()

    /**
     * 点击歌词行是否跳转到那一句（默认**关闭**，2026-10-18 用户要求）。
     *
     * 为什么默认关：车机上歌词区要上下拖动才看得全，而「点一下某行就跳过去」意味着
     * 手指在歌词上滑动时稍有位移就被判成点击，播放进度莫名其妙跳到别的段落。
     * 想用「点歌词跳转」的人可以自己打开。
     */
    var lyricTapSeeks: Boolean
        get() = sp.getBoolean(KEY_LYRIC_TAP_SEEKS, SettingsDefaults.LYRIC_TAP_SEEKS)
        set(value) = sp.edit().putBoolean(KEY_LYRIC_TAP_SEEKS, value).apply()

    /** 歌词字号缩放 */
    var lyricScale: Float
        get() = sp.getFloat(KEY_LYRIC_SCALE, SettingsDefaults.LYRIC_SCALE)
        set(value) = sp.edit().putFloat(KEY_LYRIC_SCALE, value).apply()

    /**
     * 双语歌词显示模式：0 中英都显示（默认）/ 1 只显示中文 / 2 只显示英文。
     *
     * 只影响**显示**：歌词文件、导出的 LRC、解析结果都保持原样。
     * 夹取到 0..2，防旧数据或手改写出越界值（`BilingualMode.of` 也会兜底）。
     */
    var lyricBilingual: Int
        get() = sp.getInt(KEY_LYRIC_BILINGUAL, SettingsDefaults.BILINGUAL).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_LYRIC_BILINGUAL, value.coerceIn(0, 2)).apply()

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
     * 桌面悬浮歌词一次显示几行：1..5（默认 1 = 只显示当前行）。
     *
     * 行数 > 1 时当前行居中最亮，上下各带若干行上下文（压暗），
     * 车机 / 大屏上尤其有用，不必等下一句冒出来才知道上下文。
     * 取值范围收在 [SettingsDefaults.OVERLAY_LINES_RANGE] 内，非法值就地夹取
     * （防旧数据 / 手改）；老版本只写过 1 与 3，读出来天然合法。
     */
    var lyricOverlayLines: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_LINES, SettingsDefaults.OVERLAY_LINES)
            .coerceIn(SettingsDefaults.OVERLAY_LINES_RANGE.first, SettingsDefaults.OVERLAY_LINES_RANGE.last)
        set(value) = sp.edit().putInt(
            KEY_LYRIC_OVERLAY_LINES,
            value.coerceIn(SettingsDefaults.OVERLAY_LINES_RANGE.first, SettingsDefaults.OVERLAY_LINES_RANGE.last),
        ).apply()

    /** 悬浮窗最上面是否显示「歌名 + 歌手」两行小字（默认关闭） */
    var lyricOverlayShowTitle: Boolean
        get() = sp.getBoolean(KEY_LYRIC_OVERLAY_TITLE, SettingsDefaults.OVERLAY_SHOW_TITLE)
        set(value) = sp.edit().putBoolean(KEY_LYRIC_OVERLAY_TITLE, value).apply()

    /**
     * 悬浮歌词「全屏高度」（默认关闭）。
     *
     * 打开后：窗口宽度铺满屏宽、高度 = 屏高 − 56dp（让开底部车机 Dock），
     * 位置固定在左上角（拖动与「调整位置」随之失效），内容按播放页歌词区排版 ——
     * 当前行居中放大、上下各 5 句压暗上下文、译文紧随当前行。
     *
     * 与 [lyricOverlayLines] / [lyricOverlayWidthPercent] 的关系：这两项在全屏
     * 模式下不生效（行数与宽度由屏幕决定），但**不清空它们的值** ——
     * 关掉全屏后用户原来的单条字幕设置还在。
     */
    var lyricOverlayFullHeight: Boolean
        get() = sp.getBoolean(KEY_LYRIC_OVERLAY_FULL_HEIGHT, SettingsDefaults.OVERLAY_FULL_HEIGHT)
        set(value) = sp.edit().putBoolean(KEY_LYRIC_OVERLAY_FULL_HEIGHT, value).apply()

    /**
     * 全屏模式下的窗口宽度（屏宽百分比）。
     *
     * 与 [lyricOverlayWidthPercent] 各存一份，互不影响（见
     * [SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT] 的说明）。
     * 宽度为 100% 时横向余量为 0，此时 [lyricOverlayFullOffsetPercent] 调了也不动 ——
     * 设 `full_width` 的地方要顺带把这件事讲清楚，别再让用户把它当 bug。
     */
    var lyricOverlayFullWidthPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_FULL_WIDTH, SettingsDefaults.OVERLAY_FULL_WIDTH_PERCENT)
            .coerceIn(
                SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first,
                SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last,
            )
        set(value) = sp.edit().putInt(
            KEY_LYRIC_OVERLAY_FULL_WIDTH,
            value.coerceIn(
                SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first,
                SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last,
            ),
        ).apply()

    /**
     * 全屏模式下的水平位置：存「可移动余量的百分比」（0 最左 / 50 居中 / 100 最右）。
     *
     * 桌面拖动时把像素 x 反算成这个百分比再落盘，所以换分辨率、转屏之后
     * 「靠右」仍然靠右，不会因为绝对坐标过期而跑到屏幕外。
     */
    var lyricOverlayFullOffsetPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_FULL_OFFSET, SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT)
            .coerceIn(
                SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.first,
                SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.last,
            )
        set(value) = sp.edit().putInt(
            KEY_LYRIC_OVERLAY_FULL_OFFSET,
            value.coerceIn(
                SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.first,
                SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.last,
            ),
        ).apply()

    /**
     * 歌名 / 歌手两行小字的字号档位：0 小（默认）/ 1 中 / 2 大。
     *
     * 与正文 [lyricOverlayTextScale] 分开存：标题是配角，需要能独立调小，
     * 否则「正文调大」会连带把两行标题也顶上去，挤掉歌词。
     */
    var lyricOverlayTitleScale: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_TITLE_SCALE, SettingsDefaults.OVERLAY_TITLE_SCALE).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_TITLE_SCALE, value.coerceIn(0, 2)).apply()

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

    /** 悬浮窗里的歌词文字颜色（ARGB，默认纯白） */
    var lyricOverlayTextColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, SettingsDefaults.OVERLAY_TEXT_COLOR)
        set(value) = sp.edit().putInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, value).apply()

    // ---------------- v1.1.29 新增：悬浮窗按应用隐藏 ----------------

    /**
     * 这些应用（包名）在前台时，收起悬浮歌词 —— 默认**空**，即功能完全不介入。
     *
     * 动机（2026-10-19 用户车机反馈）：打开 360 全景影像等全屏应用时，
     * 悬浮歌词浮在画面上方挡住视线；影像 / 泊车 / 视频类不止一个应用需要回避。
     * 系统级整合（7 分屏白名单 / 小迪默认音乐）已证伪走不通，只能在自己 App 内解决：
     * 检测到前台应用命中名单就临时收起，退出自动恢复。
     *
     * 空名单时**检测通道都不启动**（零开销）；名单非空或「前台应用记录」开着时才跑。
     * 检测手段（无障碍服务 / UsageStats）需要用户在系统设置里手动授权，
     * **两个都没授权时按「不隐藏」放行** —— 绝不让权限问题导致悬浮窗反而消失。
     *
     * ⚠️ setter 里拷贝一份：`getStringSet` 返回的实例不能被就地改，
     * 调用方（设置页勾选 / 诊断页）一律用新集合写回。
     */
    var lyricOverlayHiddenApps: Set<String>
        get() = sp.getStringSet(KEY_LYRIC_OVERLAY_HIDDEN_APPS, SettingsDefaults.OVERLAY_HIDDEN_APPS)
            ?: SettingsDefaults.OVERLAY_HIDDEN_APPS
        set(value) = sp.edit().putStringSet(KEY_LYRIC_OVERLAY_HIDDEN_APPS, HashSet(value)).apply()

    /**
     * 是否记录「前台应用切换」历史（默认 **false**）。
     *
     * 记录只写本机 `files/foreground_app_log.tsv`（滚动 300 条），
     * 用于诊断页查看 / 复制 / 清空 —— 悬浮窗的价值全在「听风在后台」时，
     * 实时显示反而看不到，所以主要靠这份历史：正常用车 → 停车后打开听风 →
     * 看历史 → 把 360 等应用勾进 [lyricOverlayHiddenApps]。
     * 这是行为记录，**默认关**，用户在设置里显式打开才记。
     */
    var foregroundLogEnabled: Boolean
        get() = sp.getBoolean(KEY_FOREGROUND_LOG_ENABLED, SettingsDefaults.FOREGROUND_LOG_ENABLED)
        set(value) = sp.edit().putBoolean(KEY_FOREGROUND_LOG_ENABLED, value).apply()

    // ---------------- 批次 6 新增 ----------------

    /** 跳过音频文件里的静音段（整轨/现场录音常用） */
    var skipSilence: Boolean
        get() = sp.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) = sp.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()

    /**
     * 打开应用时是否从上次的进度接着播（默认开启）。
     *
     * 与「自动续播」相关的另一半在数据库里：`player_state` 每 5 秒把当前曲目与
     * 进度写一次，`queue_songs` 存整个队列。本键只决定「恢复完要不要顺手 play」，
     * 关掉它不影响恢复 —— 队列、当前曲、进度依旧会摆好，只是停在暂停态。
     */
    var autoResume: Boolean
        get() = sp.getBoolean(KEY_AUTO_RESUME, SettingsDefaults.AUTO_RESUME)
        set(value) = sp.edit().putBoolean(KEY_AUTO_RESUME, value).apply()

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

    /**
     * 混响档位 = [com.luckylearning.windtone.playback.ReverbPresets] 的 id。
     *
     * 空串 = 从未选过（等价于「关闭」），与 [eqPresetId] 的「空串 = 没选过 / 自定义」同一口径：
     * 出厂的语义是「什么都没选过」，而不是「选了关闭这一档」。
     * 界面读到空串时把「关闭」显示成选中，写回时一律写具体 id。
     */
    var eqReverbId: String
        get() = sp.getString(KEY_EQ_REVERB_ID, "").orEmpty()
        set(value) = sp.edit().putString(KEY_EQ_REVERB_ID, value).apply()

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
        const val KEY_LYRIC_TAP_SEEKS = "lyric_tap_seeks"
        const val KEY_AUTO_PLAY_ON_BOOT = "auto_play_on_boot"
        const val KEY_LYRIC_BILINGUAL = "lyric_bilingual_mode"
        // ⚠️ 逐字歌词移除后（v1.1.8）有三个键不再读写：`lyric_word_by_word` /
        //    `lyric_sung_color` / `lyric_overlay_sung_color`。它们仍留在老用户的
        //    SharedPreferences 里，**刻意不做清理** —— 为三个死键写迁移代码不划算，
        //    且新装用户根本不会产生它们。看到它们别再当 bug 排查。
        const val KEY_LYRICS_PANE = "lyrics_pane_visible"
        const val KEY_CAR_MODE = "car_mode"
        const val KEY_LYRIC_OVERLAY = "lyric_overlay"
        const val KEY_LYRIC_OVERLAY_X = "lyric_overlay_x"
        const val KEY_LYRIC_OVERLAY_Y = "lyric_overlay_y"
        const val KEY_LYRIC_OVERLAY_LINES = "lyric_overlay_lines"
        const val KEY_LYRIC_OVERLAY_TITLE = "lyric_overlay_show_title"
        const val KEY_LYRIC_OVERLAY_TITLE_SCALE = "lyric_overlay_title_scale"
        const val KEY_LYRIC_OVERLAY_TEXT_SCALE = "lyric_overlay_text_scale"
        const val KEY_LYRIC_OVERLAY_BACKDROP = "lyric_overlay_backdrop"
        const val KEY_LYRIC_OVERLAY_PAUSE_MODE = "lyric_overlay_pause_mode"
        const val KEY_LYRIC_OVERLAY_HIDDEN_APPS = "lyric_overlay_hidden_apps"
        const val KEY_FOREGROUND_LOG_ENABLED = "foreground_log_enabled"
        const val KEY_LYRIC_OVERLAY_ALIGN = "lyric_overlay_align"
        const val KEY_LYRIC_OVERLAY_WIDTH = "lyric_overlay_width_percent"
        const val KEY_LYRIC_OVERLAY_FULL_HEIGHT = "lyric_overlay_full_height"
        /** 全屏模式的宽度（屏宽百分比）与水平位置（余量百分比），与单条字幕分开存 */
        const val KEY_LYRIC_OVERLAY_FULL_WIDTH = "lyric_overlay_full_width_percent"
        const val KEY_LYRIC_OVERLAY_FULL_OFFSET = "lyric_overlay_full_offset_percent"
        const val KEY_LYRIC_OVERLAY_TEXT_COLOR = "lyric_overlay_text_color"
        const val KEY_DYNAMIC_THEME = "dynamic_theme"
        const val KEY_SPEED = "playback_speed"
        const val KEY_PITCH = "playback_pitch_semitones"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET_ID = "eq_preset_id"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_EQ_BASS = "eq_bass"
        const val KEY_EQ_VIRTUAL = "eq_virtual"
        const val KEY_EQ_LOUDNESS = "eq_loudness"
        const val KEY_EQ_REVERB_ID = "eq_reverb_id"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_AUTO_RESUME = "auto_resume_playback"
        const val KEY_AUTO_UPDATE = "auto_check_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        const val KEY_UPDATE_ROUTE = "update_download_route"
    }
}
