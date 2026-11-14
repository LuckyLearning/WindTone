package com.anylyric.service

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.anylyric.diag.Probe
import com.anylyric.session.SessionHub

/**
 * 只读旁听入口。
 *
 * 本服务由系统在用户授予「通知使用权」后绑定并常驻，AnyLyric 不主动拉活、
 * 不起前台服务。悬浮层也挂在这个进程里，因此没有任何额外 Service 或子进程。
 * 0.2.7 起有两项可选的保活手段（默认关闭，用户在设置里打开才生效）：
 * 「开机自启」由 [BootReceiver] 在开机广播后 requestRebind；「后台保活」是
 * 用户手动申请的电池优化白名单，服务本身不改变行为。
 */
class MediaWatchService : NotificationListenerService() {

    /**
     * 活动会话变化推送（0.2.23，参照 Cardash 的 `MusicLyricsListenerService`）。
     *
     * 授权之后 `MediaSessionManager` 会把「谁开始放、谁走了」直接推过来。之前
     * AnyLyric 只能靠通知 / 定时查询去猜，换播放器要等好几秒才跟上。
     * 回调只转手一次，会话怎么挑仍然由 [SessionHub] 决定。
     */
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        SessionHub.get(this).onActiveSessionsChanged(list?.size ?: 0)
    }

    /** 只注册一次：`onListenerConnected` 在系统重连时会重复调用。 */
    private var sessionsListening = false

    override fun onCreate() {
        super.onCreate()
        // attach() 以前没有任何调用点，等于「服务起来时主动拉一次会话」这条链路从没生效过。
        // 现在补上：进程被杀后重启、通知服务又没绑（degraded）时，靠它作为第一个触发点；
        // SessionHub 的 init 也投了一次，两处都幂等（requestRefresh 会合并）。
        SessionHub.get(this).attach()
        // 服务被系统拉起来时顺带确认一次看门狗任务是否已注册（开关关闭时是 cancel）。
        ServiceWatchdog.sync(this)
        // BYD 车机：标准会话稀疏时，靠第三方媒体控制 SDK 补真实歌名/歌手/歌词。
        // 非 BYD 设备或服务不可绑时内部静默跳过，不产生任何副作用。
        BydMediaController.start(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        SessionHub.get(this).onListenerConnected()
        startSessionWatch()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        stopSessionWatch()
        SessionHub.get(this).onListenerDisconnected()
    }

    /**
     * 订阅「活动会话变了」。
     *
     * 必须带本服务自己的 `ComponentName`：这是通知使用权持有者的身份证明，
     * 不带它（或换成别的组件）系统会直接拒绝。抛异常时静默降级 ——
     * 没有这条推送只是回到 0.2.23 的行为，功能本身不受影响。
     */
    private fun startSessionWatch() {
        if (sessionsListening) return
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(this, MediaWatchService::class.java)
        val ok = runCatching {
            manager.addOnActiveSessionsChangedListener(
                sessionsListener,
                component,
                Handler(Looper.getMainLooper())
            )
        }.isSuccess
        sessionsListening = ok
        if (Probe.enabled) {
            Probe.write("会话", "activeSessionsListener=${if (ok) "on" else "failed"}")
        }
    }

    private fun stopSessionWatch() {
        if (!sessionsListening) return
        val manager = getSystemService(MediaSessionManager::class.java)
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        sessionsListening = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        SessionHub.get(this).onNotificationPosted(sbn)
    }

    override fun onDestroy() {
        stopSessionWatch()
        SessionHub.get(this).detach()
        // **不解绑 BYD 通道**：这个服务被销毁只说明通知服务断开（用户关了授权、
        // 或系统回收绑定），而直连通道不需要那个授权，正要在这种时候继续工作。
        // 进程真的退出时 binder 自然断开，不需要手动 stop。
        super.onDestroy()
    }
}
