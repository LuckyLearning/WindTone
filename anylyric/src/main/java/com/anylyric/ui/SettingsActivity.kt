package com.anylyric.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.anylyric.R
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
    private lateinit var overlayState: TextView
    private lateinit var fontValue: TextView
    private lateinit var editButton: TextView
    private lateinit var debugSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = SessionHub.get(this)
        prefs = hub.prefs()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        overlayState = text("", 13f, bold = true)
        root.addView(sectionLabel(getString(R.string.section_permissions)))
        root.addView(
            card(
                listOf(
                    text(getString(R.string.perm_notification_title), 16f, bold = true),
                    text(getString(R.string.perm_notification_desc), 13f, secondary = true).apply {
                        setPadding(0, dp(4), 0, dp(8))
                    },
                    row(
                        notificationState,
                        textButton(getString(R.string.perm_open)) { openNotificationSettings() }
                    ),
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

        statusBanner.text = getString(
            if (status.listenerConnected) R.string.status_listener_connected
            else R.string.status_listener_disconnected
        )
        statusBanner.setTextColor(if (status.listenerConnected) colorOk else colorBad)

        statusLine.text = when {
            !status.listenerConnected -> getString(R.string.perm_required_hint)
            status.sessionCount == 0 -> getString(R.string.status_no_session)
            else -> getString(
                R.string.status_session,
                status.title ?: "-",
                status.artist ?: "-"
            ) + "\n" + getString(R.string.status_channel, channelLabel(status.hitSource))
        }

        applyPermissionState(notificationState, status.listenerConnected)
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
}
