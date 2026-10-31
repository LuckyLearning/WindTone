package com.luckylearning.windtone.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunEndpoints
import com.luckylearning.windtone.data.aliyun.AliyunQrCode
import com.luckylearning.windtone.data.aliyun.AliyunQrLogin
import com.luckylearning.windtone.data.aliyun.AliyunQrLogin.ScanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 阿里云盘**扫码授权弹层** —— 授权全程在 app 内完成，不跳浏览器、不复制粘贴。
 *
 * 直连阿里官方 passport 接口（为什么不再走第三方中转，见 [AliyunQrLogin] 类注释）：
 * 本地生成二维码 → 轮询扫码状态 → CONFIRMED 时结果里直接带双令牌，
 * 存进 [AliyunAuth] 完成登录。二维码用 ZXing **本地**渲染，不依赖任何图片服务。
 *
 * 状态机：
 * ```
 * 生成中(显示已等待秒数) ──成功──→ 显示二维码 ── CONFIRMED ──→ 保存 ──→ onLoggedIn()
 *      │                        │
 *      └──失败→ 显示原因 + 重试    └── EXPIRED → 重新生成
 * ```
 *
 * 三个刻意的取舍：
 *  1. **不自动静默重试（生成）**：生成失败立刻把原因摊在界面上，分不清「慢」和「死」
 *     是之前最被诟病的体验。
 *  2. **等待秒数可见**：转圈超过 3 秒就显示「已等待 N 秒」。
 *  3. **轮询失败可见但不中断**：扫码中途切网 / 弱网时继续自动重试（用户可能刚扫上），
 *     但连续失败 2 次起就把「正在重试（第 n 次）」显示出来 —— 1.1.63 之前错误被
 *     全部吞掉，用户扫码确认后界面永远停在二维码页，完全无从判断。
 *
 * 轮询挂在 [LaunchedEffect] 协程里，弹层关闭 = 协程取消 = 轮询停止，无泄漏。
 */
