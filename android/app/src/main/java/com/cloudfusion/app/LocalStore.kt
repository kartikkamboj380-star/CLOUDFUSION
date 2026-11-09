package com.cloudfusion.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Account identifiers and resumable session URLs are encrypted; access tokens are never saved here. */
class LocalStore(private val context: Context, namespace: String = "direct_drive_v1") {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = if (namespace == "direct_drive_v1") "cloudfusion.direct.state.v1" else "cloudfusion.$namespace.state.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun read(): JSONObject {
        val encrypted = prefs.getString("state", null) ?: return JSONObject()
        val iv = prefs.getString("iv", null) ?: throw IllegalStateException("Saved state is incomplete")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
    }

    /** commit() makes a newly issued session durable before any chunk is sent. */
    fun write(data: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("state", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) {
            "Could not save local transfer state. No further data was sent."
        }
    }

    /** This direct client no longer uses the old backend URL or its encrypted bearer session. */
    fun removeLegacySession() {
        context.getSharedPreferences("cloudfusion", Context.MODE_PRIVATE).edit().clear().commit()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("cloudfusion.session") }
        } catch (_: Exception) { /* The old key may not exist. */ }
    }
}
