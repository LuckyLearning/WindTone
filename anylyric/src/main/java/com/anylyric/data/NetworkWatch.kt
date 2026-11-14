package com.anylyric.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * 「网回来了」这一个事件（0.2.23）。
 *
 * 为什么需要它：在线补词对同一首歌只尝试 4 次，退避 6/15/30/60 秒。车机上最常见的场景
 * 恰好把这 4 次全吃掉 —— **开机时还没连上网**（车机联网比系统启动晚十几秒，地库里则
 * 根本没信号）。等网真的通了，尝试次数已经用光，那首歌就永远停在「暂无歌词」，
 * 只有换歌或重启应用才会再试。
 *
 * 修法不是「把次数调大」（那会变成对没词的歌反复请求），而是**在网的可用性变化时把账清掉**：
 * 网一通，之前那些失败就不算数了，值得再试一次。系统恰好提供了这个事件，
 * 不需要轮询、不需要额外常驻。
 *
 * 只监听**默认网络**，只关心 `onAvailable`；具体要不要重试由 `SessionHub` 判断。
 * `ACCESS_NETWORK_STATE` 是普通权限（安装即授予，不弹窗），仅用于注册这个回调。
 */
object NetworkWatch {

    private const val TAG = "NetworkWatch"

    @Volatile private var action: (() -> Unit)? = null

    @Volatile private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            action?.invoke()
        }
    }

    /** 幂等：进程内只注册一次。注册失败（极少数 ROM 限制）时静默跳过。 */
    fun start(context: Context, onAvailable: () -> Unit) {
        action = onAvailable
        if (registered) return
        val manager = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return
        val ok = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        registered = ok
        if (!ok) Log.w(TAG, "registerDefaultNetworkCallback failed")
    }
}
