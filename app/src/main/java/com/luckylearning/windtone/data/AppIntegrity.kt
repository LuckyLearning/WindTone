package com.luckylearning.windtone.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.luckylearning.windtone.BuildConfig
import java.security.MessageDigest

/**
 * 安装包完整性（v1.1.97）。
 *
 * ## 解决什么问题
 *
 * 这个项目是公开源码，混淆只能抬高逆向成本；真正能对付「拿官方 APK 改个名 / 塞后门再分发」的是**签名**——
 * 改包者必须重签名才能装到别人机器上，重签名就必然与官方证书不符。
 *
 * ## 做法与边界
 *
 * 启动时读自己的签名证书 SHA-256，与内置白名单比对；不符时**不崩溃、不封禁播放**，
 * 只做两件有实际意义的事：
 *  1. **停掉自更新**（改包版拿不到后续版本，也就没有持续分发价值）；
 *  2. 在「关于与诊断」页与诊断包里**如实标注**为非官方构建。
 *
 * 为什么不封更多功能：本仓库允许任何人自行编译（甚至二次开发），把播放/歌词这些核心能力封掉
 * 会误伤自编译用户，而改包者的真实收益主要来自"能持续更新、能挂广告"，掐更新链路才是要害。
 *
 * ## 与加固的关系
 *
 * 签名校验是加固的替代方案之一，且不改变 APK 签名、不影响覆盖安装 —— 这也是本版**不做加固**的原因。
 */
object AppIntegrity {

    private const val TAG = "WindToneIntegrity"

    /**
     * 官方发布证书的 SHA-256（大写十六进制，无分隔符）。
     *
     * ⚠️ 换签名证书时必须同步改这里，否则官方包会被自己判成"非官方"。
     * 该值与 `scripts/verify-release.sh` / `scripts/verify_apk.py` 校验的是同一个指纹。
     */
    private val TRUSTED_CERT_SHA256 = setOf(
        "75EAECCD7CAB0DB4E30170F52091B3BF80AC0F1D6F89235433EBE2B5876E7398",
    )

    /** 是否为官方签名构建；debug 包恒为 true（不干扰开发） */
    @Volatile
    var isOfficial: Boolean = true
        private set

    /** 当前 APK 的签名证书指纹；取不到时为 null */
    @Volatile
    var certSha256: String? = null
        private set

    /** 在 `Application.onCreate` 里调用一次即可（诊断页与更新检查都读这里的结果） */
    fun evaluate(context: Context) {
        if (BuildConfig.DEBUG) {
            isOfficial = true
            certSha256 = runCatching { readCertSha256(context) }.getOrNull()
            return
        }
        val sha = runCatching { readCertSha256(context) }.getOrNull()
        certSha256 = sha
        isOfficial = sha != null && sha in TRUSTED_CERT_SHA256
        if (!isOfficial) {
            Log.w(TAG, "非官方签名构建：cert=$sha，已停用自更新")
        }
    }

    /** 给用户/诊断看的一句话 */
    fun describe(): String = when {
        isOfficial -> "官方签名"
        certSha256 == null -> "无法读取签名（已停用自更新）"
        else -> "非官方签名（${certSha256!!.take(12)}…，已停用自更新）"
    }

    private fun readCertSha256(context: Context): String? {
        val pm = context.packageManager
        val pkg = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val bytes = signatures?.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }
}
