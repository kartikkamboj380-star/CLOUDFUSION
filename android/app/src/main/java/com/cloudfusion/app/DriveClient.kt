package com.cloudfusion.app

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun JSONObject.text(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optString(key).toLongOrNull()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

class DriveException(val code: Int, message: String) : Exception(message)
sealed interface UploadReply {
    data class Incomplete(val offset: Long) : UploadReply
    data class Complete(val file: JSONObject) : UploadReply
}

/** HTTPS-only, fixed Google API hosts in production. No redirects or request logging. */
class DriveClient internal constructor(
    private val base: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
    private val allowedSession: (HttpUrl) -> Boolean = ::isTrustedGoogleSession,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
) {
    companion object {
        private const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime,version,parents,webViewLink,owners(emailAddress,displayName)"
        fun isTrustedGoogleSession(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.host in setOf("www.googleapis.com", "content.googleapis.com", "upload.googleapis.com") &&
            (url.encodedPath == "/upload/drive/v3/files" || url.encodedPath.startsWith("/upload/drive/v3/files/"))
    }

    private fun url(vararg segments: String): HttpUrl.Builder {
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder
    }
    private fun drive(vararg tail: String): HttpUrl.Builder = url("drive", "v3", *tail)
    private fun authorized(token: String, target: HttpUrl): Request.Builder =
        Request.Builder().url(target).header("Authorization", "Bearer $token").header("Accept", "application/json")
    private fun jsonBody(json: JSONObject) = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun checked(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw failure(code)
        }
        return response
    }

    private fun failure(code: Int): DriveException = DriveException(code, when (code) {
        401 -> "Google authorization expired. Reconnect this account."
        403 -> "Google denied access or the Drive quota/policy does not allow this action."
        404, 410 -> "Google could not find the item or the upload session has expired. Check Drive before starting a replacement."
        429 -> "Google Drive is rate limiting requests. Try again later."
        in 500..599 -> "Google Drive is temporarily unavailable. Retry or resume later."
        else -> "Google Drive request failed (HTTP $code)."
    })

    private fun responseJson(response: Response): JSONObject = response.use {
        val body = it.body?.string().orEmpty()
        if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    fun about(token: String): JSONObject {
        val target = drive("about").addQueryParameter("fields", "user(emailAddress,displayName,permissionId),storageQuota(limit,usage,usageInDrive,usageInDriveTrash)").build()
        return responseJson(checked(authorized(token, target).get().build()))
    }

    fun listFiles(token: String, search: String, type: String, sort: String, direction: String, pageToken: String?): JSONObject {
        val order = when (sort) { "name" -> "name"; "size" -> "quotaBytesUsed"; else -> "modifiedTime" }
        val target = drive("files").addQueryParameter("q", buildDriveQuery(search, type))
            .addQueryParameter("pageSize", "30").addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
            .addQueryParameter("orderBy", order + if (direction == "desc") " desc" else "")
            .addQueryParameter("supportsAllDrives", "true").addQueryParameter("includeItemsFromAllDrives", "true")
            .apply { if (!pageToken.isNullOrBlank()) addQueryParameter("pageToken", pageToken) }.build()
        return responseJson(checked(authorized(token, target).get().build()))
    }

    fun getFile(token: String, id: String): JSONObject {
        val target = drive("files", id).addQueryParameter("fields", FILE_FIELDS).addQueryParameter("supportsAllDrives", "true").build()
        return responseJson(checked(authorized(token, target).get().build()))
    }

    fun updateFile(token: String, id: String, json: JSONObject, addParent: String? = null, removeParents: String? = null): JSONObject {
        val target = drive("files", id).addQueryParameter("fields", FILE_FIELDS).addQueryParameter("supportsAllDrives", "true")
            .apply {
                if (!addParent.isNullOrBlank()) addQueryParameter("addParents", addParent)
                if (!removeParents.isNullOrBlank()) addQueryParameter("removeParents", removeParents)
            }.build()
        return responseJson(checked(authorized(token, target).patch(jsonBody(json)).build()))
    }

    fun createFolder(token: String, name: String, parent: String): JSONObject {
        val json = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parent.isNotBlank()) json.put("parents", JSONArray().put(parent))
        val target = drive("files").addQueryParameter("fields", FILE_FIELDS).addQueryParameter("supportsAllDrives", "true").build()
        return responseJson(checked(authorized(token, target).post(jsonBody(json)).build()))
    }

    fun permissions(token: String, id: String): JSONObject {
        val all = JSONArray()
        var page: String? = null
        do {
            val target = drive("files", id, "permissions").addQueryParameter("fields", "nextPageToken,permissions(id,type,role,emailAddress,allowFileDiscovery)")
                .addQueryParameter("pageSize", "100").addQueryParameter("supportsAllDrives", "true")
                .apply { if (page != null) addQueryParameter("pageToken", page) }.build()
            val result = responseJson(checked(authorized(token, target).get().build()))
            result.optJSONArray("permissions")?.objects()?.forEach { all.put(it) }
            page = result.text("nextPageToken")
        } while (page != null)
        return JSONObject().put("permissions", all).put("file", getFile(token, id))
    }

    fun makePublic(token: String, id: String) {
        val target = drive("files", id, "permissions").addQueryParameter("supportsAllDrives", "true").build()
        val body = JSONObject().put("type", "anyone").put("role", "reader").put("allowFileDiscovery", false)
        checked(authorized(token, target).post(jsonBody(body)).build()).close()
    }

    fun revokePublic(token: String, id: String) {
        val permissions = permissions(token, id).getJSONArray("permissions").objects()
        permissions.filter { it.optString("type") == "anyone" }.forEach { permission ->
            val target = drive("files", id, "permissions", permission.getString("id"))
                .addQueryParameter("supportsAllDrives", "true").build()
            checked(authorized(token, target).delete().build()).close()
        }
    }

    fun startUpload(token: String, name: String, mime: String, total: Long): String {
        require(total in 1..MAX_TRANSFER_SIZE) { "Choose a non-empty file of up to 5 TiB." }
        val target = url("upload", "drive", "v3", "files").addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,name,size,md5Checksum,webViewLink")
            .addQueryParameter("supportsAllDrives", "true").build()
        val body = JSONObject().put("name", name).put("mimeType", mime)
        return checked(authorized(token, target).header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", total.toString()).post(jsonBody(body)).build()).use { response ->
            val location = response.header("Location") ?: throw IllegalStateException("Google did not return an upload session.")
            session(location) // Never send a token to an untrusted Location.
            location
        }
    }

    private fun session(location: String): HttpUrl {
        val url = location.toHttpUrlOrNull() ?: throw IllegalStateException("Invalid Google upload session.")
        require(allowedSession(url)) { "Google returned an untrusted upload session address." }
        return url
    }

    private fun uploadReply(request: Request, total: Long): UploadReply = client.newCall(request).execute().use { response ->
        when {
            response.code == 308 -> UploadReply.Incomplete(committedOffset(response.header("Range"), total))
            response.isSuccessful -> {
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) throw IllegalStateException("Google reported completion without file metadata. Check Drive before starting again.")
                val file = JSONObject(body)
                if (file.text("id") == null) throw IllegalStateException("Google returned incomplete file metadata. Check Drive before starting again.")
                UploadReply.Complete(file)
            }
            else -> throw failure(response.code)
        }
    }

    fun probeUpload(token: String, location: String, total: Long): UploadReply {
        val request = authorized(token, session(location)).header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody()).build()
        return uploadReply(request, total)
    }

    fun sendChunk(token: String, location: String, start: Long, total: Long, mime: String, bytes: ByteArray): UploadReply {
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_SIZE && start >= 0 && start < total && bytes.size.toLong() <= total - start)
        require(start + bytes.size == total || bytes.size % (256 * 1024) == 0) { "Non-final Drive chunks must be multiples of 256 KiB." }
        val end = start + bytes.size - 1
        val body = bytes.toRequestBody(mime.toMediaType())
        val request = authorized(token, session(location)).header("Content-Range", "bytes $start-$end/$total").put(body).build()
        return uploadReply(request, total)
    }

    /** Fetch exactly one bounded range; never accept a whole-file response to a Range request. */
    fun downloadRange(token: String, id: String, start: Long, end: Long, total: Long): ByteArray {
        require(start >= 0 && end >= start && end < total && end - start < CHUNK_SIZE)
        val target = drive("files", id).addQueryParameter("alt", "media").addQueryParameter("supportsAllDrives", "true").build()
        val request = authorized(token, target).header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity").get().build()
        return checked(request).use { response ->
            check(response.code == 206 && response.header("Content-Range") == "bytes $start-$end/$total") {
                "Google did not return the requested file range; copy stopped safely."
            }
            val expected = (end - start + 1).toInt()
            val input = response.body?.byteStream() ?: throw IllegalStateException("Empty Drive response.")
            val result = ByteArray(expected)
            var read = 0
            while (read < expected) {
                val n = input.read(result, read, expected - read)
                check(n > 0) { "The source file ended unexpectedly." }
                read += n
            }
            check(input.read() == -1) { "Google returned more data than requested." }
            result
        }
    }

    fun download(token: String, id: String): Response {
        val target = drive("files", id).addQueryParameter("alt", "media").addQueryParameter("supportsAllDrives", "true").build()
        return checked(authorized(token, target).header("Accept-Encoding", "identity").get().build())
    }
}
