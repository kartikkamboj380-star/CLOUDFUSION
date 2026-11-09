package com.cloudfusion.app

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(value: Long?): String {
    if (value == null || value < 0) return "Unavailable"
    if (value < 1024) return "$value B"
    val exponent = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
    return String.format(Locale.US, "%.1f %s", value / 1024.0.pow(exponent.toDouble()), listOf("B", "KiB", "MiB", "GiB", "TiB")[exponent])
}

fun JSONObject.text(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

data class DocumentInfo(val uri: Uri, val name: String, val size: Long, val mime: String)
data class PendingUpload(val transfer: JSONObject, val document: DocumentInfo)

class CloudViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    var server by mutableStateOf(store.server); private set
    var token by mutableStateOf(store.readToken()); private set
    var email by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var dashboard by mutableStateOf<JSONObject?>(null); private set
    var accounts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var files by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var transfers by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var nextOffset by mutableStateOf<Int?>(null); private set
    var pendingUpload by mutableStateOf<PendingUpload?>(null); private set
    var transferProgress by mutableStateOf<String?>(null); private set
    var uploadAccountId by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
    var sort by mutableStateOf("modified")
    var direction by mutableStateOf("desc")
    var fileType by mutableStateOf("all")
    private var uploadJob: Job? = null
    private var activeUploadId: String? = null
    private var downloadJob: Job? = null
    private var activeDownloadId: String? = null

    private fun api() = Api(server, token)
    fun clearMessages() { error = null; notice = null }
    fun setError(message: String) { error = message }
    private fun launch(showBusy: Boolean = true, block: suspend () -> Unit) = viewModelScope.launch {
        if (showBusy) busy = true
        try { block() } catch (_: CancellationException) {
            notice = "Transfer stopped on this device. Check Transfers to cancel or resume it."
        } catch (e: Exception) {
            error = e.message ?: "Something went wrong. Please try again."
        } finally { if (showBusy) busy = false }
    }

    fun authenticate(rawServer: String, user: String, password: String, register: Boolean) = launch {
        val normalized = Api.normalizeServer(rawServer)
        val result = withContext(Dispatchers.IO) {
            JSONObject(Api(normalized, "").request(if (register) "/api/auth/register" else "/api/auth/login", "POST",
                JSONObject().put("email", user.trim()).put("password", password)))
        }
        server = normalized; store.server = normalized
        token = result.getString("token"); store.saveToken(token)
        email = result.optString("email")
        notice = "Signed in. Connect a Google account to get started."
        refreshAll()
    }

    fun saveServer(raw: String) {
        try {
            val normalized = Api.normalizeServer(raw)
            if (normalized != server) {
                store.clearToken(); token = ""; email = ""; accounts = emptyList(); files = emptyList(); dashboard = null
            }
            server = normalized; store.server = normalized
            notice = "Server saved. Sign in again when changing servers."
        } catch (e: Exception) { error = e.message }
    }

    fun logout() = launch {
        try { withContext(Dispatchers.IO) { api().request("/api/auth/logout", "POST") } } catch (_: Exception) { }
        store.clearToken(); token = ""; email = ""; accounts = emptyList(); files = emptyList(); transfers = emptyList(); dashboard = null
        notice = "Signed out on this device."
    }

    fun refreshAll() = launch {
        val result = withContext(Dispatchers.IO) {
            val api = api()
            val me = JSONObject(api.request("/api/auth/me"))
            val acc = JSONArray(api.request("/api/accounts")).objects()
            val dash = JSONObject(api.request("/api/dashboard"))
            val trans = JSONObject(api.request("/api/transfers")).getJSONArray("items").objects()
            listOf(me, JSONArray(acc), dash, JSONArray(trans))
        }
        email = (result[0] as JSONObject).optString("email")
        accounts = (result[1] as JSONArray).objects()
        dashboard = result[2] as JSONObject
        transfers = (result[3] as JSONArray).objects()
        loadFiles()
    }

    fun refreshAccounts(remote: Boolean = false) = launch {
        val result = withContext(Dispatchers.IO) {
            val api = api()
            val list = JSONArray(api.request(if (remote) "/api/accounts/refresh" else "/api/accounts", if (remote) "POST" else "GET")).objects()
            list to JSONObject(api.request("/api/dashboard"))
        }
        accounts = result.first; dashboard = result.second
        if (remote) notice = "Account quotas refreshed."
    }

    fun connectAccount(open: (Uri) -> Unit) = launch {
        val url = withContext(Dispatchers.IO) { JSONObject(api().request("/api/oauth/start", "POST")).getString("authorization_url") }
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && uri.host == "accounts.google.com") { "Unexpected Google authorization URL" }
        open(uri)
    }

    fun disconnect(id: String) = launch {
        withContext(Dispatchers.IO) { api().request("/api/accounts/$id", "DELETE") }
        notice = "Account disconnected from CloudFusion."
        refreshAll()
    }

    fun loadFiles(more: Boolean = false, sync: Boolean = false) = launch {
        val offset = if (more) nextOffset ?: return@launch else 0
        val url = "/api/files?search=${Uri.encode(search)}&sort=$sort&direction=$direction&type=$fileType&limit=30&offset=$offset"
        val result = withContext(Dispatchers.IO) {
            val api = api()
            val syncResult = if (sync) JSONObject(api.request("/api/files/sync", "POST")) else null
            JSONObject(api.request(url)) to syncResult
        }
        val items = result.first.getJSONArray("items").objects()
        files = if (more) files + items else items
        nextOffset = if (result.first.isNull("next_offset")) null else result.first.optInt("next_offset")
        result.second?.let { synced ->
            val failures = synced.getJSONArray("accounts").objects().mapNotNull { it.text("error") }
            notice = if (failures.isEmpty()) "Files refreshed from Google Drive." else "Some accounts could not refresh: ${failures.joinToString()}"
        }
    }

    fun rename(file: JSONObject, name: String) = mutateFile(file, "PATCH", "", JSONObject().put("name", name), "File renamed.")
    fun move(file: JSONObject, parent: String) = mutateFile(file, "POST", "/move", JSONObject().put("parent_id", parent), "File moved.")
    fun trash(file: JSONObject) = mutateFile(file, "DELETE", "", null, "File moved to Google Drive trash.")
    private fun mutateFile(file: JSONObject, method: String, suffix: String, body: JSONObject?, success: String) = launch {
        withContext(Dispatchers.IO) { api().request("/api/files/${file.getString("account_id")}/${file.getString("id")}$suffix", method, body) }
        notice = success; loadFiles()
    }
    fun createFolder(accountId: String, name: String, parent: String) = launch {
        val body = JSONObject().put("account_id", accountId).put("name", name)
        if (parent.isNotBlank()) body.put("parent_id", parent.trim())
        withContext(Dispatchers.IO) { api().request("/api/files/folders", "POST", body) }
        notice = "Folder created."; loadFiles()
    }
    fun copy(file: JSONObject, destination: String) = launch {
        val body = JSONObject().put("source_account_id", file.getString("account_id"))
            .put("source_file_id", file.getString("id")).put("destination_account_id", destination)
            .put("idempotency_key", UUID.randomUUID().toString())
        withContext(Dispatchers.IO) { api().request("/api/transfers/copy", "POST", body) }
        notice = "Copy started on the backend. Track it in Transfers."; refreshTransfers()
    }

    fun getSharing(file: JSONObject, onResult: (JSONObject) -> Unit) = launch {
        val result = withContext(Dispatchers.IO) { JSONObject(api().request("/api/files/${file.getString("account_id")}/${file.getString("id")}/permissions")) }
        onResult(result)
    }
    fun changeSharing(file: JSONObject, public: Boolean, onResult: (JSONObject) -> Unit) = launch {
        val result = withContext(Dispatchers.IO) {
            JSONObject(api().request("/api/files/${file.getString("account_id")}/${file.getString("id")}/public",
                if (public) "POST" else "DELETE", if (public) JSONObject().put("confirm_public", true) else null))
        }
        onResult(result)
        notice = if (public) "Public link enabled." else "Public access revoked."
    }

    private fun documentInfo(uri: Uri): DocumentInfo {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c: Cursor ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        require(size != null && size!! > 0L) { "Choose a non-empty file with a known size." }
        return DocumentInfo(uri, name ?: "upload", size!!, resolver.getType(uri) ?: "application/octet-stream")
    }

    fun prepareUpload(uri: Uri) = launch {
        try { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        val result = withContext(Dispatchers.IO) {
            val doc = documentInfo(uri)
            val body = JSONObject().put("name", doc.name).put("size", doc.size).put("mime_type", doc.mime)
                .put("idempotency_key", UUID.randomUUID().toString())
            uploadAccountId?.let { body.put("account_id", it) }
            doc to JSONObject(api().request("/api/uploads", "POST", body))
        }
        store.rememberUri(result.second.getString("id"), uri)
        pendingUpload = PendingUpload(result.second, result.first)
    }

    fun cancelPendingUpload() {
        val pending = pendingUpload ?: return
        pendingUpload = null
        cancelTransfer(pending.transfer.getString("id"))
    }
    fun confirmUpload() {
        val pending = pendingUpload ?: return
        pendingUpload = null
        startUpload(pending.transfer, pending.document.uri)
    }

    fun resumeUpload(t: JSONObject, chosen: Uri? = null) {
        val uri = chosen ?: store.uriFor(t.getString("id"))
        if (chosen != null) {
            try { getApplication<Application>().contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            store.rememberUri(t.getString("id"), chosen)
        }
        if (uri == null) { error = "The original file is no longer available. Select it again to resume."; return }
        startUpload(t, uri)
    }

    private fun startUpload(t: JSONObject, uri: Uri) {
        if (uploadJob?.isActive == true) { error = "An upload is already running on this device."; return }
        val id = t.getString("id")
        activeUploadId = id
        uploadJob = launch {
            try {
            val result = withContext(Dispatchers.IO) {
                val api = api()
                var current = JSONObject(api.request("/api/transfers/$id"))
                if (current.optString("status") == "failed") current = JSONObject(api.request("/api/transfers/$id/retry", "POST"))
                val doc = documentInfo(uri)
                require(doc.size == current.getLong("size") && doc.name == current.getString("name")) { "Select the original file with the same name and size." }
                val digest = MessageDigest.getInstance("MD5")
                val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open the selected file")
                input.use { stream ->
                    var offset = current.getLong("bytes_transferred")
                    var consumed = 0L
                    val buffer = ByteArray(4 * 1024 * 1024)
                    while (consumed < offset) {
                        ensureActive()
                        val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), offset - consumed).toInt())
                        require(n > 0) { "The selected file ended before the resume point." }
                        digest.update(buffer, 0, n); consumed += n
                    }
                    while (offset < doc.size) {
                        ensureActive()
                        val wanted = minOf(buffer.size.toLong(), doc.size - offset).toInt()
                        var read = 0
                        while (read < wanted) {
                            val n = stream.read(buffer, read, wanted - read)
                            require(n > 0) { "The selected file ended unexpectedly." }
                            read += n
                        }
                        digest.update(buffer, 0, read)
                        current = api.chunk(id, offset, if (read == buffer.size) buffer else buffer.copyOf(read))
                        require(current.getLong("bytes_transferred") == offset + read) { "Upload offset changed. Resume this transfer to continue safely." }
                        offset += read
                        val progress = "Uploading ${doc.name} · ${formatBytes(offset)} / ${formatBytes(doc.size)}"
                        withContext(Dispatchers.Main) { transferProgress = progress }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val remote = current.text("result_md5")
                if (remote != null && !remote.equals(actual, ignoreCase = true)) throw IllegalStateException("Checksum mismatch: review the destination file in Google Drive.")
                current to (remote != null)
            }
            transferProgress = null; store.forgetUri(id)
            notice = if (result.second) "Upload complete. MD5 checksum verified." else "Upload complete. Google did not provide an MD5 checksum."
            refreshAll()
            } finally { transferProgress = null; activeUploadId = null }
        }
    }

    fun refreshTransfers() = launch(showBusy = false) {
        transfers = withContext(Dispatchers.IO) { JSONObject(api().request("/api/transfers")).getJSONArray("items").objects() }
    }
    fun cancelTransfer(id: String) = launch {
        withContext(Dispatchers.IO) { api().request("/api/transfers/$id/cancel", "POST") }
        if (activeDownloadId == id) downloadJob?.cancel()
        if (activeUploadId == id) uploadJob?.cancel()
        transferProgress = null; store.forgetUri(id)
        notice = "Transfer cancelled."; refreshTransfers()
    }
    fun retryTransfer(t: JSONObject) = launch {
        val updated = withContext(Dispatchers.IO) { JSONObject(api().request("/api/transfers/${t.getString("id")}/retry", "POST")) }
        if (updated.optString("kind") == "upload") resumeUpload(updated) else { notice = "Copy resumed on the backend."; refreshTransfers() }
    }

    fun download(file: JSONObject, uri: Uri) {
        if (downloadJob?.isActive == true) { error = "A download is already running on this device."; return }
        downloadJob = launch {
            try {
            val verified = withContext(Dispatchers.IO) {
                val api = api(); val digest = MessageDigest.getInstance("MD5")
                api.download(file.getString("account_id"), file.getString("id")).use { response ->
                    activeDownloadId = response.header("X-Transfer-Id")
                    val input = response.body?.byteStream() ?: throw IllegalStateException("Empty download response")
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Could not open the destination")
                    output.use { target ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            target.write(buffer, 0, n); digest.update(buffer, 0, n); total += n
                            val progress = "Downloading ${file.optString("name")} · ${formatBytes(total)}"
                            withContext(Dispatchers.Main) { transferProgress = progress }
                        }
                    }
                }
                val local = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = file.text("md5_checksum")
                if (expected != null && !expected.equals(local, ignoreCase = true)) throw IllegalStateException("Download checksum mismatch. Delete the incomplete file and try again.")
                expected != null
            }
            activeDownloadId = null; transferProgress = null
            notice = if (verified) "Download complete. MD5 checksum verified." else "Download complete. No checksum was available."
            refreshTransfers()
            } finally { activeDownloadId = null; transferProgress = null }
        }
    }
}
