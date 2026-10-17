package com.luckylearning.windtone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「上次运行时异常退出」页。
 *
 * 为什么要有这一页：真机上出现过**装上就黑屏、连系统的「应用已停止运行」都看不到**的
 * 故障（v1.1.13，比亚迪 DiLink 车机）。那种情况下应用一启动就没了，用户永远走不到
 * 「设置 → 诊断 → 崩溃日志」，日志记了也等于没记。
 *
 * 所以启动时只要有「上一轮还没看过的崩溃」，就先把栈摆到屏幕上：车机上没法接 adb、
 * 没法导文件，但**拍张照 / 复制一段文字发出来**是人人都会的。看过一次就不再重复挡路
 * （见 `CrashLog.latestUnseen` / `CrashLog.markSeen`）。
 *
 * 这一页刻意做得极简：只用 [Surface] + [Text] + 两个按钮，不依赖曲库、播放器、主题动态色，
 * 免得排查故障的页面本身又依赖出故障的东西。
 */
@Composable
fun CrashReportScreen(
    report: String,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var copied by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 40.dp else 20.dp, vertical = 24.dp),
            ) {
            Text(
                text = "上次运行时异常退出",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "下面是系统记下来的出错位置。麻烦拍张照，或点「复制全文」贴给我 —— " +
                    "有了它才能定位问题。也可以先点「继续打开」照常用（下次启动不会再弹这一页）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString(", ")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (wide) 13.sp else 11.sp,
                    lineHeight = if (wide) 18.sp else 15.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp),
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("WindTone 崩溃日志", report))
                        }
                        copied = true
                        Toast.makeText(context, "已复制，去粘贴发送即可", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(if (copied) "已复制" else "复制全文")
                }
                Button(onClick = onContinue) { Text("继续打开") }
            }
            Spacer(Modifier.height(24.dp))
            }
        }
    }
}
