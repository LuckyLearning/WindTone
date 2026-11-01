package com.luckylearning.windtone.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.luckylearning.windtone.playback.ReplayGainMode
import com.luckylearning.windtone.R
import com.luckylearning.windtone.ui.AppStrings

/**
 * 歌曲排序方式。
 *
 * 注意：这里是**全集**，具体某个浏览视图能用哪几项由
 * `LibraryView.sortOptions` 裁剪（v1.1.4 起排序下沉到各视图，
 * 专辑列表不该出现「按时长」这种对它没意义的选项）。
 */
enum class SortOrder {
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
    DATE_ADDED,
    FILE_NAME,

    /** 文件夹视图专用：按目录完整路径排（同名目录靠它才能稳定区分） */
    FOLDER_PATH,

    /** 歌单视图专用：保持 M3U 里写死的顺序（用户自己拖出来的那个顺序） */
    PLAYLIST_ORDER,
    ;

    /**
     * 非 Compose 兜底文案（Toast / 旁路）。界面主路径走 UiText.labelRes。
     * AppStrings 未就绪时回落中文，避免进程早期空串。
     */
    val label: String
        get() {
            val id = when (this) {
                TITLE -> R.string.sort_title
                ARTIST -> R.string.sort_artist
                ALBUM -> R.string.sort_album
                DURATION -> R.string.sort_duration
                DATE_ADDED -> R.string.sort_date_added
                FILE_NAME -> R.string.sort_file_name
                FOLDER_PATH -> R.string.sort_folder_path
                PLAYLIST_ORDER -> R.string.sort_playlist_order
            }
            val s = AppStrings.s(id)
            return s.ifEmpty {
                when (this) {
                    TITLE -> "按标题"
                    ARTIST -> "按歌手"
                    ALBUM -> "按专辑"
                    DURATION -> "按时长"
                    DATE_ADDED -> "按添加时间"
                    FILE_NAME -> "按文件名"
                    FOLDER_PATH -> "按路径"
                    PLAYLIST_ORDER -> "按歌单内顺序"
                }
            }
        }
}

/**
 * 界面明暗模式（v1.2.5）。
 *
 * 存在数据层的只有「三选一」这个语义，**不含任何展示文案** ——
 * 标签走 `ui/UiText.kt` 的 `ThemeMode.labelRes()`，这样加语言不用动数据层。
 *
 * 顺序即设置页里选项的排列顺序（跟随系统 / 浅色 / 深色），
 * 界面按 `ordinal` 直接映射选中项，所以**不要随意调整枚举顺序**。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * 各设置项的**出厂默认值**，集中放这里。
 *
 * 为什么要集中：设置页从 v1.1.4 起每项右侧都有「↺ 恢复默认」，如果默认值散在
 * `Prefs` 的 getter 和设置页的常量里两处，迟早会出现「重置按钮写回的值」
 * 与「首次安装拿到的值」不一致 —— 那种 bug 用户很难描述清楚。
 * **改默认值只改这里**，`Prefs` 的 getter 与设置页的重置都引用它。
 * 一致性由 `SettingsDefaultsTest.prefsGettersAgreeWithSettingsDefaults` 逐项对质。
 */
object SettingsDefaults {
    /**
     * 界面明暗模式默认「跟随系统」。
     *
     * 默认值必须是它，而不是浅色或深色：升级到本功能的用户此前一直就是跟随系统的，
     * 默认值一变，所有老用户在系统深色下的观感会当场被改掉。
     */
    val THEME_MODE = ThemeMode.SYSTEM

    /** 换曲取色默认关：解码+取色抢 CPU/堆；车机主路径是悬浮窗跟词，不是壁纸色 */
    const val DYNAMIC_THEME = false
    const val CAR_MODE = false

    /** G7: bluetooth auto car layout (default off) */
    const val BT_AUTO_CAR_ENABLED = false
    /** true=any BT audio; false=remembered only */
    const val BT_AUTO_CAR_ANY_DEVICE = true
    const val BT_AUTO_CAR_DEVICES = ""
    const val BT_AUTO_CAR_RESTORE = true

    const val SPEED = 1.0f

    /**
     * 播放速度的合法区间（0.5..2.0）。
     *
     * 与其它区间常量放一起：速度是唯一「Prefs setter 不夹取」的数值设置
     * （`Prefs.playbackSpeed` 原样落盘），区间定义在这儿，
     * 由 `data/SettingsRepository.setSpeed` 读取后夹取。
     */
    val PLAYBACK_SPEED_RANGE = 0.5f..2.0f

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

    /**
     * 播放页左侧竖排是否显示「无障碍状态」按钮（v1.1.92，默认显示）。
     *
     * 默认开：车机上无障碍随时可能被系统关掉，而这个按钮是用户**唯一**能在
     * 播放页一眼看到「前台识别还活着吗」的入口 —— 藏在设置里等于没有。
     * 觉得碍眼的人可以关掉，关掉后不影响功能。
     */
    const val PLAYER_A11Y_ACTION = true

    /**
     * 悬浮窗左侧的侧钮（收起 / 播放暂停 / 打开播放页 / 无障碍，v1.2.0+）是否显示。
     *
     * 只在**车载模式**下才会真正挂出来 —— 鼠标键盘的手机上，那三颗钮纯属遮挡；
     * 车机上仍给一个开关，觉得挡视线的人可以关掉（关掉不影响按应用隐藏与收起逻辑本身）。
     */
    const val OVERLAY_HANDLES = true

    /** 单颗侧钮开关（v1.3.0）。默认全开：升级后观感与 v1.2.x 一致。 */
    const val OVERLAY_HANDLE_COLLAPSE = true
    const val OVERLAY_HANDLE_PLAY_PAUSE = true
    const val OVERLAY_HANDLE_PLAYER = true
    /**
     * 单颗侧钮：无障碍状态的显示策略（2026-10-31 由布尔升级为三档）。
     *
     * 0 = 不显示；1 = 仅无障碍**未运行**时显示；2 = 常显（默认，与升级前观感一致）。
     *
     * 为什么要有第 1 档：无障碍一开，这颗钮就只剩「看个绿点」的价值，而车机上
     * 屏幕位置很金贵；反过来无障碍掉了（DiLink 上很常见），它又是唯一的一键回
     * 系统无障碍页的入口 —— 「只在没开的时候出现」正好把两件事都照顾到。
     */
    const val OVERLAY_HANDLE_A11Y_MODE = 2

    /**
     * 悬浮窗侧钮上下顺序（B1）：CSV，id 为 collapse / play_pause / player。
     * 默认与改前写死顺序一致。解析见 OverlayCollapseHelper.mergeHandleOrder。
     */
    const val OVERLAY_HANDLE_ORDER = "collapse,play_pause,player,a11y"

    /**
     * 无缝播放（C1→A7）：Media3 单实例 + 连续 MediaItems 默认识别分轨 encoder delay/padding。
     * A7 起设置页改为只读说明（平台无关闭 API）；此常量仅保留给死键默认值，不再驱动 UI 开关。
     */
    const val GAPLESS_PLAYBACK = true

    /**
     * 在线后续曲预取（A11）。默认关：车机多本地，预取只浪费带宽/缓存；
     * 纯本地队列本就不进候选，此开关挡住「偶有在线」时的后台下载。
     */
    const val ONLINE_CACHE_PREFETCH = false

    /**
     * 当前曲并行分片下载（A11）。默认关：多连接抢带宽与 CPU；需要时可在设置打开。
     */
    const val ONLINE_PARALLEL_DOWNLOAD = false

    /** 开机后台自启播放：默认关（要用户明确打开，避免一上车就突然出声） */
    const val AUTO_PLAY_ON_BOOT = false

    const val LYRIC_OVERLAY = false

    /** 悬浮歌词行数默认值：1 = 只显示当前行（老行为不变） */
    const val OVERLAY_LINES = 1

    /**
     * 悬浮歌词正文字号默认值（sp）。
     *
     * v1.1.51 起字号改成**任意值**（滑杆 12–60sp），不再只有小 / 中 / 大三档。
     * 20sp 就是原来「中」档的值 —— 没动过设置的用户升级后看不出变化。
     */
    const val OVERLAY_TEXT_SP = 20

    /** 字号的合法区间（sp）：12 以下基本看不清，60 在车机上已经是半屏高的字 */
    val OVERLAY_TEXT_SP_RANGE = 12..60

    /**
     * 旧「小 / 中 / 大」三档对应的 sp —— **只用于老用户迁移**，设置页不再出现。
     *
     * 数值取自 v1.1.50 的换算（20sp × 0.82 / 1 / 1.25），四舍五入到整 sp。
     */
    val OVERLAY_TEXT_SP_BY_SCALE = intArrayOf(16, OVERLAY_TEXT_SP, 25)

    /**
     * 悬浮窗最上面是否显示「歌名 + 歌手」两行小字（v1.1.15）。
     *
     * 默认关闭：老用户看到的还是原来那块纯歌词字幕，不会因为升级突然多出两行。
     */
    const val OVERLAY_SHOW_TITLE = false

    /** 歌名 / 歌手两行小字的字号，存「正文的百分比」（v1.1.51），默认 62% */
    const val OVERLAY_TITLE_PERCENT = 62

    /** 标题占正文的百分比区间：再小看不清，再大就会挤掉歌词主体 */
    val OVERLAY_TITLE_PERCENT_RANGE = 40..100

    /** 旧「小 / 中 / 大」标题档对应的百分比 —— 只用于迁移（0.55 / 0.62 / 0.78） */
    val OVERLAY_TITLE_PERCENT_BY_SCALE = intArrayOf(55, OVERLAY_TITLE_PERCENT, 78)

    /**
     * 悬浮歌词正文行数的合法区间（0..5）。
     *
     * **0 = 自动**（v1.1.49，见 `overlay/OverlayLineFit`）：按窗口高度算出能放几行。
     * 1..5 是手选档，行数越多占屏越高。
     */
    val OVERLAY_LINES_RANGE = 0..5

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

    // ------------------------------------------------ 自定义尺寸 / 位置 / 背景（v1.1.48）

    /**
     * 单条字幕的窗口高度（屏高百分比）。**0 = 自动**（照旧按行数撑开）。
     *
     * 为什么保留「自动」：绝大多数人只想让它刚好装下几行歌词，给一个百分比滑块是
     * 给「想让它固定在屏幕某一小块区域、不被长句顶高」的场景用的。
     */
    const val OVERLAY_BOX_HEIGHT_PERCENT = 0

    /** 高度百分比的合法区间；>0 时不低于 [OVERLAY_BOX_HEIGHT_MIN_PERCENT]，否则会压成一条缝 */
    val OVERLAY_BOX_HEIGHT_RANGE = 0..100
    const val OVERLAY_BOX_HEIGHT_MIN_PERCENT = 10

    /** 全屏形态的窗口高度（屏高百分比）。100 = 屏高减车机 Dock 让位，与 v1.1.19 行为一致 */
    const val OVERLAY_FULL_HEIGHT_PERCENT = 100
    val OVERLAY_FULL_HEIGHT_RANGE = 30..100

    /** 位置百分比默认值：水平居中；单条默认略靠上，全屏贴顶 */
    const val OVERLAY_X_PERCENT = 50
    const val OVERLAY_Y_PERCENT = 12
    const val OVERLAY_FULL_Y_PERCENT = 0

    /**
     * 背景颜色默认值：深灰黑。
     *
     * 与旧的「半透明深色底板」同色相 —— 升级到可自定义背景时，没动过设置的用户
     * 看起来应该一模一样，而不是忽然变成另一种黑。
     */
    const val OVERLAY_BG_COLOR = 0xFF101014.toInt()

