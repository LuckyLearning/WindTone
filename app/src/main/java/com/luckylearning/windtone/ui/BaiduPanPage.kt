package com.luckylearning.windtone.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.baidu.BaiduAuth
import com.luckylearning.windtone.data.baidu.BaiduDeviceCode
import com.luckylearning.windtone.data.baidu.BaiduEntry
import com.luckylearning.windtone.data.baidu.BaiduPan
import com.luckylearning.windtone.data.baidu.BaiduRules
import com.luckylearning.windtone.data.baidu.DevicePoll
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 百度网盘（v1.1.39）—— 扫码授权 + 目录浏览 + 在线播放。
 *
 * 交互上刻意只保留「授权 / 浏览 / 播放 / 调起网盘 App」四个动作：
 *
 *  · 授权用**设备码 + 二维码**（不是内嵌网页登录）：车机上没有键盘，让用户拿手机扫一下
 *    是最短路径；网页授权还要处理 redirect_uri，客户端拿不到。
 *  · 只读：不提供上传/删除/重命名。能力越小越不容易触发风控，也更好解释权限。
 *  · 直链每次播放前现取：dlink 带时效签名，缓存复用必然过期。
 */
@Composable
internal fun BaiduPanPage(
    onPlay: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    val auth = remember { BaiduAuth(context) }
    val pan = remember(auth) { BaiduPan(auth) }
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(auth.isLoggedIn()) }
    var nickname by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<BaiduEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }

    fun load(dir: String) {
        loading = true
        error = null
        scope.launch {
            pan.list(dir)
                .onSuccess {
                    entries = it
                    path = dir
                }
                .onFailure { error = it.message ?: "列目录失败" }
            loading = false
        }
    }

    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        pan.nickname().onSuccess { nickname = it }
        load(path)
    }

    SectionTitle("百度网盘")
    if (!auth.isConfigured()) {
        Text(
            "当前构建未配置百度网盘应用凭据（AppKey / SecretKey），这个入口无法使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    if (!loggedIn) {
        Text(
            "用手机百度网盘 App 扫码授权后，即可直接播放网盘里的音乐。" +
                "注意：开放平台只允许应用访问「我的应用数据/<应用名>」目录，音乐需要放在那里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ActionRow(
            title = "扫码授权",
            subtitle = "生成二维码，用手机百度网盘 App 扫一下确认（不需要在车机上输入账号密码）",
            onClick = { showLogin = true },
            chevron = true,
        )
        ActionRow(
            title = "打开百度网盘 App",
            subtitle = "把音乐放进「我的应用数据」时，可以直接在网盘 App 里操作",
            onClick = { openBaiduNetdisk(context) },
            chevron = true,
        )
        if (showLogin) {
            BaiduLoginDialog(
                auth = auth,
                onDismiss = { showLogin = false },
                onAuthorized = {
                    showLogin = false
                    loggedIn = true
                },
            )
        }
        return
    }

    // ---------------------------------------------------------------- 已登录
    Text(
        text = "已登录" + if (nickname.isNotBlank()) "：$nickname" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { load(path) }) { Text("刷新") }
        BaiduRules.parentPath(path)?.let { parent ->
            TextButton(onClick = { load(parent) }) { Text("返回上级") }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { openBaiduNetdisk(context) }) { Text("打开网盘 App") }
        TextButton(onClick = {
            auth.signOut()
            loggedIn = false
            entries = emptyList()
            path = "/"
            nickname = ""
        }) { Text("退出登录") }
    }
    Text(
        text = "当前位置：$path",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    val audioEntries = entries.filter { it.isPlayableAudio }
    if (audioEntries.isNotEmpty()) {
        TextButton(
            enabled = !loading,
            onClick = {
                loading = true
                scope.launch {
                    val links = pan.dlinks(audioEntries.map { it.fsId })
                    links.onSuccess { map ->
                        val songs = pan.toSongs(audioEntries, map)
                        if (songs.isEmpty()) {
                            error = "没有取到可播放的直链（文件可能被移出或没有权限）"
                        } else {
                            onPlay(songs, 0)
                        }
                    }.onFailure { error = it.message ?: "获取播放地址失败" }
                    loading = false
                }
            },
        ) { Text("播放本目录全部音频（${audioEntries.size}）") }
    }

    if (loading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("正在读取网盘…", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (!loading && entries.isEmpty()) {
        Text(
            "这个目录里没有内容。如果提示无权限，请把音乐放到「我的应用数据/<应用名>」目录后再试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    entries.forEach { entry ->
        BaiduEntryRow(
            entry = entry,
            onOpenDir = { load(entry.path) },
            onPlayFile = {
                loading = true
                scope.launch {
                    pan.dlinks(audioEntries.map { it.fsId })
                        .onSuccess { map ->
                            val songs = pan.toSongs(audioEntries, map)
                            if (songs.isEmpty()) {
                                error = "没有取到可播放的直链（文件可能被移出或没有权限）"
                            } else {
                                val idx = songs.indexOfFirst { it.title == BaiduRules.displayTitle(entry.name) }
                                onPlay(songs, idx.coerceAtLeast(0))
                            }
                        }
                        .onFailure { error = it.message ?: "获取播放地址失败" }
                    loading = false
                }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("没能完成") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("好") } },
        )
    }
}

@Composable
private fun BaiduEntryRow(
    entry: BaiduEntry,
    onOpenDir: () -> Unit,
    onPlayFile: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (entry.isDir) "📁 ${entry.name}" else entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    formatByteSize(entry.sizeBytes) +
                        if (!entry.isPlayableAudio) " · 格式暂不支持播放" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.isDir) {
            TextButton(onClick = onOpenDir) { Text("进入") }
        } else if (entry.isPlayableAudio) {
            TextButton(onClick = onPlayFile) { Text("播放") }
        }
    }
}

