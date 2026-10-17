package com.luckylearning.windtone.ui

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme

/**
 * 三段对照页 —— 一张照片就能分清「Compose 画不出来」「界面代码有问题」「只有根内容这条路有问题」。
 *
 * 车机上已经确认：整页纯原生 View 的诊断页能正常显示（窗口、insets、合成都没事），
 * 但点「进入应用」之后全黑。所以三层依次排查，全部放在同一屏里，避免来回折腾：
 *   1. 纯原生 `TextView`      —— 控制组，它不显示就说明窗口不行；
 *   2. 最小 ComposeView       —— 只有一块绿底 + 一行字，不挂主题、不挂 ViewModel、不读 insets；
 *   3. 真实界面（主题 + MainScreen） —— 唯一差别是它作为「普通子 View」而不是 Activity 的根内容。
 *
 * 根布局铺**黄色**：ComposeView 在合成前是透明的，于是
 *   · 某一段是黄 → 那一段一个像素都没画（渲染链断了）
 *   · 某一段是黑 → 画了，只是画出来是黑的（配色 / 图层问题）
 *   · 某一段是绿 / 正常界面 → 这一段没问题
 * 黄色就是为了把「没画」和「画了黑的」分开，不是为了好看。
 */
object SplitProbeView {

    fun create(activity: Activity): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFEB00.toInt())
        }
        // 三段的分工：控制组 → Compose 最小用例 → 真实界面。
        // 权重 1:1:2 —— 第三段要放得下真实界面，否则「看不见」可能只是因为被挤成一条。
        root.addView(nativeHalf(activity), weight(1f))
        root.addView(composeHalf(activity), weight(1f))
        root.addView(appHalf(activity), weight(2f))
        return root
    }

    private fun weight(value: Float) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, value)

    /** 第一段：原生 TextView。它要是也不显示，问题就不在 Compose，而在窗口本身 */
    private fun nativeHalf(activity: Activity): View = TextView(activity).apply {
        setBackgroundColor(0xFFE0007A.toInt())
        setTextColor(0xFFFFFFFF.toInt())
        text = "第一段：原生 View"
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    /** 第二段：最小 Compose。不挂主题，避免把主题/取色也拖进来搅浑判断 */
    private fun composeHalf(activity: Activity): View = ComposeView(activity).apply {
        setContent { ProbeContent() }
    }

    /**
     * 第三段：**真实界面**（同一套主题 + MainScreen），只是放在一个普通的 ComposeView 里，
     * 而不是 Activity 的根内容。
     *
     * 这一段的用处：如果它是好的，说明界面代码和 Compose 都没问题，问题出在
     * 「作为根内容 + edge-to-edge」这条路上；如果它也是黑的，问题就在界面代码本身。
     */
    private fun appHalf(activity: Activity): View = ComposeView(activity).apply {
        setContent {
            val lib: LibraryViewModel = viewModel()
            val playerVm: PlayerViewModel = viewModel()
            val updateVm: UpdateViewModel = viewModel()
            val settings: SettingsViewModel = viewModel()
            val seed by playerVm.themeSeed.collectAsStateWithLifecycle()
            MusicPlayerTheme(seedColor = if (playerVm.dynamicThemeEnabled()) seed else null) {
                MainScreen(lib = lib, playerVm = playerVm, updateVm = updateVm, settings = settings)
            }
        }
    }

    @Composable
    private fun ProbeContent() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF17A34A)),
        ) {
            Text(
                text = "第二段：Compose（无主题）",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
            // 圆点单独放一层，顺带验证「形状 + 裁剪」这条路径；放右上角，别压住字
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(28.dp)
                    .background(Color(0xFFB6FF3B), CircleShape),
            )
        }
    }

    /** 记一笔「这一页已经挂上去」，供下一轮诊断页对照 */
    fun mark(activity: Activity) {
        runCatching { BootTrace.mark(activity, "probe:split-shown") }
    }
}
