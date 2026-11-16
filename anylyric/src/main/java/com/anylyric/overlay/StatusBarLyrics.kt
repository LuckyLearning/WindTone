package com.anylyric.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anylyric.data.Prefs

/**
 * 状态栏歌词条（0.2.29）—— **与悬浮窗完全独立的一条通路**。
 *
 * 为什么必须另开一条：[LyricRenderer] 用的 `TYPE_APPLICATION_OVERLAY` 层级是 2，
 * 而系统状态栏是 14，普通悬浮窗在任何情况下都盖不住通知栏（这不是留白或位置问题，
 * 是 Android 的窗口分层规则）。能盖住状态栏的只有 `TYPE_ACCESSIBILITY_OVERLAY`
 * （`ACCESSIBILITY_LAYER` = 20），而它**只能由无障碍服务添加** ——
 * 同车机上的「攻城狮助手」就是这条路（dex 里有该类型、没有 APPLICATION_OVERLAY）。
 *
 * 因此本类所有窗口操作都挂在 [AccessibilityService] 上：服务没连上时 [isAvailable]
 * 为 false，会话层不会尝试推送，设置页也据此提示「需要开启无障碍」。
 *
 * 与悬浮窗刻意不共享任何东西：独立的开关、独立的样式、独立的窗口与生命周期。
 * 关掉状态栏歌词不影响悬浮窗，反之亦然。
 */
object StatusBarLyrics {

    private val handler = Handler(Looper.getMainLooper())

    private var service: AccessibilityService? = null
    private var windowManager: WindowManager? = null

    private var root: LinearLayout? = null
    private var currentView: TextView? = null
    private var nextView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 上一帧内容。同一句反复推送时直接跳过，省掉一次文本布局。 */
    private var lastCurrent: String? = null
    private var lastNext: String? = null

    /** 无障碍服务连上/断开时由 [com.anylyric.accessibility.ForegroundAppService] 调用。 */
    fun attach(service: AccessibilityService) {
        this.service = service
        this.windowManager = runCatching {
            service.getSystemService(WindowManager::class.java)
        }.getOrNull()
        // 本类不持有 Application：样式全从 Prefs 现读，注入一次就够。
        prefsRef = Prefs(service.applicationContext)
        // 服务刚连上时若开关已经开着，需要立刻把窗口建出来，不必等下一句歌词。
        handler.post { ensureWindow() }
    }

    fun detach() {
        remove()
        service = null
        windowManager = null
    }

    /** 无障碍是否可用。设置页用它决定「去开启无障碍」提示要不要显示。 */
    fun isAvailable(): Boolean = service != null && windowManager != null

    fun isShowing(): Boolean = root != null

    /**
     * 推一帧内容。`current` 为 null 表示当前没有歌词 —— 此时隐藏窗口，
     * 而不是留一个空白条压在状态栏上。
     */
    fun update(current: String?, next: String?) {
        handler.post {
            val prefs = prefsRef ?: return@post
            if (!prefs.statusBarLyric || current.isNullOrBlank()) {
                remove()
                return@post
            }
            // 内容没变就直接跳过 —— 但必须确认窗口还活着：无障碍服务被系统重绑时
            // 窗口会被静默摘掉，只留 root 非空的话，这里会永远 skip、窗口再也不回来。
            if (current == lastCurrent && next == lastNext && root?.isAttachedToWindow == true) {
                return@post
            }
            lastCurrent = current
            lastNext = next
            ensureWindow()
            applyText(current, next)
        }
    }

    /** 样式或开关变了：重建一次（样式项多，重建比逐项比对简单且不会漏）。 */
    fun applyStyle() {
        handler.post {
            val prefs = prefsRef ?: return@post
            if (!prefs.statusBarLyric || !isAvailable()) {
                remove()
                return@post
            }
            // 内容没变时也要重建：字号/背景改了必须立刻可见。
            val current = lastCurrent
            remove(keepContent = true)
            if (current.isNullOrBlank()) return@post
            ensureWindow()
            applyText(current, lastNext)
        }
    }

