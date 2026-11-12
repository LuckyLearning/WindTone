package com.anylyric.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.anylyric.session.SessionHub

/**
 * 只读旁听入口。
 *
 * 本服务由系统在用户授予「通知使用权」后绑定并常驻，AnyLyric 不主动拉活、
 * 不起前台服务、不申请电池白名单。悬浮层也挂在这个进程里，因此没有任何
 * 额外 Service 或子进程。
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
