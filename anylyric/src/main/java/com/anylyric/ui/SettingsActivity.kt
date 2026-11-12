package com.anylyric.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.anylyric.R
import com.anylyric.data.ListenerState
import com.anylyric.data.Prefs
import com.anylyric.diag.Probe
import com.anylyric.session.SessionHub

/**
 * 唯一主界面：权限引导、当前状态、显示参数、探测入口。
 *
 * 权限全部在这一屏内完成授权与检测 —— 不提示连接电脑、不要求 adb。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var hub: SessionHub
    private lateinit var prefs: Prefs

    private lateinit var statusBanner: TextView
    private lateinit var statusLine: TextView
    private lateinit var notificationState: TextView
    private lateinit var notificationButton: TextView
    private lateinit var overlayState: TextView
    private lateinit var fontValue: TextView
    private lateinit var editButton: TextView
    private lateinit var debugSwitch: Switch

    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollTicks = 0

    /**
     * 空窗期轮询：已授权但系统还没绑上服务时，短时间内反复刷新状态。
     *
     * 只读授权列表与内存标志，不查会话、不开线程，代价可以忽略；一旦连上或
     * 用完次数就停，不会常驻。
     */
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
        // 已授权却没连上：先主动请系统重绑，再轮询几秒看结果。
        // 不做这一步，界面会停在「未授予」，把用户赶到系统设置里关掉再打开一次。
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

        statusBanner = text("", 15f, bold = true)
        statusLine = text("", 13f, secondary = true).apply {
            setPadding(0, dp(6), 0, 0)
        }

        root.addView(sectionLabel(getString(R.string.section_status)))
        root.addView(card(listOf(statusBanner, statusLine)))

        notificationState = text("", 13f, bold = true)
        notificationButton = textButton(getString(R.string.perm_open)) { onNotificationAction() }
        overlayState = text("", 13f, bold = true)
        root.addView(sectionLabel(getString(R.string.section_permissions)))
        root.addView(
            card(
                listOf(
                    text(getString(R.string.perm_notification_title), 16f, bold = true),
                    text(getString(R.string.perm_notification_desc), 13f, secondary = true).apply {
                        setPadding(0, dp(4), 0, dp(8))
                    },
                    row(notificationState, notificationButton),
                    spacer(16),
                    text(getString(R.string.perm_overlay_title), 16f, bold = true),
                    text(getString(R.string.perm_overlay_desc), 13f, secondary = true).apply {
                        setPadding(0, dp(4), 0, dp(8))
                    },
                    row(
                        overlayState,
                        textButton(getString(R.string.perm_open)) { openOverlaySettings() }
                    )
                )
            )
        )

        fontValue = text("", 14f, bold = true).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                text(getString(R.string.display_font_size), 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(textButton("−") { changeFont(-2f) })
            addView(fontValue)
            addView(textButton("+") { changeFont(2f) })
        }

        editButton = textButton(getString(R.string.display_edit)) { toggleEdit() }
        val editRow = row(
            text(getString(R.string.display_edit_hint), 12f, secondary = true),
            editButton
        )

        root.addView(sectionLabel(getString(R.string.section_display)))
        root.addView(
            card(
                listOf(
                    fontRow,
                    spacer(6),
                    editRow,
                    spacer(2),
                    textButton(getString(R.string.display_reset)) { resetDisplay() }
                )
            )
        )

        debugSwitch = Switch(this).apply {
            text = getString(R.string.diag_debug_toggle)
            textSize = 15f
            isChecked = prefs.debugEnabled
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnCheckedChangeListener { _, checked ->
                prefs.debugEnabled = checked
                Probe.setEnabled(checked)
            }
        }

        root.addView(sectionLabel(getString(R.string.section_probe)))
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

    private fun refresh() {
        val status = hub.currentStatus()
        val listener = status.listenerState

        statusBanner.text = getString(
            when (listener) {
                ListenerState.GRANTED -> R.string.status_listener_connected
                ListenerState.GRANTED_NOT_BOUND -> R.string.status_listener_unbound
                ListenerState.NOT_GRANTED -> R.string.status_listener_disconnected
            }
        )
        statusBanner.setTextColor(
            when (listener) {
                ListenerState.GRANTED -> colorOk
                ListenerState.GRANTED_NOT_BOUND -> colorWarn
                ListenerState.NOT_GRANTED -> colorBad
            }
        )

        statusLine.text = when (listener) {
            ListenerState.NOT_GRANTED -> getString(R.string.perm_required_hint)
            ListenerState.GRANTED_NOT_BOUND -> getString(R.string.perm_unbound_hint)
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

        fontValue.text = getString(R.string.display_font_value, prefs.fontSp.toInt().toString())
        editButton.text = getString(
            if (hub.overlay().isEditable()) R.string.display_edit_exit
            else R.string.display_edit
        )
        if (debugSwitch.isChecked != prefs.debugEnabled) {
            debugSwitch.isChecked = prefs.debugEnabled
        }
    }

    /**
     * 通知使用权这一行必须区分三态，不能只有「已授予／未授予」。
     *
     * 只显示两态时，「勾着但服务没连上」会被说成未授予，用户去系统设置里看到
     * 明明是勾选状态，只能手动关掉再打开一次 —— 这正是真机反馈的现象。
     */
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
    }

    /**
     * 按当前状态决定「去开启」按钮到底做什么。
     *
     * 空窗期不再把用户丢进系统设置：先自己请求系统重绑，再轮询几秒。
     */
    private fun onNotificationAction() {
        when (hub.listenerState()) {
            ListenerState.NOT_GRANTED -> openNotificationSettings()

            ListenerState.GRANTED_NOT_BOUND -> {
                if (hub.tryRebind(force = true)) {
                    toast(getString(R.string.perm_rebind_requested))
                    startPolling(POLL_FIRST_DELAY_MS)
                } else {
                    // 系统读不到授权列表：只能回落到让用户自己看一眼
                    openNotificationSettings()
                }
            }

            // 已经正常：这一项提供的是「去看系统设置」，不是修复入口
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
        if (!hub.overlay().isAttached()) {
            toast(getString(R.string.edit_needs_session))
            return
        }
        val next = !hub.overlay().isEditable()
        hub.overlay().setEditable(next)
        toast(getString(if (next) R.string.edit_entered else R.string.edit_exited))
        refresh()
    }

    private fun changeFont(delta: Float) {
        prefs.fontSp = prefs.fontSp + delta
        hub.applyTextSize()
        refresh()
    }

    private fun resetDisplay() {
        prefs.resetPosition()
        prefs.resetFont()
        hub.applyTextSize()
        refresh()
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Throwable) {
            toast(getString(R.string.perm_required_hint))
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Throwable) {
                toast(getString(R.string.perm_required_hint))
            }
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (_: Throwable) {
        "-"
    }

    companion object {
        /** 首次刷新的延迟：给系统一点绑定时间，免得白跑一轮。 */
        private const val POLL_FIRST_DELAY_MS = 400L

        /** 空窗期轮询间隔。只读授权列表与内存标志，代价可忽略。 */
        private const val POLL_INTERVAL_MS = 1_200L

        /** 最多轮询次数（约 10 秒）。系统不重绑时不再空转，改为提示手工重开。 */
        private const val MAX_POLL_TICKS = 8
    }
}