    /**
     * 背景不透明度默认值（%）。
     *
     * 55% ≈ 旧「半透明深色底板」那档（`Color.argb(140, 0, 0, 0)` = 54.9%）。
     * v1.1.51 把「无 / 半透明 / 实底」三档并进「不透明度 + 颜色」两项后，
     * 没动过设置的用户升级后看到的底板仍是同一块。
     */
    const val OVERLAY_BG_ALPHA_PERCENT = 55

    /** 不透明度区间：0 = 完全透明（无底板），100 = 实底 */
    val OVERLAY_BG_ALPHA_RANGE = 0..100

    /**
     * 旧「无 / 半透明 / 实底」三档对应的不透明度 —— 只用于迁移。
     *
     * 三档当时的不透明度分别是 0 / 140 与 228（都在纯黑上），换算成百分比是 0 / 55 / 89。
     */
    val OVERLAY_BG_ALPHA_BY_BACKDROP = intArrayOf(0, OVERLAY_BG_ALPHA_PERCENT, 89)

    /** 悬浮歌词水平对齐：0 居中 / 1 居左（取值与 `overlay/OverlayPositioner` 的 ALIGN_* 一致） */
    const val OVERLAY_ALIGN = 0
    const val OVERLAY_PAUSE_MODE = 0

    // ------------------------------------------------ 样式细节（v1.1.52，全部滑杆 / 取色）

    /** 悬浮窗背景圆角（dp）：全屏形态强制直角（圆角会在四角露出桌面） */
    const val OVERLAY_CORNER_RADIUS_DP = 14
    val OVERLAY_CORNER_RADIUS_RANGE = 0..28

    /** 背景边框宽度（dp）：0 = 无边框 */
    const val OVERLAY_BORDER_WIDTH_DP = 0
    val OVERLAY_BORDER_WIDTH_RANGE = 0..4

    /** 背景边框颜色：半透明白（宽度默认 0，这个色值只是滑杆拉起来时的预填） */
    const val OVERLAY_BORDER_COLOR = 0x66FFFFFF.toInt()

    /** 单条字幕的额外行距（dp）：与全屏模式的 10dp 各管各的 */
    const val OVERLAY_LINE_SPACING_DP = 2
    val OVERLAY_LINE_SPACING_RANGE = 0..12

    /** 当前行颜色：0 = 跟随「文字颜色」（默认），其余为 ARGB */
    const val OVERLAY_CURRENT_COLOR = 0

    /** 上下句压暗程度（不透明度 %）：42 与播放页 0.42 对齐；无底板时有保底下限 */
    const val OVERLAY_DIM_PERCENT = 42
    val OVERLAY_DIM_RANGE = 20..80

    /** 文字描边宽度（dp）：0 = 自动（有底板 3dp 柔光 / 无底板 2dp 硬描边） */
    const val OVERLAY_STROKE_WIDTH_DP = 0
    val OVERLAY_STROKE_WIDTH_RANGE = 0..4

    /** 描边颜色（默认纯黑） */
    const val OVERLAY_STROKE_COLOR = 0xFF000000.toInt()

    /** 换行动画时长（ms）：0 = 关闭动画（硬切） */
    const val OVERLAY_FADE_MS = 160
    val OVERLAY_FADE_RANGE = 0..500

    /** 标题显示内容：0 歌名 + 歌手（默认）/ 1 只歌名 / 2 只歌手 */
    const val OVERLAY_TITLE_MODE = 0

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

    /**
     * 调试分区是否已解锁：出厂 **false**（默认隐藏）。
     *
     * 排障工具（前台应用记录 / 悬浮歌词自检 / 崩溃日志 / 开机自启）对普通用户没有意义，
     * 摆在设置页里只会让人误点。隐藏入口 → 在「关于」连点 5 次解锁 → 调试区顶部有开关可关回去。
     * 只记「是否解锁」，不记连点次数 —— 次数是界面态，重启即清零。
     */
    const val DEBUG_UNLOCKED = false

    // ================================================================ 音效方案与播放过渡（v1.1.52）

    /** 手机模式当前音效方案（[com.luckylearning.windtone.playback.SoundSchemes] 的 id）；空串 = 没选过方案 */
    const val SOUND_SCHEME_ID = ""

    /** 车载模式当前音效方案；空串 = 进入车载模式不改声音 */
    const val CAR_SCHEME_ID = ""

    /**
     * 进入车载模式前的音效值快照（[com.luckylearning.windtone.playback.SoundStash] 编码）。
     * 空串 = 没有在车载模式里换过声音，退出时无需恢复。
     */
    const val SOUND_STASH = ""

    /** 切歌淡入淡出：出厂关（突然改变切歌手感比收益更吵） */
    const val FADE_ENABLED = false

    /** 淡入淡出时长（毫秒），单向各计 */
    const val FADE_DURATION_MS = 2000

    /** 淡入淡出时长的合法区间（毫秒）：0.5 秒以下基本无感，6 秒以上像卡带 */
    val FADE_DURATION_RANGE = 500..6000

    /** 左右声道平衡（#9）：-100..100，0=居中；走 Media3 ChannelMixing，不依赖 AudioEffect */
    const val STEREO_BALANCE = 0

    /** 曲间响度平衡：出厂关（需要后台分析音频，用户显式开启） */
    const val LOUDNESS_BALANCE = false
    const val REPLAY_GAIN_MODE = "TRACK"
    /** H2：片头固定跳过秒数；0 = 关闭。上限见 IntroOutroSkip.MAX_SECONDS */
    const val SKIP_INTRO_SECONDS = 0
    /** H2：片尾固定跳过秒数；0 = 关闭 */
    const val SKIP_OUTRO_SECONDS = 0

    /** 用户命名的均衡器预设（[com.luckylearning.windtone.playback.UserEqPresets] 编码）；空串 = 还没存过 */
    const val EQ_USER_PRESETS = ""

    /** E2 soft EQ: user confirm switch when hard EQ DEVICE_REJECTED (default off) */
    const val SOFT_EQ_USER_CONFIRM = false

    /** Active scene profile id ([com.luckylearning.windtone.playback.SceneProfiles]); empty = off */
    const val SCENE_ID = ""

    /** Snapshot before entering a scene ([com.luckylearning.windtone.playback.SceneStash]) */
    const val SCENE_STASH = ""
}

/**
 * 音乐库扫描范围。
 *
 * 默认 [UNSET]：安装后**不主动扫描任何位置**，等用户在界面上明确选择。
 *  - [FULL]：扫描全盘（系统媒体库 + 已授权时附带深度文件扫描）
 *  - [FOLDERS]：只扫描用户导入的目录（SAF）
 */
enum class ScanScope {
    UNSET,
    FULL,
    FOLDERS,
    ;

    val label: String
        get() {
            val id = when (this) {
                UNSET -> R.string.scope_unset
                FULL -> R.string.scope_full
                FOLDERS -> R.string.scope_folders
            }
            val s = AppStrings.s(id)
            return s.ifEmpty {
                when (this) {
                    UNSET -> "未选择"
                    FULL -> "全盘扫描"
                    FOLDERS -> "指定目录"
                }
            }
        }
}

/**
 * 更新线路（**检查更新与 APK 下载共用**）。
 *
 * 先直连 GitHub 官方地址；直连不通（国内很常见）时自动退到第三方加速镜像 ——
 * 检查更新本身只是一次几 KB 的 GET，多试几条线的代价远小于「直接超时、什么都查不到」。
 * 镜像有 CDN 缓存，刚发布的版本可能稍有滞后，因此**只在直连失败后才用**，
 * 且经镜像得出的「已是最新」会在界面注明可能延迟：
 *  - [AUTO]：直连优先，不通就依次退到镜像（默认，适合大多数网络）；
 *  - [DIRECT]：只用 GitHub 官方直链（最权威、最及时，但国内可能很慢）；
 *  - [GHPROXY_NET] / [GH_PROXY_COM]：固定走某条公共加速镜像（镜像只转发字节，不改内容）。
 *
 * [prefix] 为 `null` 表示「自动」（直链 + 全部镜像）；空串表示只走直链；
 * 其余为镜像前缀（给原始 github.com 地址拼前缀即生效）。
 */
enum class DownloadRoute(val prefix: String?) {
    /**
     * 自动：国内优先（jsDelivr Fastly/Gcore → gh-proxy → ghproxy → 官方直连兜底）。
     * [prefix] = null。
     */
    AUTO(null),
    /** 只用 raw.githubusercontent.com；国内常慢或超时。prefix 空串。 */
    DIRECT(""),
    /**
     * jsDelivr CDN（fastly 节点）。prefix 以 `jsdelivr:` 开头，不是 URL 前缀拼接。
     * 本机 2026-10 实测国内可达性明显好于 raw / 部分 ghproxy。
     */
    JSDELIVR("jsdelivr:fastly"),
    GH_PROXY_COM("https://gh-proxy.com/"),
    GHPROXY_NET("https://ghproxy.net/"),
    ;

    val label: String
        get() {
            val id = when (this) {
                AUTO -> R.string.drt_auto
                DIRECT -> R.string.drt_direct
                JSDELIVR -> R.string.drt_jsdelivr
                GH_PROXY_COM -> R.string.drt_gh_proxy_com
                GHPROXY_NET -> R.string.drt_ghproxy_net
            }
            val s = AppStrings.s(id)
            return s.ifEmpty {
                when (this) {
                    AUTO -> "自动"
                    DIRECT -> "直连 GitHub"
                    JSDELIVR -> "jsDelivr CDN"
                    GH_PROXY_COM -> "gh-proxy.com"
                    GHPROXY_NET -> "ghproxy.net"
                }
            }
        }

    val hint: String
        get() {
            val id = when (this) {
                AUTO -> R.string.drt_auto_hint
                DIRECT -> R.string.drt_direct_hint
                JSDELIVR -> R.string.drt_jsdelivr_hint
                GH_PROXY_COM -> R.string.drt_gh_proxy_com_hint
                GHPROXY_NET -> R.string.drt_ghproxy_net_hint
            }
            val s = AppStrings.s(id)
            return s.ifEmpty {
                when (this) {
                    AUTO -> "国内优先 CDN/镜像，失败再试官方"
                    DIRECT -> "只用官方地址，最及时；国内可能较慢"
                    JSDELIVR -> "国内节点较快，偶有缓存延迟"
                    GH_PROXY_COM -> "公共加速镜像"
                    GHPROXY_NET -> "公共加速镜像，备用"
                }
            }
        }
}

