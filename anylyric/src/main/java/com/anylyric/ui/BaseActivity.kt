package com.anylyric.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 两个界面的共用排版工具。
 *
 * 刻意不引 AppCompat / Material：只用平台控件 + 代码布局，保持零第三方依赖。
 * 颜色按当前 uiMode 手工取两套，理由与项目既有约定一致——车机的明暗判定不可靠，
 * 这里只需要「深底浅字 / 浅底深字」两套足够对比度的配色。
 */
abstract class BaseActivity : Activity() {

    protected val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    protected val colorAccent: Int
        get() = if (isNight) 0xFF85B7EB.toInt() else 0xFF185FA5.toInt()

    protected val colorSecondaryText: Int
        get() = if (isNight) 0xFFB0B0B0.toInt() else 0xFF5F5E5A.toInt()

    protected val colorCard: Int
        get() = if (isNight) 0xFF1F1F1F.toInt() else 0xFFF4F4F4.toInt()

    protected val colorOk: Int = 0xFF1D9E75.toInt()
    protected val colorWarn: Int = 0xFFB07D18.toInt()
    protected val colorBad: Int = 0xFFD85A30.toInt()

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    protected fun text(
        value: CharSequence,
        sizeSp: Float,
        bold: Boolean = false,
        secondary: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setLineSpacing(0f, 1.28f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (secondary) setTextColor(colorSecondaryText)
    }

    protected fun textButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(colorAccent)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    protected fun card(children: List<View>, bottomMarginDp: Int = 12): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(colorCard)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            children.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(bottomMarginDp) }
        }

    protected fun row(left: View, right: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
        }

    protected fun sectionLabel(value: String): TextView = text(value, 12f, bold = true).apply {
        setPadding(dp(4), dp(16), 0, dp(8))
        setTextColor(colorSecondaryText)
    }

    protected fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
