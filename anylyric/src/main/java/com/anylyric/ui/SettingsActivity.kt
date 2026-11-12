package com.anylyric.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.anylyric.R
import com.anylyric.data.ListenerState
import com.anylyric.data.Prefs
import com.anylyric.diag.Probe
import com.anylyric.overlay.Align
import com.anylyric.overlay.OverlayDefaults
import com.anylyric.overlay.OverlayGeometry
import com.anylyric.overlay.OverlayStyle
import com.anylyric.overlay.Preset
import com.anylyric.overlay.Secondary
import com.anylyric.session.SessionHub
import com.anylyric.update.ApkInstaller
import com.anylyric.update.ApkProvider
import com.anylyric.update.CheckResult
import com.anylyric.update.FailureKind
import com.anylyric.update.Release
import com.anylyric.update.UpdateChecker
import java.io.File

/**
 * 唯一主界面：权限引导、当前状态、外观、位置、探测入口。
 *
 * 权限全部在这一屏内完成授权与检测 —— 不提示连接电脑、不要求 adb。
 * 每个可调项右侧或末尾都有「重置」，写回值只取 [OverlayDefaults]。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var hub: SessionHub
    private lateinit var prefs: Prefs

    private lateinit var statusBanner: TextView
    private lateinit var statusLine: TextView
    private lateinit var notificationState: TextView
    private lateinit var notificationButton: TextView
    private lateinit var notificationSettingsButton: TextView
    private lateinit var overlayState: TextView
    private lateinit var overlayButton: TextView
    private lateinit var editButton: TextView
    private lateinit var debugSwitch: Switch

    // ---------------------------------------------------------------- 更新
    private lateinit var updateState: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateAction: TextView

    /** 更新流程当前处在哪一步。界面与按钮文案都由它推导，避免多处各存一份状态。 */
    private enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED, DOWNLOAD_FAILED }

    private var updatePhase = UpdatePhase.IDLE
    private var updateMessage = ""

    /** 下载线程写、主线程读 */
    @Volatile
    private var updateProgress = -1
    private var updateRelease: Release? = null
    private var updateApk: File? = null

    /**
     * 各分区的「刷新自己的显示」回调。
     *
     * 设置页是纯代码布局，没有数据绑定；用一个刷新列表比给每个控件都存字段干净，
     * 也不会因为增删一项就漏掉同步。
     */
    private val uiRefreshers = mutableListOf<() -> Unit>()

    /** 首次观察到「已授权未绑定」的时刻，用来判断自动恢复是否已经失败。 */
    private var firstUnboundAt = 0L

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollTicks = 0

    private val poller = object : Runnable {
        override fun run() {
            refresh()
            if (hub.listenerState() == ListenerState.GRANTED) return
            if (pollTicks >= MAX_POLL_TICKS) return
            pollTicks++
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = SessionHub.get(this)
        prefs = hub.prefs()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (hub.listenerState() != ListenerState.GRANTED && hub.tryRebind()) {
            startPolling(POLL_FIRST_DELAY_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(poller)
    }

    private fun startPolling(firstDelayMs: Long) {
        pollTicks = 0
        pollHandler.removeCallbacks(poller)
        pollHandler.postDelayed(poller, firstDelayMs)
    }

    private fun buildContent(): View {
        uiRefreshers.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
        }

        root.addView(text(getString(R.string.app_name), 28f, bold = true))
        root.addView(
            text(getString(R.string.settings_subtitle), 13f, secondary = true).apply {
                setPadding(0, dp(6), 0, dp(4))
            }
        )

        root.addView(sectionLabel(getString(R.string.section_status)))
        statusBanner = text("", 15f, bold = true)
        statusLine = text("", 13f, secondary = true).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(card(listOf(statusBanner, statusLine)))

        root.addView(sectionLabel(getString(R.string.section_permissions)))
        root.addView(buildPermissionCard())

        root.addView(sectionLabel(getString(R.string.section_appearance)))
        root.addView(buildAppearanceCard())

        root.addView(sectionLabel(getString(R.string.section_position)))
        root.addView(buildPositionCard())

        root.addView(sectionLabel(getString(R.string.section_probe)))
        debugSwitch = Switch(this).apply {
            text = getString(R.string.diag_debug_toggle)
            textSize = 15f
            isChecked = prefs.debugEnabled
            setOnCheckedChangeListener { _, checked ->
                prefs.debugEnabled = checked
                Probe.setEnabled(checked)
            }
        }
        root.addView(
            card(
                listOf(
                    debugSwitch,
                    text(getString(R.string.diag_debug_hint), 12f, secondary = true).apply {
                        setPadding(0, dp(4), 0, dp(4))
                    },
                    textButton(getString(R.string.diag_open)) {
                        startActivity(Intent(this@SettingsActivity, DiagActivity::class.java))
                    }
                )
            )
        )

        root.addView(sectionLabel(getString(R.string.section_about)))
        root.addView(buildUpdateCard())

        root.addView(
            text(getString(R.string.settings_about), 12f, secondary = true).apply {
                setPadding(dp(4), dp(6), 0, dp(4))
            }
        )

        return ScrollView(this).apply { addView(root) }
    }

    // ---------------------------------------------------------------- 权限

    private fun buildPermissionCard(): View {
        notificationState = text("", 13f, bold = true)
        notificationButton = textButton(getString(R.string.perm_open)) { onNotificationAction() }
        // 「系统设置」入口必须常驻：自动重绑在部分 ROM 上无效，手工关开是最后的自救路径，
        // 不能因为换了个状态就把这个入口藏掉。
        notificationSettingsButton = textButton(getString(R.string.perm_review)) {
            openNotificationSettings()
        }
        val notificationActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(notificationButton)
            addView(notificationSettingsButton)
        }

        overlayState = text("", 13f, bold = true)
        overlayButton = textButton(getString(R.string.perm_open)) { openOverlaySettings() }

        return card(
            listOf(
                text(getString(R.string.perm_notification_title), 16f, bold = true),
                text(getString(R.string.perm_notification_desc), 13f, secondary = true).apply {
                    setPadding(0, dp(4), 0, dp(8))
                },
                row(notificationState, notificationActions),
                spacer(16),
                text(getString(R.string.perm_overlay_title), 16f, bold = true),
                text(getString(R.string.perm_overlay_desc), 13f, secondary = true).apply {
                    setPadding(0, dp(4), 0, dp(8))
                },
                row(overlayState, overlayButton)
            )
        )
    }

    // ---------------------------------------------------------------- 外观

    private fun buildAppearanceCard(): View {
        val rows = ArrayList<View>(20)

        rows += labeled(
            getString(R.string.appearance_preset),
            chipRow(
                options = listOf(
                    getString(R.string.preset_stripe),
                    getString(R.string.preset_glass),
                    getString(R.string.preset_solid),
                    getString(R.string.preset_navi)
                ),
                current = { presetIndex() },
                onPick = { choosePreset(PRESET_ORDER[it]) }
            )
        )

        rows += spacer(14)
        rows += stepperRow(
            label = getString(R.string.appearance_font),
            value = { getString(R.string.display_font_value, prefs.style().fontSp.toInt().toString()) },
            minus = { editStyle { it.copy(fontSp = it.fontSp - 2f) } },
            plus = { editStyle { it.copy(fontSp = it.fontSp + 2f) } },
            reset = { editStyle { it.copy(fontSp = OverlayDefaults.FONT_SP) } }
        )

        rows += spacer(10)
        rows += labeled(
            getString(R.string.appearance_lines),
            chipRow(
                options = listOf("1", "2", "3"),
                current = { prefs.style().maxLines - 1 },
                onPick = { index -> editStyle { it.copy(maxLines = index + 1) } }
            )
        )

        rows += spacer(12)
        rows += labeled(
            getString(R.string.appearance_align),
            chipRow(
                options = listOf(
                    getString(R.string.align_start),
                    getString(R.string.align_center),
                    getString(R.string.align_end)
                ),
                current = { prefs.style().align.ordinal },
                onPick = { index -> editStyle { it.copy(align = ALIGN_ORDER[index]) } }
            )
        )

        rows += spacer(12)
        rows += stepperRow(
            label = getString(R.string.appearance_opacity),
            value = { getString(R.string.appearance_percent, (prefs.style().opacity * 100).toInt().toString()) },
            minus = { editStyle { it.copy(opacity = it.opacity - 0.05f) } },
            plus = { editStyle { it.copy(opacity = it.opacity + 0.05f) } },
            reset = { editStyle { it.copy(opacity = OverlayDefaults.OPACITY) } }
        )

        rows += spacer(6)
        rows += stepperRow(
            label = getString(R.string.appearance_corner),
            value = { getString(R.string.appearance_dp, prefs.style().cornerDp.toInt().toString()) },
            minus = { editStyle { it.copy(cornerDp = it.cornerDp - 2f) } },
            plus = { editStyle { it.copy(cornerDp = it.cornerDp + 2f) } },
            reset = { editStyle { it.copy(cornerDp = OverlayDefaults.CORNER_DP) } }
        )

        rows += spacer(12)
        rows += labeled(
            getString(R.string.appearance_secondary),
            chipRow(
                options = listOf(
                    getString(R.string.secondary_next),
                    getString(R.string.secondary_previous),
                    getString(R.string.secondary_track),
                    getString(R.string.secondary_none)
                ),
                current = { prefs.style().secondary.ordinal },
                onPick = { index -> editStyle { it.copy(secondary = SECONDARY_ORDER[index]) } }
            )
        )

        rows += spacer(10)
        rows += switchRow(
            label = getString(R.string.appearance_stroke),
            current = { prefs.style().stroke },
            onChange = { checked -> editStyle { it.copy(stroke = checked) } }
        )
        rows += switchRow(
            label = getString(R.string.appearance_bold),
            current = { prefs.style().bold },
            onChange = { checked -> editStyle { it.copy(bold = checked) } }
        )
        rows += switchRow(
            label = getString(R.string.appearance_fade),
            current = { prefs.style().fadeMs > 0 },
            onChange = { checked ->
                editStyle { it.copy(fadeMs = if (checked) OverlayDefaults.FADE_MS else 0) }
            }
        )
        rows += switchRow(
            label = getString(R.string.appearance_state_mark),
            current = { prefs.style().stateMark },
            onChange = { checked -> editStyle { it.copy(stateMark = checked) } }
        )
        rows += switchRow(
            label = getString(R.string.appearance_hide_empty),
            current = { prefs.hideWhenNoLyrics },
            onChange = { checked ->
                prefs.hideWhenNoLyrics = checked
                hub.applyStyle()
            }
        )

        rows += spacer(6)
        rows += textButton(getString(R.string.appearance_reset)) {
            prefs.resetStyle()
            hub.applyStyle()
            refresh()
        }

        return card(rows)
    }

    // ---------------------------------------------------------------- 位置

    private fun buildPositionCard(): View {
        editButton = textButton(getString(R.string.display_edit)) { toggleEdit() }

        val rows = ArrayList<View>(8)
        rows += labeled(getString(R.string.position_grid), nineGrid())
        rows += spacer(10)
        rows += row(
            text(getString(R.string.position_edit_hint), 12f, secondary = true),
            editButton
        )
        rows += spacer(6)

        val chooser = displayChooser()
        if (chooser != null) {
            rows += chooser
            rows += spacer(10)
        }

        rows += textButton(getString(R.string.position_reset)) {
            hub.overlay().resetPosition()
            refresh()
        }
        rows += text(getString(R.string.position_hint), 12f, secondary = true).apply {
            setPadding(0, dp(6), 0, 0)
        }

        return card(rows)
    }

    private fun nineGrid(): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cells = ArrayList<TextView>(9)

        for (rowIndex in 0..2) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (colIndex in 0..2) {
                val cell = TextView(this).apply {
                    text = "·"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        hub.overlay().setAnchor(colIndex, rowIndex)
                        refresh()
                    }
                }
                line.addView(
                    cell,
                    LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                        marginEnd = dp(6)
                        bottomMargin = dp(6)
                    }
                )
                cells += cell
            }
            wrapper.addView(line)
        }

        uiRefreshers += {
            val landscape = isLandscape()
            val col = OverlayGeometry.nearestAnchor(prefs.ratioX(landscape))
            val rowIndex = OverlayGeometry.nearestAnchor(prefs.ratioY(landscape))
            cells.forEachIndexed { index, cell ->
                styleChip(cell, (index % 3) == col && (index / 3) == rowIndex)
            }
        }
        return wrapper
    }

    /** 只有真有多块屏幕时才出现，避免手机上多一行没用的设置。 */
    private fun displayChooser(): View? {
        val manager = getSystemService(DisplayManager::class.java) ?: return null
        val displays = try {
            manager.displays
        } catch (_: Throwable) {
            null
        } ?: return null
        if (displays.size <= 1) return null

        return labeled(
            getString(R.string.position_display),
            chipRow(
                options = displays.map { it.name ?: it.displayId.toString() },
                current = { displays.indexOfFirst { it.displayId == prefs.displayId } },
                onPick = { index ->
                    prefs.displayId = displays[index].displayId
                    hub.applyStyle()
                }
            )
        )
    }

    // ---------------------------------------------------------------- 通用控件

    private fun labeled(label: String, control: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(label, 12f, secondary = true).apply { setPadding(0, 0, 0, dp(6)) })
            addView(control)
        }

    private fun chipRow(
        options: List<String>,
        current: () -> Int,
        onPick: (Int) -> Unit
    ): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = ArrayList<TextView>(options.size)

        options.forEachIndexed { index, label ->
            val cell = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                val padH = dp(12)
                val padV = dp(7)
                setPadding(padH, padV, padH, padV)
                isClickable = true
                setOnClickListener {
                    onPick(index)
                    refresh()
                }
            }
            holder.addView(
                cell,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
            cells += cell
        }

        uiRefreshers += {
            val selected = current()
            cells.forEachIndexed { index, cell -> styleChip(cell, index == selected) }
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(holder)
        }
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(if (selected) colorAccent else chipIdle)
        }
        view.setTextColor(
            when {
                selected -> Color.WHITE
                isNight -> 0xFFE2E2E2.toInt()
                else -> 0xFF2C2C2A.toInt()
            }
        )
    }

    private val chipIdle: Int
        get() = if (isNight) 0xFF2C2C2C.toInt() else 0xFFE9E9E9.toInt()

    private fun switchRow(
        label: String,
        current: () -> Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val control = Switch(this).apply {
            isChecked = current()
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                refresh()
            }
        }
        uiRefreshers += { if (control.isChecked != current()) control.isChecked = current() }
        return row(text(label, 14f), control)
    }

    private fun stepperRow(
        label: String,
        value: () -> String,
        minus: () -> Unit,
        plus: () -> Unit,
        reset: () -> Unit
    ): View {
        val valueView = text("", 14f, bold = true).apply { setPadding(dp(8), 0, dp(8), 0) }
        uiRefreshers += { valueView.text = value() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                text(label, 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(textButton("−") { minus(); refresh() })
            addView(valueView)
            addView(textButton("+") { plus(); refresh() })
            addView(textButton(getString(R.string.appearance_reset_short)) { reset(); refresh() })
        }
    }

    // ---------------------------------------------------------------- 关于与更新
    //
    // 更新源是公共分发仓库里的**静态文件**（`LuckyLearning/Release` 下的 `AnyLyric/latest.json`），
    // 不走 GitHub API：不需要鉴权，raw 是 CDN，也不占每小时 60 次的 API 配额。
    //
    // 检查与下载都在工作线程跑，结果回主线程。失败一律翻译成「能照着做」的一句话，
    // 同时写进探测报告 —— 车机上没法看 logcat，屏幕里能自证才有意义。
    //
    // 只有「检查更新 / 下载」会联网。歌词链路仍然不联网、不读文件，这是一期就定死的边界。

    private fun buildUpdateCard(): View {
        updateState = text("", 13f, secondary = true).apply { setPadding(0, dp(6), 0, dp(2)) }
        updateNotes = text("", 12f, secondary = true).apply {
            setPadding(0, dp(8), 0, dp(2))
            visibility = View.GONE
        }
        updateAction = textButton(getString(R.string.update_check)) { onUpdateAction() }

        val rows = ArrayList<View>(6)
        rows += text(
            getString(R.string.update_version_line, versionName(), versionCode()),
            14f,
            bold = true
        )
        rows += updateState
        rows += updateNotes
        rows += spacer(2)
        rows += row(
            updateAction,
            textButton(getString(R.string.update_open_page)) { openReleasePage() }
        )

        renderUpdate()
        return card(rows)
    }

    private fun onUpdateAction() {
        when (updatePhase) {
            UpdatePhase.AVAILABLE -> startDownload()
            UpdatePhase.READY -> installDownloaded()
            UpdatePhase.CHECKING -> toast(getString(R.string.update_checking))
            UpdatePhase.DOWNLOADING -> toast(getString(R.string.update_downloading_unknown))
            else -> startCheck()
        }
    }

    private fun startCheck() {
        updatePhase = UpdatePhase.CHECKING
        updateMessage = ""
        updateProgress = -1
        updateRelease = null
        updateApk = null
        renderUpdate()

        val currentName = versionName()
        val currentCode = versionCode()
        Thread({
            val result = UpdateChecker.check(currentName, currentCode)
            pollHandler.post {
                if (isFinishing || isDestroyed) return@post
                applyCheckResult(result)
            }
        }, "anylyric-update-check").start()
    }

    private fun applyCheckResult(result: CheckResult) {
        when (result) {
            is CheckResult.Available -> {
                updateRelease = result.release
                updatePhase = UpdatePhase.AVAILABLE
                updateMessage = ""
                Probe.write(
                    "更新",
                    "发现新版本 ${result.release.version}（code ${result.release.versionCode}，" +
                        "${result.release.apkName}）"
                )
            }

            is CheckResult.UpToDate -> {
                updatePhase = UpdatePhase.UP_TO_DATE
                updateMessage = result.latestVersion
                Probe.write("更新", "已是最新（${result.latestVersion}）")
            }

            is CheckResult.Failed -> {
                updatePhase = UpdatePhase.FAILED
                updateMessage = failureText(result.kind, result.httpCode)
                Probe.write("更新", "检查失败 ${result.kind} ${result.httpCode} ${result.detail}")
            }
        }
        renderUpdate()
    }

    private fun startDownload() {
        val release = updateRelease ?: return
        updatePhase = UpdatePhase.DOWNLOADING
        updateProgress = 0
        renderUpdate()

        val dir = ApkProvider.dir(this)
        Thread({
            try {
                val apk = UpdateChecker.download(release, dir) { done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                    // 只在百分比变化时回主线程，别为了几个字节刷爆消息队列
                    if (percent != updateProgress) {
                        updateProgress = percent
                        pollHandler.post { if (!isFinishing && !isDestroyed) renderUpdate() }
                    }
                }
                pollHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    updateApk = apk
                    updatePhase = UpdatePhase.READY
                    Probe.write("更新", "下载完成 ${apk.name}（${apk.length()} 字节，大小与摘要已校验）")
                    renderUpdate()
                    installDownloaded()
                }
            } catch (t: Throwable) {
                val kind = (t as? com.anylyric.update.UpdateException)?.kind ?: FailureKind.NETWORK
                pollHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    updatePhase = UpdatePhase.DOWNLOAD_FAILED
                    updateMessage = failureText(kind, 0)
                    Probe.write("更新", "下载失败 $kind ${t.message}")
                    renderUpdate()
                }
            }
        }, "anylyric-update-download").start()
    }

    /**
     * 拉起系统安装器。
     *
     * 「安装未知应用」是 Android 8 起必须由用户手动开一次的独立开关，
     * 没开时系统会静默拒掉安装 —— 所以先查、先把用户送到授权页，不要等他点完再失败。
     */
    private fun installDownloaded() {
        val apk = updateApk ?: return
        if (!ApkInstaller.canInstall(this)) {
            toast(getString(R.string.update_allow_install))
            try {
                startActivity(ApkInstaller.unknownSourcesIntent(this))
            } catch (t: Throwable) {
                Probe.write("更新", "打不开「安装未知应用」页 ${t.javaClass.simpleName}")
            }
            return
        }
        ApkInstaller.install(this, apk).onFailure { t ->
            Probe.write("更新", "拉起安装器失败 ${t.javaClass.simpleName} ${t.message}")
            toast(getString(R.string.update_install_failed, t.javaClass.simpleName))
        }
    }

    private fun openReleasePage() {
        val url = UpdateChecker.releasePageUrl
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            // 车机常没有浏览器：那就把地址复制到剪贴板，用户至少能带走
            Probe.write("更新", "打不开发布页 ${t.javaClass.simpleName}")
            copyToClipboard(url)
        }
    }

    private fun renderUpdate() {
        if (!::updateState.isInitialized) return

        val notes = updateRelease?.notes.orEmpty()
        updateNotes.visibility = if (notes.isNotBlank()) View.VISIBLE else View.GONE
        updateNotes.text = notes

        updateState.text = when (updatePhase) {
            UpdatePhase.IDLE -> getString(R.string.update_check)
            UpdatePhase.CHECKING -> getString(R.string.update_checking)
            UpdatePhase.UP_TO_DATE -> getString(R.string.update_up_to_date, updateMessage)
            UpdatePhase.AVAILABLE ->
                getString(R.string.update_available, updateRelease?.version.orEmpty())

            UpdatePhase.DOWNLOADING ->
                if (updateProgress >= 0) getString(R.string.update_downloading, updateProgress)
                else getString(R.string.update_downloading_unknown)

            UpdatePhase.READY ->
                getString(
                    R.string.update_install_ready,
                    UpdateChecker.formatSize(updateApk?.length() ?: 0L)
                )

            UpdatePhase.FAILED -> getString(R.string.update_check_failed, updateMessage)
            UpdatePhase.DOWNLOAD_FAILED -> getString(R.string.update_download_failed, updateMessage)
        }
        updateState.setTextColor(
            when (updatePhase) {
                UpdatePhase.UP_TO_DATE, UpdatePhase.READY -> colorOk
                UpdatePhase.AVAILABLE -> colorAccent
                UpdatePhase.FAILED, UpdatePhase.DOWNLOAD_FAILED -> colorBad
                else -> colorSecondaryText
            }
        )

        val busy = updatePhase == UpdatePhase.CHECKING || updatePhase == UpdatePhase.DOWNLOADING
        updateAction.text = getString(
            when (updatePhase) {
                UpdatePhase.AVAILABLE -> R.string.update_download
                UpdatePhase.READY -> R.string.update_install
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.DOWNLOADING -> R.string.update_downloading_unknown
                else -> R.string.update_check
            }
        )
        updateAction.isEnabled = !busy
        updateAction.alpha = if (busy) 0.45f else 1f
    }

    /** 失败类别 → 用户能看懂的一句话。 */
    private fun failureText(kind: FailureKind, httpCode: Int): String = when (kind) {
        FailureKind.DNS -> getString(R.string.update_reason_dns)
        FailureKind.TIMEOUT -> getString(R.string.update_reason_timeout)
        FailureKind.HTTP ->
            if (httpCode > 0) getString(R.string.update_reason_http, httpCode)
            else getString(R.string.update_reason_network)

        FailureKind.NOT_FOUND -> getString(R.string.update_reason_not_found)
        FailureKind.BAD_MANIFEST -> getString(R.string.update_reason_bad_manifest)
        FailureKind.NO_APK -> getString(R.string.update_reason_no_apk)
        FailureKind.VERIFY -> getString(R.string.update_reason_verify)
        FailureKind.NETWORK -> getString(R.string.update_reason_network)
    }

    // ---------------------------------------------------------------- 状态刷新

    private fun refresh() {
        val status = hub.currentStatus()
        val listener = status.listenerState

        // 「已授权未绑定」超过时限仍未恢复 → 自动重绑在这台机器上不奏效，
        // 继续显示「正在等待」只会让人干等，必须改指手工关开。
        if (listener == ListenerState.GRANTED_NOT_BOUND) {
            if (firstUnboundAt == 0L) firstUnboundAt = SystemClock.elapsedRealtime()
        } else {
            firstUnboundAt = 0L
        }
        val timedOut = listener == ListenerState.GRANTED_NOT_BOUND &&
            firstUnboundAt != 0L &&
            SystemClock.elapsedRealtime() - firstUnboundAt >= UNBOUND_TIMEOUT_MS

        statusBanner.text = getString(
            when (listener) {
                ListenerState.GRANTED -> R.string.status_listener_connected
                ListenerState.GRANTED_NOT_BOUND ->
                    if (timedOut) R.string.status_listener_unbound_failed
                    else R.string.status_listener_unbound

                ListenerState.NOT_GRANTED -> R.string.status_listener_disconnected
            }
        )
        statusBanner.setTextColor(
            when (listener) {
                ListenerState.GRANTED -> colorOk
                ListenerState.GRANTED_NOT_BOUND -> if (timedOut) colorBad else colorWarn
                ListenerState.NOT_GRANTED -> colorBad
            }
        )

        statusLine.text = when (listener) {
            ListenerState.NOT_GRANTED -> getString(R.string.perm_required_hint)
            ListenerState.GRANTED_NOT_BOUND -> getString(
                if (timedOut) R.string.perm_unbound_failed_hint else R.string.perm_unbound_hint
            )

            ListenerState.GRANTED -> if (status.sessionCount == 0) {
                getString(R.string.status_no_session)
            } else {
                getString(
                    R.string.status_session,
                    status.title ?: "-",
                    status.artist ?: "-"
                ) + "\n" + getString(R.string.status_channel, channelLabel(status.hitSource))
            }
        }

        applyListenerState(listener)
        applyPermissionState(overlayState, status.overlayGranted)
        overlayButton.text = getString(
            if (status.overlayGranted) R.string.perm_review else R.string.perm_open
        )

        uiRefreshers.forEach { it() }
        renderUpdate()
        editButton.text = getString(
            if (hub.overlay().isEditable()) R.string.display_edit_exit
            else R.string.display_edit
        )
        if (debugSwitch.isChecked != prefs.debugEnabled) {
            debugSwitch.isChecked = prefs.debugEnabled
        }
    }

    // ---------------------------------------------------------------- 样式操作

    private fun presetIndex(): Int {
        val preset = prefs.style().preset
        return PRESET_ORDER.indexOf(preset).takeIf { it >= 0 } ?: -1
    }

    private fun choosePreset(preset: Preset) {
        prefs.saveStyle(OverlayDefaults.preset(preset, prefs.style()))
        hub.applyStyle()
        refresh()
    }

    /**
     * 改任意一项样式。
     *
     * 改完就把预设标记为 CUSTOM：否则界面还高亮着「玻璃胶囊」，实际参数早已不是它，
     * 用户下次点同一颗按钮会以为没生效。
     */
    private fun editStyle(block: (OverlayStyle) -> OverlayStyle) {
        val next = block(prefs.style())
        prefs.saveStyle(next.copy(preset = Preset.CUSTOM))
        hub.applyStyle()
        refresh()
    }

    // ---------------------------------------------------------------- 权限交互

    private fun applyListenerState(state: ListenerState) {
        notificationState.text = getString(
            when (state) {
                ListenerState.GRANTED -> R.string.perm_granted
                ListenerState.GRANTED_NOT_BOUND -> R.string.perm_state_unbound
                ListenerState.NOT_GRANTED -> R.string.perm_missing
            }
        )
        notificationState.setTextColor(
            when (state) {
                ListenerState.GRANTED -> colorOk
                ListenerState.GRANTED_NOT_BOUND -> colorWarn
                ListenerState.NOT_GRANTED -> colorBad
            }
        )
        notificationButton.text = getString(
            when (state) {
                ListenerState.GRANTED -> R.string.perm_review
                ListenerState.GRANTED_NOT_BOUND -> R.string.perm_retry
                ListenerState.NOT_GRANTED -> R.string.perm_open
            }
        )
        notificationButton.visibility =
            if (state == ListenerState.GRANTED) View.GONE else View.VISIBLE
    }

    private fun onNotificationAction() {
        when (hub.listenerState()) {
            ListenerState.NOT_GRANTED -> openNotificationSettings()

            ListenerState.GRANTED_NOT_BOUND -> {
                if (hub.tryRebind(force = true)) {
                    toast(getString(R.string.perm_rebind_requested))
                    startPolling(POLL_FIRST_DELAY_MS)
                } else {
                    openNotificationSettings()
                }
            }

            ListenerState.GRANTED -> openNotificationSettings()
        }
        refresh()
    }

    private fun applyPermissionState(view: TextView, granted: Boolean) {
        view.text = getString(if (granted) R.string.perm_granted else R.string.perm_missing)
        view.setTextColor(if (granted) colorOk else colorBad)
    }

    private fun channelLabel(source: String?): String = when (source) {
        "L0" -> getString(R.string.channel_l0)
        "L1" -> getString(R.string.channel_l1)
        "L2" -> getString(R.string.channel_l2)
        else -> getString(R.string.channel_none)
    }

    private fun toggleEdit() {
        if (!hub.overlay().isEditable() && !hub.overlay().isAttached()) {
            toast(getString(R.string.edit_needs_session))
            return
        }
        val next = !hub.overlay().isEditable()
        hub.overlay().setEditable(next)
        toast(getString(if (next) R.string.edit_entered else R.string.edit_exited))
        refresh()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---------------------------------------------------------------- 打开系统设置
    //
    // 2026-11-12 真机反馈：「去开启」和「系统设置」两个按钮点了都没反应。
    // 根因链有两层，第二层才是真凶：
    //   ① 老写法 tryStart 把异常全吞成 false，失败原因当场就丢了；
    //   ② 5f2d207 为了「少让用户翻列表」，把
    //      Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS 排到了第一位。
    //      这台荣耀设备上该 action **有注册的 Activity，但页面打不开**，而且
    //      startActivity 既不抛异常也不返回失败 —— 代码认为成功就 return，
    //      永远走不到后面的列表页。首版用的是列表页，所以一直是好的。
    //      「什么都没打开」且不弹失败说明，正是这个静默失败的样子。
    //   ⇒ **不要再让 DETAIL 页排第一**：它失败得太安静，既检测不到也没法兜底。
    //
    // 现在：
    //  · 列表页（首版验证可用）永远排第一，后面才是应用详情页与设置首页；
    //  · 点击先给一句 toast —— 让用户能区分「点击没生效」和「跳转被拒」；
    //  · 全部抛异常才弹对话框，逐条列出真实原因，可一键复制；
    //  · 对话框里直接写出该设备的**手工路径**。
    // 仍然不依赖 adb：失败原因就显示在这块屏幕上。

    private fun openNotificationSettings() {
        openSystemSettings(
            candidates = listOf(
                "通知使用权列表" to Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                "应用详情页" to appDetailsIntent(),
                "设置首页" to Intent(Settings.ACTION_SETTINGS)
            ),
            manualHint = getString(R.string.perm_notification_manual)
        )
    }

    private fun openOverlaySettings() {
        openSystemSettings(
            candidates = listOf(
                "悬浮窗权限页" to Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                "特殊应用权限列表" to Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                "应用详情页" to appDetailsIntent(),
                "设置首页" to Intent(Settings.ACTION_SETTINGS)
            ),
            manualHint = getString(R.string.perm_overlay_manual)
        )
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /**
     * 逐个尝试候选设置页；全部抛异常时把失败明细摆到用户面前。
     *
     * 两条经验写在这里，避免以后又"优化"回去：
     *  · **不用** resolveActivity 做前置门禁：包可见性、ROM 裁剪、动态组件都会让它
     *    与真实结果不一致，唯一可判的是 startActivity 有没有抛异常。
     *  · **startActivity 不抛异常也不代表页面真能打开**（DETAIL 页就是这么坑的），
     *    所以候选顺序只按真机实测结果排，不按"理论上体验更好"排。
     */
    private fun openSystemSettings(
        candidates: List<Pair<String, Intent>>,
        manualHint: String
    ) {
        toast(getString(R.string.perm_opening_settings))
        val failures = StringBuilder()
        for ((label, intent) in candidates) {
            try {
                startActivity(intent)
                Probe.write("设置跳转", "$label 已启动（${intent.action}）")
                return
            } catch (t: Throwable) {
                val reason = t.javaClass.simpleName + (t.message?.let { "：$it" } ?: "")
                failures.append("· ").append(label).append(" → ").append(reason).append('\n')
                Probe.write("设置跳转", "$label 失败 $reason")
            }
        }

        val detail = failures.toString().trim().ifBlank { getString(R.string.perm_open_failed_none) }
        val body = getString(R.string.perm_open_failed_body, manualHint, detail)
        Probe.write("设置跳转", "全部候选失败，改为屏幕内说明")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.perm_open_failed_title))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(getString(R.string.perm_copy_detail)) { _, _ ->
                copyToClipboard(body)
            }
            .show()
    }

    private fun copyToClipboard(value: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.perm_copy_label), value)
            )
            toast(getString(R.string.perm_copied))
        } catch (t: Throwable) {
            Probe.write("设置跳转", "复制失败 ${t.javaClass.simpleName}")
            toast(getString(R.string.perm_copy_failed))
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) {
        "-"
    }

    /** 安装包的 versionCode。更新判断以它为准，避免只看版本名带来的「看不见较低版本」死角。 */
    private fun versionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    } catch (_: Throwable) {
        0
    }

    companion object {
        private val PRESET_ORDER = listOf(
            Preset.STRIPE, Preset.GLASS, Preset.SOLID, Preset.NAVI
        )
        private val ALIGN_ORDER = listOf(Align.START, Align.CENTER, Align.END)
        private val SECONDARY_ORDER = listOf(
            Secondary.NEXT, Secondary.PREVIOUS, Secondary.TRACK, Secondary.NONE
        )

        /** 首次刷新的延迟：给系统一点绑定时间，免得白跑一轮。 */
        private const val POLL_FIRST_DELAY_MS = 400L

        /** 空窗期轮询间隔。只读授权列表与内存标志，代价可忽略。 */
        private const val POLL_INTERVAL_MS = 1_200L

        /** 最多轮询次数（约 10 秒）。系统不重绑时不再空转，改为提示手工重开。 */
        private const val MAX_POLL_TICKS = 8

        private const val UNBOUND_TIMEOUT_MS = 9_000L
    }
}
