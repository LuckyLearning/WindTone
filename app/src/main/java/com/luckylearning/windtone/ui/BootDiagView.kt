package com.luckylearning.windtone.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.luckylearning.windtone.data.BootRecord
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.data.CrashLog
import java.util.Locale

/**
 * 启动诊断页 —— **纯 Android View，刻意不用 Compose**。
 *
 * 背景：车机 DiLink 上「装上打开全黑，什么都看不到」，此前的做法是把崩溃栈用 Compose
 * 画出来。但如果出问题的恰恰是 Compose（GL 驱动 / 动态取色 / insets），那这个崩溃页
 * 本身也是黑的 —— 用一个可能坏掉的东西去报告另一个东西坏了，等于没报告。
 *
 * 所以这一页只用 `TextView` / `Button` / `ScrollView`：不读主题色、不读系统栏 insets
 * 来排版、不依赖任何 Composable。它在绝大多数 ROM 上一定能显示，这是它存在的全部理由。
 *
 * 页面内容按「一眼能看完就够定位」排序：设备与系统 → 屏幕与 insets → 上一轮启动记录
 * → 最近崩溃。全部可复制，用户拍照也行。
 */
object BootDiagView {

    private const val BG = 0xFFFFFFFF.toInt()
    private const val FG = 0xFF16161A.toInt()
    private const val MUTED = 0xFF5C5C66.toInt()
    private const val ACCENT = 0xFF5B4BE0.toInt()

