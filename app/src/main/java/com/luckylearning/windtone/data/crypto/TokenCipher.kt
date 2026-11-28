package com.luckylearning.windtone.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 加密本地敏感字符串（云盘 refresh token）。
 *
 * ## 为什么不用 EncryptedSharedPreferences
 * `androidx.security:security-crypto` 不在当前依赖树里，新增依赖会让发版 CI
 * 重新解析 Maven（v1.3.9 的 429 事故）。这里用 JDK + AndroidKeyStore 自带的
 * AES-GCM，零新依赖即可达到同样目标：密钥材料留在 Keystore，不可导出。
 *
 * ## 存储格式
 * 密文以 Base64 字符串落进 SharedPreferences，形如 `enc:v1:<base64>`，
 * 前缀用于区分「历史明文」以便首次读取时自动迁移。GCM 的 IV 每次随机生成，
 * 拼在密文之前（12 字节），不额外占一个字段。
 *
 * ## 兼容性
 * 密钥保存在应用私有 Keystore 条目里，取不出明文密钥材料。项目不做备份/导入导出，
 * 换机后新密钥重新生成、token 需重新授权，与「不做跨设备数据兼容」口径一致。
 */
object TokenCipher {
    private const val TAG = "TokenCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "windtone_token_key"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 明文 → 可落盘字符串；Keystore 不可用时退回原文（绝不因为加密失败让用户登不上） */
    fun encrypt(plain: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + cipherText.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(cipherText, 0, it, iv.size, cipherText.size)
        }
        PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    } catch (t: Throwable) {
        Log.w(TAG, "Keystore 不可用，退回明文存储", t)
        plain
    }

    /**
     * 密文 → 明文；非 enc 前缀的历史明文原样返回（由调用方迁移）；
     * 解密失败返回 null —— 继续用坏值只会导致反复 401，必须重新授权。
     */
    fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (payload.size <= IV_BYTES) return null
            val iv = payload.copyOfRange(0, IV_BYTES)
            val cipherText = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "解密失败（密钥丢失或数据损坏）", t)
            null
        }
    }

    /** 是不是历史明文（需要在读到后加密重写） */
    fun isPlaintext(stored: String?): Boolean =
        !stored.isNullOrEmpty() && !stored.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
        return ks.getKey(ALIAS, null) as SecretKey
    }
}
