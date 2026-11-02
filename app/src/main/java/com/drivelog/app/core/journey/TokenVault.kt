package com.drivelog.app.core.journey

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device token, encrypted with a non-exportable Android Keystore key as the Pleos security guide asks. */
class TokenVault(context: Context) {
    private val file = File(context.filesDir, "device-token.bin")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun read(): String? = runCatching {
        if (!file.exists()) return null
        val (iv, data) = file.readText().split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrElse {
        Log.w("DriveLogStore", "token unreadable: ${it.javaClass.simpleName}")
        null
    }

    fun write(token: String?) {
        if (token == null) { file.delete(); return }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            file.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP))
        }.onFailure { Log.e("DriveLogStore", "token not stored: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "drivelog-device-token"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
