package com.luckylearning.windtone.ui
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.state.PlayMode
import com.luckylearning.windtone.state.playModeOf
import com.luckylearning.windtone.state.PlayerUiState

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.luckylearning.windtone.overlay.ForegroundAppDetector
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.data.overlayPresetLabel
import com.luckylearning.windtone.state.overlayPresetParams
import com.luckylearning.windtone.ui.theme.AppVisual
import com.luckylearning.windtone.ui.theme.windShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** 车载模式：歌词态无操作多久后收起顶栏 / 控制键 / 状态行（毫秒） */
private const val CAR_IMMERSIVE_DELAY_MS = 5_000L

/**
 * 车机播放页的控制键尺寸（v1.1.23 收紧）。
 *
 * 原先是 112 / 96 / 76 —— 三个键占掉将近 120dp 高度，歌词区被压得很扁，
 * 用户的原话是「不要播放按钮的占高度」。现在整排压到 64~76dp：
 * 触控目标仍在 64dp 以上（行车中按得中），但纵向只占原来的一半。
 */

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    progressFlow: StateFlow<PlaybackProgress>,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onLyricOffset: (Int) -> Unit,
    onResetLyricOffset: () -> Unit,
    /**
     * 长按歌词行校准偏移（批次 10.4-②）。
     *
     * 用户听到「现在正唱这一句」时直接长按那一行，由「该行时间戳 − 当前播放位置」
     * 反算出需要的偏移**绝对值**，一次到位；比让他自己按十几次 ±100ms 靠谱得多。
     * 增量的 [onLyricOffset] 留着做微调（弹层里那对按钮）。
     */
    onLyricOffsetAbsolute: (Int) -> Unit = {},
    /**
     * 播放页中部显示歌词（true）还是封面（false）。
     *
     * 这个状态由引擎持有并落盘（批次 10.4-③）：以前是 `remember`，每次进播放页
     * 都重置回歌词态，切到封面后退出再进来又变回歌词——用户会以为开关坏了。
     */
    onLyricsPaneVisible: (Boolean) -> Unit = {},
    /**
     * true = 作为宽屏右栏的**面板**（平板双栏）；false = 手机上的全屏覆盖层。
     * 面板态没有「收起播放页」这回事——它本来就不是盖上去的，箭头点了没反应。
     */
    pane: Boolean = false,
    onLyricScale: (Float) -> Unit,
    onShowQueue: () -> Unit,
    onSleep: (Int) -> Unit,
    onSleepEndOfSong: () -> Unit,
    onSleepEndOfAlbum: () -> Unit,
    onCancelSleep: () -> Unit,
    onSpeed: (Float) -> Unit,
    /** 变调：半音（-12..+12，0 = 原调） */
    onPitch: (Int) -> Unit,
    onMarkAbLoopA: () -> Unit = {},
    onMarkAbLoopB: () -> Unit = {},
    onClearAbLoop: () -> Unit = {},
    /** D1：逐句循环（灌入同一 A-B 引擎） */
    onLoopLyricLine: (Long, Long) -> Unit = { _, _ -> },
    onExportLyrics: (android.net.Uri) -> Unit,
    onShowAudioInfo: () -> Unit = {},
    /**
     * 悬浮歌词的快捷入口（播放设置弹层 → 歌词分组）。
     *
     * 原先只有「设置 → 歌词 → 桌面悬浮歌词」一个入口，藏在三级菜单里——
     * 功能再好，用户找不到就等于没有。这里给一个顺手就能碰到的地方。
     */
    canDrawOverlay: Boolean = false,
    onLyricOverlay: (Boolean) -> Unit = {},
    /**
     * 套用悬浮歌词样式预设（v1.1.78，速选面板 → FR-03）。
     * 直通 PlayerActions.applyOverlayPreset（`by` 委托），这里只透传。
     */
    onApplyOverlayPreset: (OverlayPreset) -> Unit = {},
    /** 速选面板「更多设置…」：打开设置页（MainScreen 负责具体导航） */
    onOpenSettings: () -> Unit = {},
    // 车载沉浸态下常驻歌名行右侧的红心需要自己知道收藏状态并触发切换，
    // 手机版歌名在顶栏、不带红心，这两个参数在手机模式下不会被用到。
    isFavorite: Boolean = false,
    onToggleFavorite: (Song) -> Unit = {},
) {
    // 歌词 / 封面二选一：状态在引擎里（落盘，跨会话保留），这里只是个只读视图 + 回调。
    val showLyrics = state.lyricsPaneVisible

    // v1.1.92：播放页左侧的「前台识别状态」按钮。
    // 状态不能只在进入页面时读一次 —— 用户点按钮去系统里开/关，回来必须立刻反映，
    // 所以挂一个 ON_RESUME 监听重读（读一次 Settings.Secure 很便宜）。
    val a11yContext = LocalContext.current
    var a11yEnabled by remember {
        mutableStateOf(ForegroundAppDetector.accessibilityEnabled(a11yContext))
    }
    val a11yLifecycle = LocalLifecycleOwner.current
    DisposableEffect(a11yLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yEnabled = ForegroundAppDetector.accessibilityEnabled(a11yContext)
            }
        }
        a11yLifecycle.lifecycle.addObserver(observer)
        onDispose { a11yLifecycle.lifecycle.removeObserver(observer) }
    }
    var showOptions by remember { mutableStateOf(false) }
    // 悬浮窗样式速选面板（v1.1.78）：从播放设置弹层的「悬浮窗样式」行打开
    var showOverlayStyle by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    // 长按歌词行校准偏移：存「该行时间戳 to 按下时的播放位置」。
    // 刻意在按下瞬间把位置**快照**下来，而不是让弹窗每 250ms 读一次进度——
    // 否则用户盯着弹窗读数字的那几秒里，"当前要设成多少"一直在跳。
    var offsetCalibrate by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // D1：长按歌词 → 先选「循环此句 / 校准偏移」；存行号 + 行时间戳 + 按下时进度
    var lyricLineMenu by remember { mutableStateOf<Triple<Int, Long, Long>?>(null) }

    // 播放模式（顺序 / 列表循环 / 单曲循环 / 随机）—— 4 个档位共用下面控制区的一个按钮
    val playMode = playModeOf(state.repeatMode, state.shuffle)
    // 模式名要在 LaunchedEffect 里用，而后者不是组合上下文，先在此处解析。
    val playModeLabel = playMode.displayName()
    var modeHint by remember { mutableStateOf("") }
    var modeHintVisible by remember { mutableStateOf(false) }
    var lastPlayMode by remember { mutableStateOf(playMode) }
    // 切换后浮出模式名 ~1.4s，避免只靠图标让人猜当前是哪一档
    LaunchedEffect(playMode) {
        if (playMode == lastPlayMode) return@LaunchedEffect
        lastPlayMode = playMode
        modeHint = playModeLabel
        modeHintVisible = true
        delay(1400)
        modeHintVisible = false
    }

    val lyricsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) onExportLyrics(uri) }

    // ------------------------------------------------------ 沉浸态（手机 + 车机）
    //
    // 歌词态 5 秒无触摸 → 收起顶栏 / 控制键行 / 底部状态行；保留歌名行（含红心）
    // + 歌词 + 进度条 + 时间，把纵向空间全让给歌词（见 docs/ui ⑫D 屏）。
    // 2026-10-20 用户确认：沉浸态不是车机专属，手机版歌词态同样生效。
    //
    // 计时模型：把「最后一次用户操作时刻」记在 [lastActionAt]，
    // 再让协程睡满 5 秒后把 [immersive] 置真。三处状态的分工：
    //  · [lastActionAt]：所有交互（点按钮 / 触摸屏幕 / 按返回）统一刷新的时间戳，
    //    它一变就重启下面那个 delay；
    //  · [showLyrics]：封面态本身就以封面为主要视觉，不收起，切回歌词态时重新计时；
    //  · [immersiveOn]：由协程置真、由交互置假，保证「任意触摸立即恢复」。
    var lastActionAt by remember { mutableStateOf(0L) }
    var immersiveOn by remember { mutableStateOf(false) }
    val immersive = showLyrics && immersiveOn

    /** 任意交互：立刻退出沉浸态，并让 5 秒从头计起 */
    val keepAwake: () -> Unit = {
        immersiveOn = false
        lastActionAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------ 左右滑动切歌
    //
    // 中部内容区的横向滑动（左滑下一首 / 右滑上一首），判据与参数见 PlayerSwipe。
    // 偏移量在这里持有、在下面那层 Box 上用 graphicsLayer 平移 —— 只动中部，
    // 顶栏与底部控制行不跟着晃。
    //
    // ⚠️ 作用域必须在组合期取一次：手势回调不是挂起函数，而 snapTo / animateTo 是，
    //    在 modifier 内部每次重组新建 scope 会让进行中的回弹动画被取消。
    val swipeScope = rememberCoroutineScope()
    val swipeOffset = remember { Animatable(0f) }

    // 进入车载模式且停在播放页时，先给满 5 秒再考虑收起
    LaunchedEffect(state.carMode) {
        if (state.carMode) keepAwake()
    }

    // 手机版同样有沉浸态：进入 / 切回歌词态时把计时起点重新种下。
    // 否则 lastActionAt 恒为 0，手机端没人碰过屏幕就永远不会开始倒数。
    LaunchedEffect(showLyrics) {
        if (showLyrics) lastActionAt = System.currentTimeMillis()
    }

    // 空闲 5 秒 → 收起。关键：这里的重启条件是 [lastActionAt] + [showLyrics]，
    // 两者都不含 [immersiveOn]，所以本协程自己置真后不会被自己的写入取消，
    // 否则 setState 会让 LaunchedEffect 重启，「5 秒后收起」永远执行不完。
    // 2026-10-20：不再限定车载模式，手机歌词态同样生效。
    LaunchedEffect(showLyrics, lastActionAt) {
        if (!showLyrics || lastActionAt <= 0L) return@LaunchedEffect
        delay(CAR_IMMERSIVE_DELAY_MS)
        immersiveOn = true
    }

    // 沉浸态下按返回：先恢复完整界面，而不是直接收起播放页（手机 / 车机一致）。
    // enabled 只在沉浸态为真，其余情况不拦截，交给上层原有的返回逻辑。
    BackHandler(enabled = immersive) { keepAwake() }

    // ---------------------------------------------------------- 音量手势
    val appContext = LocalContext.current.applicationContext
    val audioManager = remember(appContext) {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumePercent by remember { mutableStateOf<Int?>(null) }
    var volumeTick by remember { mutableIntStateOf(0) }
    var volumeAccum by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(volumeTick) {
        if (volumeTick > 0) {
            delay(1200)
            volumePercent = null
        }
    }

    val onVolumeDelta: (Float) -> Unit = { delta ->
        volumeAccum += delta
        val steps = volumeAccum.toInt()
        if (steps != 0) {
            volumeAccum -= steps
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next = (current + steps).coerceIn(0, maxVolume)
            if (next != current) {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
            }
            volumePercent = next * 100 / maxVolume
            volumeTick++
        }
    }

    val song = state.current
    val scheme = MaterialTheme.colorScheme
    // v1.1.98：车机版所有"块头"尺寸统一取自 [CarMetrics]，不再各处写死
    val carM = rememberCarMetrics()

    // 顶层只「收集」不「读取」：State 对象可以安全地传给子组件，
    // Compose 的失效是跟着 .value 的读取点走的，所以播放页本身不会随进度重组，
    // 只有真正读它的进度条 / 时间文字 / 歌词面板会重组。
    val progressState = progressFlow.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // 背景：封面主色 → 背景色的垂直渐变，营造氛围。
        // 注意必须用「不透明混色」而不是 copy(alpha=)：播放页是覆盖在主界面之上的，
        // 半透明背景会把底下的歌曲列表和歌词透出来，整个页面糊成一片（v3.0.1 修复）。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            lerp(scheme.background, scheme.primary, 0.32f),
                            lerp(scheme.background, scheme.primary, 0.10f),
                            scheme.background,
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                // 车机：只撑满高度（宽度交给 widthIn 约束，再由 align 居中）；
                // 手机：撑满整个屏幕，行为与改动前完全一致。
                .then(
                    if (state.carMode) Modifier.fillMaxHeight() else Modifier.fillMaxSize()
                )
                .statusBarsPadding()
                // ⚠️ 不加 navigationBarsPadding：宿主 MainScreen 已经让出过一次
                // bottomSafe（系统导航栏与车机 Dock 取大值），这里再让一次就是
                // **双重让位** —— 沉浸态的进度条 / 时间会悬在 Dock 上方一大截
                // （2026-10-18 用户反馈「进度条及歌曲名应移到最下面」，模拟器实测撞上）。
                // 顶部没有对应预留，statusBarsPadding 保留。
                // 任意触摸（包括点按钮、拖进度条）都算「有操作」，回到完整界面并重新计时。
                // 只观察不消费，所有既有手势（滑动切歌、拖动进度、点击按钮）保持原样。
                // 注意：同一个手势会同时命中这里和子按钮的 onClick，但两者都只是把
                // 时间戳往前推，重复刷新无副作用。
                .pointerInput(Unit) {
                    // 2026-10-20：手机也进沉浸态，所以「任意触摸即恢复并重新计时」两端都要挂。
                    // 只观察不消费，既有手势（滑动切歌 / 拖进度）不受影响。
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            immersiveOn = false
                            lastActionAt = System.currentTimeMillis()
                        }
                    }
                }
                // 车机是 1707dp 宽的横屏，若让手机版竖屏内容直接铺满，控件会被拉到
                // 屏幕两端：返回键在最左、设置键在最右，视线来回扫一两千像素；
                // 歌词一行也可能长到 40 字，读完要扭头。这里限宽居中，版式与手机一致。
                //
                // 但**沉浸态不限宽**——沉浸态已经把顶栏 / 控制键 / 状态行全收起了，
                // 只剩歌词 + 歌名行 + 进度条，此时的诉求是「歌词尽量大、尽量宽」，
                // 再套 760dp 反而把最该利用的宽屏空间浪费掉（效果图 ㊲ 正是全宽歌词）。
                // v1.1.23：车机不再限宽（原先 760dp）。用户要的是「进来就是全屏宽度」——
                // 歌词本来就在宽屏上更好读，限宽反而把两侧留成大片空白。
                .align(Alignment.Center)
                .padding(horizontal = if (state.carMode) 40.dp else 20.dp)
                // 沉浸态贴底（用户 2026-10-18 反馈「进度条及歌曲名应移到最下面，
                // 现在和 Dock 之间还有很大间隙」）：底部不留白，直接贴到 MainScreen
                // 让出的 Dock 安全区上沿；完整态保留 12dp 呼吸感。
                .padding(top = 8.dp, bottom = if (immersive) 0.dp else 12.dp),
        ) {
            // ------------------------------------------------------ 顶栏
            // 车载沉浸态收起：整条不参与测量，歌词区顺势向上占满。
            // 注意：歌名 / 歌手不在这里 —— 按效果图 ⑫D，沉浸态只保留
            // 「歌词 + 歌名行（含红心）+ 进度条 + 时间」，歌名行是常驻的。
            if (!immersive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 面板态（平板右栏）不显示收起箭头：播放页不是盖上去的，
                    // 点了也没有"收起来"这回事，留着只会让人以为按钮坏了。
                    if (!pane) {
                        IconButton(onClick = { keepAwake(); onClose() }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "收起播放页",
                                tint = scheme.onSurface,
                            )
                        }
                    }
                    if (state.carMode) {
                        // 车机（v1.1.44 参考车机版布局）：歌名 / 歌手居中放在顶栏正中，
                        // 下方左歌词右唱片；底部只留进度与控制键，不再重复一行歌名。
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = song?.title ?: "未在播放",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = song?.artistOrUnknown ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        // 收藏 / 播放设置移到左侧竖排（v1.1.53）：
                        // 顶栏只留「收起箭头 + 居中歌名 / 歌手」。
                        // 右侧补一个与左箭头等宽的占位，歌名才是**屏幕正中**而不是
                        // 「箭头右侧那段的正中」（偏右约 24dp，肉眼一眼就看得出）。
                        if (!pane) Spacer(Modifier.width(48.dp))
                    } else {
                        // 手机顶栏中间留空 —— 歌名 / 歌手在下方常驻歌名行里（效果图 ⑫ 的 p-nowrow）
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { keepAwake(); onLyricsPaneVisible(!showLyrics) }) {
                            Icon(
                                imageVector = if (showLyrics) AppIcons.Album else AppIcons.Lyrics,
                                contentDescription = if (showLyrics) "切换为封面" else "切换为歌词",
                                tint = scheme.onSurface,
                            )
                        }
                    }
                    // 车机：播放设置已移进左侧竖排功能列（v1.1.53），顶栏不再出现
                    if (!state.carMode) {
                        IconButton(onClick = { keepAwake(); showOptions = true }) {
                            Icon(
                                imageVector = AppIcons.Tune,
                                contentDescription = "播放设置",
                                tint = scheme.onSurface,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // --------------------------------------------- 中部：封面 / 歌词
            //
            // 2026-10-27：这一层是「左滑下一首 / 右滑上一首」的手势区。
            // **只挂中部、不挂整页**：底部进度条本身就是横向拖动，整页挂必然抢手势；
            // 中部歌词区是纵向滚动，与横向手势方向互斥，不会互相打岔。
            //
            // 2026-10-28 统一：跟手位移不再加在**这一层**，而是加在各分支的**内容**上。
            // 因为车机分支里同一层还装着左侧那列功能键（收藏 / 队列 / 设置），
            // 挂整层会让三颗键跟着横滑 —— 那是导航控件，不该跟着歌走。
            // 手机上三个分支的唯一内容就是这个 slide，效果与挂整层等价。
            Box(
                Modifier
                    .weight(1f)
                    .playerSwipeToSwitch(
                        offset = swipeOffset,
                        scope = swipeScope,
                        onSwipe = { dir ->
                            keepAwake()
                            when (dir) {
                                SwipeSwitch.NEXT -> onNext()
                                SwipeSwitch.PREVIOUS -> onPrevious()
                                SwipeSwitch.NONE -> Unit
                            }
                        },
                    )
            ) {
                // 跟手位移（只动内容，不动容器）
                val slide = Modifier.graphicsLayer { translationX = swipeOffset.value }
                // 长按哪一行就在哪一刻对表：位置在这里取一次快照
                val onLineLongPress: (Int, Long) -> Unit = { lineIndex, lineMs ->
                    keepAwake()
                    lyricLineMenu = Triple(lineIndex, lineMs, progressState.value.positionMs)
                }
                if (state.carMode) {
                    // 车机（v1.1.44）：参考车机版音乐应用的版式 —— **左歌词、右唱片**，
                    // 两者同时在场，不再「二选一」。宽屏上这样排的信息密度最高：
                    // 歌词占主视区（瞄一眼就能读），唱片在右侧做视觉锚点。
                    //
                    // v1.1.53（2026-10-22 用户要求）：收藏 / 播放队列 / 播放设置
                    // 三键从「顶栏右上 + 底部行最右」**收进最左侧一列竖排** ——
                    // 1707dp 宽屏上这三点原本散在三个角，视线要横穿整块屏；
                    // 竖排一列后驾驶位单手一列盲按就行，且底部控制行只剩
                    // 「播放模式 + ⏮⏯⏭」，主键居中不再被右槽抢视线。
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 沉浸态（5 秒无触摸）与顶栏 / 控制行同步收起，歌词拿回全宽
                        if (!immersive) {
                            CarSideActions(
                                isFavorite = isFavorite,
                                canFavorite = song != null,
                                onFavorite = {
                                    song?.let { onToggleFavorite(it) }
                                    keepAwake()
                                },
                                onQueue = { keepAwake(); onShowQueue() },
                                showA11y = state.showA11yAction,
                                a11yEnabled = a11yEnabled,
                                onA11y = {
                                    keepAwake()
                                    // 直接落到系统无障碍页（DiLink 上是我们解析出的 BYD 页）；
                                    // 全失败时给一句可执行的提示，而不是静默没反应。
                                    if (!ForegroundAppDetector.openAccessibilitySettings(a11yContext)) {
                                        val msg = if (
                                            ForegroundAppDetector.canOpenAccessibilityPage(a11yContext)
                                        ) {
                                            "打不开无障碍页，请到 设置 → 关于与诊断 → 前台应用记录 查看路径"
                                        } else {
                                            "本机没有无障碍设置页，请改用「使用情况访问」"
                                        }
                                        Toast.makeText(a11yContext, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onOptions = { keepAwake(); showOptions = true },
                            )
                        }
                        PlayerLyrics(
                            state = state,
                            progressState = progressState,
                            onSeekTo = onSeekTo,
                            onLineLongPress = onLineLongPress,
                            modifier = Modifier
                                .weight(1.08f)
                                .fillMaxHeight()
                                .padding(end = 24.dp)
                                .then(slide),
                        )
                        CarVinylPane(
                            song = song,
                            isPlaying = state.isPlaying,
                            onToggle = onToggle,
                            modifier = Modifier
                                .weight(0.92f)
                                .fillMaxHeight()
                                // 右侧留一点边，唱片不要贴到屏幕右缘（参考车机版布局是居中偏右）
                                .padding(end = 16.dp)
                                .then(slide),
                        )
                    }
                } else if (showLyrics) {
                    PlayerLyrics(
                        state = state,
                        progressState = progressState,
                        onSeekTo = onSeekTo,
                        onLineLongPress = onLineLongPress,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(slide),
                    )
                } else {
                    // 封面态：横向切歌由父级那层统一手势负责（跟手 + 双判据），
                    // 这里只留纵向音量与双击播放/暂停 —— 见 CoverPane 的注释。
                    Box(Modifier.fillMaxSize().then(slide)) {
                        CoverPane(
                            song = song,
                            isPlaying = state.isPlaying,
                            onToggle = onToggle,
                            onVolumeDelta = onVolumeDelta,
                        )
                    }
                }

                // 播放模式切换提示：浮在封面 / 歌词之上，不参与布局，所以不会顶动其他元素
                val hintAlpha by animateFloatAsState(
                    targetValue = if (modeHintVisible) 1f else 0f,
                    animationSpec = tween(180),
                    label = "playModeHint",
                )
                if (hintAlpha > 0.01f) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = scheme.surfaceContainerHighest.copy(alpha = 0.92f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .alpha(hintAlpha),
                    ) {
                        Text(
                            text = modeHint,
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // ------------------------------------------- 歌名行（手机 / 车机通用）
            // 效果图 ⑫ 的 p-nowrow：歌名 + 歌手 + 红心，贴在进度条正上方。
            // 手机版与车机版都有这一行 —— 车机沉浸态下顶栏 / 控制键 / 状态行全部收起，
            // 这一行是唯一保留的歌曲标识（效果图 ⑫D 的红框保留项）；
            // 手机版顶栏中间是留空的，歌名同样靠这一行显示（早先误把它做成车机专属，
            // 导致手机版顶栏空一截、歌名消失，已修回）。
            if (!state.carMode || immersive) Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.carMode) {
                    // 车机（效果图 ⑫D）：红心**紧跟歌名/歌手**，不贴屏幕最右缘 ——
                    // 驾驶时视线不用扫到边上（1.0.6 起的既定要求）。
                    // v1.1.23 曾为「控制键并进这一行靠右」把标题列撑满整行、红心随之贴右；
                    // 控制键已回到独立的三段式控制行（2026-10-18 用户反馈「应该居中」），
                    // 这里一并改回紧跟式：标题按内容收缩（超长省略），红心紧随其后。
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            text = song?.title ?: "未在播放",
                            // 车机：歌名 / 歌手各放大一档（22sp / 16sp），60~70cm 视距下读得清
                            style = if (state.carMode) {
                                MaterialTheme.typography.headlineSmall.copy(fontSize = carM.titleSp)
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song?.artistOrUnknown ?: "",
                            style = if (state.carMode) {
                                MaterialTheme.typography.bodyLarge.copy(fontSize = carM.artistSp)
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (song != null) {
                        IconButton(onClick = { onToggleFavorite(song); keepAwake() }) {
                            Icon(
                                imageVector = if (isFavorite) AppIcons.Favorite
                                else AppIcons.FavoriteBorder,
                                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                tint = if (isFavorite) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // 手机：标题占满 + 红心贴右（效果图 ⑫）。
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song?.title ?: "未在播放",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song?.artistOrUnknown ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (song != null) {
                        IconButton(onClick = { onToggleFavorite(song); keepAwake() }) {
                            Icon(
                                imageVector = if (isFavorite) AppIcons.Favorite
                                else AppIcons.FavoriteBorder,
                                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                tint = if (isFavorite) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))

            // ------------------------------------------------------ 进度条
            PlaybackSlider(
                progressState = progressState,
                fallbackDurationMs = state.durationMs,
                onSeekTo = onSeekTo,
            )

            val durationFallback = state.durationMs
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TimeLabel(progressState, durationFallback, showPosition = true)
                Spacer(Modifier.weight(1f))
                TimeLabel(progressState, durationFallback, showPosition = false)
            }

            // 与下方控制行的间隔只在完整态需要；沉浸态控制行不渲染，
            // 这 4dp 会变成时间行与 Dock 之间的悬空感（同上条反馈），一并去掉。
            if (!immersive) Spacer(Modifier.height(4.dp))

            // ------------------------------------------------------ 控制区
            // 车载沉浸态整块收起（与顶栏同一开关，见文件头的沉浸态说明）。
            // v1.1.23 曾把车机控制键并进歌名行靠右排（省一整行高度），
            // 用户 2026-10-18 反馈「播放按钮及上一曲下一曲等按钮应该居中，不应该居右」
            // → 恢复独立三段式控制行：播放模式左槽 / ⏮ ⏯ ⏭ 居中 / 队列右槽，
            // 与手机同一套结构，只是尺寸放大（车机尺寸分支保留至今）。
            if (!immersive) {
                // 三段式布局：左右两个等宽槽位夹住中间的「上一首 / 播放 / 下一首」，
                // 保证主控按钮绝对居中；播放模式固定在左槽，不再把整排按钮挤偏。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        // 播放模式（顺序 / 列表循环 / 单曲循环 / 随机）合并在这一个按钮上轮换
                        IconButton(
                            onClick = { onCyclePlayMode(); keepAwake() },
                            modifier = if (state.carMode) Modifier.size(carM.secondaryButton) else Modifier,
                        ) {
                            Icon(
                                imageVector = playMode.icon,
                                contentDescription = stringResource(
                                    R.string.cd_play_mode,
                                    playMode.displayName(),
                                ),
                                modifier = if (state.carMode) Modifier.size(34.dp) else Modifier,
                                tint = if (playMode == PlayMode.SEQUENTIAL) scheme.onSurfaceVariant
                                else scheme.primary,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (state.carMode) 40.dp else 20.dp),
                    ) {
                        IconButton(
                            onClick = { onPrevious(); keepAwake() },
                            modifier = if (state.carMode) Modifier.size(carM.sideButton) else Modifier,
                        ) {
                            Icon(
                                imageVector = AppIcons.SkipPrevious,
                                contentDescription = "上一首",
                                tint = scheme.onSurface,
                                modifier = Modifier.size(if (state.carMode) 36.dp else 34.dp),
                            )
                        }
                        // 设计规范 v2：主播放键改风之渐变 + 靛蓝投影（--grad-action / --glow）
                        IconButton(
                            onClick = { onToggle(); keepAwake() },
                            modifier = Modifier
                                .size(if (state.carMode) carM.mainButton else 66.dp)
                                .windShadow(level = 3, shape = CircleShape)
                                .clip(CircleShape)
                                .background(AppVisual.ActionGradient),
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(if (state.carMode) 40.dp else 34.dp),
                            )
                        }
                        IconButton(
                            onClick = { onNext(); keepAwake() },
                            modifier = if (state.carMode) Modifier.size(carM.sideButton) else Modifier,
                        ) {
                            Icon(
                                imageVector = AppIcons.SkipNext,
                                contentDescription = "下一首",
                                tint = scheme.onSurface,
                                modifier = Modifier.size(if (state.carMode) 36.dp else 34.dp),
                            )
                        }
                    }
                    // 右侧对称槽：与左槽等宽，把中间三键顶回屏幕正中。
                    // 槽内放播放队列入口（效果图 ⑫ 的 p-ctl 右槽），与左边的播放模式对称。
                    // ⚠️ 车机不放：队列键已移进左侧竖排功能列（v1.1.53），
                    // 但**槽位必须留着**——空了也要占着 1f，否则三键会被左槽顶偏。
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (!state.carMode) {
                            IconButton(onClick = { keepAwake(); onShowQueue() }) {
                                Icon(
                                    imageVector = AppIcons.QueueMusic,
                                    contentDescription = "播放队列",
                                    tint = scheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 状态信息（内置歌词 / 偏移 / 队列数）只在手机显示：
                // 车机这一行要占 30dp 高度，而它能提供的信息在播放页并不是驾驶时要看的
                // （v1.1.23，用户要求「不要播放按钮的占高度」，这一行同理让位给歌词）。
                if (!state.carMode && !immersive) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val src = state.lyrics.source
                    LyricSourceChip(label = src.label)
                    if (state.lyricOffsetMs != 0) {
                        Spacer(Modifier.width(6.dp))
                        LyricSourceChip(label = "偏移 ${state.lyricOffsetMs}ms")
                    }
                    if (state.sleepRemainingMs > 0) {
                        Spacer(Modifier.width(6.dp))
                        LyricSourceChip(
                            label = "睡眠 %d:%02d".format(
                                state.sleepRemainingMs / 60000,
                                (state.sleepRemainingMs % 60000) / 1000,
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    song?.let {
                        Text(
                            text = com.luckylearning.windtone.data.MusicRepository.sourceLabel(it.source),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = "队列 ${state.queueSize} 首",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 音量提示浮层
        volumePercent?.let { pct ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = scheme.inverseSurface.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 40.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.VolumeUp,
                        contentDescription = null,
                        tint = scheme.inverseOnSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "$pct%",
                        color = scheme.inverseOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    if (showOptions) {
        PlayerOptionsSheet(
            speed = state.speed,
            pitchSemitones = state.pitchSemitones,
            lyricScale = state.lyricScale,
            lyricOffsetMs = state.lyricOffsetMs,
            sleepRemainingMs = state.sleepRemainingMs,
            onSpeed = onSpeed,
            onPitch = onPitch,
            onLyricScale = onLyricScale,
            onLyricOffset = onLyricOffset,
            onResetOffset = onResetLyricOffset,
            onOpenEq = { showOptions = false; showEq = true },
            onOpenSleep = { showOptions = false; showSleep = true },
            onExportLyrics = {
                showOptions = false
                lyricsExportLauncher.launch("${song?.baseName ?: "歌词"}.lrc")
            },
            onAudioInfo = { showOptions = false; onShowAudioInfo() },
            lyricOverlay = state.lyricOverlay,
            canDrawOverlay = canDrawOverlay,
            onToggleLyricOverlay = {
                when {
                    // 已开 → 直接关
                    state.lyricOverlay -> onLyricOverlay(false)
                    // 未授权 → 先跳系统授权页；授权返回后由 MainActivity 的 onResume
                    // 重新对账并自动打开开关（与设置页那套完全一致，这里不预置 true，
                    // 免得界面显示「已开启」而桌面上什么都没有）
                    canDrawOverlay -> onLyricOverlay(true)
                    else -> appContext.startOverlayPermissionPage()
                }
            },
            // v1.1.78 歌词菜单：开关副标题报当前预设名 + 「悬浮窗样式」速选入口
            overlayPresetLabel = overlayPresetLabel(state.overlayPresetParams()),
            onOpenOverlayStyle = { showOptions = false; showOverlayStyle = true },
            abLoopAMs = state.abLoopAMs,
            abLoopBMs = state.abLoopBMs,
            abLoopFromLyric = state.abLoopFromLyric,
            onMarkAbLoopA = onMarkAbLoopA,
            onMarkAbLoopB = onMarkAbLoopB,
            onClearAbLoop = onClearAbLoop,
            onDismiss = { showOptions = false },
        )
    }
    if (showOverlayStyle) {
        OverlayStyleSheet(
            carMode = state.carMode,
            currentParams = state.overlayPresetParams(),
            onApply = onApplyOverlayPreset,
            onOpenSettings = onOpenSettings,
            onDismiss = { showOverlayStyle = false },
        )
    }
    if (showEq) EqSheet(onClose = { showEq = false })
    if (showSleep) SleepSheet(
        onClose = { showSleep = false },
        onSet = { onSleep(it); showSleep = false },
        onEndOfSong = { onSleepEndOfSong(); showSleep = false },
        onEndOfAlbum = { onSleepEndOfAlbum(); showSleep = false },
        onCancel = { onCancelSleep(); showSleep = false },
    )

    // ---------------------------------------------- 长按歌词行 → D1 菜单 / 校准偏移
    lyricLineMenu?.let { (lineIndex, lineMs, positionMs) ->
        val durationMs = progressState.value.durationMs
        val interval = state.lyrics.lineIntervalMs(lineIndex, fallbackEndMs = durationMs)
        val loopingThis = state.abLoopFromLyric &&
            state.abLoopAMs != null &&
            interval != null &&
            state.abLoopAMs == interval.first &&
            state.abLoopBMs == interval.second
        AlertDialog(
            onDismissRequest = { lyricLineMenu = null },
            title = { Text(if (loopingThis) "逐句循环中" else "这一句") },
            text = {
                Text(
                    if (loopingThis) {
                        "正在循环此句。可退出循环，或改去校准歌词偏移。"
                    } else {
                        "可循环播放这一句，或把播放位置对准这句来校准偏移。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (loopingThis) {
                    TextButton(
                        onClick = {
                            onClearAbLoop()
                            lyricLineMenu = null
                        },
                    ) { Text("退出循环") }
                } else {
                    TextButton(
                        onClick = {
                            val pair = interval
                            if (pair != null) {
                                onLoopLyricLine(pair.first, pair.second)
                            }
                            lyricLineMenu = null
                        },
                        enabled = interval != null,
                    ) { Text("循环此句") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            offsetCalibrate = lineMs to positionMs
                            lyricLineMenu = null
                        },
                    ) { Text("校准偏移") }
                    TextButton(onClick = { lyricLineMenu = null }) { Text("取消") }
                }
            },
        )
    }

    // ---------------------------------------------- 长按歌词行 → 校准偏移（10.4-②）
    //
    // 偏移的定义是 `indexAt(position + offset)`：要让这一行"刚好唱到"，
    // 就需要 `position + offset ≈ lineMs`，即 `offset = lineMs − position`。
    // 两个都是绝对值，一次相减就够，不必让用户按 ±100ms 的按钮试十几次。
    offsetCalibrate?.let { (lineMs, positionMs) ->
        val suggested = (lineMs - positionMs).coerceIn(-20_000L, 20_000L).toInt()
        AlertDialog(
            onDismissRequest = { offsetCalibrate = null },
            title = { Text("校准歌词偏移") },
            text = {
                Column {
                    Text(
                        "把这一刻正在唱的这一句对准。当前播放位置 " +
                            "${formatDuration(positionMs)}，" +
                            "该行时间戳 ${formatDuration(lineMs)}。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "将把这首歌的歌词偏移设为 ${suggested} ms" +
                            if (state.lyricOffsetMs != 0) {
                                "（当前 ${state.lyricOffsetMs} ms）"
                            } else {
                                ""
                            } + "。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "偏移只作用于这首歌，逐曲记住。没对准时再长按一次即可重设；" +
                            "想微调用播放设置里的 ±100ms。",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onLyricOffsetAbsolute(suggested)
                        offsetCalibrate = null
                    },
                ) { Text("就唱到这句") }
            },
            dismissButton = {
                Row {
                    if (state.lyricOffsetMs != 0) {
                        TextButton(
                            onClick = {
                                onResetLyricOffset()
                                offsetCalibrate = null
                            },
                        ) { Text("重置为 0") }
                    }
                    TextButton(onClick = { offsetCalibrate = null }) { Text("取消") }
                }
            },
        )
    }
}

// ================================================================ 车机播放页（v1.1.44）

/**
 * 歌词面板的薄包装。
 *
 * 车机（左歌词）与手机（整屏歌词）用的是同一个 [LyricsPane]，只有 modifier 不同；
 * 抽出来是为了让"长按对表"的回调只写一次 —— 两处各写一遍很容易只改一边。
 */
@Composable
private fun PlayerLyrics(
    state: PlayerUiState,
    progressState: State<PlaybackProgress>,
    onSeekTo: (Long) -> Unit,
    onLineLongPress: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LyricsPane(
        lyrics = state.lyrics,
        loading = state.lyricsLoading,
        progressState = progressState,
        offsetMs = state.lyricOffsetMs,
        scale = state.lyricScale,
        onSeekTo = onSeekTo,
        modifier = modifier,
        carMode = state.carMode,
        tapSeeks = state.lyricTapSeeks,
        onLineLongPress = onLineLongPress,
    )
}

/** 车机左侧功能列的键尺寸（v1.1.53）。64dp 触控目标 = 行车中不用瞄。 */

/**
 * 车机播放页左侧的功能列：收藏 / 播放队列 / 播放设置（v1.1.53）。
 *
 * 为什么要竖排一列：车机屏 1707dp 宽，这三颗原本散在「顶栏右上」和「底部行最右」，
 * 视线要从歌词横穿到屏幕边缘才能找到；收成最左一列后，驾驶位一只手沿着一列摸下去
 * 就能按到，且不再和底部的播放控制键抢位置。
 *
 * 三颗全程显示、不随状态增删（无当前曲时收藏键置灰）—— 入口时有时无比置灰更难理解。
 * 沉浸态由调用方整列不渲染（与顶栏 / 控制行同一开关）。
 *
 * 间距用 [Arrangement.SpaceEvenly] 而不是固定 gap：矮横屏（如 600px 高的车机档位）
 * 中部区可能装不下「3 键 + 固定间距」，固定间距会把最下面那颗顶进进度条里；
 * SpaceEvenly 在高度富余时拉开、紧张时收紧，永不越界。
 */
@Composable
private fun CarSideActions(
    isFavorite: Boolean,
    canFavorite: Boolean,
    onFavorite: () -> Unit,
    onQueue: () -> Unit,
    onOptions: () -> Unit,
    showA11y: Boolean = false,
    a11yEnabled: Boolean = false,
    onA11y: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val m = rememberCarMetrics()
    Column(
        modifier = Modifier
            // v1.1.98：内侧留白跟着尺码走；SpaceEvenly 在 656dp 高的车机上会把
            // 几颗键拉到屏幕上下两端，视线来回横跳，改成「定距 + 垂直居中」
            .padding(end = if (m.compact) 12.dp else 20.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(m.sideGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CarSideAction(
            icon = if (isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
            description = if (isFavorite) "取消收藏" else "收藏",
            highlighted = isFavorite,
            enabled = canFavorite,
            onClick = onFavorite,
        )
        CarSideAction(
            icon = AppIcons.QueueMusic,
            description = "播放队列",
            onClick = onQueue,
        )
        // v1.1.92：无障碍状态按钮 —— 开着是主题色，关掉是错误色（一眼可辨）；
        // 点击直接进系统无障碍页，开完返回即变绿。
        if (showA11y) {
            CarSideAction(
                icon = AppIcons.Accessibility,
                description = if (a11yEnabled) "前台识别：无障碍已开启" else "前台识别：无障碍已关闭，点此开启",
                tint = if (a11yEnabled) scheme.primary else scheme.error,
                onClick = onA11y,
            )
        }
        CarSideAction(
            icon = AppIcons.Tune,
            description = "播放设置",
            tint = scheme.onSurfaceVariant,
            onClick = onOptions,
        )
    }
}

/**
 * 左侧功能列里的一颗键：圆角底片 + 图标。
 *
 * 底片不是装饰 —— 车机背景是占位图 / 深色渐变混合，纯图标在浅色区域会「消失」；
 * 一层 6% 的 onSurface 底片保证任何背景下都有可见边界，选中态换 20% 主色。
 */
@Composable
private fun CarSideAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val m = rememberCarMetrics()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(m.sideTile)
            .clip(RoundedCornerShape(m.sideCorner))
            .background(
                if (highlighted) scheme.primary.copy(alpha = 0.20f)
                else scheme.onSurface.copy(alpha = 0.06f)
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
                highlighted -> scheme.primary
                else -> tint ?: scheme.onSurface
            },
            modifier = Modifier.size(if (m.compact) 24.dp else 30.dp),
        )
    }
}

/**
 * 车机右侧的旋转黑胶（v1.1.44）。
 *
 * 为什么自己画而不是直接放方形封面：车机版音乐应用的一致视觉就是「转动的唱片」，
 * 它同时承担三件事 —— 这是哪首歌（中心贴封面）、在不在播（转 / 停）、
 * 以及把右侧那片宽屏填满（方形封面在大横屏上会显得很空）。
 *
 * 旋转用 [Animatable] 而不是 `rememberInfiniteTransition`：后者在暂停时会把角度
 * 跳回起点，观感像"唱片被拨回原位"。这里暂停即停在当前角度，继续播接着转。
 * 一圈 24 秒，接近真实 33 转唱片的观感，也不会让人眼晕。
 *
 * 2026-10-28：这里**没有**左右滑切歌 —— 横向切歌由 `PlayerScreen` 中部那层统一手势负责
 * （跟手 + 双判据，与手机封面 / 歌词态完全一致）。本组件只挂了双击播放/暂停，
 * `detectTapGestures` 只消费按下与抬起、不消费移动，横向拖动会正常上浮给父级。
 * 可见性由 `private` 放到 `internal` 是为了让 `CarPaneSwipeTest` 能直接验这一条。
 */
@Composable
internal fun CarVinylPane(
    song: Song?,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            angle.animateTo(
                targetValue = angle.value + 360f,
                animationSpec = tween(durationMillis = 24_000, easing = LinearEasing),
            )
            // 360° 与 0° 视觉等价，重置一下避免浮点无限累加后丢精度
            angle.snapTo(0f)
        }
    }

    BoxWithConstraints(
        modifier = modifier.pointerInput(Unit) {
            // 双击唱片 = 播放 / 暂停，和手机封面一致（行车中不敢去点小按钮时的兜底）
            detectTapGestures(onDoubleTap = { onToggle() })
        },
        contentAlignment = Alignment.Center,
    ) {
        // 留一点边距，避免唱片顶到歌词或控制行；边长取可用空间的短边
        val side = minOf(maxWidth, maxHeight) * 0.86f
        Box(
            modifier = Modifier
                .size(side)
                .graphicsLayer { rotationZ = angle.value },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                drawCircle(Color(0xFF121214), radius = radius)
                // 细密同心纹：黑胶的"沟槽"。透明度压得很低，只是让深色盘面有层次
                val rings = 18
                for (i in 1..rings) {
                    val r = radius * (0.58f + 0.42f * i / rings)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = r,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                // 外沿高光：让盘面从背景里"浮"出来
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f),
                    radius = radius - 1.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            // 中心贴纸（封面）
            CoverArt(
                song = song,
                size = side * 0.54f,
                modifier = Modifier.size(side * 0.54f),
                circular = true,
            )
            // 轴孔
            Box(
                modifier = Modifier
                    .size(side * 0.035f)
                    .clip(CircleShape)
                    .background(Color(0xFF08080A)),
            )
        }
    }
}