@Composable
internal fun AliyunQrLoginDialog(
    auth: AliyunAuth,
    onLoggedIn: () -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    /** 当前扫码会话；null = 还没生成出来 */
    var session by remember { mutableStateOf<AliyunQrLogin.QrSession?>(null) }
    var status by remember { mutableStateOf<ScanStatus?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    /** 生成阶段已等待的秒数（>3 秒才显示，区分「慢」与「卡死」） */
    var waitedSeconds by remember { mutableStateOf(0) }
    /** 重试计数：自增即触发重新生成 */
    var attempt by remember { mutableStateOf(0) }
    /** 轮询连续失败次数（成功一次即清零；>0 时显示在界面上） */
    var pollFailures by remember { mutableStateOf(0) }
    /** 轮询最近一次失败的原因（>1 次才显示） */
    var pollError by remember { mutableStateOf<String?>(null) }

    // 生成二维码：进入弹层、点重试、二维码过期都走这里
    LaunchedEffect(attempt) {
        session = null
        failed = null
        status = null
        waitedSeconds = 0
        val ticker = launch {
            while (isActive) {
                delay(1_000)
                waitedSeconds++
            }
        }
        try {
            session = AliyunQrLogin.generateQr()
            pollFailures = 0
            pollError = null
        } catch (e: AliyunQrLogin.QrLoginException) {
            failed = e.message ?: context.getString(R.string.aqr_gen_failed)
        } finally {
            ticker.cancel()
        }
    }

    // 轮询扫码状态：换了 session（过期重来）就重新开始
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        while (isActive) {
            try {
                val poll = AliyunQrLogin.checkLogin(current)
                pollFailures = 0
                pollError = null
                status = poll.status
                when {
                    poll.status == ScanStatus.CONFIRMED -> {
                        saving = true
                        // 来源固定为 CS（网页版扫码登录）—— 刷新端点必须与之匹配，
                        // 混用会「能用 2 小时后永久失效」（见 AliyunTokenSource 注释）
                        auth.saveFromQrLogin(
                            accessToken = poll.accessToken ?: "",
                            refreshToken = poll.refreshToken ?: "",
                            expiresInSec = poll.expiresInSec,
                        )
                        onLoggedIn()
                        return@LaunchedEffect
                    }
                    poll.status == ScanStatus.EXPIRED -> {
                        // 二维码过期：重新生成一张（attempt 自增触发上面的生成逻辑）
                        session = null
                        attempt++
                        return@LaunchedEffect
                    }
                    else -> Unit
                }
            } catch (e: AliyunQrLogin.QrLoginException) {
                // 轮询偶发失败（切网 / 弱网）不该终止整个流程：等一轮再来，
                // 但要让用户看得见在重试（以前静默吞掉，扫码后界面永远不动）
                pollFailures++
                pollError = e.message
                status = null
            }
            delay(AliyunQrLogin.POLL_INTERVAL_MS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aqr_title)) },
        text = {
            QrLoginContent(
                session = session,
                status = status,
                saving = saving,
                failed = failed,
                waitedSeconds = waitedSeconds,
                pollFailures = pollFailures,
                pollError = pollError,
                onOpenWebTool = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AliyunEndpoints.TOKEN_TOOL_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        },
        confirmButton = {
            when {
                failed != null -> TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.aqr_retry)) }
                // 扫不上 / 怀疑这张码已经死了：手动换一张（正常过期也会自动换）
                session != null -> TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.aqr_new_code)) }
            }
        },
        dismissButton = {
            if (session != null || failed != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.aqr_close)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun QrLoginContent(
    session: AliyunQrLogin.QrSession?,
    status: ScanStatus?,
    saving: Boolean,
    failed: String?,
    waitedSeconds: Int,
    pollFailures: Int,
    pollError: String?,
    onOpenWebTool: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            failed != null -> {
                Text(failed, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.aqr_fail_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onOpenWebTool) { Text(stringResource(R.string.aqr_web_token)) }
            }
            saving -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.aqr_saving), modifier = Modifier.padding(top = 12.dp))
            }
            session == null -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text(
                    if (waitedSeconds > 3) stringResource(R.string.aqr_generating_waited, waitedSeconds)
                    else stringResource(R.string.aqr_generating),
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (waitedSeconds > 10) {
                    Text(
                        stringResource(R.string.aqr_generating_slow),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            else -> {
                QrCodeImage(session.qrContent, modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    when (status) {
                        ScanStatus.SCANED -> stringResource(R.string.aqr_status_scanned)
                        else -> stringResource(R.string.aqr_status_scan)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (pollFailures > 1) {
                    Text(
                        if (pollError != null) stringResource(R.string.aqr_poll_retry_detail, pollFailures, pollError!!)
                        else stringResource(R.string.aqr_poll_retry, pollFailures),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.aqr_scan_tips),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 本地渲染二维码（ZXing → Bitmap → Compose Image），不依赖任何图片服务。
 *
 * 关键点全在 [AliyunQrCode] 里：静区 4 个模块、整数倍放大、整像素边界。
 *
 * **位图与视图必须逐像素 1:1**：位图尺寸是「模块数 × 整数倍」，几乎不可能恰好等于
 * 弹层宽度，如果让 `Image` 把位图缩放到视图尺寸（哪怕 1.06 倍的最近邻），模块边界
 * 会出现 ±1 像素的不均匀 —— 摄像头扫屏本来就是最难的一档，再叠加重采样更容易翻车。
 * 所以这里反过来：先按可用像素宽算好位图，再把视图定成位图的**精确**尺寸，
 * 全链路零缩放、零重采样。
 *
 * 二维码尽量大（占满弹层宽）：模块越大，摄像头越容易对上焦、越不怕摩尔纹。
 */
@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val availPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(AliyunQrCode.MIN_SCALE * 8)
        val bitmap = remember(content, availPx) {
            val spec = AliyunQrCode.spec(content, availPx)
            Bitmap.createBitmap(spec.pixelSize, spec.pixelSize, Bitmap.Config.ARGB_8888).apply {
                setPixels(
                    AliyunQrCode.argb(spec),
                    0,
                    spec.pixelSize,
                    0,
                    0,
                    spec.pixelSize,
                    spec.pixelSize,
                )
            }
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.aqr_cd_qr),
            // 视图尺寸 = 位图尺寸：1:1，没有任何缩放
            modifier = Modifier.size(with(density) { bitmap.width.toDp() }),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}
