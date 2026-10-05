package com.kingzcheung.xime.plugin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 插件配置值加解密抽象（set 加密 / get 解密）。 */
interface ValueCipher {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String?
}

/**
 * Android Keystore AES-GCM 实现：密钥不可导出（硬件隔离），
 * 密文格式 `enc:` + base64(iv + ciphertext)，GCM 认证失败视为无效返回 null。
 * 明文（无 `enc:` 前缀）原样返回，兼容历史数据。
 */
object SecureValueCipher : ValueCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "xime_plugin_config"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "enc:"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let {
                cachedKey = it
                return it
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey().also { cachedKey = it }
        }
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val blob = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(cipherText, 0, blob, iv.size, cipherText.size)
        return PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (blob.size < IV_BYTES) return null
            val iv = blob.copyOfRange(0, IV_BYTES)
            val cipherText = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
