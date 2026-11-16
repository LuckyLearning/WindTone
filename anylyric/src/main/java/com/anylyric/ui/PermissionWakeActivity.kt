package com.anylyric.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.anylyric.R
import com.anylyric.data.PermissionWake
import com.anylyric.data.Prefs

/**
 * 开机后的权限确认页（0.2.29）。
 *
 * 由 [com.anylyric.service.BootReceiver] 在开机/解锁后拉起，**只在有缺失时**弹出。
 * 它不申请任何东西 —— 系统权限只能由用户亲手点，这里做的是「把人送到正确的那一页」，
 * 并把「还差几项」一次说清。
 *
 * 弹成对话框样式而不是整页：开机后屏上往往已经是导航或桌面，
 * 整页设置会让人以为「应用自己启动了」。
 */
class PermissionWakeActivity : BaseActivity() {

    private lateinit var host: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(if (isNight) 0xCC000000.toInt() else 0x99000000.toInt())
        }
        host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(host, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(scroller, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        rebuild()
    }

    /**
     * 每次回到这一页都重算一遍：用户从系统设置按返回键回来时，刚补上的那一项
     * 必须立刻从列表里消失 —— 否则他会以为「点了没用」，再去点一次。
     */
    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        if (!::host.isInitialized) return
        val missing = PermissionWake.missing(this, Prefs(this))
        host.removeAllViews()
        if (missing.isEmpty()) {
            // 全齐了：直接收掉，别留一个「什么都不缺」的页面让用户猜要干什么。
            finish()
            return
        }

        val rows = ArrayList<View>(missing.size + 3)
        rows += text(getString(R.string.pw_title), 17f, bold = true)
        rows += spacerView(4)
        rows += text(getString(R.string.pw_desc), 13f).apply {
            setTextColor(colorSecondaryText)
        }
        rows += spacerView(8)
        for (kind in missing) {
            rows += itemRow(kind)
        }
        rows += spacerView(8)
        rows += textButton(getString(R.string.pw_later)) { finish() }

        host.addView(card(rows, bottomMarginDp = 0))
    }

    private fun itemRow(kind: PermissionWake.Kind): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(text(titleOf(kind), 14f, bold = true))
        texts.addView(text(hintOf(kind), 12f).apply { setTextColor(colorSecondaryText) })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        row.addView(
            textButton(getString(R.string.pw_open)) {
                open(kind)
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        )
        return row
    }

    private fun open(kind: PermissionWake.Kind) {
        try {
            startActivity(PermissionWake.intentFor(this, kind))
        } catch (_: ActivityNotFoundException) {
            // 定制 ROM 偶尔把系统设置页裁掉；此时只能告诉用户「自己找一下」。
            toast(getString(R.string.pw_no_settings))
        }
    }

    private fun titleOf(kind: PermissionWake.Kind): String = getString(
        when (kind) {
            PermissionWake.Kind.OVERLAY -> R.string.pw_overlay
            PermissionWake.Kind.LISTENER -> R.string.pw_listener
            PermissionWake.Kind.ACCESSIBILITY -> R.string.pw_a11y
        }
    )

    private fun hintOf(kind: PermissionWake.Kind): String = getString(
        when (kind) {
            PermissionWake.Kind.OVERLAY -> R.string.pw_overlay_hint
            PermissionWake.Kind.LISTENER -> R.string.pw_listener_hint
            PermissionWake.Kind.ACCESSIBILITY -> R.string.pw_a11y_hint
        }
    )

    private fun spacerView(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp))
    }
}
