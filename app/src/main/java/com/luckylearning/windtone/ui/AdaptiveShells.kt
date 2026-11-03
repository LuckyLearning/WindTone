package com.luckylearning.windtone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.luckylearning.windtone.data.LibraryFormatFilter
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlaybackProgress
import com.luckylearning.windtone.ui.theme.LocalUiFormFactor
import com.luckylearning.windtone.ui.theme.adaptiveDensity
import kotlinx.coroutines.flow.StateFlow

/**
 * 一体化「外壳」统一入口（UI v2 · 阶段四）。
 *
 * ## 这一层解决什么
 *
 * 重构前 MainScreen 里有三处 `if (carMode)` 各自决定用哪一套自绘组件：
 * `CarNavRail`↔`LibraryDrawer`、`CarLibraryHeader`↔`LibraryHeader`、
 * `CarPlayerBar`↔`MiniPlayer`。两套组件不只是长得不同，**参数面也不同**
 * （头部 15 个回调 vs 3 个参数），于是「车机没抽屉 → 云盘 / 扫描范围入口要各补一遍」
 * 这类割裂反复出现。
 *
 * 现在把决策收进组件内部的一次 `LocalUiFormFactor` 查表：调用点只组装**一套**
 * [AdaptiveNavActions] / [LibraryToolsActions] / 播放回调，两端拿到的是同一批动作。
 * 呈现态仍允许不同（竖屏抽屉 vs 横屏常驻栏），但那只是密度与位置参数，
 * 不再是两套互不相干的功能集合。
 *
 * 内部实现暂仍委托给已验证过真机的既有 Composable —— 这样先把「调用面」一体化，
 * 视觉零回归；后续逐块合并内部实现时，收口点只有本文件。
 */

// ---------------------------------------------------------------- 一级导航

/**
 * 一级导航的**唯一动作集合**。
 *
 * Rail 与 Drawer 共用它，杜绝「某端多一个入口、某端漏一个入口」。
 */
data class AdaptiveNavActions(
    val onSelectView: (LibraryView) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenAliyun: () -> Unit,
    val onOpenScanScope: () -> Unit,
    val onClose: () -> Unit = {},
)

/** 常驻侧栏位（挂在主内容 Row 内）；手机形态直接不渲染，改由 [AdaptiveNavDrawer] 承载。 */
@Composable
fun AdaptiveNavRail(
    current: LibraryView,
    aliyunActive: Boolean,
    actions: AdaptiveNavActions,
) {
    if (!LocalUiFormFactor.current.hasSideNav) return
    CarNavRail(
        current = current,
        onSelect = actions.onSelectView,
        onOpenSettings = actions.onOpenSettings,
        aliyunActive = aliyunActive,
        onOpenAliyun = actions.onOpenAliyun,
        onOpenScanScope = actions.onOpenScanScope,
    )
}

/** 抽屉覆盖层（挂在内容 Box 之上）；宽形态直接不渲染，导航已由 [AdaptiveNavRail] 常驻提供。 */
@Composable
fun AdaptiveNavDrawer(
    open: Boolean,
    current: LibraryView,
    aliyunActive: Boolean,
    actions: AdaptiveNavActions,
    modifier: Modifier = Modifier,
) {
    if (LocalUiFormFactor.current.hasSideNav) return
    LibraryDrawer(
        open = open,
        currentView = current,
        onSelectView = actions.onSelectView,
        onOpenScanScope = actions.onOpenScanScope,
        onOpenSettings = actions.onOpenSettings,
        onClose = actions.onClose,
        aliyunActive = aliyunActive,
        onOpenAliyun = actions.onOpenAliyun,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- 曲库头部

/**
 * 曲库头部的统一入口。
 *
 * 关键变化：车机也拿到**全套** [LibraryToolsActions]，⋮ 菜单与手机共用同一个
 * `LibraryToolsMenu`（9 个就地动作），不再因为「行车中找不到」而整块裁掉功能。
 */
@Composable
internal fun AdaptiveHeader(
    library: LibraryUiState,
    granted: Boolean,
    tools: LibraryToolsActions,
    onOpenDrawer: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onFormatFilter: (LibraryFormatFilter) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
) {
    if (LocalUiFormFactor.current.isCar) {
        CarLibraryHeader(
            library = library,
            onQuery = onQuery,
            onSubmitSearch = onSubmitSearch,
            granted = granted,
            toolsActions = tools,
        )
    } else {
        LibraryHeader(
            library = library,
            granted = granted,
            onOpenDrawer = onOpenDrawer,
            onQuery = onQuery,
            onSort = onSort,
            onFormatFilter = onFormatFilter,
            onRequestPermission = tools.onRequestPermission,
            onImportFolder = tools.onImportFolder,
            onRescan = tools.onRescan,
            onRefreshLyrics = tools.onRefreshLyrics,
            onDeepScan = tools.onDeepScan,
            onImportM3U = tools.onImportM3U,
            onSubmitSearch = onSubmitSearch,
            onClearSearchHistory = onClearSearchHistory,
            onOpenScanScope = tools.onOpenScanScope,
            onFullScan = tools.onFullScan,
            onOpenReport = tools.onOpenReport,
        )
    }
}

// ---------------------------------------------------------------- 现在播放条

/**
 * 现在播放条的统一入口。
 *
 * 手机也拿到收藏键（此前只有车机大条有）；平板上按密度表启用 `large` 呈现态。
 * 播放控制回调两端完全一致，只是尺寸与附加键由形态决定。
 */
@Composable
fun NowPlayingBar(
    song: Song?,
    isPlaying: Boolean,
    progressFlow: StateFlow<PlaybackProgress>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val density = adaptiveDensity()
    if (density.formFactor.isCar) {
        CarPlayerBar(
            song = song,
            isPlaying = isPlaying,
            progressFlow = progressFlow,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onExpand = onExpand,
            onToggle = onToggle,
            onNext = onNext,
            onPrevious = onPrevious,
            onSeek = onSeek,
        )
    } else {
        MiniPlayer(
            song = song,
            isPlaying = isPlaying,
            progressFlow = progressFlow,
            onExpand = onExpand,
            onToggle = onToggle,
            onNext = onNext,
            onPrevious = onPrevious,
            onSeek = onSeek,
            large = density.formFactor == com.luckylearning.windtone.ui.theme.UiFormFactor.TABLET,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
        )
    }
}
