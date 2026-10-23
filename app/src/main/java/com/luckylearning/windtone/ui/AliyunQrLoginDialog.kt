package com.luckylearning.windtone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunQrLogin
import com.luckylearning.windtone.data.aliyun.AliyunQrLogin.ScanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 阿里云盘**扫码授权弹层** —— 授权全程在 app 内完成，不跳浏览器、不复制粘贴。
 *
 * 复刻 OpenList-APIPages 网页工具的同一条流程（接口细节见 [AliyunQrLogin]）：
 * 生成二维码 → 轮询扫码状态 → 拿到 refresh_token 直接保存进 [AliyunAuth]。
 * 二维码用 ZXing **本地**渲染，不依赖任何图片服务。
 *
 * 状态机：
 * ```
 * Generating ──→ Showing(qr, status) ── CONFIRMED ──→ Saving ──→ onLoggedIn()
 *      ↑                │  │                (取令牌)
 *      └── EXPIRED ─────┘  └── 出错 → Failed(reason)，可重试
 * ```
 * 轮询挂在 [LaunchedEffect] 协程里，弹层关闭 = 协程取消 = 轮询停止，无泄漏。
 */
@Composable
internal fun AliyunQrLoginDialog(
    auth: AliyunAuth,
    onLoggedIn: () -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    /** 弹层阶段。session == null 且 generating = 正在生成；非空 = 展示二维码轮询中 */
    var session by remember { mutableStateOf<AliyunQrLogin.QrSession?>(null) }
    var generating by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<ScanStatus?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }

    /** 一次授权流程共用的指纹（服务端要求三步同一个值，见 AliyunQrLogin.randomFingerprint） */
    val fingerprint = remember { AliyunQrLogin.randomFingerprint() }

    // 生成二维码：初次进入 + 过期自动重来都走这里（以 session 为 key 不会死循环，
    // 过期时先把 session 置空再触发重新生成）
    LaunchedEffect(Unit) {
        while (isActive && session == null && failed == null) {
            generating = true
            try {
                session = AliyunQrLogin.generateQr(fingerprint)
                status = null
            } catch (e: AliyunQrLogin.QrLoginException) {
                failed = e.message ?: "生成二维码失败"
            }
            generating = false
            if (session == null && failed == null) delay(3_000) // 生成失败：缓 3 秒再试
        }
    }

    // 轮询扫码状态：session 换了（过期重来）就重新开始
    LaunchedEffect(session) {
        val sid = session?.sessionId ?: return@LaunchedEffect
        while (isActive) {
            try {
                val s = AliyunQrLogin.checkLogin(sid, fingerprint)
                status = s
                when {
                    s == ScanStatus.CONFIRMED -> {
                        saving = true
                        val result = AliyunQrLogin.fetchResult(sid, fingerprint)
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
                        // 二维码过期：重新生成一张（退出本轮轮询，由上面的生成循环接手）
                        session = null
                        return@LaunchedEffect
                    }
                    else -> Unit
                }
            } catch (e: AliyunQrLogin.QrLoginException) {
                // 网络抖动不终止轮询，连着多次失败再报错（每轮间隔照常，累计由用户关弹层控制）
                repeat(2) { delay(AliyunQrLogin.POLL_INTERVAL_MS) }
                status = null
            }
            delay(AliyunQrLogin.POLL_INTERVAL_MS)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码登录阿里云盘") },
        text = { QrLoginContent(session, generating, status, saving, failed) },
        confirmButton = {
            if (failed != null) {
                TextButton(onClick = {
                    failed = null
                    session = null
                    generating = true
                }) { Text("重试") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun QrLoginContent(
    session: AliyunQrLogin.QrSession?,
    generating: Boolean,
    status: ScanStatus?,
    saving: Boolean,
    failed: String?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            failed != null -> {
                Text(failed, textAlign = TextAlign.Center)
                Text(
                    "也可以退回上一页用「手动粘贴令牌」完成登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            saving -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text("授权成功，正在保存…", modifier = Modifier.padding(top = 12.dp))
            }
            session == null -> {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text("正在生成二维码…", modifier = Modifier.padding(top = 12.dp))
            }
            else -> {
                QrCodeImage(session.qrContent, modifier = Modifier.size(220.dp))
                Text(
                    when (status) {
                        ScanStatus.SCANED -> "已扫码：请在手机上确认授权"
                        else -> "用阿里云盘 App 扫这个二维码"
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "二维码过期会自动刷新；应用对网盘只读，不会上传、修改或删除任何文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** 本地渲染二维码（ZXing BitMatrix → Compose Canvas），不依赖任何图片服务 */
@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    // BitMatrix 一次算好：内容不变就不再重算
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            600,
            600,
            mapOf(EncodeHintType.MARGIN to 1),
        )
    }
    Box(modifier) {
        Canvas(Modifier.size(220.dp).align(Alignment.Center)) {
            val fg = Color(0xFF1B1B1B)
            val bg = Color.White
            drawRect(bg) // 白底：阿里云盘 App 对对比度有要求，深色主题下也不能透出底色
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) {
                        drawRect(
                            fg,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
    }
}
