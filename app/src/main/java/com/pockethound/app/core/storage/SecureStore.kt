package com.pockethound.app.core.storage

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guarda os segredos do pareamento (token do PC e chave P2P).
 *
 * Tink com chave-mestra no Android Keystore — EncryptedSharedPreferences está
 * deprecado e não é usado aqui.
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sharedPreferences = context.getSharedPreferences("ph_secure_values", Context.MODE_PRIVATE)
    private val aead: Aead

    init {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "pockethound_tink_keyset", "ph_secure_keyset")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://pockethound_master_key")
            .build()
            .keysetHandle
        aead = keysetHandle.getPrimitive(Aead::class.java)
    }

    fun readToken(): String? = readSecret(KEY_TOKEN)

    fun writeToken(value: String) = writeSecret(KEY_TOKEN, value)

    /** Chave privada/identidade do endpoint P2P, guardada em base64. */
    fun readP2pKey(): ByteArray? = readSecret(KEY_P2P)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun writeP2pKey(value: ByteArray) =
        writeSecret(KEY_P2P, Base64.encodeToString(value, Base64.NO_WRAP))

    fun clear() {
        sharedPreferences.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_P2P)
            .apply()
    }

    private fun readSecret(key: String): String? {
        val stored = sharedPreferences.getString(key, null) ?: return null
        // O nome da chave entra como dados associados: um blob movido de lugar não decifra.
        val plaintext = aead.decrypt(Base64.decode(stored, Base64.DEFAULT), key.toByteArray())
        return plaintext.decodeToString()
    }

    private fun writeSecret(key: String, value: String) {
        val ciphertext = aead.encrypt(value.encodeToByteArray(), key.toByteArray())
        sharedPreferences.edit()
            .putString(key, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_P2P = "p2p_key"
    }
}