/**
 * 扫码授权弹层。
 *
 * 三个必须处理的细节：
 *  1. 轮询间隔取服务端返回的 interval（≥5 秒），过快会被 slow_down；
 *  2. 二维码图片要**自己下载解码**（`qrcode_url` 是图片地址），失败就退回显示 user_code；
 *  3. 二维码过期（expired_token）要能重新生成，而不是让用户关掉重进。
 */
@Composable
private fun BaiduLoginDialog(
    auth: BaiduAuth,
    onDismiss: () -> Unit,
    onAuthorized: () -> Unit,
) {
    var code by remember { mutableStateOf<BaiduDeviceCode?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf("正在申请授权二维码…") }
    var busy by remember { mutableStateOf(true) }

    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        busy = true
        status = "正在申请授权二维码…"
        bitmap = null
        auth.requestDeviceCode()
            .onSuccess {
                code = it
                bitmap = runCatching { fetchBitmap(it.qrcodeUrl) }.getOrNull()
                status = if (bitmap != null) {
                    "请用手机百度网盘 App 扫描二维码"
                } else {
                    "二维码图片没加载出来，请用下方地址与代码手动授权"
                }
                busy = false
            }
            .onFailure {
                status = it.message ?: "申请二维码失败"
                busy = false
            }
    }

    // 轮询：只有拿到 device_code 才开始，且按服务端 interval 走
    LaunchedEffect(code?.deviceCode) {
        val c = code ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + c.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(c.intervalSec * 1000L)
            when (val r = auth.poll(c.deviceCode)) {
                is DevicePoll.Ready -> {
                    auth.save(r.token)
                    status = "授权成功"
                    onAuthorized()
                    return@LaunchedEffect
                }
                DevicePoll.SlowDown -> status = "轮询太快，正在放慢…"
                DevicePoll.Expired -> {
                    status = "二维码已过期，请重新生成"
                    return@LaunchedEffect
                }
                DevicePoll.Denied -> {
                    status = "已拒绝授权"
                    return@LaunchedEffect
                }
                DevicePoll.Pending -> Unit
            }
        }
        status = "等待超时，请重新生成二维码"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码授权百度网盘") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "授权二维码",
                        modifier = Modifier.size(220.dp),
                    )
                } ?: run {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                code?.let { c ->
                    if (c.userCode.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "或在浏览器打开 ${c.verificationUrl}，输入 ${c.userCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "扫码并确认后请稍等几秒，不要立刻切走网盘 App。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { reloadKey++ },
            ) { Text("重新生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 二维码是普通图片地址，自己拉一次即可，不为这一张图引入图片库。
 *
 * ⚠️ 必须在 IO 线程：`LaunchedEffect` 跑在主线程，直接在这里发请求会抛
 * `NetworkOnMainThreadException`；被 `runCatching` 吞掉后表现就是「二维码区域一片空白」，
 * 而且不报任何错 —— 2026-10-21 在模拟器上就是这样复现的。
 */
private suspend fun fetchBitmap(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", "pan.baidu.com")
    }
    try {
        if (conn.responseCode !in 200..299) return@withContext null
        val bytes = conn.inputStream.use { it.readBytes() }
        return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } finally {
        runCatching { conn.disconnect() }
    }
}

/**
 * 调起本机百度网盘 App。
 *
 * 先按包名拉起（`com.baidu.netdisk`），失败再退回 `bdnetdisk://` scheme ——
 * 车机上常见「装了但包名被改过」的情况，单一方式会直接失败且没有任何反馈。
 */
private fun openBaiduNetdisk(context: Context) {
    val byPackage = Intent().apply {
        setClassName("com.baidu.netdisk", "com.baidu.netdisk.ui.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val started = runCatching { context.startActivity(byPackage) }.isSuccess ||
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("bdnetdisk://")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess
    if (!started) {
        android.widget.Toast.makeText(context, "没有找到百度网盘 App", android.widget.Toast.LENGTH_SHORT).show()
    }
}
