package com.anylyric.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anylyric.data.Prefs
import com.anylyric.session.SessionHub
import com.anylyric.theme.ThemeContext
import com.anylyric.theme.ThemeSwitch

/**
 * 两个界面的共用排版工具。
 *
 * 刻意不引 AppCompat / Material：只用平台控件 + 代码布局，保持零第三方依赖。
 * 颜色按当前 uiMode 手工取两套，理由与项目既有约定一致——车机的明暗判定不可靠，
 * 这里只需要「深底浅字 / 浅底深字」两套足够对比度的配色。
 *
 * 0.2.6 起这套工具承担「设置页的视觉规范」：卡片、分区、步进器、胶囊、开关行都只在这里
 * 定义一次，页面本身只负责摆放。改观感不必碰页面代码，也就不容易出现
 * 「同一个控件在两处长得不一样」。
 */
abstract class BaseActivity : Activity() {

    /**
     * 界面明暗的唯一入口。
     *
     * 放在 `attachBaseContext` 而不是每个页面的 `onCreate` 里 `setTheme()`：
     * 这里换掉的是 Activity 的整份 Configuration，`values-night/` 的 style、
     * `resources.configuration.uiMode`、以及本类的 [isNight] 会同时跟着变，
     * 不会出现「style 是夜间、取色却按白天算」的错位。
     *
     * 副作用要记住：改了存储里的主题后必须 `recreate()`，否则新值要等下次进页面才生效。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeContext.wrap(newBase, Prefs(newBase).themeMode))
    }

    /**
     * 主题切换的淡入。
     *
     * 必须在 `onCreate`（页面已经把内容 setContentView 进来）之后、第一帧之前做，
     * 所以挂在 `onPostCreate`：早了没有内容可遮，晚了会先闪一下新界面。
     */
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        ThemeSwitch.play(this)
    }

    /**
     * 本应用界面在屏期间，让悬浮窗让位（0.2.11「按应用隐藏」的零权限那一半）。
     *
     * 为什么放在基类、用 onStart/onStop 而不是 onResume/onPause：
     *  · 基类一处写清，将来新加页面不会漏；
     *  · onStart/onStop 覆盖「被系统对话框盖住但界面还在」的情况 ——
     *    用户点开权限页再回来时，悬浮窗不该在中间那一下冒出来；
     *  · 用计数而不是布尔，避免「设置 → 诊断」两页交接时闪一下。
     */
    override fun onStart() {
        super.onStart()
        SessionHub.get(this).setOwnUiVisible(true)
    }

    override fun onStop() {
        SessionHub.get(this).setOwnUiVisible(false)
        super.onStop()
    }

    protected val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    protected val colorAccent: Int
        get() = if (isNight) 0xFF85B7EB.toInt() else 0xFF185FA5.toInt()

    /** 主按钮底色：比 accent 更实，作为一屏里唯一的「主操作」。 */
    protected val colorAccentStrong: Int
        get() = if (isNight) 0xFF2C6EA8.toInt() else 0xFF185FA5.toInt()

    protected val colorSecondaryText: Int
        get() = if (isNight) 0xFFB0B0B0.toInt() else 0xFF5F5E5A.toInt()

    protected val colorCard: Int
        get() = if (isNight) 0xFF1F1F1F.toInt() else 0xFFF5F5F4.toInt()

    /** 卡片描边。纯色块在车机的低对比屏上会糊成一片，一条极淡的边能把层次拉回来。 */
    protected val colorCardStroke: Int
        get() = if (isNight) 0x1FFFFFFF else 0x14000000

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

    /** 文字按钮。触摸目标抬到 40dp：车机上手指落点粗，纯文字高度容易点偏。 */
    protected fun textButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(colorAccent)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minHeight = dp(40)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** 主操作按钮：实心底 + 白字。一屏只该出现一个。 */
    protected fun primaryButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        minHeight = dp(46)
        background = pill(colorAccentStrong, dp(12).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    protected fun card(children: List<View>, bottomMarginDp: Int = 12): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(colorCard)
                setStroke(dp(1).coerceAtLeast(1), colorCardStroke)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            children.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(bottomMarginDp) }
        }

    /**
     * 带标题的分区卡片。
     *
     * 标题放进卡片里而不是浮在卡片上方：一次滚动能看到「这块是什么」，
     * 双栏布局时也不会出现「标题在左栏、内容在两栏」的错位。
     */
    protected fun cardSection(
        title: String,
        subtitle: String?,
        rows: List<View>
    ): LinearLayout {
        val children = ArrayList<View>(rows.size + 3)
        children += text(title, 16f, bold = true)
        if (!subtitle.isNullOrBlank()) {
            children += text(subtitle, 12f, secondary = true).apply {
                setPadding(0, dp(3), 0, dp(2))
            }
        }
        children += spacer(if (subtitle.isNullOrBlank()) 8 else 12)
        children += rows
        return card(children)
    }

    /**
     * 左右分栏的一行。
     *
     * 两侧都用显式 LayoutParams(MATCH_PARENT)：父容器若是 wrap_content，带 weight 的
     * 子控件在部分 ROM 的第 1 次测量里会算成 0 宽，界面直接空白 —— 这类布局塌陷
     * 在真机上很难复现，只能从写法上先堵住。
     */
    protected fun row(left: View, right: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    protected fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    /** 一条极淡的分隔线，用来切分同一张卡片里的不同小节。 */
    protected fun divider(topMarginDp: Int = 12, bottomMarginDp: Int = 12): View =
        View(this).apply {
            setBackgroundColor(colorCardStroke)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1)
            ).apply {
                topMargin = dp(topMarginDp)
                bottomMargin = dp(bottomMarginDp)
            }
        }

    /** 小字说明。默认挂在控件下方，行距略松，长句也能读。 */
    protected fun hint(value: String): TextView = text(value, 11.5f, secondary = true).apply {
        setPadding(0, dp(4), 0, 0)
    }

    protected fun pill(color: Int, radiusPx: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(color)
            if (strokeColor != null) setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
