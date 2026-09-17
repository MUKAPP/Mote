package com.mukapp.mote.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mukapp.mote.util.MoteLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 设置中敏感字段（API 密钥）的落盘编解码。 */
interface SecretCodec {
    fun encode(plain: String): String
    fun decode(stored: String): String
}

/** 明文透传，用于 JVM 单元测试等无 Android Keystore 的环境。 */
object PlainSecretCodec : SecretCodec {
    override fun encode(plain: String): String = plain
    override fun decode(stored: String): String = stored
}

/**
 * Android Keystore AES-GCM 编解码。密文格式 `enc1:` + Base64(IV ‖ 密文)；
 * 无前缀的值按旧版明文原样返回，保证升级前存量数据可读。
 * 密钥不可导出，设备上其他进程即使读到偏好文件也无法解密。
 */
object KeystoreSecretCodec : SecretCodec {
    private const val Component = "Settings"
    private const val KeyAlias = "mote_settings_secret"
    private const val Prefix = "enc1:"
    private const val AndroidKeyStoreName = "AndroidKeyStore"
    private const val Transformation = "AES/GCM/NoPadding"
    private const val GcmTagBits = 128
    private const val GcmIvLength = 12

    fun isEncoded(stored: String): Boolean = stored.startsWith(Prefix)

    override fun encode(plain: String): String {
        if (plain.isEmpty()) {
            return plain
        }
        return runCatching {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Prefix + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrElse { error ->
            // Keystore 在个别设备上可能暂时不可用；此时保留明文可读性优先于加密，下次保存再尝试升级。
            MoteLog.w(Component, "密钥加密失败，本次按明文保存。", error)
            plain
        }
    }

    override fun decode(stored: String): String {
        if (!isEncoded(stored)) {
            return stored
        }
        return runCatching {
            val payload = Base64.decode(stored.removePrefix(Prefix), Base64.NO_WRAP)
            require(payload.size > GcmIvLength) { "密文长度不足。" }
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(GcmTagBits, payload, 0, GcmIvLength)
            )
            String(cipher.doFinal(payload, GcmIvLength, payload.size - GcmIvLength), Charsets.UTF_8)
        }.getOrElse { error ->
            MoteLog.w(Component, "密钥解密失败，该密钥需要重新填写。", error)
            ""
        }
    }

    @Synchronized
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStoreName).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStoreName)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
