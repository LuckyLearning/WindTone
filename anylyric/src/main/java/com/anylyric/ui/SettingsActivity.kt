package com.anylyric.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
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
import com.anylyric.service.MediaWatchService
import com.anylyric.session.SessionHub

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

        root.addView(
            text(getString(R.string.settings_version, versionName()), 12f, secondary = true).apply {
                setPadding(0, dp(10), 0, dp(4))
            }
        )
        root.addView(text(getString(R.string.settings_about), 12f, secondary = true))

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

    private fun openNotificationSettings() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this@SettingsActivity, MediaWatchService::class.java)
            )
        }
        if (tryStart(detail)) return
        if (tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        if (tryStart(Intent(Settings.ACTION_SETTINGS))) return
        toast(getString(R.string.perm_required_hint))
    }

    private fun openOverlaySettings() {
        val direct = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        if (tryStart(direct)) return
        if (tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) return
        if (tryStart(Intent(Settings.ACTION_SETTINGS))) return
        toast(getString(R.string.perm_required_hint))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) {
        "-"
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
