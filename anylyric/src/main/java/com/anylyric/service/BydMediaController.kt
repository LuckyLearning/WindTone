package com.anylyric.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.anylyric.session.SessionHub

/**
 * BYD 第三方媒体控制 SDK（只读旁听，仅 BYD 车机生效）。
 *
 * 为什么需要它：BYD 车机的标准 `MediaSession` 经常很稀疏 —— 有的播放器只把歌词写进
 * `MediaMetadata`，TITLE/ARTIST 为空；有的干脆不发布标准会话。BYD 自己的媒体中心
 * 通过一个**面向第三方应用**的 Binder 服务把完整元数据推出来：
 *
 *  · 包名 `com.byd.mediacontroller`，action `com.byd.mediacontroller.thirdapp.controller`；
 *  · 接口 `com.byd.mediacontroller.sdk.IMediaControllerSdkInterface`，
 *    注册时 `transact(1)`，参数是「本应用包名 + 监听 Binder」；
 *  · 回调接口 `...IMediaControllerSdkListener`，事务码 4 是一个 Bundle：
 *    TITLE / ARTIST / ALBUM / DURATION / LYRICS（含 `com.byd.mediacenter.metadata.LYRICS`）。
 *
 * 只读、不申请任何系统权限、不调播放控制；服务不存在或不可绑时静默跳过。
 * 绑定生命周期跟随 [MediaWatchService]，服务销毁即解绑。
 */
object BydMediaController {

    private const val TAG = "BydMediaController"
    private const val PACKAGE = "com.byd.mediacontroller"
    private const val ACTION = "com.byd.mediacontroller.thirdapp.controller"
    private const val INTERFACE = "com.byd.mediacontroller.sdk.IMediaControllerSdkInterface"
    private const val LISTENER = "com.byd.mediacontroller.sdk.IMediaControllerSdkListener"
    private const val TRANSACT_REGISTER = 1

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var bound = false
    @Volatile private var deathRecipient: IBinder.DeathRecipient? = null

    /** 回调码 4 的解析结果直接交给 SessionHub；解析失败不影响主链路。 */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER)
                return true
            }
            try {
                data.enforceInterface(LISTENER)
                when (code) {
                    // 注册确认：读掉 int，无业务含义
                    1 -> data.readInt()
                    // 封面 Bundle：本应用不加载封面，读掉即可
                    2 -> if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data)
                    // 播放状态：标准会话通常也有，这里只消费不采用
                    3 -> if (data.readInt() != 0) {
                        android.media.session.PlaybackState.CREATOR.createFromParcel(data)
                    }
                    // 元数据 Bundle：歌名 / 歌手 / 专辑 / 时长 / 歌词
                    4 -> {
                        val bundle = if (data.readInt() != 0) {
                            Bundle.CREATOR.createFromParcel(data)
                        } else {
                            null
                        }
                        if (bundle != null) {
                            bundle.classLoader = BydMediaController::class.java.classLoader
                            deliver(bundle)
                        }
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
                reply?.writeNoException()
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "callback failed code=$code", t)
                return false
            }
        }
    }

    /** 服务创建时调用；可重复调用，只有第一次真正绑定。 */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        val intent = Intent(ACTION).setPackage(PACKAGE)
        val resolves = runCatching {
            app.packageManager.resolveService(intent, 0) != null
        }.getOrDefault(false)
        // 非 BYD 设备且解析不到服务：直接跳过，不产生任何绑定尝试。
        if (!resolves && !isBydDevice()) return

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) return
                try {
                    val death = IBinder.DeathRecipient {
                        Log.w(TAG, "BYD media controller died")
                        bound = false
                    }
                    deathRecipient = death
                    service.linkToDeath(death, 0)

                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(INTERFACE)
                        data.writeString(app.packageName)
                        data.writeStrongBinder(callback)
                        service.transact(TRANSACT_REGISTER, data, reply, 0)
                        reply.readException()
                        Log.i(TAG, "BYD media controller registered")
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "register failed", t)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = false
            }

            override fun onBindingDied(name: ComponentName?) {
                bound = false
            }

            override fun onNullBinding(name: ComponentName?) {
                bound = false
            }
        }
        connection = conn
        runCatching {
            bound = app.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w(TAG, "bind failed", it)
            connection = null
        }
        appContext = app
    }

    /** 服务销毁时解绑，避免进程退出后留下悬挂连接。 */
    fun stop() {
        val app = appContext ?: return
        val conn = connection
        if (bound && conn != null) {
            runCatching { app.unbindService(conn) }
        }
        bound = false
        connection = null
        deathRecipient = null
        appContext = null
    }

    private fun deliver(bundle: Bundle) {
        val title = firstText(
            bundle,
            "android.media.metadata.TITLE",
            "android.media.metadata.DISPLAY_TITLE",
            "title",
            "song"
        )
        val artist = firstText(
            bundle,
            "android.media.metadata.ARTIST",
            "android.media.metadata.ALBUM_ARTIST",
            "android.media.metadata.DISPLAY_SUBTITLE",
            "artist"
        )
        val album = firstText(bundle, "android.media.metadata.ALBUM", "album")
        val duration = firstLong(bundle, "android.media.metadata.DURATION", "duration")
        val lyrics = firstText(
            bundle,
            "com.byd.mediacenter.metadata.LYRICS",
            "android.media.metadata.LYRICS",
            "lyrics"
        )
        val ctx = appContext ?: return
        main.post { SessionHub.get(ctx).onBydMetadata(title, artist, album, duration, lyrics) }
    }

    // Bundle.get 在新 SDK 被标弃用（类型不安全）；这里正是要按实际类型兼容 Number/String。
    @Suppress("DEPRECATION")
    private fun firstLong(bundle: Bundle, vararg keys: String): Long {
        for (key in keys) {
            when (val value = bundle.get(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun firstText(bundle: Bundle, vararg keys: String): String? {
        for (key in keys) {
            val text = bundle.getCharSequence(key)?.toString()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun isBydDevice(): Boolean {
        val name = "${Build.BRAND} ${Build.MANUFACTURER} ${Build.MODEL}".lowercase()
        return name.contains("byd") || name.contains("dilink")
    }
}