/**
 * 全应用共享偏好（SharedPreferences 薄封装）。
 *
 * ## 按域分组（v1.1.37 起）
 * 历史上各设置项是「谁加谁往末尾追加」的（文件里曾留下「批次 2/3 新增」这类时间戳注释），
 * 读起来像编年史而不是说明书。现按**功能域**重新分区，域名与 `SettingsDefaultsTest`
 * 的对账分组、设置页的分区一一对应：
 *
 *  1. 音乐库与扫描   2. 播放       3. 播放页歌词
 *  4. 均衡器与音效   5. 桌面悬浮歌词              6. 前台检测与调试
 *  7. 外观与主题     8. 应用内更新
 *
 * 分区只是可读性；属性名、存储键、默认值、夹取逻辑全部保持不变，调用方无感。
 * 下方 companion 的键常量按同一域名分区，与属性一一对位。
 *
 * ## 双写迁移（v1.1.37）
 * 存储正从 SharedPreferences 迁往 DataStore（见 [SettingsDataStore]）。分三步走，**本版只做第一步**：
 *
 *  - **第一步（本版）双写 + 搬存量**：每个 setter 同时写 SharedPreferences（同步）与
 *    DataStore（异步）；首次启动由 [SettingsDataStore.backfillFrom] 把存量灌进 DataStore。
 *    **读仍然只走 SharedPreferences** —— 见下方「为什么这一步不切读路径」。
 *  - **第二步（后续版本）切读**：待 DataStore 在真机上稳定跑够一个版本，再把 getter 改为读
 *    DataStore 的内存快照。届时 setter 需同步更新快照，否则「写完立刻回读」会读到旧值。
 *  - **第三步（再后续）删 SharedPreferences**：读已不依赖它，双写与 backfill 一并拆除。
 *
 * 双写放在这里而不是调用方，是为了「不管谁落盘，新存储都不会变旧」。
 *
 * ### 为什么这一步不切读路径
 * DataStore 的写是异步的，读它的内存快照就意味着「写完不能立刻读到」——
 * 单测里 `setSpeed(2.0f)` 紧接着回读得到 0.5f，正是这么翻车的。
 * 而 setter 同步更新的快照又会和「预热时装填的快照」抢先后（启动头几毫秒的竞态），
 * 治干净要么加锁要么加版本号，代价远大于收益。**先让新存储在只写模式下跑稳，再切读**，
 * 是存储迁移的标准做法：写切换与读切换分开，任何一步都能独立回滚。
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE)

    /** 新存储（DataStore）；与 [sp] 双写过渡 */
    private val store = SettingsDataStore(context)

    /**
     * 双写与预热用的作用域。
     *
     * 随本实例存活 —— 而本实例按 Application 缓存（见 companion 的 `invoke`），
     * 所以它实际上与应用同寿。
     *
     * 两个刻意的选择：
     *  1. **不用 `Dispatchers.Main`**：这些都是「落个盘、搬个存量」的杂活，没有任何理由
     *     占用主线程；更要紧的是主 looper 一旦繁忙（首帧、曲库全量扫描），迁移就会被拖着，
     *     而 Robolectric 的主 looper 是暂停的，挂上去的协程会直接挂死。
     *  2. **并行度锁成 1**（`limitedParallelism(1)`）：连续两次改同一个设置
     *     （如先 1.5 倍速再 2 倍速）必须**按派发顺序**落到 DataStore。若用线程池，
     *     两次异步写可能乱序落地，DataStore 停在旧值而 SharedPreferences 已是新值 ——
     *     两边从此分家。单线程串行则天然保序。
     *
     * DataStore 自己的磁盘 IO 在它内部另切线程，这里只是排队。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        // 搬存量。失败不影响启动：读一直走 SharedPreferences（那边数据始终完整），
        // 下次启动会再试一次（KEY_MIGRATED 没落下，backfill 会重跑）。
        ioScope.launch { runCatching { store.warmUp(sp) } }
    }

    // ============================================================ 1. 音乐库与扫描

    /** 扫描范围：默认未选择，装机后不自动扫描 */

    /**
     * 曲库「仅无损 / 按扩展名」筛选（H5）。
     * 扩展名落盘为逗号分隔小写串（空串 = 不限扩展名）；与 [LibraryFormatFilter] 同源，
     * 重启后仍生效。
     */
    var libraryFormatFilter: LibraryFormatFilter
        get() {
            val lossless = sp.getBoolean(KEY_LIB_LOSSLESS_ONLY, false)
            val raw = sp.getString(KEY_LIB_FORMAT_EXTS, "").orEmpty()
            val exts = raw.split(',')
                .map { normalizeAudioExtension(it.trim()) }
                .filter { it.isNotEmpty() }
                .toSet()
            return LibraryFormatFilter(losslessOnly = lossless, extensions = exts)
        }
        set(value) {
            val exts = value.extensions
                .map { normalizeAudioExtension(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .joinToString(",")
            sp.edit()
                .putBoolean(KEY_LIB_LOSSLESS_ONLY, value.losslessOnly)
                .putString(KEY_LIB_FORMAT_EXTS, exts)
                .apply()
            ioScope.launch {
                store.putBoolean(KEY_LIB_LOSSLESS_ONLY, value.losslessOnly)
                store.putString(KEY_LIB_FORMAT_EXTS, exts)
            }
        }

    var scanScope: ScanScope
        get() = runCatching { ScanScope.valueOf(sp.getString(KEY_SCAN_SCOPE, ScanScope.UNSET.name)!!) }
            .getOrDefault(ScanScope.UNSET)
        set(value) {
            sp.edit().putString(KEY_SCAN_SCOPE, value.name).apply()
            ioScope.launch { store.putString(KEY_SCAN_SCOPE, value.name) }
        }

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
        set(value) {
            sp.edit().putBoolean(KEY_SCAN_SCOPE_CHOSEN, value).apply()
            ioScope.launch { store.putBoolean(KEY_SCAN_SCOPE_CHOSEN, value) }
        }

    /**
     * 上一次**自动**扫描结束时记录的曲库指纹（见 `MediaStoreScanner.fingerprint`）。
     *
     * 只服务于冷启动节流：指纹没变就没必要把整个存储再走一遍。
     * 空串 = 从未记过，调用方应当照常扫描。
     */
    var lastScanFingerprint: String
        get() = sp.getString(KEY_LAST_SCAN_FP, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_LAST_SCAN_FP, value).apply()
            ioScope.launch { store.putString(KEY_LAST_SCAN_FP, value) }
        }

    /** 上一次扫描完成的时间戳（毫秒），0 表示从未扫过 */
    var lastScanAt: Long
        get() = sp.getLong(KEY_LAST_SCAN_AT, 0L)
        set(value) {
            sp.edit().putLong(KEY_LAST_SCAN_AT, value).apply()
            ioScope.launch { store.putLong(KEY_LAST_SCAN_AT, value) }
        }

    /** 是否启用「深度扫描」（读取同目录 .lrc 歌词文件） */
    var deepScanEnabled: Boolean
        get() = sp.getBoolean(KEY_DEEP_SCAN, false)
        set(value) {
            sp.edit().putBoolean(KEY_DEEP_SCAN, value).apply()
            ioScope.launch { store.putBoolean(KEY_DEEP_SCAN, value) }
        }

    /**
     * 短音频过滤阈值（秒）。
     * 时长已知且小于该值的音频不会进入曲库（用于滤掉提示音、转场音效等）；
     * 0 表示不过滤。默认 60 秒。时长未知的歌曲不受影响。
     */
    var minSongDurationSec: Int
        get() = sp.getInt(KEY_MIN_DURATION_SEC, SettingsDefaults.MIN_DURATION_SEC)
        set(value) {
            sp.edit().putInt(KEY_MIN_DURATION_SEC, value.coerceIn(0, 3600)).apply()
            ioScope.launch { store.putInt(KEY_MIN_DURATION_SEC, value.coerceIn(0, 3600)) }
        }

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
        val perView = KEY_SORT_PREFIX + viewName
        val raw = sp.getString(perView, null)
            ?: sp.getString(KEY_SORT, null)
            ?: return default
        return runCatching { SortOrder.valueOf(raw) }.getOrDefault(default)
    }

    fun setSortOrder(viewName: String, order: SortOrder) {
        val perView = KEY_SORT_PREFIX + viewName
        sp.edit().putString(perView, order.name).apply()
        ioScope.launch { store.putString(perView, order.name) }
    }

    /** 清掉某个视图的排序覆盖，让它回到 [LibraryView.defaultSort] */
    fun resetSortOrder(viewName: String) {
        val perView = KEY_SORT_PREFIX + viewName
        sp.edit().remove(perView).apply()
        ioScope.launch { store.remove(perView) }
    }

    /** 上次停留的浏览视图（歌曲 / 专辑 / 歌手 / 文件夹 / 歌单），重启后恢复 */
    var libraryView: String
        get() = sp.getString(KEY_VIEW, "SONGS").orEmpty()
        set(value) {
            sp.edit().putString(KEY_VIEW, value).apply()
            ioScope.launch { store.putString(KEY_VIEW, value) }
        }

    // ============================================================ 2. 播放

    /** 播放速度 */
    var playbackSpeed: Float
        get() = sp.getFloat(KEY_SPEED, SettingsDefaults.SPEED)
        set(value) {
            sp.edit().putFloat(KEY_SPEED, value).apply()
            ioScope.launch { store.putFloat(KEY_SPEED, value) }
        }

    /**
     * 变调（半音，-12..+12；0 = 原调）。
     *
     * 存半音而不是倍率：界面上用户看到的、能理解的单位就是「半音」，
     * 倍率只在写进 `PlaybackParameters` 那一刻由 [com.luckylearning.windtone.playback.Pitch] 换算。
     * 存倍率的话，界面每次都要反算 `12*log2(f)`，浮点误差会让「+0 半音」显示成「-0 半音」。
     */
    var playbackPitchSemitones: Int
        get() = sp.getInt(KEY_PITCH, SettingsDefaults.PITCH_SEMITONES)
        set(value) {
            sp.edit().putInt(KEY_PITCH, value).apply()
            ioScope.launch { store.putInt(KEY_PITCH, value) }
        }

    /** 跳过音频文件里的静音段（整轨/现场录音常用） */
    var skipSilence: Boolean
        get() = sp.getBoolean(KEY_SKIP_SILENCE, SettingsDefaults.SKIP_SILENCE)
        set(value) {
            sp.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
            ioScope.launch { store.putBoolean(KEY_SKIP_SILENCE, value) }
        }

    /**
     * 打开应用时是否从上次的进度接着播（默认开启）。
     *
     * 与「自动续播」相关的另一半在数据库里：`player_state` 每 5 秒把当前曲目与
     * 进度写一次，`queue_songs` 存整个队列。本键只决定「恢复完要不要顺手 play」，
     * 关掉它不影响恢复 —— 队列、当前曲、进度依旧会摆好，只是停在暂停态。
     */
    var autoResume: Boolean
        get() = sp.getBoolean(KEY_AUTO_RESUME, SettingsDefaults.AUTO_RESUME)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_RESUME, value).apply()
            ioScope.launch { store.putBoolean(KEY_AUTO_RESUME, value) }
        }

    /**
     * 开机后台自启播放（v1.1.24，默认关）。
     *
     * 打开后：开机广播里**不打开主界面**，只把播放服务拉起来，从数据库恢复上次队列并直接起播。
     * 车机场景的诉求是「上车通电就有歌」，而弹一个全屏界面会挡住导航 / 桌面。
     * 与「车载模式 + 启动直进播放页」的区别就在这里：那条要界面，这条不要。
     */
    var autoPlayOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTO_PLAY_ON_BOOT, SettingsDefaults.AUTO_PLAY_ON_BOOT)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_PLAY_ON_BOOT, value).apply()
            ioScope.launch { store.putBoolean(KEY_AUTO_PLAY_ON_BOOT, value) }
        }

    // ============================================================ 3. 播放页歌词

    /** 歌词字号缩放 */
    var lyricScale: Float
        get() = sp.getFloat(KEY_LYRIC_SCALE, SettingsDefaults.LYRIC_SCALE)
        set(value) {
            sp.edit().putFloat(KEY_LYRIC_SCALE, value).apply()
            ioScope.launch { store.putFloat(KEY_LYRIC_SCALE, value) }
        }

    /**
     * 点击歌词行是否跳转到那一句（默认**关闭**，2026-10-18 用户要求）。
     *
     * 为什么默认关：车机上歌词区要上下拖动才看得全，而「点一下某行就跳过去」意味着
     * 手指在歌词上滑动时稍有位移就被判成点击，播放进度莫名其妙跳到别的段落。
     * 想用「点歌词跳转」的人可以自己打开。
     */
    /** 单颗侧钮：收起（v1.3.0） */
    var overlayHandleCollapse: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_HANDLE_COLLAPSE, SettingsDefaults.OVERLAY_HANDLE_COLLAPSE)
        set(value) {
            sp.edit().putBoolean(KEY_OVERLAY_HANDLE_COLLAPSE, value).apply()
            ioScope.launch { store.putBoolean(KEY_OVERLAY_HANDLE_COLLAPSE, value) }
        }

    /** 单颗侧钮：播放 / 暂停（v1.3.0） */
    var overlayHandlePlayPause: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_HANDLE_PLAY_PAUSE, SettingsDefaults.OVERLAY_HANDLE_PLAY_PAUSE)
        set(value) {
            sp.edit().putBoolean(KEY_OVERLAY_HANDLE_PLAY_PAUSE, value).apply()
            ioScope.launch { store.putBoolean(KEY_OVERLAY_HANDLE_PLAY_PAUSE, value) }
        }

    /** 单颗侧钮：打开播放页（v1.3.0） */
    var overlayHandlePlayer: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_HANDLE_PLAYER, SettingsDefaults.OVERLAY_HANDLE_PLAYER)
        set(value) {
            sp.edit().putBoolean(KEY_OVERLAY_HANDLE_PLAYER, value).apply()
            ioScope.launch { store.putBoolean(KEY_OVERLAY_HANDLE_PLAYER, value) }
        }

    /**
     * 单颗侧钮：无障碍状态的显示策略（0 不显示 / 1 仅未运行时显示 / 2 常显）。
     *
     * 迁移：v1.2.38 及以前只存布尔 `overlay_handle_a11y`（true = 常显 / false = 隐藏）。
     * 新键读不到时回落到旧布尔，再读不到才用默认值；旧键**只读不写、也不删**
     * —— 删了以后想回滚旧版本就没有依据了，留着一个 bool 也不占地方。
     */
    var overlayHandleA11yMode: Int
        get() {
            if (sp.contains(KEY_OVERLAY_HANDLE_A11Y_MODE)) {
                return sp.getInt(
                    KEY_OVERLAY_HANDLE_A11Y_MODE,
                    SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE,
                )
            }
            if (sp.contains(KEY_OVERLAY_HANDLE_A11Y)) {
                return if (sp.getBoolean(KEY_OVERLAY_HANDLE_A11Y, true)) {
                    SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE
                } else {
                    0
                }
            }
            return SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE
        }
        set(value) {
            val v = value.coerceIn(0, 2)
            sp.edit().putInt(KEY_OVERLAY_HANDLE_A11Y_MODE, v).apply()
            ioScope.launch { store.putInt(KEY_OVERLAY_HANDLE_A11Y_MODE, v) }
        }

    /** 悬浮窗侧钮开关（v1.2.0；仅在车载模式下生效） */
    var overlayHandles: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_HANDLES, SettingsDefaults.OVERLAY_HANDLES)
        set(value) {
            sp.edit().putBoolean(KEY_OVERLAY_HANDLES, value).apply()
            ioScope.launch { store.putBoolean(KEY_OVERLAY_HANDLES, value) }
        }

    /** 悬浮窗侧钮上下顺序（B1），CSV；非法/缺项由 OverlayCollapseHelper.mergeHandleOrder 合并 */
    var overlayHandleOrder: String
        get() = sp.getString(KEY_OVERLAY_HANDLE_ORDER, SettingsDefaults.OVERLAY_HANDLE_ORDER)
            ?: SettingsDefaults.OVERLAY_HANDLE_ORDER
        set(value) {
            val normalized = value.ifBlank { SettingsDefaults.OVERLAY_HANDLE_ORDER }
            sp.edit().putString(KEY_OVERLAY_HANDLE_ORDER, normalized).apply()
            ioScope.launch { store.putString(KEY_OVERLAY_HANDLE_ORDER, normalized) }
        }

    /**
     * 无缝播放偏好死键（C1 写入；A7 起 UI 不再读写）。
     * 保留 getter 供诊断包注明「历史偏好」；setter 仍可用但不接播放内核。
     * Media3 无缝始终由单 ExoPlayer + 连续队列提供，与本键无关。
     */
    var gaplessPlayback: Boolean
        get() = sp.getBoolean(KEY_GAPLESS_PLAYBACK, SettingsDefaults.GAPLESS_PLAYBACK)
        set(value) {
            sp.edit().putBoolean(KEY_GAPLESS_PLAYBACK, value).apply()
            ioScope.launch { store.putBoolean(KEY_GAPLESS_PLAYBACK, value) }
        }

    /** 在线后续曲预取（默认关，A11） */
    var onlineCachePrefetch: Boolean
        get() = sp.getBoolean(KEY_ONLINE_CACHE_PREFETCH, SettingsDefaults.ONLINE_CACHE_PREFETCH)
        set(value) {
            sp.edit().putBoolean(KEY_ONLINE_CACHE_PREFETCH, value).apply()
            ioScope.launch { store.putBoolean(KEY_ONLINE_CACHE_PREFETCH, value) }
        }

    /** 当前曲并行分片下载（默认关，A11） */
    var onlineParallelDownload: Boolean
        get() = sp.getBoolean(KEY_ONLINE_PARALLEL_DOWNLOAD, SettingsDefaults.ONLINE_PARALLEL_DOWNLOAD)
        set(value) {
            sp.edit().putBoolean(KEY_ONLINE_PARALLEL_DOWNLOAD, value).apply()
            ioScope.launch { store.putBoolean(KEY_ONLINE_PARALLEL_DOWNLOAD, value) }
        }

    /** 播放页左侧竖排是否显示「无障碍状态」按钮（v1.1.92） */
    var playerA11yAction: Boolean
        get() = sp.getBoolean(KEY_PLAYER_A11Y_ACTION, SettingsDefaults.PLAYER_A11Y_ACTION)
        set(value) {
            sp.edit().putBoolean(KEY_PLAYER_A11Y_ACTION, value).apply()
            ioScope.launch { store.putBoolean(KEY_PLAYER_A11Y_ACTION, value) }
        }

    var lyricTapSeeks: Boolean
        get() = sp.getBoolean(KEY_LYRIC_TAP_SEEKS, SettingsDefaults.LYRIC_TAP_SEEKS)
        set(value) {
            sp.edit().putBoolean(KEY_LYRIC_TAP_SEEKS, value).apply()
            ioScope.launch { store.putBoolean(KEY_LYRIC_TAP_SEEKS, value) }
        }

    /**
     * 播放页中部显示的是歌词（true，默认）还是封面（false）。
     *
     * 落盘而不是用 `remember`：播放页是覆盖层，收起后整棵子树离开组合，
     * `remember` 的状态会一起丢——用户每次打开播放页都被拨回歌词态，
     * 对习惯"封面党"的人来说就是每次都要多点一下。
     */
    var lyricsPaneVisible: Boolean
        get() = sp.getBoolean(KEY_LYRICS_PANE, true)
        set(value) {
            sp.edit().putBoolean(KEY_LYRICS_PANE, value).apply()
            ioScope.launch { store.putBoolean(KEY_LYRICS_PANE, value) }
        }

    // ============================================================ 4. 均衡器与音效

    /** 均衡器总开关 */
    var eqEnabled: Boolean
        get() = sp.getBoolean(KEY_EQ_ENABLED, false)
        set(value) {
            sp.edit().putBoolean(KEY_EQ_ENABLED, value).apply()
            ioScope.launch { store.putBoolean(KEY_EQ_ENABLED, value) }
        }

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
        set(value) {
            sp.edit().putString(KEY_EQ_PRESET_ID, value).apply()
            ioScope.launch { store.putString(KEY_EQ_PRESET_ID, value) }
        }

    /** 各频段的增益（JSON 数组），空串 = 平台默认（未手动调过） */
    var eqBands: String
        get() = sp.getString(KEY_EQ_BANDS, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_EQ_BANDS, value).apply()
            ioScope.launch { store.putString(KEY_EQ_BANDS, value) }
        }

    /** 低音增强强度（0 = 关） */
    var eqBassBoost: Int
        get() = sp.getInt(KEY_EQ_BASS, 0)
        set(value) {
            sp.edit().putInt(KEY_EQ_BASS, value).apply()
            ioScope.launch { store.putInt(KEY_EQ_BASS, value) }
        }

    /** 虚拟环绕声强度（0 = 关） */
    var eqVirtualizer: Int
        get() = sp.getInt(KEY_EQ_VIRTUAL, 0)
        set(value) {
            sp.edit().putInt(KEY_EQ_VIRTUAL, value).apply()
            ioScope.launch { store.putInt(KEY_EQ_VIRTUAL, value) }
        }

    /** 响度增强（LoudnessEnhancer 目标增益，单位 mB） */
    var eqLoudness: Int
        get() = sp.getInt(KEY_EQ_LOUDNESS, 0)
        set(value) {
            val v = value.coerceIn(0, 1500)
            sp.edit().putInt(KEY_EQ_LOUDNESS, v).apply()
            ioScope.launch { store.putInt(KEY_EQ_LOUDNESS, v) }
        }

    /**
     * 混响档位 = [com.luckylearning.windtone.playback.ReverbPresets] 的 id。
     *
     * 空串 = 从未选过（等价于「关闭」），与 [eqPresetId] 的「空串 = 没选过 / 自定义」同一口径：
     * 出厂的语义是「什么都没选过」，而不是「选了关闭这一档」。
     * 界面读到空串时把「关闭」显示成选中，写回时一律写具体 id。
     */
    var eqReverbId: String
        get() = sp.getString(KEY_EQ_REVERB_ID, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_EQ_REVERB_ID, value).apply()
            ioScope.launch { store.putString(KEY_EQ_REVERB_ID, value) }
        }

    // ---------------- 音效方案与播放过渡（v1.1.52） ----------------

    /**
     * 手机模式当前音效方案 = [com.luckylearning.windtone.playback.SoundSchemes] 的 id。
     * 空串 = 没选过方案 / 手动微调过（与 [eqPresetId] 的「空串 = 自定义」同一口径）。
     */

    /**
     * Soft EQ user confirm (E5 Prefs key `soft_eq_user_confirm`).
     *
     * When hard EQ reports DEVICE_REJECTED, SoftEqGate marks a candidate; soft
     * PCM path enables only if this is true. Hard available again forces soft
     * off (preference kept). Default false — auto-candidate only, no surprise
     * enable. EqSheet switch (E4) + diagnostics SoftEqProbe (E5).
     */
    var softEqUserConfirm: Boolean
        get() = sp.getBoolean(KEY_SOFT_EQ_USER_CONFIRM, SettingsDefaults.SOFT_EQ_USER_CONFIRM)
        set(value) {
            sp.edit().putBoolean(KEY_SOFT_EQ_USER_CONFIRM, value).apply()
            ioScope.launch { store.putBoolean(KEY_SOFT_EQ_USER_CONFIRM, value) }
        }

    var soundSchemeId: String
        get() = sp.getString(KEY_SOUND_SCHEME_ID, SettingsDefaults.SOUND_SCHEME_ID).orEmpty()
        set(value) {
            sp.edit().putString(KEY_SOUND_SCHEME_ID, value).apply()
            ioScope.launch { store.putString(KEY_SOUND_SCHEME_ID, value) }
        }

    /** 车载模式当前音效方案。空串 = 进入车载模式不改声音。 */
    var carSchemeId: String
        get() = sp.getString(KEY_CAR_SCHEME_ID, SettingsDefaults.CAR_SCHEME_ID).orEmpty()
        set(value) {
            sp.edit().putString(KEY_CAR_SCHEME_ID, value).apply()
            ioScope.launch { store.putString(KEY_CAR_SCHEME_ID, value) }
        }

    /**
     * 进入车载模式前的音效值快照（[com.luckylearning.windtone.playback.SoundStash] 编码）。
     * 空串 = 当前不在「换过声音的车载会话」里。
     */
    var soundStash: String
        get() = sp.getString(KEY_SOUND_STASH, SettingsDefaults.SOUND_STASH).orEmpty()
        set(value) {
            sp.edit().putString(KEY_SOUND_STASH, value).apply()
            ioScope.launch { store.putString(KEY_SOUND_STASH, value) }
        }

    /** 切歌淡入淡出（自动切歌时旧曲结尾淡出、新曲开头淡入） */
    var fadeEnabled: Boolean
        get() = sp.getBoolean(KEY_FADE_ENABLED, SettingsDefaults.FADE_ENABLED)
        set(value) {
            sp.edit().putBoolean(KEY_FADE_ENABLED, value).apply()
            ioScope.launch { store.putBoolean(KEY_FADE_ENABLED, value) }
        }

    /** 淡入 / 淡出单向时长（毫秒），合法区间见 [SettingsDefaults.FADE_DURATION_RANGE] */
    var fadeDurationMs: Int
        get() = sp.getInt(KEY_FADE_DURATION_MS, SettingsDefaults.FADE_DURATION_MS)
        set(value) {
            sp.edit().putInt(KEY_FADE_DURATION_MS, value).apply()
            ioScope.launch { store.putInt(KEY_FADE_DURATION_MS, value) }
        }

    /**
     * 左右声道平衡（#9）：-100 = 全左，0 = 居中，+100 = 全右。
     * 由 StereoBalanceFx 乘到 PCM，不经过 AudioFx 效果器。
     */
    var stereoBalance: Int
        get() = sp.getInt(KEY_STEREO_BALANCE, SettingsDefaults.STEREO_BALANCE).coerceIn(-100, 100)
        set(value) {
            val v = value.coerceIn(-100, 100)
            sp.edit().putInt(KEY_STEREO_BALANCE, v).apply()
            ioScope.launch { store.putInt(KEY_STEREO_BALANCE, v) }
        }

    /** 曲间响度平衡（自动压低响度过大的歌，分析结果缓存在应用私有目录） */
    var loudnessBalance: Boolean
        get() = sp.getBoolean(KEY_LOUDNESS_BALANCE, SettingsDefaults.LOUDNESS_BALANCE)
        set(value) {
            sp.edit().putBoolean(KEY_LOUDNESS_BALANCE, value).apply()
            ioScope.launch { store.putBoolean(KEY_LOUDNESS_BALANCE, value) }
        }


    /**
     * H2：每曲自动跳过片头的固定秒数（0 = 关闭，上限 120）。
     * 与 [skipSilence] 无关——那是 ExoPlayer 静音检测；这里是固定秒 seek。
     */
    var skipIntroSeconds: Int
        get() = sp.getInt(KEY_SKIP_INTRO_SECONDS, SettingsDefaults.SKIP_INTRO_SECONDS)
            .coerceIn(0, 120)
        set(value) {
            val v = value.coerceIn(0, 120)
            sp.edit().putInt(KEY_SKIP_INTRO_SECONDS, v).apply()
            ioScope.launch { store.putInt(KEY_SKIP_INTRO_SECONDS, v) }
        }

    /** H2：距结束 ≤ 该秒数时切下一首（0 = 关闭，上限 120） */
    var skipOutroSeconds: Int
        get() = sp.getInt(KEY_SKIP_OUTRO_SECONDS, SettingsDefaults.SKIP_OUTRO_SECONDS)
            .coerceIn(0, 120)
        set(value) {
            val v = value.coerceIn(0, 120)
            sp.edit().putInt(KEY_SKIP_OUTRO_SECONDS, v).apply()
            ioScope.launch { store.putInt(KEY_SKIP_OUTRO_SECONDS, v) }
        }

    /**
     * ReplayGain 曲目 / 专辑模式（N6）。音量平衡开启时生效；非法存量回落曲目。
     */
    var replayGainMode: ReplayGainMode
        get() = ReplayGainMode.fromStored(
            sp.getString(KEY_REPLAY_GAIN_MODE, SettingsDefaults.REPLAY_GAIN_MODE),
        )
        set(value) {
            sp.edit().putString(KEY_REPLAY_GAIN_MODE, value.name).apply()
            ioScope.launch { store.putString(KEY_REPLAY_GAIN_MODE, value.name) }
        }


    /**
     * 用户自己保存的均衡器预设。
     *
     * 编码见 [com.luckylearning.windtone.playback.UserEqPresets]。
     * 和 [eqPresetId] 分开存：后者只记「当前选中哪一条」（内置 id、用户 id 或空），
     * 这里是整本预设册，杀进程还在。恢复默认音效不会清这本册子。
     */
    var eqUserPresets: String
        get() = sp.getString(KEY_EQ_USER_PRESETS, SettingsDefaults.EQ_USER_PRESETS).orEmpty()
        set(value) {
            sp.edit().putString(KEY_EQ_USER_PRESETS, value).apply()
            ioScope.launch { store.putString(KEY_EQ_USER_PRESETS, value) }
        }

    /**
     * Active scene = [com.luckylearning.windtone.playback.SceneProfiles] id.
     * Empty = off / left after a manual EQ edit.
     */
    var sceneId: String
        get() = sp.getString(KEY_SCENE_ID, SettingsDefaults.SCENE_ID).orEmpty()
        set(value) {
            sp.edit().putString(KEY_SCENE_ID, value).apply()
            ioScope.launch { store.putString(KEY_SCENE_ID, value) }
        }

    /** Snapshot before entering a scene ([com.luckylearning.windtone.playback.SceneStash]); empty = none */
    var sceneStash: String
        get() = sp.getString(KEY_SCENE_STASH, SettingsDefaults.SCENE_STASH).orEmpty()
        set(value) {
            sp.edit().putString(KEY_SCENE_STASH, value).apply()
            ioScope.launch { store.putString(KEY_SCENE_STASH, value) }
        }

    // ============================================================ 5. 桌面悬浮歌词

    /**
     * 桌面悬浮歌词（默认关闭）。
     *
     * 车机上用：退到桌面 / 导航页时，在最上层挂一条只有文字的单行字幕，
     * 持续显示当前歌词行。需要 [android.provider.Settings.canDrawOverlays] 授权
     * 「出现在其他应用上」才能生效——比亚迪 DiLink 车机该权限在
     * 设置 → 应用 → 权限 → 出现在其他应用上。
     */
    var lyricOverlay: Boolean
        get() = sp.getBoolean(KEY_LYRIC_OVERLAY, SettingsDefaults.LYRIC_OVERLAY)
        set(value) {
            sp.edit().putBoolean(KEY_LYRIC_OVERLAY, value).apply()
            ioScope.launch { store.putBoolean(KEY_LYRIC_OVERLAY, value) }
        }

    /**
     * 桌面悬浮歌词一次显示几行：0 / 1..5（默认 1 = 只显示当前行）。
     *
     * 行数 > 1 时当前行居中最亮，上下各带若干行上下文（压暗），
     * 车机 / 大屏上尤其有用，不必等下一句冒出来才知道上下文。
     *
     * **0 = 自动**（v1.1.49）：按窗口高度反推能放几行，见 `overlay/OverlayLineFit`。
     * 单条字幕只有在高度被固定成某个百分比时才有得算；高度也是「自动」时按
     * [OverlayLineFit.STRIP_FALLBACK_LINES] 行显示。
     *
     * 取值范围收在 [SettingsDefaults.OVERLAY_LINES_RANGE] 内，非法值就地夹取
     * （防旧数据 / 手改）；老版本只写过 1 与 3，读出来天然合法。
     */
    var lyricOverlayLines: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_LINES, SettingsDefaults.OVERLAY_LINES)
            .coerceIn(SettingsDefaults.OVERLAY_LINES_RANGE.first, SettingsDefaults.OVERLAY_LINES_RANGE.last)
        set(value) {
            val v = value.coerceIn(SettingsDefaults.OVERLAY_LINES_RANGE.first, SettingsDefaults.OVERLAY_LINES_RANGE.last)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_LINES, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_LINES, v) }
        }

    /** 悬浮窗最上面是否显示「歌名 + 歌手」两行小字（默认关闭） */
    var lyricOverlayShowTitle: Boolean
        get() = sp.getBoolean(KEY_LYRIC_OVERLAY_TITLE, SettingsDefaults.OVERLAY_SHOW_TITLE)
        set(value) {
            sp.edit().putBoolean(KEY_LYRIC_OVERLAY_TITLE, value).apply()
            ioScope.launch { store.putBoolean(KEY_LYRIC_OVERLAY_TITLE, value) }
        }

    /**
     * 歌名 / 歌手两行小字的字号，存**正文的百分比**（v1.1.51，默认 62%）。
     *
     * 为什么是比例而不是独立 sp：标题是配角，用户调大正文时它应当跟着放大，
     * 而不是要再手动去追一次；比例还能保证「标题永远比正文小」这条排版约束。
     *
     * **迁移**：新键没写过时，按旧的 0/1/2 档换算成 55 / 62 / 78%，观感不变。
     */
    var lyricOverlayTitlePercent: Int
        get() {
            val stored = sp.getInt(KEY_LYRIC_OVERLAY_TITLE_PERCENT, Int.MIN_VALUE)
            val raw = when {
                stored != Int.MIN_VALUE -> stored
                // 老用户：按当时的 0/1/2 档换算（0.55 / 0.62 / 0.78）
                sp.contains(KEY_LYRIC_OVERLAY_TITLE_SCALE) -> SettingsDefaults.OVERLAY_TITLE_PERCENT_BY_SCALE[
                    sp.getInt(KEY_LYRIC_OVERLAY_TITLE_SCALE, 0).coerceIn(0, 2),
                ]
                else -> SettingsDefaults.OVERLAY_TITLE_PERCENT
            }
            return raw.coerceIn(
                SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.first,
                SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.last,
            )
        }
        set(value) {
            val v = value.coerceIn(
                SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.first,
                SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE.last,
            )
            sp.edit().putInt(KEY_LYRIC_OVERLAY_TITLE_PERCENT, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_TITLE_PERCENT, v) }
        }

    /**
     * 悬浮歌词正文字号（sp，v1.1.51 起可调 12–60）。
     *
     * v1.1.50 之前存的是 0/1/2 三档。**迁移**：新键没写过时按旧档位换算
     * （16 / 20 / 25sp），老用户升级后字号一模一样，不必重设。
     */
    var lyricOverlayTextSp: Int
        get() {
            val stored = sp.getInt(KEY_LYRIC_OVERLAY_TEXT_SP, Int.MIN_VALUE)
            val raw = when {
                stored != Int.MIN_VALUE -> stored
                // 老用户：0/1/2 档换算成 16 / 20 / 25sp，升级后字号一模一样
                sp.contains(KEY_LYRIC_OVERLAY_TEXT_SCALE) -> SettingsDefaults.OVERLAY_TEXT_SP_BY_SCALE[
                    sp.getInt(KEY_LYRIC_OVERLAY_TEXT_SCALE, 1).coerceIn(0, 2),
                ]
                else -> SettingsDefaults.OVERLAY_TEXT_SP
            }
            return raw.coerceIn(
                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first,
                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last,
            )
        }
        set(value) {
            val v = value.coerceIn(
                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.first,
                SettingsDefaults.OVERLAY_TEXT_SP_RANGE.last,
            )
            sp.edit().putInt(KEY_LYRIC_OVERLAY_TEXT_SP, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_TEXT_SP, v) }
        }

    /**
     * 背景不透明度（%，v1.1.51 起背景只由「颜色 + 不透明度」两项决定）。
     *
     * **迁移**：新键没写过时——
     *  - 老用户开着「自定义背景」→ 用他当时设的 [KEY_LYRIC_OVERLAY_BG_ALPHA] 默认值；
     *  - 没开（走旧三档）→ 按 无 / 半透明 / 实底 换算成 0 / 55 / 89%。
     * 0% 即无底板，文字自动换硬描边，不再需要一个「无底板」开关。
     */
    var lyricOverlayBgAlphaPercent: Int
        get() {
            // 新键写过就以它为准；否则按旧数据换算一次（不写回，保持读取无副作用）
            if (sp.contains(KEY_LYRIC_OVERLAY_BG_ALPHA)) {
                return sp.getInt(KEY_LYRIC_OVERLAY_BG_ALPHA, SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT)
                    .coerceIn(0, 100)
            }
            val custom = sp.getBoolean(KEY_LYRIC_OVERLAY_CUSTOM_BG, false)
            if (custom) return SettingsDefaults.OVERLAY_BG_ALPHA_PERCENT
            val backdrop = sp.getInt(KEY_LYRIC_OVERLAY_BACKDROP, 1).coerceIn(0, 2)
            return SettingsDefaults.OVERLAY_BG_ALPHA_BY_BACKDROP[backdrop]
        }
        set(value) {
            val v = value.coerceIn(0, 100)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_BG_ALPHA, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_BG_ALPHA, v) }
        }

    // ---------------- 样式细节（v1.1.52）----------------

    /** 悬浮窗背景圆角（dp）：全屏形态在服务里强制直角，这个值只作用于单条字幕 */
    var lyricOverlayCornerRadiusDp: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_CORNER, SettingsDefaults.OVERLAY_CORNER_RADIUS_DP)
            .coerceIn(0, 28)
        set(value) {
            val v = value.coerceIn(0, 28)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_CORNER, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_CORNER, v) }
        }

    /** 背景边框宽度（dp）：0 = 无边框 */
    var lyricOverlayBorderWidthDp: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_BORDER_W, SettingsDefaults.OVERLAY_BORDER_WIDTH_DP)
            .coerceIn(0, 4)
        set(value) {
            val v = value.coerceIn(0, 4)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_BORDER_W, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_BORDER_W, v) }
        }

    /** 背景边框颜色（ARGB）。保留原 alpha：默认就是半透明白（0x66FFFFFF），细线不该画成实心 */
    var lyricOverlayBorderColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_BORDER_C, SettingsDefaults.OVERLAY_BORDER_COLOR)
        set(value) {
            sp.edit().putInt(KEY_LYRIC_OVERLAY_BORDER_C, value).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_BORDER_C, value) }
        }

    /** 单条字幕的额外行距（dp）：改了之后行数「自动」会跟着重算 */
    var lyricOverlayLineSpacingDp: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_LINE_SPACING, SettingsDefaults.OVERLAY_LINE_SPACING_DP)
            .coerceIn(0, 12)
        set(value) {
            val v = value.coerceIn(0, 12)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_LINE_SPACING, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_LINE_SPACING, v) }
        }

    /** 当前行颜色：0 = 跟随「文字颜色」 */
    var lyricOverlayCurrentColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_CURRENT_COLOR, SettingsDefaults.OVERLAY_CURRENT_COLOR)
        set(value) {
            sp.edit().putInt(KEY_LYRIC_OVERLAY_CURRENT_COLOR, value).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_CURRENT_COLOR, value) }
        }

    /** 上下句压暗程度（%）：无底板时有保底下限（见 OverlayLegibility） */
    var lyricOverlayDimPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_DIM, SettingsDefaults.OVERLAY_DIM_PERCENT)
            .coerceIn(SettingsDefaults.OVERLAY_DIM_RANGE.first, SettingsDefaults.OVERLAY_DIM_RANGE.last)
        set(value) {
            val v = value.coerceIn(
                SettingsDefaults.OVERLAY_DIM_RANGE.first,
                SettingsDefaults.OVERLAY_DIM_RANGE.last,
            )
            sp.edit().putInt(KEY_LYRIC_OVERLAY_DIM, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_DIM, v) }
        }

    /** 文字描边宽度（dp）：0 = 自动（有底板 3dp 柔光 / 无底板 2dp 硬描边） */
    var lyricOverlayStrokeWidthDp: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_STROKE_W, SettingsDefaults.OVERLAY_STROKE_WIDTH_DP)
            .coerceIn(0, 4)
        set(value) {
            val v = value.coerceIn(0, 4)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_STROKE_W, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_STROKE_W, v) }
        }

    /** 描边颜色（ARGB） */
    var lyricOverlayStrokeColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_STROKE_C, SettingsDefaults.OVERLAY_STROKE_COLOR)
        set(value) {
            val v = value or 0xFF000000.toInt()
            sp.edit().putInt(KEY_LYRIC_OVERLAY_STROKE_C, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_STROKE_C, v) }
        }

    /** 换行动画时长（ms）：0 = 关闭动画 */
    var lyricOverlayFadeMs: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_FADE, SettingsDefaults.OVERLAY_FADE_MS)
            .coerceIn(0, 500)
        set(value) {
            val v = value.coerceIn(0, 500)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_FADE, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_FADE, v) }
        }

    /** 标题显示内容：0 歌名 + 歌手 / 1 只歌名 / 2 只歌手 */
    var lyricOverlayTitleMode: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_TITLE_MODE, SettingsDefaults.OVERLAY_TITLE_MODE).coerceIn(0, 2)
        set(value) {
            val v = value.coerceIn(0, 2)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_TITLE_MODE, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_TITLE_MODE, v) }
        }

    /** 暂停时的表现：0 淡出（默认，保留最后一句便于看清唱到哪）/ 1 隐藏 */
    var lyricOverlayPauseMode: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_PAUSE_MODE, SettingsDefaults.OVERLAY_PAUSE_MODE).coerceIn(0, 1)
        set(value) {
            val v = value.coerceIn(0, 1)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_PAUSE_MODE, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_PAUSE_MODE, v) }
        }

    /**
     * 悬浮歌词窗内文字的对齐：0 居中（默认）/ 1 居左。
     *
     * 2026-10-16 起语义收窄：**只管歌词文字在窗口内部怎么排**
     * （居中 = 折行后各行也居中；居左 = 每行从左沿同一条竖线起排），
     * 与窗口摆在屏幕哪儿无关 —— 窗口位置完全由拖动决定（[lyricOverlayX] / [lyricOverlayY]）。
     */
    var lyricOverlayAlign: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_ALIGN, SettingsDefaults.OVERLAY_ALIGN).coerceIn(0, 1)
        set(value) {
            val v = value.coerceIn(0, 1)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_ALIGN, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_ALIGN, v) }
        }

    /**
     * 悬浮歌词窗口的宽度，**按屏幕宽度的百分比**（1..100，默认 80）。
     *
     * 此前窗口宽度是 `WRAP_CONTENT`（内容多宽就多宽），长句会一路顶到屏幕边缘、
     * 短句又只有一小条 —— 同一首歌里宽度不停跳。改成固定百分比后宽度稳定，
     * 文字超出就折行。
     *
     * ⚠️ 宽度变了必须重新摆一次窗口：`OverlayPositioner` 的夹取上限是
     * 「屏宽 − 窗口宽」，宽窗口留的空间更小（见 `LyricOverlayService.applyAppearance`）。
     * 默认 80% 的缘由见 [SettingsDefaults.OVERLAY_WIDTH_PERCENT]。
     */
    var lyricOverlayWidthPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_WIDTH, SettingsDefaults.OVERLAY_WIDTH_PERCENT)
            .coerceIn(SettingsDefaults.OVERLAY_WIDTH_RANGE.first, SettingsDefaults.OVERLAY_WIDTH_RANGE.last)
        set(value) {
            val v = value.coerceIn(SettingsDefaults.OVERLAY_WIDTH_RANGE.first, SettingsDefaults.OVERLAY_WIDTH_RANGE.last)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_WIDTH, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_WIDTH, v) }
        }

    /** 悬浮窗里的歌词文字颜色（ARGB，默认纯白） */
    var lyricOverlayTextColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, SettingsDefaults.OVERLAY_TEXT_COLOR)
        set(value) {
            sp.edit().putInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, value).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_TEXT_COLOR, value) }
        }

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
        set(value) {
            sp.edit().putBoolean(KEY_LYRIC_OVERLAY_FULL_HEIGHT, value).apply()
            ioScope.launch { store.putBoolean(KEY_LYRIC_OVERLAY_FULL_HEIGHT, value) }
        }

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
            .coerceIn(SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first, SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last)
        set(value) {
            val v = value.coerceIn(SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first, SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_FULL_WIDTH, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_FULL_WIDTH, v) }
        }

    /**
     * 全屏模式下的水平位置：存「可移动余量的百分比」（0 最左 / 50 居中 / 100 最右）。
     *
     * 桌面拖动时把像素 x 反算成这个百分比再落盘，所以换分辨率、转屏之后
     * 「靠右」仍然靠右，不会因为绝对坐标过期而跑到屏幕外。
     */
    var lyricOverlayFullOffsetPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_FULL_OFFSET, SettingsDefaults.OVERLAY_FULL_OFFSET_PERCENT)
            .coerceIn(SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.first, SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.last)
        set(value) {
            val v = value.coerceIn(SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.first, SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.last)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_FULL_OFFSET, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_FULL_OFFSET, v) }
        }

    /**
     * 悬浮歌词的横向偏移（像素，相对屏幕左边缘）。
     *
     * 2026-10-16 起重新启用：窗口位置完全由拖动决定（此前横向曾归
     * [lyricOverlayAlign] 管、此键一度废弃）。`Int.MIN_VALUE` 表示未拖过，
     * 走默认的水平居中。
     */
    var lyricOverlayX: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_X, Int.MIN_VALUE)
        set(value) {
            sp.edit().putInt(KEY_LYRIC_OVERLAY_X, value).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_X, value) }
        }

    /**
     * 悬浮歌词的纵向偏移（像素，相对屏幕顶端）。
     *
     * `Int.MIN_VALUE` 表示未调整过，走默认的「距顶 48dp 让开状态栏」。
     */
    var lyricOverlayY: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_Y, Int.MIN_VALUE)
        set(value) {
            sp.edit().putInt(KEY_LYRIC_OVERLAY_Y, value).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_Y, value) }
        }

    // ------------------------------------------------ 自定义尺寸 / 位置 / 背景（v1.1.48）

    /**
     * 单条字幕的窗口高度（屏高百分比，0 = 自动按行数）。
     *
     * 与 [lyricOverlayLines] 的关系：行数决定**显示几行**，高度决定**窗口多大**。
     * 高度固定后，行数超出的部分会挤在窗口里居中显示（文字仍按行距排布），
     * 所以两者不冲突，只是两个独立的旋钮。
     */
    var lyricOverlayBoxHeightPercent: Int
        get() {
            val v = sp.getInt(KEY_LYRIC_OVERLAY_BOX_HEIGHT, SettingsDefaults.OVERLAY_BOX_HEIGHT_PERCENT)
            if (v <= 0) return 0
            return v.coerceIn(
                SettingsDefaults.OVERLAY_BOX_HEIGHT_MIN_PERCENT,
                SettingsDefaults.OVERLAY_BOX_HEIGHT_RANGE.last,
            )
        }
        set(value) {
            val v = if (value <= 0) 0 else value.coerceIn(
                SettingsDefaults.OVERLAY_BOX_HEIGHT_MIN_PERCENT,
                SettingsDefaults.OVERLAY_BOX_HEIGHT_RANGE.last,
            )
            sp.edit().putInt(KEY_LYRIC_OVERLAY_BOX_HEIGHT, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_BOX_HEIGHT, v) }
        }

    /** 全屏形态的窗口高度（屏高百分比，30..100，默认 100 = 减去车机 Dock 让位） */
    var lyricOverlayFullHeightPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_FULL_HEIGHT_PCT, SettingsDefaults.OVERLAY_FULL_HEIGHT_PERCENT)
            .coerceIn(SettingsDefaults.OVERLAY_FULL_HEIGHT_RANGE.first, SettingsDefaults.OVERLAY_FULL_HEIGHT_RANGE.last)
        set(value) {
            val v = value.coerceIn(
                SettingsDefaults.OVERLAY_FULL_HEIGHT_RANGE.first,
                SettingsDefaults.OVERLAY_FULL_HEIGHT_RANGE.last,
            )
            sp.edit().putInt(KEY_LYRIC_OVERLAY_FULL_HEIGHT_PCT, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_FULL_HEIGHT_PCT, v) }
        }

    /**
     * 单条字幕的横向位置（可移动余量的百分比，0..100）。
     *
     * `-1` 表示**还没迁移过**：此时回退到旧的像素坐标 [lyricOverlayX]，
     * 服务首次挂窗时会换算成百分比并写回（见 `LyricOverlayService.migrateLegacyPosition`）。
     * 存百分比而不是像素，是为了换分辨率 / 转屏后位置仍然正确 —— 这与全屏形态
     * 用 [lyricOverlayFullOffsetPercent] 的理由是同一个。
     */
    var lyricOverlayXPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_X_PCT, -1).coerceIn(-1, 100)
        set(value) {
            val v = value.coerceIn(0, 100)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_X_PCT, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_X_PCT, v) }
        }

    /** 单条字幕的纵向位置（可移动余量的百分比，0..100；-1 = 未迁移） */
    var lyricOverlayYPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_Y_PCT, -1).coerceIn(-1, 100)
        set(value) {
            val v = value.coerceIn(0, 100)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_Y_PCT, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_Y_PCT, v) }
        }

    /** 全屏形态的纵向位置（可移动余量的百分比，0..100，默认 0 = 贴顶） */
    var lyricOverlayFullYPercent: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_FULL_Y_PCT, SettingsDefaults.OVERLAY_FULL_Y_PERCENT)
            .coerceIn(0, 100)
        set(value) {
            val v = value.coerceIn(0, 100)
            sp.edit().putInt(KEY_LYRIC_OVERLAY_FULL_Y_PCT, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_FULL_Y_PCT, v) }
        }

    /** 背景颜色（ARGB，alpha 位不使用 —— 不透明度由 [lyricOverlayBgAlphaPercent] 单独管） */
    var lyricOverlayBgColor: Int
        get() = sp.getInt(KEY_LYRIC_OVERLAY_BG_COLOR, SettingsDefaults.OVERLAY_BG_COLOR)
        set(value) {
            // 只保留 RGB：alpha 走独立滑块，避免两个地方都能改不透明度
            val v = value or 0xFF000000.toInt()
            sp.edit().putInt(KEY_LYRIC_OVERLAY_BG_COLOR, v).apply()
            ioScope.launch { store.putInt(KEY_LYRIC_OVERLAY_BG_COLOR, v) }
        }

    // ============================================================ 6. 前台检测与调试

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
        set(value) {
            sp.edit().putStringSet(KEY_LYRIC_OVERLAY_HIDDEN_APPS, HashSet(value)).apply()
            ioScope.launch { store.putStringSet(KEY_LYRIC_OVERLAY_HIDDEN_APPS, HashSet(value)) }
        }

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
        set(value) {
            sp.edit().putBoolean(KEY_FOREGROUND_LOG_ENABLED, value).apply()
            ioScope.launch { store.putBoolean(KEY_FOREGROUND_LOG_ENABLED, value) }
        }

    /**
     * 调试分区是否已解锁（默认 **false**，即隐藏）。
     *
     * 解锁动线是「关于」连点 5 次；关掉的动线是调试分区顶部那个开关（把这里写回 false）。
     * 键值与 [SettingsDefaults.DEBUG_UNLOCKED] 对质见 `SettingsDefaultsTest`。
     */
    var debugUnlocked: Boolean
        get() = sp.getBoolean(KEY_DEBUG_UNLOCKED, SettingsDefaults.DEBUG_UNLOCKED)
        set(value) {
            sp.edit().putBoolean(KEY_DEBUG_UNLOCKED, value).apply()
            ioScope.launch { store.putBoolean(KEY_DEBUG_UNLOCKED, value) }
        }

    // ============================================================ 7. 外观与主题

    /**
     * 界面明暗模式：跟随系统 / 浅色 / 深色（v1.2.5）。
     *
     * 存 `name` 而不是 ordinal —— 枚举顺序是界面排列顺序，将来插入档位时
     * ordinal 会整体错位，把老用户的选择读成另一档。解析失败（脏值 / 降级安装）
     * 一律回落到出厂默认，不让一条坏字符串把界面锁死在某个模式上。
     */
    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(sp.getString(KEY_THEME_MODE, null)!!) }
            .getOrDefault(SettingsDefaults.THEME_MODE)
        set(value) {
            sp.edit().putString(KEY_THEME_MODE, value.name).apply()
            ioScope.launch { store.putString(KEY_THEME_MODE, value.name) }
        }

    /** 主题是否跟随封面取色 */
    var dynamicTheme: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_THEME, SettingsDefaults.DYNAMIC_THEME)
        set(value) {
            sp.edit().putBoolean(KEY_DYNAMIC_THEME, value).apply()
            ioScope.launch { store.putBoolean(KEY_DYNAMIC_THEME, value) }
        }

    /**
     * 车载模式（默认关闭）。
     * 开启后：启动直进播放页；播放页 5 秒无操作收起全部控件只留歌词与进度。
     */
    var carMode: Boolean
        get() = sp.getBoolean(KEY_CAR_MODE, SettingsDefaults.CAR_MODE)
        set(value) {
            sp.edit().putBoolean(KEY_CAR_MODE, value).apply()
            ioScope.launch { store.putBoolean(KEY_CAR_MODE, value) }
        }

    /** G7: bluetooth auto enter car layout */
    var btAutoCarEnabled: Boolean
        get() = sp.getBoolean(KEY_BT_AUTO_CAR_ENABLED, SettingsDefaults.BT_AUTO_CAR_ENABLED)
        set(value) {
            sp.edit().putBoolean(KEY_BT_AUTO_CAR_ENABLED, value).apply()
            ioScope.launch { store.putBoolean(KEY_BT_AUTO_CAR_ENABLED, value) }
        }

    var btAutoCarAnyDevice: Boolean
        get() = sp.getBoolean(KEY_BT_AUTO_CAR_ANY_DEVICE, SettingsDefaults.BT_AUTO_CAR_ANY_DEVICE)
        set(value) {
            sp.edit().putBoolean(KEY_BT_AUTO_CAR_ANY_DEVICE, value).apply()
            ioScope.launch { store.putBoolean(KEY_BT_AUTO_CAR_ANY_DEVICE, value) }
        }

    var btAutoCarDevices: String
        get() = sp.getString(KEY_BT_AUTO_CAR_DEVICES, SettingsDefaults.BT_AUTO_CAR_DEVICES).orEmpty()
        set(value) {
            sp.edit().putString(KEY_BT_AUTO_CAR_DEVICES, value).apply()
            ioScope.launch { store.putString(KEY_BT_AUTO_CAR_DEVICES, value) }
        }

    var btAutoCarRestore: Boolean
        get() = sp.getBoolean(KEY_BT_AUTO_CAR_RESTORE, SettingsDefaults.BT_AUTO_CAR_RESTORE)
        set(value) {
            sp.edit().putBoolean(KEY_BT_AUTO_CAR_RESTORE, value).apply()
            ioScope.launch { store.putBoolean(KEY_BT_AUTO_CAR_RESTORE, value) }
        }

    /** G7 session flag (runtime; not in SettingsDefaultsTest). */
    var btAutoCarSessionActive: Boolean
        get() = sp.getBoolean(KEY_BT_AUTO_CAR_SESSION, false)
        set(value) {
            sp.edit().putBoolean(KEY_BT_AUTO_CAR_SESSION, value).apply()
            ioScope.launch { store.putBoolean(KEY_BT_AUTO_CAR_SESSION, value) }
        }

    /** Stash prior carMode: "" none / "0" off / "1" on */
    var btAutoCarStashCarMode: String
        get() = sp.getString(KEY_BT_AUTO_CAR_STASH, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_BT_AUTO_CAR_STASH, value).apply()
            ioScope.launch { store.putString(KEY_BT_AUTO_CAR_STASH, value) }
        }

    // ============================================================ 8. 应用内更新

    /**
     * 启动时自动检查更新。
     *
     * 默认开启——这是唯一会联网的功能，且只访问 GitHub 公开接口。
     * 用户可以在设置页一键关闭，关闭后应用完全不联网。
     */
    var autoCheckUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, SettingsDefaults.AUTO_UPDATE)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()
            ioScope.launch { store.putBoolean(KEY_AUTO_UPDATE, value) }
        }

    /** 上次检查更新的时间戳（毫秒），用于限制自动检查频率 */
    var lastUpdateCheckAt: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) {
            sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
            ioScope.launch { store.putLong(KEY_LAST_UPDATE_CHECK, value) }
        }

    /**
     * 更新线路（检查更新与下载安装包都走它）。默认 [DownloadRoute.AUTO]。
     * 直连不通时会自动退到镜像；手动选定则只走那一条。
     */
    var updateDownloadRoute: DownloadRoute
        get() = runCatching {
            DownloadRoute.valueOf(
                 sp.getString(KEY_UPDATE_ROUTE, SettingsDefaults.UPDATE_ROUTE.name)!!,
            )
        }.getOrDefault(DownloadRoute.AUTO)
        set(value) {
            sp.edit().putString(KEY_UPDATE_ROUTE, value.name).apply()
            ioScope.launch { store.putString(KEY_UPDATE_ROUTE, value.name) }
        }

    /**
     * 已下载完成、待安装的 APK 路径（v1.2.8）。
     *
     * 此前它只存在于内存状态里：下载完弹一次「立即安装」，用户切出去（去授权、
     * 接电话、杀后台）回来后弹窗没了，只能从头再下载一遍。现在落盘持久化，
     * 冷启动后只要文件还在就能直接装。空串 = 没有待安装的包。
     */
    var updateReadyApkPath: String
        get() = sp.getString(KEY_UPDATE_READY_APK, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_UPDATE_READY_APK, value).apply()
            ioScope.launch { store.putString(KEY_UPDATE_READY_APK, value) }
        }

    /** 待安装 APK 对应的版本号（与 [updateReadyApkPath] 成对出现） */
    var updateReadyApkVersion: String
        get() = sp.getString(KEY_UPDATE_READY_APK_VERSION, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_UPDATE_READY_APK_VERSION, value).apply()
            ioScope.launch { store.putString(KEY_UPDATE_READY_APK_VERSION, value) }
        }

    // ================================================================ 私有

    companion object {

        /**
         * 按 Application 缓存的进程内实例（P2-2）。
         *
         * 生产环境 Application 只有一个 → 实际就是单例，30 多处 `Prefs(context)`
         * 不再反复新建 wrapper；用 WeakHashMap 而不是硬单例，是为了让 Robolectric
         * 每个测试的新 Application 各自拿到独立实例，不串数据。
         */
        private val instances = java.util.WeakHashMap<Context, Prefs>()

        operator fun invoke(context: Context): Prefs {
            val app = context.applicationContext ?: context
            synchronized(instances) {
                return instances.getOrPut(app) { Prefs(app) }
            }
        }

        /**
         * 供测试：清掉按 Application 缓存的实例。
         *
         * 单元测试里多个用例共用同一个 Application，若不清缓存，后一个用例拿到的
         * `Prefs` 是前一个用例建的（它的预热协程早已跑完），「构造即预热」就没法验证。
         * 生产永不调用。
         */
        internal fun clearCacheForTest() {
            synchronized(instances) { instances.clear() }
        }

        // ---- 1. 音乐库与扫描 ----
        const val KEY_LIB_LOSSLESS_ONLY = "library_lossless_only"
        const val KEY_LIB_FORMAT_EXTS = "library_format_exts"
        const val KEY_SCAN_SCOPE = "scan_scope"
        const val KEY_SCAN_SCOPE_CHOSEN = "scan_scope_chosen"
        const val KEY_LAST_SCAN_FP = "last_scan_fingerprint"
        const val KEY_LAST_SCAN_AT = "last_scan_at"
        const val KEY_DEEP_SCAN = "deep_scan"
        const val KEY_MIN_DURATION_SEC = "min_song_duration_sec"
        /** 旧的**全局**排序键：v1.1.4 起排序按视图各存一份，它只作老数据兼容回落 */
        const val KEY_SORT = "sort"

        /** 各视图的排序键前缀：`sort_SONGS` / `sort_ALBUM` / `sort_ARTIST` / `sort_FOLDER` / `sort_PLAYLIST` */
        const val KEY_SORT_PREFIX = "sort_"
        const val KEY_VIEW = "library_view"

        // ---- 2. 播放 ----
        const val KEY_SPEED = "playback_speed"
        const val KEY_PITCH = "playback_pitch_semitones"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_AUTO_RESUME = "auto_resume_playback"
        const val KEY_AUTO_PLAY_ON_BOOT = "auto_play_on_boot"

        // ---- 3. 播放页歌词 ----
        const val KEY_LYRIC_SCALE = "lyric_scale"
        const val KEY_LYRIC_TAP_SEEKS = "lyric_tap_seeks"
        const val KEY_PLAYER_A11Y_ACTION = "player_a11y_action"
        const val KEY_OVERLAY_HANDLES = "overlay_handles"
        const val KEY_OVERLAY_HANDLE_COLLAPSE = "overlay_handle_collapse"
        const val KEY_OVERLAY_HANDLE_PLAY_PAUSE = "overlay_handle_play_pause"
        const val KEY_OVERLAY_HANDLE_PLAYER = "overlay_handle_player"
        /** 侧钮无障碍三档（2026-10-31）。旧布尔键见下，只读作迁移 */
        const val KEY_OVERLAY_HANDLE_A11Y_MODE = "overlay_handle_a11y_mode"
        const val KEY_OVERLAY_HANDLE_A11Y = "overlay_handle_a11y"
        const val KEY_OVERLAY_HANDLE_ORDER = "overlay_handle_order"
        const val KEY_GAPLESS_PLAYBACK = "gapless_playback"
        const val KEY_ONLINE_CACHE_PREFETCH = "online_cache_prefetch"
        const val KEY_ONLINE_PARALLEL_DOWNLOAD = "online_parallel_download"
        const val KEY_LYRICS_PANE = "lyrics_pane_visible"
        // ⚠️ 逐字歌词移除后（v1.1.8）有三个键不再读写：`lyric_word_by_word` /
        //    `lyric_sung_color` / `lyric_overlay_sung_color`。它们仍留在老用户的
        //    SharedPreferences 里，**刻意不做清理** —— 为三个死键写迁移代码不划算，
        //    且新装用户根本不会产生它们。看到它们别再当 bug 排查。

        // ---- 4. 均衡器与音效 ----
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_PRESET_ID = "eq_preset_id"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_EQ_BASS = "eq_bass"
        const val KEY_EQ_VIRTUAL = "eq_virtual"
        const val KEY_EQ_LOUDNESS = "eq_loudness"
        const val KEY_EQ_REVERB_ID = "eq_reverb_id"
        const val KEY_SOFT_EQ_USER_CONFIRM = "soft_eq_user_confirm"
        const val KEY_SOUND_SCHEME_ID = "sound_scheme_id"
        const val KEY_CAR_SCHEME_ID = "car_scheme_id"
        const val KEY_SOUND_STASH = "sound_stash"
        const val KEY_FADE_ENABLED = "fade_enabled"
        const val KEY_FADE_DURATION_MS = "fade_duration_ms"
        const val KEY_STEREO_BALANCE = "stereo_balance"
        const val KEY_LOUDNESS_BALANCE = "loudness_balance"
        const val KEY_SKIP_INTRO_SECONDS = "skip_intro_seconds"
        const val KEY_SKIP_OUTRO_SECONDS = "skip_outro_seconds"
        const val KEY_REPLAY_GAIN_MODE = "replay_gain_mode"
        const val KEY_EQ_USER_PRESETS = "eq_user_presets"
        const val KEY_SCENE_ID = "scene_id"
        const val KEY_SCENE_STASH = "scene_stash"

        // ---- 5. 桌面悬浮歌词 ----
        const val KEY_LYRIC_OVERLAY = "lyric_overlay"
        const val KEY_LYRIC_OVERLAY_X = "lyric_overlay_x"
        const val KEY_LYRIC_OVERLAY_Y = "lyric_overlay_y"
        const val KEY_LYRIC_OVERLAY_LINES = "lyric_overlay_lines"
        const val KEY_LYRIC_OVERLAY_TITLE = "lyric_overlay_show_title"

        /** 字号（sp）与标题占正文的百分比，v1.1.51 起取代下面的 scale 两键 */
        const val KEY_LYRIC_OVERLAY_TEXT_SP = "lyric_overlay_text_sp"
        const val KEY_LYRIC_OVERLAY_TITLE_PERCENT = "lyric_overlay_title_percent"

        /** 旧字号档位（0/1/2）—— 只读，用于迁移；别再写 */
        const val KEY_LYRIC_OVERLAY_TITLE_SCALE = "lyric_overlay_title_scale"
        const val KEY_LYRIC_OVERLAY_TEXT_SCALE = "lyric_overlay_text_scale"

        /** 旧底板三档 —— 只读，用于迁移 */
        const val KEY_LYRIC_OVERLAY_BACKDROP = "lyric_overlay_backdrop"
        const val KEY_LYRIC_OVERLAY_PAUSE_MODE = "lyric_overlay_pause_mode"
        const val KEY_LYRIC_OVERLAY_ALIGN = "lyric_overlay_align"
        const val KEY_LYRIC_OVERLAY_WIDTH = "lyric_overlay_width_percent"
        const val KEY_LYRIC_OVERLAY_TEXT_COLOR = "lyric_overlay_text_color"
        const val KEY_LYRIC_OVERLAY_FULL_HEIGHT = "lyric_overlay_full_height"
        /** 全屏模式的宽度（屏宽百分比）与水平位置（余量百分比），与单条字幕分开存 */
        const val KEY_LYRIC_OVERLAY_FULL_WIDTH = "lyric_overlay_full_width_percent"
        const val KEY_LYRIC_OVERLAY_BOX_HEIGHT = "lyric_overlay_box_height_percent"
        const val KEY_LYRIC_OVERLAY_FULL_HEIGHT_PCT = "lyric_overlay_full_height_percent"
        const val KEY_LYRIC_OVERLAY_X_PCT = "lyric_overlay_x_percent"
        const val KEY_LYRIC_OVERLAY_Y_PCT = "lyric_overlay_y_percent"
        const val KEY_LYRIC_OVERLAY_FULL_Y_PCT = "lyric_overlay_full_y_percent"
        const val KEY_LYRIC_OVERLAY_BG_COLOR = "lyric_overlay_bg_color"
        const val KEY_LYRIC_OVERLAY_BG_ALPHA = "lyric_overlay_bg_alpha_percent"

        /** 旧「自定义背景」开关 —— 只读，用于迁移（v1.1.51 起背景始终可自定义） */
        const val KEY_LYRIC_OVERLAY_CUSTOM_BG = "lyric_overlay_custom_bg"
        const val KEY_LYRIC_OVERLAY_FULL_OFFSET = "lyric_overlay_full_offset_percent"

        // ---- 样式细节（v1.1.52）----
        const val KEY_LYRIC_OVERLAY_CORNER = "lyric_overlay_corner_dp"
        const val KEY_LYRIC_OVERLAY_BORDER_W = "lyric_overlay_border_width_dp"
        const val KEY_LYRIC_OVERLAY_BORDER_C = "lyric_overlay_border_color"
        const val KEY_LYRIC_OVERLAY_LINE_SPACING = "lyric_overlay_line_spacing_dp"
        const val KEY_LYRIC_OVERLAY_CURRENT_COLOR = "lyric_overlay_current_color"
        const val KEY_LYRIC_OVERLAY_DIM = "lyric_overlay_dim_percent"
        const val KEY_LYRIC_OVERLAY_STROKE_W = "lyric_overlay_stroke_width_dp"
        const val KEY_LYRIC_OVERLAY_STROKE_C = "lyric_overlay_stroke_color"
        const val KEY_LYRIC_OVERLAY_FADE = "lyric_overlay_fade_ms"
        const val KEY_LYRIC_OVERLAY_TITLE_MODE = "lyric_overlay_title_mode"

        // ---- 6. 前台检测与调试 ----
        const val KEY_LYRIC_OVERLAY_HIDDEN_APPS = "lyric_overlay_hidden_apps"
        const val KEY_FOREGROUND_LOG_ENABLED = "foreground_log_enabled"
        const val KEY_DEBUG_UNLOCKED = "settings_debug_unlocked"

        // ---- 7. 外观与主题 ----
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_THEME = "dynamic_theme"
        const val KEY_CAR_MODE = "car_mode"
        const val KEY_BT_AUTO_CAR_ENABLED = "bt_auto_car_enabled"
        const val KEY_BT_AUTO_CAR_ANY_DEVICE = "bt_auto_car_any_device"
        const val KEY_BT_AUTO_CAR_DEVICES = "bt_auto_car_devices"
        const val KEY_BT_AUTO_CAR_RESTORE = "bt_auto_car_restore"
        const val KEY_BT_AUTO_CAR_SESSION = "bt_auto_car_session"
        const val KEY_BT_AUTO_CAR_STASH = "bt_auto_car_stash"

        // ---- 8. 应用内更新 ----
        const val KEY_AUTO_UPDATE = "auto_check_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        const val KEY_UPDATE_ROUTE = "update_download_route"
        const val KEY_UPDATE_READY_APK = "update_ready_apk_path"
        const val KEY_UPDATE_READY_APK_VERSION = "update_ready_apk_version"
    }
}
