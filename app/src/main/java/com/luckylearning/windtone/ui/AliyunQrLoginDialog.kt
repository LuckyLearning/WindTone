package com.luckylearning.windtone.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunQrCode
import com.luckylearning.windtone.data.aliyun.AliyunQrLogin
import com.luckylearning.windtone.data.aliyun.AliyunQrLogin.ScanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 阿里云盘**扫码授权弹层** —— 授权全程在 app 内完成，不跳浏览器、不复制粘贴。
 *
 * 复刻 OpenList-APIPages 网页工具的同一条流程（接口细节见 [AliyunQrLogin]）：
 * 生成二维码 → 轮询扫码状态 → 拿到 refresh_token 直接保存进 [AliyunAuth]。
 * 二维码用 ZXing **本地**渲染，不依赖任何图片服务。
 *
 * 状态机：
 * ```
 * 生成中(显示已等待秒数) ──成功──→ 显示二维码 ── CONFIRMED ──→ 保存 ──→ onLoggedIn()
 *      │                        │
 *      └──失败→ 显示原因 + 重试    └── EXPIRED → 重新生成
 * ```
 *
 * 两个刻意的取舍：
 *  1. **不自动静默重试**：生成失败立刻把原因摊在界面上。以前失败后默默再试，
 *     用户看到的只是「正在生成二维码…」永远转圈，分不清是慢、是死、还是根本没发出去。
 *  2. **等待秒数可见**：转圈超过 3 秒就显示「已等待 N 秒」，让「慢」和「卡死」可区分。
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

    /** 一次授权流程共用的指纹（服务端要求三步同一个值，见 AliyunQrLogin.randomFingerprint） */
    val fingerprint = remember { AliyunQrLogin.randomFingerprint() }

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
            session = AliyunQrLogin.generateQr(fingerprint)
        } catch (e: AliyunQrLogin.QrLoginException) {
            failed = e.message ?: "生成二维码失败"
        } finally {
            ticker.cancel()
        }
    }

    // 轮询扫码状态：换了 session（过期重来）就重新开始
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        while (isActive) {
            try {
                val s = AliyunQrLogin.checkLogin(current, fingerprint)
                status = s
                when {
                    s == ScanStatus.CONFIRMED -> {
                        saving = true
                        val result = AliyunQrLogin.fetchResult(current, fingerprint)
                        // 来源固定为 CS（网页版扫码登录）—— 刷新端点必须与之匹配，
                        // 混用会「能用 2 小时后永久失效」（见 AliyunTokenSource 注释）
                        auth.saveFromQrLogin(
                            accessToken = result.accessToken,
                            refreshToken = result.refreshToken,
                            expiresInSec = result.expiresInSec,
                        )
                        onLoggedIn()
                        return@LaunchedEffect
                    }
                    s == ScanStatus.EXPIRED -> {
                        // 二维码过期：重新生成一张（attempt 自增触发上面的生成逻辑）
                        session = null
                        attempt++
                        return@LaunchedEffect
                    }
                    else -> Unit
                }
            } catch (e: AliyunQrLogin.QrLoginException) {
                // 轮询偶发失败（切网 / 弱网）不该终止整个流程：等一轮再来
                status = null
            }
            delay(AliyunQrLogin.POLL_INTERVAL_MS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码登录阿里云盘") },
        text = {
            QrLoginContent(
                session = session,
                status = status,
                saving = saving,
                failed = failed,
                waitedSeconds = waitedSeconds,
                onOpenWebTool = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AliyunQrLogin.BASES.first()))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        },
        confirmButton = {
            when {
                failed != null -> TextButton(onClick = { attempt++ }) { Text("重试") }
                session != null -> TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = {
            if (session != null || failed != null) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
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
                    "扫码授权依赖第三方在线服务，连不上时可以：① 点下面「网页获取令牌」用浏览器扫码，" +
                        "拿到的刷新令牌回上一页「手动粘贴令牌」；② 换 Wi-Fi 重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onOpenWebTool) { Text("网页获取令牌") }
            }
            saving -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text("授权成功，正在保存…", modifier = Modifier.padding(top = 12.dp))
            }
            session == null -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text(
                    if (waitedSeconds > 3) "正在生成二维码…（已等待 ${waitedSeconds} 秒）"
                    else "正在生成二维码…",
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (waitedSeconds > 10) {
                    Text(
                        "网络较慢时会多等一会；若一直停在这里，关掉重试或改用「网页获取令牌」",
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
                        ScanStatus.SCANED -> "已扫码：请在手机上确认授权"
                        else -> "用阿里云盘 App 扫这个二维码"
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "二维码过期会自动换新码；应用对网盘只读，不会上传、修改或删除任何文件",
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
 * 这里只负责把算好的 ARGB 装进 `Bitmap` —— 直接给位图而不是用 Canvas 逐格画，
 * 是为了避免任何重采样（`FilterQuality.None` + 画布尺寸等于位图尺寸 = 1:1 拷贝）。
 *
 * 220dp 太小：阿里云盘 App 的扫描框偏小，深色主题下还容易把白边吃掉。
 * 取 260dp，并把白边算进图片内，保证扫码器看得到静区。
 */
@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val targetPx = with(LocalDensity.current) { QR_SIZE.roundToPx() }
    val bitmap = remember(content, targetPx) {
        val spec = AliyunQrCode.spec(content, targetPx)
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
        contentDescription = "阿里云盘扫码授权二维码",
        modifier = modifier.size(QR_SIZE),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

/** 二维码显示边长：再小扫码框对不上 */
private val QR_SIZE = 260.dp