    /** 由会话层在首次构造时注入，避免本类持有 Application 引用链。 */
    var prefsRef: Prefs? = null

    private fun ensureWindow() {
        val prefs = prefsRef ?: return
        if (!prefs.statusBarLyric || !isAvailable()) {
            remove()
            return
        }
        val existing = root
        if (existing != null) {
            if (existing.isAttachedToWindow) return
            // 引用还在、窗口已被摘：清干净再重建，否则会一直往一个死窗口里写字。
            root = null
            currentView = null
            nextView = null
            params = null
        }
        val wm = windowManager ?: return
        val ctx = service ?: return

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
        }
        val cur = TextView(ctx).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        val nxt = TextView(ctx).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        // 左右按比例分：当前行更重要，默认 3:2。
        val split = prefs.sblSplit.coerceIn(0.3f, 0.8f)
        bar.addView(cur, LinearLayout.LayoutParams(0, WRAP, split))
        bar.addView(nxt, LinearLayout.LayoutParams(0, WRAP, 1f - split))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = prefs.sblOffsetY
        }

        val added = runCatching { wm.addView(bar, lp) }.isSuccess
        if (!added) {
            // 车机 ROM 拒绝 a11y overlay 时不要留半个状态：下一次推送会再试。
            return
        }
        root = bar
        currentView = cur
        nextView = nxt
        params = lp
    }

    private fun applyText(current: String, next: String?) {
        val prefs = prefsRef ?: return
        val cur = currentView ?: return
        val nxt = nextView ?: return
        cur.text = current
        nxt.text = next.orEmpty()
        nxt.visibility = if (prefs.sblShowNext && !next.isNullOrBlank()) View.VISIBLE else View.GONE
        applyTextStyle(prefs, cur, nxt)
        applyBackground(prefs)
        // 偏移与分栏只在窗口已存在时能热改；改了分栏权重需要重新布局。
        val bar = root ?: return
        val split = prefs.sblSplit.coerceIn(0.3f, 0.8f)
        (cur.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = split
            cur.layoutParams = it
        }
        (nxt.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = 1f - split
            nxt.layoutParams = it
        }
        params?.let {
            if (it.y != prefs.sblOffsetY) {
                it.y = prefs.sblOffsetY
                runCatching { windowManager?.updateViewLayout(bar, it) }
            }
        }
    }

    private fun applyTextStyle(prefs: Prefs, cur: TextView, nxt: TextView) {
        val size = prefs.sblFontSp.coerceIn(9f, 30f)
        val typeface = if (prefs.sblBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        cur.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        cur.typeface = typeface
        cur.setTextColor(prefs.sblColor)
        nxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, (size * 0.88f).coerceAtLeast(8f))
        nxt.typeface = typeface
        nxt.setTextColor(prefs.sblNextColor)
    }

    private fun applyBackground(prefs: Prefs) {
        val bar = root ?: return
        val alpha = (prefs.sblBgAlpha.coerceIn(0f, 1f) * 255f).toInt()
        if (alpha <= 2) {
            bar.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        val fill = (prefs.sblBgColor and 0x00FFFFFF) or (alpha shl 24)
        bar.background = GradientDrawable().apply {
            setColor(fill)
            // 圆角固定 0：状态栏是贴边的横条，圆角在两侧会露出下面的东西。
            cornerRadius = 0f
        }
    }

    private fun remove(keepContent: Boolean = false) {
        val bar = root ?: run {
            if (!keepContent) {
                lastCurrent = null
                lastNext = null
            }
            return
        }
        runCatching { windowManager?.removeView(bar) }
        root = null
        currentView = null
        nextView = null
        params = null
        if (!keepContent) {
            lastCurrent = null
            lastNext = null
        }
    }

    private fun dp(value: Float): Int {
        // 用系统 displayMetrics 而不是某个 Context 的：这条窗口不一定挂在本应用
        // 当前 Activity 上，取错 Context 会让 density 随缩放设置漂移。
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        return (value * density).toInt()
    }

    private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
}
