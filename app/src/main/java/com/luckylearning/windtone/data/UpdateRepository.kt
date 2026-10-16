package com.luckylearning.windtone.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.luckylearning.windtone.state.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用内更新域。
 *
 * 与播放、曲库完全无关，原先却和它们挤在同一个 ViewModel 里——更新一次状态
 * 会让整个主界面重组。抽出来之后它有自己的状态流与作用域，
 * 发起检查 / 下载 / 安装都不再需要界面在场。
 *
 * 唯一的外部依赖是 [Prefs]（自动检查开关、限频时间戳、下载线路）与 [MessageBus]。
 */
class UpdateRepository(
    private val app: Application,
    private val prefs: Prefs,
    private val messages: MessageBus,
) : UpdateActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _update = MutableStateFlow(
        UpdateUiState(
            currentVersion = UpdateChecker.currentVersion,
            route = prefs.updateDownloadRoute,
        )
    )
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    init {
        maybeAutoCheck()
    }

    /**
     * 启动时自动检查更新。
     * 默认开启（可在设置页关闭），24 小时内最多查一次，且失败完全静默——不打扰听歌。
     */
    private fun maybeAutoCheck() {
        if (!prefs.autoCheckUpdate) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheckAt < AUTO_CHECK_INTERVAL_MS) return
        scope.launch {
            // 让启动帧与首次扫描先跑完，再发网络请求
            delay(4_000)
            if (!prefs.autoCheckUpdate) return@launch
            prefs.lastUpdateCheckAt = System.currentTimeMillis()
            when (val r = UpdateChecker.checkLatest()) {
                is UpdateCheckResult.Available -> {
                    _update.update { it.copy(info = r.info) }
                    // 指路要指到**实际那一级**：设置主页上是「更新」分类卡，
                    // 「检查更新」是它下面那一行（2026-10-17 入口收敛后更是只剩这一处，
                    // 见《AI 开发指南》§11 第 16 条）。
                    messages.post("发现新版本 v${r.info.version}，可在「设置 → 更新」中升级")
                }
                // 已是最新 / 检查失败：自动模式下都不提示
                else -> Unit
            }
        }
    }

    /** 手动检查更新（设置页按钮） */
    override fun checkUpdate() {
        if (_update.value.checking) return
        scope.launch {
            _update.update { it.copy(checking = true, message = null) }
            prefs.lastUpdateCheckAt = System.currentTimeMillis()
            when (val r = UpdateChecker.checkLatest()) {
                is UpdateCheckResult.Available ->
                    _update.update { it.copy(checking = false, info = r.info, dialogVisible = true, message = null) }

                is UpdateCheckResult.UpToDate ->
                    _update.update {
                        it.copy(checking = false, info = null, message = "已是最新版本（v${r.latestVersion}）")
                    }

                is UpdateCheckResult.Failed ->
                    _update.update { it.copy(checking = false, message = r.reason) }
            }
        }
    }

    /** 下载新版本 APK（下载完成后停留在待安装状态） */
    override fun downloadUpdate() {
        val info = _update.value.info ?: return
        if (_update.value.downloading) return
        // 线路在点「下载」这一刻定下来，避免下载中途被设置页的改动影响
        val route = prefs.updateDownloadRoute
        scope.launch {
            _update.update {
                it.copy(
                    downloading = true,
                    progress = 0,
                    downloadedBytes = 0L,
                    totalBytes = info.apkSizeBytes,
                    message = null,
                    readyApkPath = null,
                    needInstallPermission = false,
                    route = route,
                )
            }
            runCatching {
                UpdateChecker.download(
                    app,
                    info,
                    route = route,
                    onProgress = { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                        _update.update { it.copy(progress = pct, downloadedBytes = done, totalBytes = total) }
                    },
                    onLineFallback = { attempt, count ->
                        // 只有「自动」模式才有多条线路可切
                        _update.update {
                            it.copy(progress = 0, downloadedBytes = 0L, message = "直连太慢，切换镜像线路 $attempt/$count…")
                        }
                    },
                )
            }.onSuccess { file ->
                _update.update {
                    it.copy(
                        downloading = false,
                        progress = 100,
                        readyApkPath = file.absolutePath,
                        dialogVisible = true,
                        message = null, // 清掉「切换镜像」之类的下载过程提示
                        needInstallPermission = !UpdateChecker.canInstall(app),
                    )
                }
            }.onFailure { t ->
                _update.update {
                    it.copy(
                        downloading = false,
                        progress = 0,
                        message = UpdateChecker.describeDownloadFailure(route, t),
                    )
                }
            }
        }
    }

    /** 下载完成后拉起系统安装器 */
    override fun installUpdate() {
        val path = _update.value.readyApkPath ?: return
        if (!UpdateChecker.canInstall(app)) {
            _update.update { it.copy(needInstallPermission = true) }
            return
        }
        UpdateChecker.install(app, File(path))
            .onSuccess { _update.update { it.copy(dialogVisible = false) } }
            .onFailure { t ->
                _update.update {
                    it.copy(message = "无法启动安装器：${t.message ?: "请到 Releases 页面手动下载"}")
                }
            }
    }

    /** 跳转系统「安装未知应用」授权页 */
    override fun grantInstallPermission() {
        val intent = UpdateChecker.unknownSourcesIntent(app)
        if (intent == null) {
            _update.update { it.copy(needInstallPermission = false, message = "当前系统无需额外授权，请直接点击安装") }
            return
        }
        runCatching { app.startActivity(intent) }.onFailure {
            _update.update { it.copy(message = "无法打开授权页，请到「设置 → 应用 → 安装未知应用」中手动允许") }
        }
    }

    /** 关掉更新弹窗（保留已发现的新版本信息，方便稍后再升） */
    override fun dismissUpdateDialog() = _update.update { it.copy(dialogVisible = false) }

    /** 关闭更新提示文案 */
    override fun consumeUpdateMessage() = _update.update { it.copy(message = null) }

    /** 已发现新版本但用户暂时不升级：连同信息一起清掉 */
    override fun ignoreUpdate() = _update.update {
        it.copy(
            info = null,
            dialogVisible = false,
            readyApkPath = null,
            needInstallPermission = false,
            progress = 0,
        )
    }

    override fun setAutoCheckUpdate(enabled: Boolean) {
        prefs.autoCheckUpdate = enabled
        // 重新打开时允许立即检查一次
        if (enabled) prefs.lastUpdateCheckAt = 0L
    }

    override fun autoCheckUpdateEnabled(): Boolean = prefs.autoCheckUpdate

    /** 下载线路（设置页读到当前值） */
    override fun downloadRoute(): DownloadRoute = prefs.updateDownloadRoute

    /** 切换下载线路（只影响 APK 下载；检查更新永远走 GitHub 官方地址） */
    override fun setDownloadRoute(route: DownloadRoute) {
        prefs.updateDownloadRoute = route
        _update.update { it.copy(route = route) }
    }

    /** 浏览器打开 Releases 页面（下载失败或想手动下载时的兜底） */
    override fun openReleasePage() {
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.releasePageUrl()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private companion object {
        /** 自动检查更新的最小间隔：24 小时 */
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

/** 更新域的动作面（供 ViewModel 用 `by` 整块委托） */
interface UpdateActions {
    fun checkUpdate()
    fun downloadUpdate()
    fun installUpdate()
    fun grantInstallPermission()
    fun dismissUpdateDialog()
    fun consumeUpdateMessage()
    fun ignoreUpdate()
    fun setAutoCheckUpdate(enabled: Boolean)
    fun autoCheckUpdateEnabled(): Boolean
    fun downloadRoute(): DownloadRoute
    fun setDownloadRoute(route: DownloadRoute)
    fun openReleasePage()
}
