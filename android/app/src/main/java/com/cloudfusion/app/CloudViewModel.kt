package com.cloudfusion.app

import android.accounts.Account
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

data class DocumentInfo(val uri: Uri, val name: String, val size: Long, val mime: String)
data class PendingUpload(val transfer: JSONObject, val document: DocumentInfo)
private data class FileCursor(var page: String? = null, var done: Boolean = false)
private data class FileQuery(val search: String, val type: String, val sort: String, val direction: String)
private class ReconnectRequired(email: String) : Exception("Reconnect $email in Accounts to authorize Google Drive again.")

class CloudViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val drive = DriveClient()
    private val authorization = Identity.getAuthorizationClient(application)
    // Short-lived OAuth tokens are memory-only; Google Play services manages its own cache.
    private val tokens = mutableMapOf<String, String>()
    private val tokenMutex = Mutex()
    private var busyCount = 0
    private var authEmail: String? = null
    private var fileJob: Job? = null
    private val cursors = mutableMapOf<String, FileCursor>()
    private var loadedQuery: FileQuery? = null
    private var transferJob: Job? = null
    private var activeTransferId: String? = null

    var busy by mutableStateOf(false); private set
    var authInProgress by mutableStateOf(false); private set
    var authorizationResolution by mutableStateOf<PendingIntent?>(null); private set
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

    init {
        store.removeLegacySession()
        try {
            val saved = store.read()
            accounts = saved.optJSONArray("accounts")?.objects()?.mapNotNull { item ->
                item.text("email")?.let(::unknownAccount)
            } ?: emptyList()
            transfers = saved.optJSONArray("transfers")?.objects()?.map { t ->
                if (t.optString("status") in listOf("pending", "in_progress", "hashing")) {
                    t.put("status", "interrupted").put("error", if (t.optString("kind") == "download") "Stopped when the app closed. A partial local file may remain; download again." else "Stopped when the app closed. Resume while the Google session is valid.")
                }
                t
            } ?: emptyList()
        } catch (_: Exception) {
            error = "Saved local state could not be read. Reconnect your Google accounts; Drive files were not deleted."
        }
        updateDashboard()
    }

    private fun unknownAccount(email: String) = JSONObject().put("id", email).put("email", email)
        .put("used_bytes", JSONObject.NULL).put("limit_bytes", JSONObject.NULL).put("free_bytes", JSONObject.NULL)
        .put("unlimited", false).put("quota_error", "Refresh quota")

    private fun quota(account: JSONObject) = QuotaSnapshot(
        account.optString("id"), account.longOrNull("used_bytes"), account.longOrNull("limit_bytes"),
        account.optBoolean("unlimited"), account.text("quota_error") == null && !account.optBoolean("reauthorization_required")
    )


    private fun updateDashboard() {
        val aggregate = aggregateQuota(accounts.map(::quota))
        val used = aggregate.used
        val capacity = aggregate.capacity
        val percent = if (used != null && capacity != null && capacity > 0) used.toDouble() * 100 / capacity else null
        dashboard = JSONObject().put("used_bytes", used ?: JSONObject.NULL).put("capacity_bytes", capacity ?: JSONObject.NULL)
            .put("remaining_bytes", aggregate.remaining ?: JSONObject.NULL).put("utilization_percent", percent ?: JSONObject.NULL)
            .put("connected_accounts", accounts.size)
            .put("active_transfers", transfers.count { it.optString("status") in listOf("pending", "hashing", "in_progress") })
            .put("failed_transfers", transfers.count { it.optString("status") in listOf("failed", "interrupted", "review_required") })
            .put("recent_files", JSONArray(files.take(4))).put("recent_transfers", JSONArray(transfers.take(4)))
    }

    private fun persist() {
        val accountIds = JSONArray()
        accounts.forEach { accountIds.put(JSONObject().put("email", it.optString("email"))) }
        store.write(JSONObject().put("accounts", accountIds).put("transfers", JSONArray(transfers)))
    }

    fun clearMessages() { error = null; notice = null }
    fun showError(message: String) { error = message }
    private fun displayError(e: Exception): String = when (e) {
        is ApiException -> "Google Play services authorization failed (code ${e.statusCode}). Check the Android OAuth package, signing SHA-1, Drive API and test-user setup."
        is IOException -> "Network or file I/O failed. Resume later and check Drive before starting a replacement."
        else -> e.message ?: "Something went wrong. Please try again."
    }
    private fun launch(showBusy: Boolean = true, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (showBusy) { busyCount++; busy = true }
        try { block() }
        catch (_: CancellationException) { }
        catch (e: Exception) { error = displayError(e) }
        finally { if (showBusy) { busyCount--; busy = busyCount > 0 } }
    }

    private fun requestFor(email: String): AuthorizationRequest = AuthorizationRequest.builder()
        .setAccount(Account(email, "com.google")).setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()

    /** Called only after the user explicitly picks an account (or presses Reconnect). */
    fun connectAccount(email: String) {
        if (authInProgress) return
        authEmail = email.trim()
        if (authEmail.isNullOrBlank()) { error = "Google did not return an account. Try again."; return }
        authInProgress = true
        launch {
            try {
                val result = authorization.authorize(requestFor(authEmail!!)).awaitResult()
                if (result.hasResolution()) {
                    authorizationResolution = result.pendingIntent ?: throw IllegalStateException("Google did not provide a consent screen.")
                } else {
                    completeAuthorization(result)
                }
            } catch (e: Exception) {
                authEmail = null; authInProgress = false; throw e
            }
        }
    }

    fun consumeAuthorizationResolution(): PendingIntent? = authorizationResolution.also { authorizationResolution = null }
    fun authorizationCancelled() {
        authorizationResolution = null; authEmail = null; authInProgress = false
        notice = "Google consent did not finish; no account was connected. If you did not cancel, check Settings → Google developer setup: the installed APK's SHA-1 and package must match your Android OAuth client. Also check Drive API, test users and Google Play services."
    }
    fun finishAuthorization(data: Intent?) {
        if (authEmail == null) {
            authorizationCancelled()
            error = "Google connection state was lost. Select the account again."
            return
        }
        if (data == null) { authorizationCancelled(); return }
        launch {
            try { completeAuthorization(authorization.getAuthorizationResultFromIntent(data)) }
            catch (e: Exception) { authEmail = null; authInProgress = false; throw e }
        }
    }

    private suspend fun completeAuthorization(result: AuthorizationResult) {
        val email = authEmail ?: throw IllegalStateException("Select the Google account again.")
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Google did not grant a Drive access token. Try connecting again.")
        val about = withContext(Dispatchers.IO) { drive.about(token) }
        val account = accountFromAbout(email, about) // Identity checked before accepting or storing the token.
        val existing = accounts.firstOrNull { it.optString("id").equals(email, true) }
        if (existing != null) {
            val id = existing.getString("id")
            account.put("id", id).put("email", id)
            accounts = accounts.map { if (it.optString("id") == id) account else it }
            tokens[id] = token
        } else {
            accounts = accounts + account
            tokens[email] = token
        }
        persist(); updateDashboard()
        authEmail = null; authInProgress = false
        notice = "Google account connected directly. Your accounts remain separate."
        loadFiles()
    }

    private fun markReconnect(email: String) {
        accounts = accounts.map {
            if (it.optString("id") == email) JSONObject(it.toString()).put("reauthorization_required", true)
                .put("quota_error", "Reconnect this account") else it
        }
        updateDashboard()
    }

    private suspend fun clearCachedToken(token: String) {
        try { authorization.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The subsequent authorization may still require user action. */ }
    }

    private suspend fun getToken(email: String): String = tokenMutex.withLock { getTokenUnlocked(email) }

    private suspend fun getTokenUnlocked(email: String, retry: Boolean = true): String {
        tokens[email]?.let { return it }
        val result = authorization.authorize(requestFor(email)).awaitResult()
        if (result.hasResolution()) { markReconnect(email); throw ReconnectRequired(email) }
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: run { markReconnect(email); throw ReconnectRequired(email) }
        try {
            val about = withContext(Dispatchers.IO) { drive.about(token) }
            accountFromAbout(email, about) // Do not allow a token for another account to touch data.
        } catch (e: DriveException) {
            if (e.code == 401 && retry) { clearCachedToken(token); return getTokenUnlocked(email, false) }
            throw e
        }
        if (accounts.none { it.optString("id") == email }) throw IllegalStateException("This Google account was disconnected.")
        tokens[email] = token
        return token
    }

    private suspend fun <T> withDrive(email: String, operation: (String) -> T): T {
        check(accounts.any { it.optString("id") == email }) { "Reconnect the required Google account first." }
        val token = getToken(email)
        if (accounts.none { it.optString("id") == email }) {
            tokens.remove(email)
            throw IllegalStateException("This Google account was disconnected.")
        }
        try { return withContext(Dispatchers.IO) { operation(token) } }
        catch (e: DriveException) {
            if (e.code != 401) throw e
            tokens.remove(email); clearCachedToken(token)
            val fresh = getToken(email)
            return withContext(Dispatchers.IO) { operation(fresh) }
        }
    }

    fun disconnect(id: String) {
        if (authInProgress && authEmail?.equals(id, true) == true) {
            error = "Finish or cancel Google authorization before disconnecting this account."; return
        }
        val active = transfers.firstOrNull { it.optString("id") == activeTransferId }
        if (active != null && (active.optString("source_account_id") == id || active.optString("destination_account_id") == id)) {
            error = "Cancel the active transfer before disconnecting this account."; return
        }
        launch {
            // Remove the account before suspending to clear Google's cache, so new operations cannot start.
            val oldToken = tokens.remove(id)
            accounts = accounts.filterNot { it.optString("id") == id }
            transfers = transfers.map { t ->
                if (t.optString("source_account_id") == id || t.optString("destination_account_id") == id) {
                    if (t.optString("status") in listOf("failed", "interrupted", "pending", "in_progress")) {
                        JSONObject(t.toString()).put("status", "review_required").put("resume_blocked", true)
                            .put("error", "Account disconnected; inspect Drive before starting a replacement.").also { it.remove("session_url") }
                    } else t
                } else t
            }
            if (uploadAccountId == id) uploadAccountId = null
            files = files.filterNot { it.optString("account_id") == id }
            persist(); updateDashboard()
            oldToken?.let { clearCachedToken(it) }
            notice = "Account removed from this device. Your Drive files and Google's authorization remain unchanged."
            loadFiles()
        }
    }

    fun refreshAll() = launch { refreshAccountsNow(); loadFiles() }
    fun refreshAccounts(remote: Boolean = false) = launch {
        val failed = refreshAccountsNow()
        if (remote) notice = when {
            accounts.isEmpty() -> "Connect a Google account before refreshing quotas."
            failed > 0 -> "Some accounts could not refresh. Check Accounts and reconnect if needed."
            else -> "Account quotas refreshed directly from Google Drive."
        }
    }
    private suspend fun refreshAccountsNow(): Int {
        var failures = 0
        for (account in accounts.toList()) {
            val id = account.getString("id")
            try {
                val about = withDrive(id) { drive.about(it) }
                val updated = accountFromAbout(id, about)
                accounts = accounts.map { if (it.optString("id") == id) updated else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failures++
                val failed = unknownAccount(id).put("quota_error", displayError(e))
                    .put("reauthorization_required", e is ReconnectRequired || (e is DriveException && e.code == 401))
                accounts = accounts.map { if (it.optString("id") == id) failed else it }
            }
        }
        updateDashboard()
        return failures
    }

    private fun mapFile(account: String, f: JSONObject): JSONObject {
        val owners = f.optJSONArray("owners")?.objects().orEmpty()
        return JSONObject().put("id", f.getString("id")).put("account_id", account).put("account_email", account)
            .put("name", f.optString("name", "Untitled")).put("mime_type", f.optString("mimeType", "application/octet-stream"))
            .put("size", f.longOrNull("size") ?: JSONObject.NULL).put("md5_checksum", f.text("md5Checksum") ?: JSONObject.NULL)
            .put("modified_time", f.text("modifiedTime") ?: JSONObject.NULL).put("version", f.text("version") ?: JSONObject.NULL)
            .put("owner_email", owners.firstOrNull()?.text("emailAddress") ?: JSONObject.NULL)
            .put("web_view_link", f.text("webViewLink") ?: JSONObject.NULL)
    }

    fun loadFiles(more: Boolean = false, sync: Boolean = false) {
        fileJob?.cancel()
        val query = FileQuery(search, fileType, sort, direction)
        fileJob = launch {
            val canContinue = more && loadedQuery == query
            // Work on copies: cancelling an in-flight refresh must not advance page tokens without committing its files.
            val nextCursors = if (canContinue) cursors.mapValues { (_, value) -> value.copy() }.toMutableMap()
                else accounts.associate { it.getString("id") to FileCursor() }.toMutableMap()
            val merged = (if (canContinue) files else emptyList()).toMutableList()
            var failures = 0
            for ((id, cursor) in nextCursors) {
                if (cursor.done || accounts.none { it.optString("id") == id }) continue
                try {
                    val result = withDrive(id) { drive.listFiles(it, query.search, query.type, query.sort, query.direction, cursor.page) }
                    val newItems = result.optJSONArray("files")?.objects().orEmpty().map { mapFile(id, it) }
                    newItems.forEach { item ->
                        if (merged.none { it.optString("id") == item.optString("id") && it.optString("account_id") == id }) merged.add(item)
                    }
                    cursor.page = result.text("nextPageToken"); cursor.done = cursor.page == null
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failures++; cursor.done = true }
            }
            val comparator = Comparator<JSONObject> { a, b ->
                when (query.sort) {
                    "name" -> a.optString("name").compareTo(b.optString("name"), ignoreCase = true)
                    "size" -> (a.longOrNull("size") ?: -1).compareTo(b.longOrNull("size") ?: -1)
                    else -> a.optString("modified_time").compareTo(b.optString("modified_time"))
                }
            }
            files = if (query.direction == "desc") merged.sortedWith(comparator.reversed()) else merged.sortedWith(comparator)
            cursors.clear(); cursors.putAll(nextCursors); loadedQuery = query
            nextOffset = if (cursors.values.any { !it.done }) files.size else null
            updateDashboard()
            if (failures > 0) notice = "Files from $failures account(s) could not be loaded. Check Accounts, then refresh files."
            else if (sync) notice = if (accounts.isEmpty()) "Connect a Google account first." else "Files refreshed directly from Google Drive."
        }
    }

    fun rename(file: JSONObject, name: String) = mutateFile(file, JSONObject().put("name", name), "File renamed.")
    fun trash(file: JSONObject) = mutateFile(file, JSONObject().put("trashed", true), "File moved to Google Drive trash.")
    private fun mutateFile(file: JSONObject, body: JSONObject, success: String) = launch {
        withDrive(file.getString("account_id")) { drive.updateFile(it, file.getString("id"), body) }
        notice = success; loadFiles()
    }
    fun move(file: JSONObject, parent: String) = launch {
        val account = file.getString("account_id"); val id = file.getString("id")
        val current = withDrive(account) { drive.getFile(it, id) }
        val parents = current.optJSONArray("parents")?.let { array -> (0 until array.length()).map { array.getString(it) }.joinToString(",") }
        withDrive(account) { drive.updateFile(it, id, JSONObject(), parent.ifBlank { "root" }, parents) }
        notice = "File moved within its Google Drive."; loadFiles()
    }
    fun createFolder(accountId: String, name: String, parent: String) = launch {
        withDrive(accountId) { drive.createFolder(it, name, parent) }
        notice = "Folder created."; loadFiles()
    }
    fun getSharing(file: JSONObject, onResult: (JSONObject) -> Unit) = launch {
        onResult(withDrive(file.getString("account_id")) { drive.permissions(it, file.getString("id")) })
    }
    fun changeSharing(file: JSONObject, public: Boolean, onResult: (JSONObject) -> Unit) = launch {
        val account = file.getString("account_id"); val id = file.getString("id")
        if (public) withDrive(account) { drive.makePublic(it, id) } else withDrive(account) { drive.revokePublic(it, id) }
        onResult(withDrive(account) { drive.permissions(it, id) })
        notice = if (public) "Public link enabled." else "Public access revoked where Google allowed it."
    }

    private fun documentInfo(uri: Uri): DocumentInfo {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null; var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c: Cursor ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        require(size != null && size!! in 1..MAX_TRANSFER_SIZE) { "Choose a non-empty file with a known size up to 5 TiB." }
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() && !isWorkspaceNative(it) } ?: "application/octet-stream"
        return DocumentInfo(uri, name?.takeIf { it.isNotBlank() } ?: "upload", size!!, mime)
    }

    fun prepareUpload(uri: Uri) = launch {
        try { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* The user can select the file again when resuming. */ }
        val document = withContext(Dispatchers.IO) { documentInfo(uri) }
        refreshAccountsNow()
        val destination = chooseDestination(accounts.map(::quota), document.size, uploadAccountId)
            ?: throw IllegalStateException("No selected account has a known quota with enough space. Refresh accounts or choose another destination.")
        val transfer = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "upload")
            .put("name", document.name).put("size", document.size).put("mime_type", document.mime)
            .put("destination_account_id", destination.id).put("source_uri", uri.toString())
            .put("bytes_transferred", 0).put("status", "pending")
        pendingUpload = PendingUpload(transfer, document)
    }
    fun cancelPendingUpload() { pendingUpload = null }
    fun confirmUpload() {
        val pending = pendingUpload ?: return
        if (transferJob?.isCompleted == false) { error = "A transfer is already running on this device."; return }
        pendingUpload = null; transfers = listOf(pending.transfer) + transfers
        try { persist(); updateDashboard(); startTransfer(pending.transfer.getString("id")) }
        catch (e: Exception) { error = displayError(e) }
    }

    fun copy(file: JSONObject, destination: String) = launch {
        check(transferJob?.isCompleted != false) { "A transfer is already running on this device." }
        val source = file.getString("account_id")
        require(source != destination) { "Choose a different destination account." }
        val mime = file.optString("mime_type")
        require(!isWorkspaceNative(mime)) { "Folders and Google Workspace documents cannot be copied as binary files." }
        val size = file.longOrNull("size")
        require(size != null && size in 1..MAX_TRANSFER_SIZE) { "Copy requires a known, non-empty file size up to 5 TiB." }
        val checksum = file.text("md5_checksum") ?: throw IllegalStateException("Google did not provide a source checksum; this copy cannot be verified safely.")
        refreshAccountsNow()
        check(chooseDestination(accounts.map(::quota), size, destination) != null) { "Destination quota is unknown or does not have enough free space." }
        // Another action may have started a transfer while the quota refresh was suspended.
        check(transferJob?.isCompleted != false) { "A transfer is already running on this device." }
        val t = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "copy").put("name", file.optString("name"))
            .put("mime_type", mime).put("size", size).put("source_account_id", source).put("source_file_id", file.getString("id"))
            .put("destination_account_id", destination).put("expected_md5", checksum).put("bytes_transferred", 0).put("status", "pending")
        transfers = listOf(t) + transfers; persist(); updateDashboard(); startTransfer(t.getString("id"))
    }

    private fun transfer(id: String): JSONObject = transfers.firstOrNull { it.optString("id") == id }
        ?: throw IllegalStateException("Transfer not found.")
    private fun changeTransfer(id: String, change: (JSONObject) -> Unit) {
        transfers = transfers.map { old -> if (old.optString("id") == id) JSONObject(old.toString()).also(change) else old }
        persist(); updateDashboard()
    }

    fun resumeUpload(t: JSONObject, chosen: Uri? = null) {
        if (t.optBoolean("resume_blocked")) { error = "Review the destination in Drive before starting a replacement."; return }
        val uri = chosen ?: t.text("source_uri")?.let(Uri::parse)
        if (uri == null) { error = "Select the original file to resume."; return }
        if (chosen != null) {
            try { getApplication<Application>().contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        }
        startTransfer(t.getString("id"), uri)
    }
    fun retryTransfer(t: JSONObject) {
        if (t.optBoolean("resume_blocked")) { error = "Review the destination in Drive before starting a replacement."; return }
        if (t.optString("kind") == "copy") startTransfer(t.getString("id")) else resumeUpload(t)
    }

    fun cancelTransfer(id: String) {
        val t = transfers.firstOrNull { it.optString("id") == id } ?: return
        if (t.optString("status") !in listOf("pending", "hashing", "in_progress", "failed", "interrupted")) return
        if (activeTransferId == id) transferJob?.cancel()
        try {
            changeTransfer(id) { it.put("status", "cancelled").put("resume_blocked", true).put("error", "Stopped on this phone. An in-flight request may still have completed; check Drive."); it.remove("session_url") }
            notice = "Transfer stopped on this phone. Check Drive for a possible completed file."
        } catch (e: Exception) { error = displayError(e) }
    }

    private fun startTransfer(id: String, selectedUri: Uri? = null) {
        if (transferJob?.isCompleted == false) { error = "A transfer is already running on this device."; return }
        activeTransferId = id
        transferJob = viewModelScope.launch {
            try {
                runTransfer(id, selectedUri)
            } catch (_: CancellationException) {
                // cancelTransfer records the state. Process death is detected at next launch.
            } catch (e: Exception) {
                if (transfer(id).optString("status") == "cancelled") return@launch
                val blocked = e is DriveException && e.code in listOf(404, 410)
                try {
                    changeTransfer(id) {
                        it.put("status", if (blocked) "review_required" else "failed").put("error", displayError(e))
                        if (blocked) it.put("resume_blocked", true)
                    }
                } catch (_: Exception) { }
                error = displayError(e)
            } finally {
                activeTransferId = null; transferProgress = null
                refreshAccounts()
            }
        }
    }

    private suspend fun hashDocument(uri: Uri, expectedSize: Long): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5"); var total = 0L
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open the selected file.")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                total += n; check(total <= expectedSize) { "The selected file changed size." }
                digest.update(buffer, 0, n)
            }
        }
        check(total == expectedSize) { "The selected file changed size or could not be read completely." }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun skipTo(input: InputStream, offset: Long) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(64 * 1024); var remaining = offset
        while (remaining > 0) {
            coroutineContext.ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0) { "The selected file ended before the resume point." }; remaining -= n
            }
        }
    }
    private suspend fun readChunk(input: InputStream, size: Int): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(size); var read = 0
        while (read < size) {
            coroutineContext.ensureActive()
            val n = input.read(buffer, read, size - read)
            check(n > 0) { "The selected file ended unexpectedly." }; read += n
        }
        buffer
    }

    private suspend fun runTransfer(id: String, selectedUri: Uri?) {
        var t = transfer(id)
        val kind = t.getString("kind"); val size = t.getLong("size"); val destination = t.getString("destination_account_id")
        require(size in 1..MAX_TRANSFER_SIZE)
        val uri = if (kind == "upload") selectedUri ?: t.text("source_uri")?.let(Uri::parse) else null
        if (kind == "upload") {
            check(uri != null) { "Select the original file to resume." }
            changeTransfer(id) { it.put("status", "hashing").remove("error") }
            transferProgress = "Checking the selected file before upload…"
            val info = withContext(Dispatchers.IO) { documentInfo(uri) }
            check(info.size == size && info.name == t.getString("name")) { "Select the original file with the same name and size." }
            val digest = hashDocument(uri, size)
            val previous = t.text("expected_md5")
            check(previous == null || previous.equals(digest, true)) { "Selected file content differs from the original. Upload was not resumed." }
            changeTransfer(id) { it.put("expected_md5", digest).put("source_uri", uri.toString()) }
        } else {
            val sourceAccount = t.getString("source_account_id")
            val source = withDrive(sourceAccount) { drive.getFile(it, t.getString("source_file_id")) }
            check(source.longOrNull("size") == size && source.text("md5Checksum")?.equals(t.text("expected_md5"), true) == true) {
                "The source file changed or its checksum is unavailable. Copy stopped; review Drive."
            }
        }
        t = transfer(id)
        var session = t.text("session_url")
        if (session == null) {
            session = withDrive(destination) { drive.startUpload(it, t.getString("name"), t.getString("mime_type"), size) }
            val savedSession = session
            changeTransfer(id) { it.put("session_url", savedSession) } // Persist before sending bytes.
        }
        changeTransfer(id) { it.put("status", "in_progress").remove("error") }
        val location = requireNotNull(session)
        val probe = withDrive(destination) { drive.probeUpload(it, location, size) }
        if (probe is UploadReply.Complete) { finishTransfer(id, probe.file); return }
        var offset = (probe as UploadReply.Incomplete).offset
        check(offset < size) { "Google has all bytes but has not confirmed the file. Resume later and check Drive before starting again." }
        changeTransfer(id) { it.put("bytes_transferred", offset) }
        val stream = if (kind == "upload") withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri!!) ?: throw IllegalStateException("Could not open the selected file.")
        } else null
        try {
            if (stream != null) skipTo(stream, offset)
            while (offset < size) {
                coroutineContext.ensureActive()
                val count = minOf(CHUNK_SIZE.toLong(), size - offset).toInt()
                val bytes = if (stream != null) readChunk(stream, count) else {
                    val sourceAccount = t.getString("source_account_id")
                    withDrive(sourceAccount) { drive.downloadRange(it, t.getString("source_file_id"), offset, offset + count - 1, size) }
                }
                val start = offset
                val reply = withDrive(destination) { drive.sendChunk(it, location, start, size, t.getString("mime_type"), bytes) }
                when (reply) {
                    is UploadReply.Incomplete -> {
                        if (reply.offset != start + count) {
                            changeTransfer(id) { it.put("bytes_transferred", reply.offset) }
                            throw IllegalStateException("Google accepted a different byte count. Resume to reconcile safely.")
                        }
                        offset = reply.offset
                        check(offset < size) { "Google has all bytes but has not confirmed the file. Resume later and check Drive." }
                        changeTransfer(id) { it.put("bytes_transferred", offset) }
                        transferProgress = "${if (kind == "copy") "Copying" else "Uploading"} ${t.optString("name")} · ${formatBytes(offset)} / ${formatBytes(size)}"
                    }
                    is UploadReply.Complete -> {
                        check(start + count == size) { "Google reported completion before the final chunk. Check Drive." }
                        finishTransfer(id, reply.file); return
                    }
                }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { stream?.close() } }
    }

    private fun finishTransfer(id: String, result: JSONObject) {
        val t = transfer(id); val size = t.getLong("size"); val expected = t.text("expected_md5")
        val actual = result.text("md5Checksum"); val actualSize = result.longOrNull("size")
        val integrity = completionIntegrity(size, actualSize, expected, actual)
        changeTransfer(id) {
            it.put("bytes_transferred", size).put("result_file_id", result.optString("id"))
            it.put("checksum_verified", integrity == CompletionIntegrity.VERIFIED)
            it.put("status", if (integrity == CompletionIntegrity.MISMATCH) "review_required" else "completed")
            it.put("resume_blocked", true).remove("session_url")
            if (integrity == CompletionIntegrity.MISMATCH) it.put("error", "Destination size or checksum differs. The file may exist in Drive; review it manually.") else it.remove("error")
        }
        notice = when (integrity) {
            CompletionIntegrity.MISMATCH -> "Transfer finished with a mismatch. Review the destination file in Drive."
            CompletionIntegrity.VERIFIED -> "Transfer complete. MD5 checksum verified."
            CompletionIntegrity.UNVERIFIED -> "Transfer complete. Google did not return a checksum; verify the destination."
        }
        loadFiles()
    }

    fun download(file: JSONObject, uri: Uri) {
        if (transferJob?.isCompleted == false) { error = "A transfer is already running on this device."; return }
        if (isWorkspaceNative(file.optString("mime_type"))) { error = "Folders and Google Workspace documents cannot be downloaded as binary files."; return }
        val t = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "download").put("name", file.optString("name"))
            .put("size", file.longOrNull("size") ?: JSONObject.NULL).put("source_account_id", file.getString("account_id"))
            .put("bytes_transferred", 0).put("status", "pending")
        transfers = listOf(t) + transfers
        try { persist(); updateDashboard() } catch (e: Exception) { error = displayError(e); return }
        val id = t.getString("id"); activeTransferId = id
        transferJob = viewModelScope.launch {
            try {
                changeTransfer(id) { it.put("status", "in_progress") }
                val response = withDrive(file.getString("account_id")) { drive.download(it, file.getString("id")) }
                val digest = MessageDigest.getInstance("MD5"); var total = 0L; var lastSaved = 0L
                response.use { r ->
                    val input = r.body?.byteStream() ?: throw IllegalStateException("Empty Drive response.")
                    val output = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt") }
                        ?: throw IllegalStateException("Could not open the selected destination.")
                    try {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = withContext(Dispatchers.IO) { input.read(buffer) }
                            if (n < 0) break
                            if (n == 0) continue
                            withContext(Dispatchers.IO) { output.write(buffer, 0, n) }
                            digest.update(buffer, 0, n); total += n
                            transferProgress = "Downloading ${file.optString("name")} · ${formatBytes(total)}"
                            if (total - lastSaved >= CHUNK_SIZE) { changeTransfer(id) { it.put("bytes_transferred", total) }; lastSaved = total }
                        }
                        withContext(Dispatchers.IO) { output.flush() }
                    } finally { withContext(NonCancellable + Dispatchers.IO) { output.close() } }
                }
                val expectedSize = file.longOrNull("size")
                check(expectedSize == null || total == expectedSize) { "Download size mismatch. Delete the incomplete local file and try again." }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                val expected = file.text("md5_checksum")
                check(expected == null || expected.equals(actual, true)) { "Download checksum mismatch. Delete the local file and try again." }
                changeTransfer(id) { it.put("status", "completed").put("bytes_transferred", total).put("checksum_verified", expected != null) }
                notice = if (expected != null) "Download complete. MD5 checksum verified." else "Download complete. Google did not provide a checksum."
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                if (transfer(id).optString("status") == "cancelled") return@launch
                try { changeTransfer(id) { it.put("status", "failed").put("error", "${displayError(e)} A partial local file may remain.") } } catch (_: Exception) { }
                error = "${displayError(e)} A partial local file may remain."
            } finally { activeTransferId = null; transferProgress = null }
        }
    }
}
