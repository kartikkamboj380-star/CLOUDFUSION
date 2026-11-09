package com.cloudfusion.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloudfusion", Context.MODE_PRIVATE)
    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(value) { prefs.edit().putString("server", value).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("cloudfusion.session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("cloudfusion.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    fun readToken(): String {
        return try {
            val encrypted = prefs.getString("token", null) ?: return ""
            val iv = prefs.getString("iv", null) ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { clearToken(); "" }
    }

    fun clearToken() {
        val editor = prefs.edit().remove("token").remove("iv")
        prefs.all.keys.filter { it.startsWith("uri_") }.forEach { editor.remove(it) }
        editor.apply()
    }
    fun rememberUri(id: String, uri: Uri) { prefs.edit().putString("uri_$id", uri.toString()).apply() }
    fun uriFor(id: String): Uri? = prefs.getString("uri_$id", null)?.let(Uri::parse)
    fun forgetUri(id: String) { prefs.edit().remove("uri_$id").apply() }
}

class ApiException(message: String, val code: Int) : Exception(message)

class Api(private val server: String, private val token: String) {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES).writeTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false).build()
        fun normalizeServer(raw: String): String {
            val input = raw.trim().trimEnd('/')
            val uri = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid server URL") }
            val isEmulator = BuildConfig.DEBUG && uri.scheme == "http" && uri.host == "10.0.2.2"
            require((uri.scheme == "https" || isEmulator) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                "Use an HTTPS server origin (or http://10.0.2.2 for the debug emulator)."
            }
            return input
        }
    }

    private fun build(path: String, method: String, body: ByteArray? = null, mediaType: String = "application/json", offset: Long? = null): Request {
        val builder = Request.Builder().url(normalizeServer(server) + path)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        if (offset != null) builder.header("Upload-Offset", offset.toString())
        val requestBody = if (body != null) body.toRequestBody(mediaType.toMediaType()) else null
        return builder.method(method, requestBody ?: if (method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null).build()
    }

    fun request(path: String, method: String = "GET", json: JSONObject? = null): String =
        execute(build(path, method, json?.toString()?.toByteArray())).use { it.body?.string() ?: "" }

    fun chunk(id: String, offset: Long, data: ByteArray): JSONObject =
        JSONObject(execute(build("/api/uploads/$id/chunk", "PUT", data, "application/octet-stream", offset)).use { it.body!!.string() })

    fun download(account: String, file: String): Response = execute(build("/api/files/$account/$file/download", "GET"))

    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val content = response.body?.string().orEmpty()
            val detail = try {
                when (val value = JSONObject(content).opt("detail")) {
                    is JSONArray -> (0 until value.length()).joinToString("; ") { value.optJSONObject(it)?.optString("msg") ?: "Invalid value" }
                    is JSONObject -> value.optString("message", "Request failed")
                    null -> "Request failed"
                    else -> value.toString()
                }
            } catch (_: Exception) { "Request failed" }
            val code = response.code
            response.close()
            throw ApiException(detail, code)
        }
        return response
    }
}
