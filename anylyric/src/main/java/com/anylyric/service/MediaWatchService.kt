package com.anylyric.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

    override fun onCreate() {
        super.onCreate()
        SessionHub.get(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        SessionHub.get(this).onListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        SessionHub.get(this).onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        SessionHub.get(this).onNotificationPosted(sbn)
    }

    override fun onDestroy() {
        SessionHub.get(this).detach()
        super.onDestroy()
    }
}