    /**
     * @param record   上一轮启动的完整记录（可能是「出过首帧」的正常一轮，也可能是没画成的一轮）
     * @param forced   是否「构建时就要求必显」（诊断包），只影响首段文案
     * @param onEnter  用户点「进入应用」时调用
     */
    fun create(
        activity: Activity,
        record: BootRecord?,
        forced: Boolean,
        onEnter: () -> Unit,
    ): View {
        val ctx = activity
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 40))
            setBackgroundColor(BG)
        }

        // 顶部这一条是**探针**，不是装饰。
        // 色条用 Canvas 直接画：不依赖字体、不依赖主题、不依赖 Compose，
        // 是「这台车机到底能不能画出我们的窗口」这个问题最省事的答案。
        // 只靠文字判断不可靠 —— 车机上字体缺失/设置异常时字会画成空白，但色条不会。
        content.addView(BarsView(ctx))
        content.addView(
            TextView(ctx).apply {
                text = "能看到上面的色条和这一行字，说明车机能正常显示本应用的窗口"
                setTextColor(0xFF7A0F5A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 6))
            }
        )

        content.addView(title(ctx, "WindTone 启动诊断"))
        content.addView(
            paragraph(
                ctx,
                when {
                    // 这三种情况的处置完全不同，所以要在最上面一句话里就分开：
                    // 「崩了」→ 有栈；「没画」→ 渲染链在首帧之前就断了；
                    // 「画了但看不见」→ 画布/合成侧，不是没画。
                    record == null && forced ->
                        "这是诊断构建：每次打开都先停在这一页。这是安装后的第一次启动，还没有上一轮记录。"
                    record == null -> "上次启动异常退出。下面是留下的记录。"
                    record.stoppedAtDiag ->
                        "上一轮停在诊断页，没点「进入应用」，所以没有首帧记录 —— 点下面的按钮继续。"
                    record.reachedFrame ->
                        "上一轮已经画出首帧。如果屏幕仍然全黑，说明界面画出来了但看不见（渲染/合成侧），不是没画。"
                    else ->
                        "上一轮没有画出界面 —— 进程活着，但一帧都没送出去（所以既没有崩溃对话框，也没什么可看）。"
                },
                color = MUTED,
            )
        )

        content.addView(section(ctx, "设备与系统", BootTrace.deviceInfo(ctx)))
        content.addView(section(ctx, "屏幕", screenInfo(ctx)))

        // insets 必须等 View 挂上去才拿得到，先占位、post 里回填
        val insetsText = paragraph(ctx, "…")
        content.addView(sectionView(ctx, "系统栏 insets", insetsText))

        // 车机上还有一个必须排除的可能：界面被放到了另一块屏幕上（中控 + 仪表是两块 display），
        // 主屏只会显示桌面。窗口尺寸和 displayId 能把这种情况直接暴露出来。
        val windowText = paragraph(ctx, "…")
        content.addView(sectionView(ctx, "窗口与显示", windowText))

        content.addView(
            section(
                ctx,
                "上一轮启动",
                record?.let { BootTrace.summary(it) } ?: "（还没有上一轮记录）",
            )
        )

        val crash = runCatching { CrashLog.latest(ctx) }.getOrNull()
        if (!crash.isNullOrBlank()) {
            content.addView(section(ctx, "最近一次崩溃", truncate(crash, 2000)))
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 24), 0, 0)
        }
        buttons.addView(
            Button(ctx).apply {
                text = "进入应用"
                setOnClickListener { onEnter() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        buttons.addView(
            Button(ctx).apply {
                text = "复制全部"
                setOnClickListener {
                    val all = buildString {
                        appendLine(BootTrace.deviceInfo(ctx))
                        appendLine(screenInfo(ctx))
                        appendLine("insets：" + insetsText.text)
                        record?.let { appendLine(BootTrace.summary(it)) }
                        crash?.let { appendLine("--- 最近崩溃 ---"); appendLine(it) }
                    }
                    copy(ctx, all)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        content.addView(buttons)

        content.addView(
            paragraph(
                ctx,
                "如果点「进入应用」后又是全黑，说明窗口能显示、但界面画不出来 —— 请把这个页面拍照发回。",
                color = ACCENT,
                topPaddingDp = 20,
            )
        )

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        }
        scroll.post {
            val insets = runCatching { ViewCompat.getRootWindowInsets(scroll) }.getOrNull()
            insetsText.text = if (insets == null) {
                "取不到（rootWindowInsets = null）"
            } else {
                buildString {
                    appendLine("statusBars     ${insets.getInsets(WindowInsetsCompat.Type.statusBars())}")
                    appendLine("navigationBars ${insets.getInsets(WindowInsetsCompat.Type.navigationBars())}")
                    appendLine("systemBars     ${insets.getInsets(WindowInsetsCompat.Type.systemBars())}")
                    append("displayCutout  ${insets.getInsets(WindowInsetsCompat.Type.displayCutout())}")
                }
            }
            // 记「这一页画出来了」：它与「Compose 能不能画」是两件事，
            // 分开记才能把「窗口不行」和「界面不行」区分开。
            BootTrace.mark(ctx, BootTrace.STAGE_DIAG_SHOWN)
        }
        // 窗口要等布局完成才有真实尺寸，与 insets 一起回填
        scroll.post { windowText.text = windowInfo(ctx, scroll) }
        return scroll
    }

    /**
     * 窗口与显示设备快照。
     *
     * 为什么单独一块：车机通常有两块 display（15.6" 中控 + 10.25" 仪表）。
     * 如果 Activity 被放到了仪表那块上，用户看主屏就只有桌面 —— 表现与「黑屏」一模一样，
     * 但成因和渲染毫无关系。窗口尺寸为 0 也是同一类症状。
     */
    private fun windowInfo(ctx: Activity, root: View): String = buildString {
        runCatching {
            val decor = ctx.window.decorView
            appendLine("窗口：${decor.width} × ${decor.height} px（decorView）")
            appendLine("页面所在屏：displayId=${root.display?.displayId ?: -1}")
            appendLine("多窗口：${ctx.isInMultiWindowMode}　画中画：${ctx.isInPictureInPictureMode}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = ctx.windowManager.currentWindowMetrics.bounds
                appendLine("WindowMetrics：${bounds.width()} × ${bounds.height()}")
            }
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val displays = dm?.displays.orEmpty()
            appendLine("显示设备：${displays.size} 个")
            displays.forEach { d ->
                val mode = d.mode
                append(
                    "  #${d.displayId}  ${d.name}  ${mode.physicalWidth}×${mode.physicalHeight}" +
                        " @${String.format(Locale.US, "%.0f", d.refreshRate ?: 0f)}\n"
                )
            }
        }.onFailure { append("取不到：${it.javaClass.simpleName}") }
    }.trimEnd()

    private fun screenInfo(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        val cfg = ctx.resources.configuration
        val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val orientation = if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏"
        return buildString {
            appendLine("像素：${dm.widthPixels} × ${dm.heightPixels}")
            appendLine(
                "dp：${(dm.widthPixels / dm.density).toInt()} × ${(dm.heightPixels / dm.density).toInt()}" +
                    "（density ${dm.density} / dpi ${dm.densityDpi}）"
            )
            appendLine("方向：$orientation　深色模式：${if (night) "是" else "否"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rate = runCatching { ctx.display?.refreshRate }.getOrNull()
                appendLine("刷新率：${rate?.let { String.format(Locale.US, "%.0f", it) } ?: "未知"}")
            }
            append("字体缩放：${cfg.fontScale}")
        }
    }

    /**
     * 四条纯色色条，全部在 `onDraw` 里用 Canvas 填。
     *
     * 刻意**不**做任何依赖：不读主题、不用 drawable、不设圆角/阴影（那些走 RenderNode），
     * 只调 `drawRect`。如果连这个都看不到，问题一定在窗口/合成层，而不是界面代码。
     */
    private class BarsView(ctx: Context) : View(ctx) {
        private val bars = intArrayOf(
            0xFFE0007A.toInt(), // 品红
            0xFF00A8E8.toInt(), // 青
            0xFFF5B800.toInt(), // 黄
            0xFF17A34A.toInt(), // 绿
        )
        private val paint = Paint().apply { isAntiAlias = false }

        init {
            setBackgroundColor(BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 40),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val step = width.toFloat() / bars.size
            bars.forEachIndexed { i, color ->
                paint.color = color
                canvas.drawRect(i * step, 0f, (i + 1) * step, height.toFloat(), paint)
            }
        }
    }

    private fun title(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(ctx, 8))
    }

    private fun section(ctx: Context, title: String, body: String) =
        sectionView(ctx, title, paragraph(ctx, body))

    private fun sectionView(ctx: Context, title: String, body: TextView): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 18), 0, 0)
            addView(
                TextView(ctx).apply {
                    text = title
                    setTextColor(ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(ctx, 4))
                }
            )
            addView(body)
        }

    private fun paragraph(
        ctx: Context,
        body: String,
        color: Int = FG,
        topPaddingDp: Int = 0,
    ) = TextView(ctx).apply {
        text = body
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        // 等宽：栈和阶段表要对齐才好读
        typeface = Typeface.MONOSPACE
        gravity = Gravity.START
        setPadding(0, dp(ctx, topPaddingDp), 0, 0)
        setTextIsSelectable(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 长栈不要在小屏上把行折得七零八落
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
    }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.substring(0, limit) + "\n…（已截断）"

    private fun copy(ctx: Context, text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("WindTone 诊断", text))
            Toast.makeText(ctx, "已复制，可以直接粘贴发回", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
